package org.blefinder.data

import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.blefinder.core.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class SearchState(
    val ready: Boolean = false, val active: Boolean = false, val sessionId: String? = null,
    val simulated: Boolean = false, val devices: List<DeviceRecord> = emptyList(),
    val mutes: MuteRules = MuteRules(), val settings: Settings = Settings(),
    val target: String? = null, val targetStats: SignalStats? = null,
    val baseline: Baseline? = null, val baselineReview: Set<String> = emptySet(),
    val audioMuted: Boolean = false, val error: String? = null,
    val nowElapsed: Long = 0, val nowWall: Long = 0,
    val signalHistory: Map<String, List<RssiSample>> = emptyMap(),
    val sessionStartedElapsed: Long = 0, val hasSearched: Boolean = false,
    val featuredAddress: String? = null,
    val targetSeed: String? = null, val targetCandidate: PhysicalDeviceCandidate? = null,
    val identitySuggestions: List<IdentityRelationship> = emptyList(),
    val targetAddressChange: TargetAddressChange? = null,
    val targetHistory: List<RssiSample> = emptyList(),
    val gpsGeneration: Long = 0,
    val targetIdentityNotice: String? = null,
)
// Mutable search state lives on one serial command consumer. UI snapshots are published at 5 Hz.
class SearchRepository(val db: SearchDatabase, private val preferences: Preferences,
    private val scope: CoroutineScope,
    private val limits: StorageLimits = StorageLimits(),
    private val storageUsage: () -> StorageUsage = { StorageUsage.read(db) },
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
) {
    // Radio input is bounded separately so control commands can still be enqueued.
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val pendingResults = AtomicInteger()
    private val backlogFailure = AtomicReference<String?>(null)
    @Volatile private var acceptingResults = false
    private var nextStorageCheck = 0L
    private var resultsSinceStorageCheck = 0
    private val mutable = MutableStateFlow(SearchState())
    val state = mutable.asStateFlow()
    val sessions = db.dao().sessions()
    private var current = SearchState()
    private val devices = linkedMapOf<String, DeviceRecord>()
    private val signalHistory = linkedMapOf<String, ArrayDeque<RssiSample>>()
    private val strongestSelector = StrongestDeviceSelector()
    private var sessionStartedElapsed = 0L
    private var session: SessionEntity? = null
    private var targetOverride: SignalStats? = null
    private var identity = PhysicalIdentityEngine()
    private val targetHistory = ArrayDeque<RssiSample>()
    private var targetMembers: Set<String> = emptySet()
    private val initialized = CompletableDeferred<Unit>()
    var sound: ((Observation, Boolean, Settings) -> Unit)? = null
    var fatalError: (() -> Unit)? = null
    init {
        scope.launch(Dispatchers.IO) {
            try {
                val (settings, persistent) = preferences.load()
                Companies.size(); IeeeAssignments.warmUp()
                db.dao().recoverInterrupted()
                current = current.copy(ready = true, settings = settings, mutes = MuteRules(persistent = persistent))
                publish(); initialized.complete(Unit)
            } catch (e: Exception) { current = current.copy(error = "Storage unavailable: ${e.message}"); publish(); initialized.completeExceptionally(e) }
            for (command in commands) {
                try { command() } catch (e: Exception) {
                    acceptingResults = false
                    current = current.copy(error = "Search stopped: ${e.message}", active = false)
                    publish(); fatalError?.invoke()
                }
            }
        }
        scope.launch { while (isActive) { delay(200); tick(elapsedNow()) } }
    }
    internal fun tick(at: Long) = enqueue {
        checkStorage()
        finishBaseline(at)
        publish()
    }
    private fun enqueue(block: suspend () -> Unit) { check(commands.trySend(block).isSuccess) }
    private suspend fun <T> serial(block: suspend () -> T): T {
        initialized.await()
        val reply = CompletableDeferred<T>()
        enqueue { try { reply.complete(block()) } catch (e: Exception) { reply.completeExceptionally(e); throw e } }
        return reply.await()
    }
    private fun publish() {
        val now = elapsedNow()
        val cutoff = now - SIGNAL_HISTORY_MS
        signalHistory.values.forEach { samples -> samples.removeAll { it.elapsedMillis < cutoff } }
        signalHistory.entries.removeAll { it.value.isEmpty() }
        targetHistory.removeAll { it.elapsedMillis < cutoff }
        val candidate = current.targetSeed?.let(identity::candidate)
        val suggestions = current.targetSeed?.let { seed ->
            (candidate?.addresses.orEmpty() + seed).flatMap(identity::relationships).distinctBy { it.addresses }
                .filter { relation -> relation.addresses.any { it !in candidate?.addresses.orEmpty() } || relation.contradictions.isNotEmpty() }
        }.orEmpty()
        val history = signalHistory.mapValues { it.value.toList() }
        val featured = strongestSelector.update(devices.values, current.mutes, history, now)
        current = current.copy(targetCandidate = candidate, identitySuggestions = suggestions, targetHistory = targetHistory.toList(), featuredAddress = featured, devices = devices.values.toList(), targetStats = current.target?.let { targetOverride ?: devices[it]?.stats },
            nowElapsed = now, nowWall = System.currentTimeMillis(),
            signalHistory = history, sessionStartedElapsed = sessionStartedElapsed)
        mutable.value = current
    }
    private fun event(type: String, detail: String) { session?.let { db.dao().event(EventEntity(sessionId = it.id, timestamp = System.currentTimeMillis(), type = type, detail = detail)) } }
    suspend fun start(simulated: Boolean) = serial {
        if (current.active) return@serial
        storageUsage().limitReason(limits)?.let { reason ->
            current = current.copy(error = "$reason Search was not started. Saved results are available to export.")
            publish()
            return@serial
        }
        if (session == null || session?.simulated != simulated) createSession(simulated)
        if (devices.size >= limits.maxSessionAddresses) {
            current = current.copy(error = "Session address limit reached (${limits.maxSessionAddresses}). Start a new session to continue; saved results are available to export.")
            publish()
            return@serial
        }
        session = session!!.copy(endedAt = null, status = "active")
        db.dao().putSession(session!!)
        current = current.copy(active = true, hasSearched = true, simulated = simulated, error = null)
        backlogFailure.set(null); acceptingResults = true
        nextStorageCheck = elapsedNow() + 1000
        resultsSinceStorageCheck = 0
        event("start", "User started search"); publish()
    }
    private fun createSession(simulated: Boolean) {
        val s = SessionEntity(UUID.randomUUID().toString(), System.currentTimeMillis(), simulated = simulated, settingsJson = SearchJson.encodeToString(current.settings))
        sessionStartedElapsed = elapsedNow()
        db.dao().putSession(s); session = s; devices.clear(); signalHistory.clear(); strongestSelector.reset(); targetOverride = null; identity = PhysicalIdentityEngine(); targetHistory.clear(); targetMembers = emptySet()
        current = current.copy(sessionId = s.id, simulated = simulated, mutes = current.mutes.nextSession(), target = null,
            baseline = null, baselineReview = emptySet(), targetSeed = null, targetCandidate = null,
            identitySuggestions = emptyList(), targetAddressChange = null, targetHistory = emptyList(), targetIdentityNotice = null)
    }
    suspend fun stop() = serial { stopSession() }
    private fun stopSession() {
        acceptingResults = false
        val open = session?.takeIf { it.status == "active" } ?: return
        val endedAt = System.currentTimeMillis()
        db.runInTransaction {
            if (current.baseline != null) event("baseline_cancelled", "Search stopped before baseline completed")
            db.dao().finish(open.id, endedAt, "stopped")
            event("stop", "Search stopped")
        }
        session = open.copy(endedAt = endedAt, status = "stopped")
        current = current.copy(active = false, baseline = null)
        publish()
    }
    private fun stopForLimit(reason: String, notifyService: Boolean = true) {
        if (!current.active) return
        event("logging_limit", "$reason Results after this cutoff were not logged.")
        stopSession()
        current = current.copy(error = "$reason Search stopped. Saved results are available to export; results after the cutoff were not logged.")
        publish()
        if (notifyService) fatalError?.invoke()
    }
    private fun checkStorage(): Boolean {
        if (!current.active) return false
        backlogFailure.get()?.let { stopForLimit(it, notifyService = false); return false }
        val now = elapsedNow()
        if (now >= nextStorageCheck || resultsSinceStorageCheck >= 128) {
            nextStorageCheck = now + 1000; resultsSinceStorageCheck = 0
            storageUsage().limitReason(limits)?.let { stopForLimit(it); return false }
        }
        return true
    }
    fun restartSession() = enqueue {
        session?.let { db.dao().finish(it.id, it.endedAt ?: System.currentTimeMillis(), "archived") }
        createSession(current.simulated)
        if (!current.active) {
            session = session!!.copy(endedAt = System.currentTimeMillis(), status = "stopped")
            db.dao().finish(session!!.id, session!!.endedAt!!, "stopped")
        }
        event("new_session", "Previous session retained in Sessions"); publish()
    }
    fun receive(observation: Observation, gpsGeneration: Long = state.value.gpsGeneration) {
        if (!acceptingResults) return
        if (pendingResults.incrementAndGet() > limits.maxPendingResults) {
            pendingResults.decrementAndGet()
            if (backlogFailure.compareAndSet(null, "Pending scan limit reached (${limits.maxPendingResults} results).")) {
                acceptingResults = false
                // Stop the radio promptly, without waiting behind the pending database writes.
                fatalError?.invoke()
                enqueue { backlogFailure.get()?.let { stopForLimit(it, notifyService = false) } }
            }
            return
        }
        enqueue {
            try { storeObservation(observation, gpsGeneration) }
            finally { pendingResults.decrementAndGet() }
        }
    }
    private fun storeObservation(observation: Observation, gpsGeneration: Long) {
        if (!checkStorage()) return
        if (observation.address !in devices && devices.size >= limits.maxSessionAddresses) {
            stopForLimit("Session address limit reached (${limits.maxSessionAddresses}).")
            return
        }
        // Baseline membership uses callback receipt time, so delayed controller timestamps are not misclassified.
        current = current.copy(baseline = current.baseline?.observe(observation.address, observation.receivedElapsedMillis))
        val located = observation.copy(location = observation.location.takeIf { current.settings.gps && !current.simulated && gpsGeneration == current.gpsGeneration })
        identity.observe(located)
        val seed = current.targetSeed
        val activeTarget = current.target
        if (seed != null && activeTarget != null) {
            val members = identity.candidate(seed).addresses
            if (targetMembers.any { it !in members }) {
                // Aggregate smoothing cannot subtract a rejected address. Restart the bounded target view.
                targetOverride = SignalStats()
                targetHistory.clear()
                current = current.copy(targetIdentityNotice = "Identity association changed. Target graph and statistics restarted; saved address observations are preserved.")
                event("target_statistics_restarted", "Identity membership changed from ${targetMembers.sorted()} to ${members.sorted()}; raw observations retained")
            }
            targetMembers = members
            // A newly observed contradiction can revoke an association. Return to the selected seed.
            if (activeTarget !in members) {
                event("target_identity_revoked", "$activeTarget returned to seed $seed; identity evidence changed")
                current = current.copy(target = seed, targetAddressChange = null)
            }
            identity.follow(seed, requireNotNull(current.target), located)?.let { change ->
                current = current.copy(target = change.to, targetAddressChange = change)
                event("target_address_changed", SearchJson.encodeToString(change))
            }
        }
        val o = located.copy(target = current.target == observation.address, mute = current.mutes.kind(observation.address))
        val previous = devices[o.address]
        val stats = (previous?.stats ?: SignalStats()).add(o, current.settings.smoothing)
        val record = DeviceRecord(o.address, stats, o, o.advertisement.localName ?: o.deviceName ?: previous?.displayName,
            o.advertisement.manufacturers.firstNotNullOfOrNull { it.company } ?: o.metadata.androidManufacturers.firstNotNullOfOrNull { it.company } ?: previous?.company)
        // Sound follows each raw result; dashboard smoothing and render cadence do not control pitch.
        if (!current.audioMuted && !current.mutes.isMuted(o.address) && (current.target == null || current.target == o.address)) {
            sound?.invoke(o, previous == null, current.settings)
        }
        val sid = requireNotNull(session).id
        db.runInTransaction {
            db.dao().insertObservation(ObservationEntity(sessionId = sid, address = o.address, timestamp = o.timestamp, rssi = o.rssi, json = SearchJson.encodeToString(o)))
            db.dao().putDevice(DeviceEntity(sid, o.address, SearchJson.encodeToString(record)))
        }
        resultsSinceStorageCheck++
        devices[o.address] = record
        if (o.rssi in -127..126 && o.elapsedMillis >= elapsedNow() - SIGNAL_HISTORY_MS) {
            val samples = signalHistory.getOrPut(o.address) { ArrayDeque() }
            samples.addLast(RssiSample(o.elapsedMillis, o.rssi, requireNotNull(stats.smoothed)))
        }
        if (o.target) {
            targetOverride = (targetOverride ?: SignalStats()).add(o, current.settings.smoothing)
            if (o.rssi in -127..126 && o.elapsedMillis >= elapsedNow() - SIGNAL_HISTORY_MS)
                targetHistory.addLast(RssiSample(o.elapsedMillis, o.rssi, requireNotNull(targetOverride!!.smoothed)))
        }
    }
    fun location(fix: GeoFix, gpsGeneration: Long = state.value.gpsGeneration) = enqueue {
        if (current.settings.gps && !current.simulated && gpsGeneration == current.gpsGeneration && checkStorage()) session?.let { db.dao().insertLocation(LocationEntity(sessionId = it.id, json = SearchJson.encodeToString(fix))) }
    }
    fun selectTarget(address: String?) = enqueue {
        current = current.copy(target = address, targetSeed = address, targetAddressChange = null, targetIdentityNotice = null)
        targetMembers = address?.let { identity.candidate(it).addresses }.orEmpty()
        targetOverride = address?.let { devices[it]?.stats }
        targetHistory.clear(); address?.let { targetHistory.addAll(signalHistory[it].orEmpty()) }
        event("target", address ?: "scan mode"); publish()
    }
    fun clearTargetStats() = enqueue { targetOverride = SignalStats(); event("reset_target_statistics", current.target ?: "none"); publish() }
    fun audioMute() = enqueue { current = current.copy(audioMuted = !current.audioMuted); publish() }
    fun cycleSoundMode() = enqueue {
        val next = when {
            current.audioMuted -> current.copy(audioMuted = false, settings = current.settings.copy(loudspeaker = false))
            !current.settings.loudspeaker -> current.copy(settings = current.settings.copy(loudspeaker = true))
            else -> current.copy(audioMuted = true, settings = current.settings.copy(loudspeaker = false))
        }
        val valid = next.settings.validated()
        preferences.settings(valid)
        current = next.copy(settings = valid)
        event("settings", SearchJson.encodeToString(valid)); publish()
    }
    fun setMute(address: String, always: Boolean = false, muted: Boolean = true) = enqueue {
        val updated = if (muted) current.mutes.mute(address, always) else current.mutes.unmute(address)
        preferences.mutes(updated.persistent)
        current = current.copy(mutes = updated); event("mute", "$address: ${updated.kind(address)}"); publish()
    }
    fun unmuteSession() = enqueue { current = current.copy(mutes = current.mutes.copy(session = emptySet())); event("session_mutes_cleared", "all"); publish() }
    fun beginBaseline() = enqueue {
        if (!current.active) return@enqueue
        val now = elapsedNow()
        current = current.copy(target = null, targetSeed = null, targetAddressChange = null, baseline = Baseline(now, now + current.settings.baselineSeconds * 1000L), baselineReview = emptySet())
        event("baseline_started", "${current.settings.baselineSeconds} seconds; session mutes only"); publish()
    }
    fun cancelBaseline() = enqueue { current = current.copy(baseline = null); event("baseline_cancelled", "No new mutes applied"); publish() }
    private fun finishBaseline(at: Long) {
        val baseline = current.baseline ?: return
        if (at >= baseline.endElapsed) {
            current = current.copy(mutes = baseline.apply(current.mutes), baseline = null, baselineReview = baseline.addresses)
            event("baseline_finished", SearchJson.encodeToString(baseline.addresses))
        }
    }
    fun updateSettings(settings: Settings) = enqueue {
        val valid = settings.validated(); preferences.settings(valid)
        // A generation survives StateFlow conflation, including a rapid off/on toggle.
        val generation = current.gpsGeneration + if (valid.gps != current.settings.gps) 1 else 0
        current = current.copy(settings = valid, gpsGeneration = generation); event("settings", SearchJson.encodeToString(valid)); publish()
    }
    fun reportError(message: String) = enqueue { current = current.copy(error = message); publish() }
    suspend fun exportSnapshot(id: String, address: String?): SessionExporter.Snapshot = serial {
        val candidate = if (id == current.sessionId && address != null) current.targetSeed?.let(identity::candidate)
            ?.takeIf { address in it.addresses } else null
        SessionExporter(db).snapshot(id, address, candidate)
    }
    suspend fun allExportSelection(): AllSessionsExporter.Selection = serial {
        AllSessionsExporter.Selection(db.dao().storedSessions().map { it.id }, current.settings, current.mutes.persistent)
    }
    suspend fun recent(address: String): List<Observation> = serial {
        session?.let { active ->
            val members = if (address == current.target) current.targetSeed?.let { identity.candidate(it).addresses } ?: setOf(address) else setOf(address)
            members.flatMap { member -> db.dao().recent(active.id, member).map { row -> SearchJson.decodeFromString<Observation>(row.json) } }
                .sortedByDescending { it.elapsedMillis }.take(50)
        } ?: emptyList()
    }
}
