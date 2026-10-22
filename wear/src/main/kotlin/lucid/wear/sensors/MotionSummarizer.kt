package lucid.wear.sensors

import lucid.core.model.MotionSample
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Reduces raw accelerometer readings (m/s^2) to one [MotionSample] per second, so 25-50 Hz data
 * never crosses Bluetooth:
 * - activity: mean |‖a‖ - 1 g| in milli-g (ENMO-like, both directions)
 * - angle change: change of the arm's z-angle (van Hees et al. 2015) against the previous second
 */
class MotionSummarizer {
    private var second = Long.MIN_VALUE
    private var sumDev = 0.0
    private var sumAngle = 0.0
    private var n = 0
    private var lastAngle: Double? = null

    /** Add one reading; returns a summary when a second completes. */
    fun add(tMs: Long, x: Float, y: Float, z: Float): MotionSample? {
        val sec = Math.floorDiv(tMs, 1000L)
        var out: MotionSample? = null
        if (sec != second) {
            out = close()
            second = sec
        }
        val g = sqrt((x * x + y * y + z * z).toDouble()) / G
        sumDev += abs(g - 1.0)
        sumAngle += Math.toDegrees(atan2(z.toDouble(), sqrt((x * x + y * y).toDouble())))
        n++
        return out
    }

    fun close(): MotionSample? {
        if (n == 0 || second == Long.MIN_VALUE) return null
        val angle = sumAngle / n
        val change = lastAngle?.let { abs(angle - it) } ?: 0.0
        lastAngle = angle
        val sample = MotionSample(second * 1000, (sumDev / n * 1000).toFloat(), change.toFloat())
        sumDev = 0.0; sumAngle = 0.0; n = 0
        return sample
    }

    companion object {
        const val G = 9.80665
    }
}
