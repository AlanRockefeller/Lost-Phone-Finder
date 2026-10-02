package org.blefinder.service

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.blefinder.*
import org.blefinder.audio.ChirpEngine
import org.blefinder.ble.*

class SearchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo get() = (application as SearchApplication).repository
    private val wakeLock by lazy { SearchWakeLock(this, scope) }
    private var source: BleSource? = null
    private var audio: ChirpEngine? = null
    private var gps: LocationLogger? = null
    private var starting = false
    private var stopping = false
    private var simulated = false
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!simulated && !stopping && intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) in listOf(BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF)) {
                repo.reportError("Bluetooth switched off. Search stopped; turn it on and start again."); end()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Active phone search", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), RECEIVER_EXPORTED)
        else registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        repo.fatalError = { Handler(mainLooper).post { end() } }
    }
    @android.annotation.SuppressLint("MissingPermission") // A denied notification permission may suppress the drawer entry; FGS remains visible in Android active apps.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { end(); return START_NOT_STICKY }
        if (!starting && source == null && !stopping) {
            simulated = intent?.getBooleanExtra("simulate", false) == true && DemoFactory.available
        }
        try {
            check(Readiness.scanPermissions(this)) { "Grant Nearby devices and precise location permissions before starting" }
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) else 0
            val foregroundNotification = notification(repo.state.value.devices.size)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, foregroundNotification, type)
            else startForeground(NOTIFICATION, foregroundNotification)
            // Every foreground start request is promoted, including duplicates and shutdown races.
            if (stopping) { stopForegroundAndSelf(); return START_NOT_STICKY }
            if (starting || source != null) return START_NOT_STICKY
            starting = true
            // Promotion must precede radio checks: Bluetooth can switch off after the activity preflight.
            Readiness.startIssue(this, simulated)?.let { repo.reportError(it); end(); return START_NOT_STICKY }
            wakeLock.start()
            scope.launch {
                try {
                    repo.start(simulated)
                    if (stopping) return@launch
                    if (!repo.state.value.active) { end(); return@launch }
                    audio = ChirpEngine(this@SearchService) { repo.reportError(it) }
                    audio?.configure(repo.state.value.settings)
                    repo.sound = { observation, new, settings -> audio?.offer(observation, new, settings) }
                    if (repo.state.value.settings.gps && !simulated) {
                        gps = LocationLogger(this@SearchService, repo::location).also { it.start() }
                    }
                    val receive: (org.blefinder.core.Observation) -> Unit = { o -> repo.receive(o.copy(location = gps?.recent(o.elapsedMillis))) }
                    source = if (simulated) DemoFactory.create(scope, receive) else AndroidBleSource(this@SearchService, receive) {
                        repo.reportError(it); end()
                    }
                    scope.launch {
                        repo.state.map { Triple(it.audioMuted, it.target, it.mutes) }.distinctUntilChanged().collect {
                            audio?.silence(true); audio?.silence(it.first)
                        }
                    }
                    scope.launch {
                        repo.state.map { it.settings }.distinctUntilChanged().collect { audio?.configure(it) }
                    }
                    source!!.start()
                    scope.launch {
                        var lastCount = -1
                        while (isActive) {
                            val count = repo.state.value.devices.size
                            if (count != lastCount) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(count)); lastCount = count }
                            delay(1000)
                        }
                    }
                } catch (e: Exception) { repo.reportError("Unable to start search: ${e.message}"); end() }
                finally { starting = false }
            }
        } catch (e: Exception) {
            repo.reportError("Unable to start foreground search: ${e.message}")
            if (stopping) stopForegroundAndSelf() else end()
        }
        return START_NOT_STICKY
    }
    private fun notification(count: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, SearchService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_search)
            .setContentTitle(getString(if (simulated) R.string.search_simulation_title else R.string.search_active_title, getString(R.string.app_name)))
            .setContentText("$count addresses seen • Screen-off search active • Stop when finished")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop search", stop).build()).build()
    }
    private fun end() {
        if (stopping) return
        stopping = true
        wakeLock.stop()
        runCatching { source?.stop() }; source = null; runCatching { gps?.stop() }; gps = null; audio?.close(); audio = null; repo.sound = null
        // Never leave a rejected foreground-service request waiting for database initialization.
        stopForegroundAndSelf()
        (application as SearchApplication).scope.launch { runCatching { repo.stop() } }
    }
    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        wakeLock.stop()
        runCatching { source?.stop() }; runCatching { gps?.stop() }; audio?.close(); repo.sound = null; repo.fatalError = null
        unregisterReceiver(bluetoothReceiver)
        scope.cancel()
        if (!stopping) (application as SearchApplication).scope.launch { runCatching { repo.stop() } }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object { const val STOP = "org.blefinder.STOP"; private const val CHANNEL = "search"; private const val NOTIFICATION = 1 }
}
