package lucid.wear.sensors

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Built without the Samsung Health Sensor SDK (its AAR is not in wear/libs/). Real-sensor mode
 * falls back to [PlatformSensorSource]: heart rate and accelerometer, no inter-beat intervals.
 */
object SamsungHealth {
    const val COMPILED_IN = false

    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, scope: CoroutineScope, onUnavailable: (String) -> Unit): SensorSource? = null
}
