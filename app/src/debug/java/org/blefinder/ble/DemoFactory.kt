package org.blefinder.ble

import android.os.SystemClock
import kotlinx.coroutines.*
import org.blefinder.core.*
import kotlin.math.sin

object DemoFactory {
    const val available = true
    fun create(scope: CoroutineScope, receive: (Observation) -> Unit): BleSource = object : BleSource {
        private var job: Job? = null
        override fun start() {
            job = scope.launch {
                var tick = 0
                val names = listOf("Trail phone", "Search watch", "BLE sensor", "Camp beacon")
                while (isActive) {
                    val count = if (tick < 16) 3 else 4
                    repeat(count) { i ->
                        val now = System.currentTimeMillis(); val nanos = SystemClock.elapsedRealtimeNanos()
                        val name = names[i].toByteArray()
                        val manufacturer = if (i == 0) "07FF4C0002150102" else "05FF59000102"
                        val bytes = "02010603030F18".hexBytes() + byteArrayOf((name.size + 1).toByte(), 9) + name +
                            manufacturer.hexBytes() + "04160F1864020AFC00".hexBytes()
                        receive(Observation("D0:DE:00:00:00:0$i", now, now,
                            (-72 + i * 3 + sin(tick / 7.0 + i) * 22).toInt(), nanos / 1_000_000,
                            "random", names[i], AdvertisementParser.parse(bytes),
                            ScanMetadata(nanos, connectable = i == 0, legacy = true, primaryPhy = 1, secondaryPhy = 0,
                                androidAddressType = 1, dataStatus = 0), simulated = true))
                    }
                    tick++; delay(350)
                }
            }
        }
        override fun stop() { job?.cancel(); job = null }
    }
}
