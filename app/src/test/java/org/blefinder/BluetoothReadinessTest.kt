package org.blefinder

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.blefinder.service.Readiness
import org.blefinder.service.SearchService
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = SearchApplication::class)
class BluetoothReadinessTest {
    private lateinit var app: SearchApplication
    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(*Readiness.requiredPermissions())
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(false)
    }
    @After fun close() = runBlocking {
        val job = app.scope.coroutineContext[Job]!!
        job.cancel()
        withTimeout(5000) {
            while (!job.isCompleted) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(1)
            }
        }
        app.database.close()
    }
    @Test fun disabledBluetoothExplainsHowToStartAndSimulationRemainsAvailable() {
        assertTrue(Readiness.startIssue(app, false)!!.contains("Turn on Bluetooth"))
        assertNull(Readiness.startIssue(app, true))
    }
    @Test fun bluetoothOffRacePromotesThenStopsWithoutWaitingForStorage() = runBlocking {
        val controller = Robolectric.buildService(SearchService::class.java).create()
        try {
            controller.startCommand(0, 1)
            val service = shadowOf(controller.get())
            assertTrue(service.lastForegroundNotificationId > 0)
            assertTrue(service.isStoppedBySelf)
            controller.startCommand(0, 2)
            assertFalse(service.isLastForegroundNotificationAttached)
            app.repository.recent("unused")
            assertFalse(app.repository.state.value.active)
            assertFalse(app.repository.state.value.hasSearched)
            assertTrue(app.repository.state.value.error!!.contains("Bluetooth"))
        } finally { controller.destroy() }
    }
    @Test fun turningBluetoothOffFinalizesBeforeStoppingService() = runBlocking {
        val controller = Robolectric.buildService(SearchService::class.java).create()
        try {
            app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_TURNING_OFF))
            awaitStopped(controller.get())
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        } finally { controller.destroy() }
    }
    private suspend fun awaitStopped(service: SearchService) {
        withTimeout(5000) {
            while (!shadowOf(service).isStoppedBySelf) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(1)
            }
        }
    }

    @Test fun normalStopRetainsForegroundServiceUntilSessionIsSavedIncludingDuplicateStarts() = runBlocking {
        org.robolectric.shadows.ShadowStatFs.registerStats(
            app.getDatabasePath("ble-search.db").parentFile!!.absolutePath, 1_000_000, 900_000, 900_000)
        app.repository.start(false)
        val id = app.repository.state.value.sessionId!!
        val controller = Robolectric.buildService(SearchService::class.java).create()
        val instance = controller.get()
        instance.startForeground(1, android.app.Notification.Builder(app, "search").build())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = launch(Dispatchers.IO) {
            app.database.runInTransaction {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            instance.onStartCommand(Intent(app, SearchService::class.java).setAction(SearchService.STOP), 0, 1)
            val service = shadowOf(instance)
            assertFalse(service.isStoppedBySelf)
            assertTrue(service.isLastForegroundNotificationAttached)
            controller.startCommand(0, 2)
            assertFalse(service.isStoppedBySelf)
            assertTrue(service.isLastForegroundNotificationAttached)
            release.countDown()
            blocker.join()
            awaitStopped(instance)
            assertFalse(service.isLastForegroundNotificationAttached)
            withContext(Dispatchers.IO) {
                val session = app.database.dao().session(id)!!
                assertEquals("stopped", session.status)
                assertNotNull(session.endedAt)
                assertEquals(1, app.database.dao().events(id, Long.MAX_VALUE).count { it.type == "stop" })
            }
        } finally {
            release.countDown()
            blocker.join()
            controller.destroy()
        }
    }

    @Test fun gpsCanBeToggledWithoutRestartingTheSearch() = runBlocking {
        org.blefinder.data.Preferences(app).settings(org.blefinder.core.Settings())
        org.robolectric.shadows.ShadowStatFs.registerStats(
            app.getDatabasePath("ble-search.db").parentFile!!.absolutePath, 1_000_000, 900_000, 900_000)
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        val location = shadowOf(app.getSystemService(android.location.LocationManager::class.java))
        val provider = android.location.LocationManager.GPS_PROVIDER
        location.setLocationEnabled(true)
        location.setProviderEnabled(provider, true)
        val controller = Robolectric.buildService(SearchService::class.java).create()
        suspend fun awaitGps(enabled: Boolean) {
            withTimeout(5000) {
                while (location.getLocationRequests(provider).isNotEmpty() != enabled) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(1)
                }
            }
        }
        try {
            controller.startCommand(0, 1)
            withTimeout(5000) {
                while (!app.repository.state.value.active) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(1)
                }
            }
            val id = app.repository.state.value.sessionId!!
            assertTrue(location.getLocationRequests(provider).isEmpty())
            app.repository.updateSettings(app.repository.state.value.settings.copy(gps = true))
            awaitGps(true)
            val fix = android.location.Location(provider).apply {
                latitude = 1.0; longitude = 2.0; accuracy = 3f
                time = 1000; elapsedRealtimeNanos = 1_000_000_000
            }
            location.simulateLocation(fix)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            app.repository.exportSnapshot(id, null)
            withContext(Dispatchers.IO) { assertEquals(1, app.database.dao().locations(id, 0, Long.MAX_VALUE).size) }
            app.repository.updateSettings(app.repository.state.value.settings.copy(gps = false))
            awaitGps(false)
            location.simulateLocation(fix)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            app.repository.exportSnapshot(id, null)
            withContext(Dispatchers.IO) { assertEquals(1, app.database.dao().locations(id, 0, Long.MAX_VALUE).size) }
            assertEquals(id, app.repository.state.value.sessionId)
            assertTrue(app.repository.state.value.active)
            assertFalse(shadowOf(controller.get()).isStoppedBySelf)
            app.repository.updateSettings(app.repository.state.value.settings.copy(gps = true))
            awaitGps(true)
            controller.get().onStartCommand(Intent(app, SearchService::class.java).setAction(SearchService.STOP), 0, 2)
            awaitStopped(controller.get())
            assertTrue(location.getLocationRequests(provider).isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun recordingsAreBundledAndContainWholeOfflineClips() {
        val recordings = org.blefinder.audio.loadDiscoveryRecordings(app)
        assertEquals(setOf(org.blefinder.core.DiscoverySound.TUGBOAT, org.blefinder.core.DiscoverySound.OROPENDOLA), recordings.keys)
        recordings.values.forEach { pcm ->
            assertTrue(pcm.size in 48_000..144_000)
            assertEquals(0, pcm.first().toInt())
            assertEquals(0, pcm.last().toInt())
            assertTrue(pcm.maxOf { kotlin.math.abs(it.toInt()) } in 29000..30000)
        }
    }

    @Test fun startDuringShutdownAttemptsPromotionAndStopsAgainIfItFails() = runBlocking {
        val controller = Robolectric.buildService(SearchService::class.java).create()
        try {
            controller.startCommand(0, 1) // Bluetooth off begins shutdown.
            val service = shadowOf(controller.get())
            assertTrue(service.isStoppedBySelf)
            service.setThrowInStartForeground(SecurityException("promotion regression test"))
            controller.startCommand(0, 2)
            app.repository.recent("unused")
            assertTrue(app.repository.state.value.error!!.contains("promotion regression test"))
            assertTrue(service.isStoppedBySelf)
            assertFalse(service.isLastForegroundNotificationAttached)
        } finally { controller.destroy() }
    }

    @Test fun duplicateStartRestoresForegroundNotificationDuringPendingStartup() {
        shadowOf(app.getSystemService(BluetoothManager::class.java).adapter).setEnabled(true)
        shadowOf(app.getSystemService(android.location.LocationManager::class.java)).setLocationEnabled(true)
        val controller = Robolectric.buildService(SearchService::class.java).create()
        try {
            controller.startCommand(0, 1)
            val instance = controller.get()
            val service = shadowOf(instance)
            assertNotNull(service.lastForegroundNotification)
            instance.stopForeground(android.app.Service.STOP_FOREGROUND_REMOVE)
            assertNull(service.lastForegroundNotification)
            controller.startCommand(0, 2)
            assertNotNull(service.lastForegroundNotification)
        } finally {
            controller.get().onStartCommand(Intent(app, SearchService::class.java).setAction(SearchService.STOP), 0, 3)
            controller.destroy()
        }
    }

}
