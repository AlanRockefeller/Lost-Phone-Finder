package org.blefinder

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.blefinder.service.SearchWakeLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class, manifest = Config.NONE)
class SearchWakeLockTest {
    @Test fun stopReleasesCpuLockAndRestartAcquiresANewOne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = SearchWakeLock(context, scope)
        try {
            owner.start()
            val first = ShadowPowerManager.getLatestWakeLock()
            assertTrue(first.isHeld)
            owner.start()
            assertSame(first, ShadowPowerManager.getLatestWakeLock())
            owner.stop()
            assertFalse(first.isHeld)
            owner.start()
            val second = ShadowPowerManager.getLatestWakeLock()
            assertNotSame(first, second)
            assertTrue(second.isHeld)
            owner.stop()
            assertFalse(second.isHeld)
        } finally { owner.stop(); scope.cancel() }
    }

    @Test fun serviceScopeCancellationReleasesCpuLock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = SearchWakeLock(context, scope)
        owner.start()
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(lock.isHeld)
        scope.cancel()
        assertFalse(lock.isHeld)
        owner.stop()
    }
}
