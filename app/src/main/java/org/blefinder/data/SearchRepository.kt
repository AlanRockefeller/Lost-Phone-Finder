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
    private val initialized = CompletableDeferred<Unit>()
    var sound: ((Observation, Boolean, Settings) -> Unit)? = null
    var fatalError: (() -> Unit)? = null
    init {
        scope.launch(Dispatchers.IO) {
            try {
                val (settings, persistent) = preferences.load()
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
        val history = signalHistory.mapValues { it.value.toList() }
        val featured = strongestSelector.update(devices.values, current.mutes, history, now)
        current = current.copy(featuredAddress = featured, devices = devices.values.toList(), targetStats = current.target?.let { targetOverride ?: devices[it]?.stats },
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
        db.dao().putSession(s); session = s; devices.clear(); signalHistory.clear(); strongestSelector.reset(); targetOverride = null
        current = current.copy(sessionId = s.id, simulated = simulated, mutes = current.mutes.nextSession(), target = null,
            baseline = null, baselineReview = emptySet())
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
    fun receive(observation: Observation) {
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
            try { storeObservation(observation) }
            finally { pendingResults.decrementAndGet() }
        }
    }
    private fun storeObservation(observation: Observation) {
        if (!checkStorage()) return
        if (observation.address !in devices && devices.size >= limits.maxSessionAddresses) {
            stopForLimit("Session address limit reached (${limits.maxSessionAddresses}).")
            return
        }
        // Baseline membership uses callback receipt time, so delayed controller timestamps are not misclassified.
        current = current.copy(baseline = current.baseline?.observe(observation.address, observation.receivedElapsedMillis))
        val o = observation.copy(target = current.target == observation.address, mute = current.mutes.kind(observation.address))
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
        if (o.target && targetOverride != null) targetOverride = targetOverride!!.add(o, current.settings.smoothing)
    }
    fun location(fix: GeoFix) = enqueue {
        if (checkStorage()) session?.let { db.dao().insertLocation(LocationEntity(sessionId = it.id, json = SearchJson.encodeToString(fix))) }
    }
    fun selectTarget(address: String?) = enqueue { current = current.copy(target = address); targetOverride = null; event("target", address ?: "scan mode"); publish() }
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
        current = current.copy(target = null, baseline = Baseline(now, now + current.settings.baselineSeconds * 1000L), baselineReview = emptySet())
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
        current = current.copy(settings = valid); event("settings", SearchJson.encodeToString(valid)); publish()
    }
    fun reportError(message: String) = enqueue { current = current.copy(error = message); publish() }
    suspend fun exportSnapshot(id: String, address: String?): SessionExporter.Snapshot = serial { SessionExporter(db).snapshot(id, address) }
    suspend fun recent(address: String): List<Observation> = serial {
        session?.let { db.dao().recent(it.id, address).map { row -> SearchJson.decodeFromString<Observation>(row.json) } } ?: emptyList()
    }
}
