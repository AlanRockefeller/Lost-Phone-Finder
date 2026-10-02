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
        app.scope.coroutineContext[Job]!!.cancelAndJoin()
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
            app.repository.recent("unused")
            assertFalse(app.repository.state.value.active)
            assertFalse(app.repository.state.value.hasSearched)
            assertTrue(app.repository.state.value.error!!.contains("Bluetooth"))
        } finally { controller.destroy() }
    }
    @Test fun turningBluetoothOffStopsServiceImmediately() {
        val controller = Robolectric.buildService(SearchService::class.java).create()
        try {
            app.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_TURNING_OFF))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
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

}
