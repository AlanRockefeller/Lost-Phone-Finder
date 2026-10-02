package org.blefinder.ble

import kotlinx.coroutines.CoroutineScope
import org.blefinder.core.Observation

object DemoFactory {
    const val available = false
    fun create(scope: CoroutineScope, receive: (Observation) -> Unit): BleSource =
        error("Simulation is absent from release builds")
}
