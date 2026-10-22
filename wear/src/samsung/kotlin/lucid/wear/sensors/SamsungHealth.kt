package lucid.wear.sensors

import android.content.Context
import android.os.Build
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lucid.core.model.DataOrigin
import lucid.core.model.HeartRateSample
import lucid.core.model.IbiSample
import lucid.core.model.SampleQuality
import lucid.core.model.SensorSample
import lucid.core.model.SensorStatus
import lucid.core.model.SensorStatusSample
import lucid.core.model.SourceInfo

/**
 * Compiled only when samsung-health-sensor-api*.aar is in wear/libs/.
 *
 * STATUS: written against the documented SDK 1.4.x API but NOT yet compiled or run, because the
 * AAR is not redistributable and the watch has not arrived. Verify on the Galaxy Watch 7 with
 * Health Platform developer mode enabled (see docs/HARDWARE.md).
 */
object SamsungHealth {
    const val COMPILED_IN = true

    fun create(context: Context, scope: CoroutineScope, onUnavailable: (String) -> Unit): SensorSource =
        SamsungHealthSensorSource(context, scope, onUnavailable)
}

/**
 * Galaxy Watch sensors through the Samsung Health Sensor SDK: continuous heart rate (1 Hz) with
 * inter-beat intervals, and the raw 25 Hz accelerometer reduced to 1 s summaries on the watch.
 *
 * With the screen off the SDK batches data (about 10 min per delivery); [FLUSH_EVERY_MS] forces
 * delivery so cue decisions on the phone stay timely. Its battery cost has to be measured.
 */
class SamsungHealthSensorSource(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Called when the Samsung service can't be used, so the caller can fall back to plain sensors. */
    private val onUnavailable: (String) -> Unit = {},
) : SensorSource {
    override val info = SourceInfo(DataOrigin.REAL_DEVICE, "${Build.MANUFACTURER} ${Build.MODEL}", "samsung-health-sensor")
    override val capabilities = SensorCapabilities(heartRate = true, interBeatIntervals = true, accelerometer = true)
    override fun now() = System.currentTimeMillis()

    private var service: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var accelTracker: HealthTracker? = null
    private var flushJob: Job? = null
    private var sink: ((List<SensorSample>) -> Unit)? = null
    private val motion = MotionSummarizer()
    private var lastContact = true

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            sink?.invoke(convertHeartRate(points))
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) {
            Log.w(TAG, "heart-rate tracker error: $error (PERMISSION_ERROR = missing READ_HEART_RATE; SDK_POLICY_ERROR = developer mode off)")
            onUnavailable("Samsung heart-rate tracker error: $error")
        }
    }

    private val accelListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            val out = ArrayList<SensorSample>()
            for (p in points) {
                val x = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) ?: continue
                val y = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) ?: continue
                val z = p.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) ?: continue
                motion.add(p.timestamp, toMs2(x), toMs2(y), toMs2(z))?.let { out += it }
            }
            if (out.isNotEmpty()) sink?.invoke(out)
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) {
            Log.w(TAG, "accelerometer tracker error: $error")
        }
    }

    private val connection = object : ConnectionListener {
        override fun onConnectionSuccess() {
            val s = service ?: return
            val supported = s.trackingCapability.supportHealthTrackerTypes
            Log.i(TAG, "Health Sensor SDK connected; supported trackers: $supported")
            hrTracker = s.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS).also { it.setEventListener(hrListener) }
            if (HealthTrackerType.ACCELEROMETER_CONTINUOUS in supported) {
                accelTracker = s.getHealthTracker(HealthTrackerType.ACCELEROMETER_CONTINUOUS).also { it.setEventListener(accelListener) }
            }
            flushJob = scope.launch {
                while (isActive) {
                    delay(FLUSH_EVERY_MS)
                    runCatching { hrTracker?.flush(); accelTracker?.flush() }
                }
            }
        }

        override fun onConnectionEnded() {
            Log.i(TAG, "Health Sensor SDK connection ended")
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "Health Sensor SDK connection failed: code ${e.errorCode}, resolvable ${e.hasResolution()}")
            onUnavailable("Samsung Health Sensor service connection failed (code ${e.errorCode})")
        }
    }

    override fun start(onSamples: (List<SensorSample>) -> Unit, onLinkSimulation: (Boolean) -> Unit) {
        sink = onSamples
        service = HealthTrackingService(connection, context).also { it.connectService() }
    }

    override fun stop() {
        flushJob?.cancel()
        runCatching { hrTracker?.unsetEventListener() }
        runCatching { accelTracker?.unsetEventListener() }
        runCatching { service?.disconnectService() }
        motion.close()?.let { m -> sink?.invoke(listOf(m)) }
        sink = null
    }

    /**
     * One callback's data points -> samples. In a screen-off batch the SDK puts every IBI of the
     * batch into the first data point, so beat times are rebuilt backwards from the last point's
     * timestamp. (Approximation; check against real recordings.)
     */
    private fun convertHeartRate(points: List<DataPoint>): List<SensorSample> {
        val out = ArrayList<SensorSample>(points.size * 2)
        val ibis = ArrayList<Pair<Int, Int>>() // value, status
        for (p in points) {
            val status = p.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: 0
            val bpm = p.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: 0
            val quality = when (status) {
                1 -> SampleQuality.GOOD
                -3 -> SampleQuality.NO_CONTACT
                -2, -8, -10 -> SampleQuality.POOR
                else -> null // 0 = initialising, -999 = another sensor has priority
            }
            val contact = quality != SampleQuality.NO_CONTACT
            if (quality != null && contact != lastContact) {
                out += SensorStatusSample(p.timestamp, if (contact) SensorStatus.ON_WRIST else SensorStatus.OFF_WRIST)
                lastContact = contact
            }
            if (quality != null) out += HeartRateSample(p.timestamp, bpm.toFloat(), quality)
            val list = p.getValue(ValueKey.HeartRateSet.IBI_LIST)
            val statuses = p.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
            list?.forEachIndexed { i, v -> ibis += v to (statuses?.getOrNull(i) ?: 0) }
        }
        if (ibis.isNotEmpty() && points.isNotEmpty()) {
            var t = points.last().timestamp
            val beats = ArrayList<IbiSample>(ibis.size)
            for ((v, st) in ibis.asReversed()) {
                if (v <= 0) continue
                beats += IbiSample(t, v, if (st == 0) SampleQuality.GOOD else SampleQuality.POOR)
                t -= v
            }
            out += beats.asReversed()
        }
        return out.sortedBy { it.t }
    }

    private fun toMs2(raw: Int): Float = (raw * 9.81 / (16383.75 / 4.0)).toFloat()

    private companion object {
        const val TAG = "SamsungHealth"
        const val FLUSH_EVERY_MS = 60_000L
    }
}
