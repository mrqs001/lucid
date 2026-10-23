package lucid.wear.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import lucid.core.model.DataOrigin
import lucid.core.model.HeartRateSample
import lucid.core.model.SampleQuality
import lucid.core.model.SensorSample
import lucid.core.model.SourceInfo

/**
 * Standard Android sensors: Sensor.TYPE_HEART_RATE (BPM only, no inter-beat intervals) and the
 * accelerometer at ~25 Hz, both batched in the sensor hub. Works on any Wear OS watch and on the
 * emulator (where `adb emu sensor set heart-rate 60` and the virtual sensors drive the values).
 *
 * Wake-up sensor variants are preferred: non-wake-up FIFOs overwrite data while the CPU sleeps.
 */
class PlatformSensorSource(context: Context) : SensorSource {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val hr: Sensor? = sm.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val motion = MotionSummarizer()
    private var sink: ((List<SensorSample>) -> Unit)? = null

    override val info = SourceInfo(DataOrigin.REAL_DEVICE, "${Build.MANUFACTURER} ${Build.MODEL}", "android-sensors")
    override val capabilities = SensorCapabilities(heartRate = hr != null, interBeatIntervals = false, accelerometer = accel != null)

    override fun now() = System.currentTimeMillis()

    private fun wallTime(eventNanos: Long): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - eventNanos) / 1_000_000

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val t = wallTime(e.timestamp)
            when (e.sensor.type) {
                Sensor.TYPE_HEART_RATE -> {
                    val q = when (e.accuracy) {
                        SensorManager.SENSOR_STATUS_NO_CONTACT -> SampleQuality.NO_CONTACT
                        SensorManager.SENSOR_STATUS_UNRELIABLE -> SampleQuality.POOR
                        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> SampleQuality.FAIR
                        else -> SampleQuality.GOOD
                    }
                    sink?.invoke(listOf(HeartRateSample(t, e.values[0], q)))
                }
                Sensor.TYPE_ACCELEROMETER -> motion.add(t, e.values[0], e.values[1], e.values[2])?.let { sink?.invoke(listOf(it)) }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun start(onSamples: (List<SensorSample>) -> Unit, onLinkSimulation: (Boolean) -> Unit) {
        sink = onSamples
        // Batch in the sensor hub for up to 10 s to let the CPU sleep between deliveries.
        hr?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, 10_000_000) }
        accel?.let { sm.registerListener(listener, it, 40_000, 25_000_000) }
    }

    override fun stop() {
        sm.unregisterListener(listener)
        motion.close()?.let { m -> sink?.invoke(listOf(m)) }
        sink = null
    }
}
