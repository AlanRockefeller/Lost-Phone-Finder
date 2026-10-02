package org.blefinder.service

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.*

/** CPU-only lock for a user-started search; the display can still turn off. */
internal class SearchWakeLock(context: Context, private val scope: CoroutineScope) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var renewal: Job? = null
    private var lock: PowerManager.WakeLock? = null

    fun start() {
        if (renewal?.isActive == true) return
        stop()
        val current = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "blefinder:active-search")
        current.setReferenceCounted(false)
        current.acquire(TIMEOUT_MS)
        lock = current
        renewal = scope.launch {
            while (isActive) {
                delay(RENEW_MS)
                current.acquire(TIMEOUT_MS)
            }
        }.also { it.invokeOnCompletion { release(current) } }
    }

    fun stop() {
        renewal?.cancel(); renewal = null
        lock?.let(::release); lock = null
    }

    private fun release(current: PowerManager.WakeLock) = synchronized(current) {
        if (current.isHeld) current.release()
    }

    private companion object {
        const val TIMEOUT_MS = 10 * 60 * 1000L
        const val RENEW_MS = 5 * 60 * 1000L
    }
}
