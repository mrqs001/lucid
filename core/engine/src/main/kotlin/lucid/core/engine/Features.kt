package lucid.core.engine

import kotlinx.serialization.Serializable
import lucid.core.model.SampleQuality
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Physiological features of one 30 s epoch. Window features (instability, RMSSD) look back over
 * the trailing [FeatureExtractor.WINDOW_EPOCHS] epochs. Null means "not enough data", never zero.
 */
@Serializable
data class EpochFeatures(
    val index: Int,
    val startMs: Long,
    val hrMean: Float?,
    /** Fraction of the expected 1 Hz HR samples that arrived with usable quality. */
    val hrCoverage: Float,
    /** Heart rate above tonight's (or the prior) sleeping baseline, bpm. */
    val hrRel: Float?,
    /** Standard deviation of 1 Hz heart rate over the trailing window, bpm. */
    val hrInstability: Float?,
    /**
     * ln(instability / tonight's median instability during quiet epochs). Relative, so it does not
     * depend on how strongly a given watch smooths its heart-rate output.
     */
    val hrInstabilityRel: Float? = null,
    /** Root mean square of successive IBI differences over the trailing window, ms. */
    val rmssdMs: Float?,
    /** ln(RMSSD) minus tonight's median ln(RMSSD) during quiet epochs. */
    val lnRmssdRel: Float?,
    val cleanIbiCount: Int,
    val ibiArtifactFraction: Float,
    val activityMg: Float?,
    val activeSeconds: Int,
    val maxAngleChangeDeg: Float,
    val motionCoverage: Float,
    val minutesSinceMovement: Float?,
    val contactLost: Boolean,
    val baselineReady: Boolean,
    /** 0..1 summary of how trustworthy this epoch's data is. */
    val quality: Float,
    val rejectedSamples: Int,
)

/**
 * Converts epochs of validated samples into [EpochFeatures]. Keeps the short history needed for
 * window features and the running per-night baselines.
 */
class FeatureExtractor(
    private val epochMs: Long,
    private val priorHrBaseline: Float?,
) {
    private val ibiCleaner = IbiCleaner()
    private val recent = ArrayDeque<EpochWindow>()
    private val quietHr = ArrayList<Float>()
    private val quietLnRmssd = ArrayList<Float>()
    private val quietLnInstability = ArrayList<Float>()
    private var lastMovementMs: Long? = null
    private val recentActivity = ArrayDeque<Float>()

    private class EpochWindow(val hr: FloatArray, val ibis: List<CleanIbi>)

    fun extract(e: EpochData): EpochFeatures {
        val expectedSeconds = (epochMs / 1000).toInt()
        val usableHr = e.hr.filter { it.quality != SampleQuality.POOR }
        val poorFraction = if (e.hr.isEmpty()) 0f else (e.hr.size - usableHr.size).toFloat() / e.hr.size
        val hrCoverage = (usableHr.size.toFloat() / expectedSeconds).coerceIn(0f, 1f)
        val hrMean = if (usableHr.size >= expectedSeconds / 3) usableHr.map { it.bpm }.average().toFloat() else null

        // Intervals flagged POOR by the watch (motion, loose strap) never enter HRV features.
        val cleaned = ibiCleaner.clean(e.ibi.filter { it.quality != SampleQuality.POOR }.map { it.t to it.ibiMs })
        val artifactFraction = if (e.ibi.isEmpty()) 0f else 1f - cleaned.count { it.accepted }.toFloat() / e.ibi.size

        recent.addLast(EpochWindow(usableHr.map { it.bpm }.toFloatArray(), cleaned.filter { it.accepted }))
        while (recent.size > WINDOW_EPOCHS) recent.removeFirst()

        val windowHr = recent.flatMap { it.hr.asList() }
        val hrInstability = if (windowHr.size >= 60) stdDev(windowHr) else null
        val windowIbis = recent.flatMap { it.ibis }
        val rmssd = rmssd(windowIbis)

        val motionCoverage = (e.motion.size.toFloat() / expectedSeconds).coerceIn(0f, 1f)
        val moving = e.motion.map { isMovement(it.activityMg, it.angleChangeDeg) }
        val activeSeconds = moving.count { it }
        val activity = if (e.motion.isEmpty()) null else e.motion.map { it.activityMg }.average().toFloat()
        if (activeSeconds >= 2) lastMovementMs = e.motion[moving.lastIndexOf(true)].t
        val minutesSinceMovement = when {
            e.motion.isEmpty() -> null
            lastMovementMs == null -> (e.endMs - e.startMs).toFloat() * (e.index + 1) / 60_000f
            else -> (e.endMs - lastMovementMs!!) / 60_000f
        }

        val contactLost = e.offWrist || e.noContactSeconds > expectedSeconds / 2

        // Quiet epochs (no movement, decent coverage) feed the nightly baselines.
        val quiet = activeSeconds == 0 && hrCoverage >= 0.6f && !contactLost
        if (quiet && hrMean != null) quietHr += hrMean
        val lnRmssd = rmssd?.let { ln(it) }
        if (quiet && lnRmssd != null) quietLnRmssd += lnRmssd
        val lnInstability = hrInstability?.let { ln(it.coerceAtLeast(0.05f)) }
        if (quiet && lnInstability != null) quietLnInstability += lnInstability

        val baselineReady = quietHr.size >= BASELINE_READY_EPOCHS
        val hrBaseline = when {
            baselineReady -> percentile(quietHr, 0.2f)
            priorHrBaseline != null -> priorHrBaseline
            quietHr.size >= 4 -> percentile(quietHr, 0.2f)
            else -> null
        }
        val lnRmssdBaseline = if (quietLnRmssd.size >= 6) percentile(quietLnRmssd, 0.5f) else null
        val lnInstabilityBaseline = if (quietLnInstability.size >= 10) percentile(quietLnInstability, 0.5f) else null

        val ibiPenalty = if (e.ibi.isEmpty()) 1f else 1f - (2f * artifactFraction).coerceAtMost(1f)
        val quality = if (contactLost) {
            0f
        } else {
            (hrCoverage * (1f - 0.5f * poorFraction) * ibiPenalty * (if (motionCoverage < 0.5f) 0.7f else 1f)).coerceIn(0f, 1f)
        }

        return EpochFeatures(
            index = e.index,
            startMs = e.startMs,
            hrMean = hrMean,
            hrCoverage = hrCoverage,
            hrRel = if (hrMean != null && hrBaseline != null) hrMean - hrBaseline else null,
            hrInstability = hrInstability,
            hrInstabilityRel = if (lnInstability != null && lnInstabilityBaseline != null) lnInstability - lnInstabilityBaseline else null,
            rmssdMs = rmssd,
            lnRmssdRel = if (lnRmssd != null && lnRmssdBaseline != null) lnRmssd - lnRmssdBaseline else null,
            cleanIbiCount = cleaned.count { it.accepted },
            ibiArtifactFraction = artifactFraction,
            activityMg = activity,
            activeSeconds = activeSeconds,
            maxAngleChangeDeg = e.motion.maxOfOrNull { it.angleChangeDeg } ?: 0f,
            motionCoverage = motionCoverage,
            minutesSinceMovement = minutesSinceMovement,
            contactLost = contactLost,
            baselineReady = baselineReady || priorHrBaseline != null,
            quality = quality,
            rejectedSamples = e.rejectedCount,
        )
    }

    /**
     * Activity is |acceleration magnitude - 1 g|, so an accelerometer whose gain is off by a few percent
     * reads tens of mg while perfectly still, and the offset changes with wrist orientation (seen on a
     * Galaxy Watch7: still plateaus from 4 to 47 mg). Movement is therefore judged against the median of
     * the preceding [REST_WINDOW_S] seconds, i.e. this wrist position's resting level.
     */
    private fun isMovement(activityMg: Float, angleChangeDeg: Float): Boolean {
        val rest = if (recentActivity.size >= 10) percentile(recentActivity.toList(), 0.5f) else 0f
        recentActivity.addLast(activityMg)
        if (recentActivity.size > REST_WINDOW_S) recentActivity.removeFirst()
        return activityMg - rest > ACTIVE_MG || angleChangeDeg > ACTIVE_DEG
    }

    companion object {
        const val WINDOW_EPOCHS = 5
        const val REST_WINDOW_S = 60
        const val BASELINE_READY_EPOCHS = 20
        /** Thresholds for "this second contained a real movement" (activity above the resting level, or rotation). */
        const val ACTIVE_MG = 40f
        const val ACTIVE_DEG = 3f

        fun stdDev(xs: List<Float>): Float {
            val m = xs.average()
            return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1).coerceAtLeast(1)).toFloat()
        }

        fun percentile(xs: List<Float>, q: Float): Float {
            val sorted = xs.sorted()
            val pos = q * (sorted.size - 1)
            val lo = pos.toInt()
            val hi = minOf(lo + 1, sorted.lastIndex)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }

        /** RMSSD over successive pairs of accepted beats that were adjacent in the raw series. */
        fun rmssd(ibis: List<CleanIbi>, minPairs: Int = 40): Float? {
            var sum = 0.0
            var n = 0
            for (i in 1 until ibis.size) {
                if (!ibis[i].followsAccepted) continue
                val d = (ibis[i].ibiMs - ibis[i - 1].ibiMs).toDouble()
                sum += d * d
                n++
            }
            return if (n >= minPairs) sqrt(sum / n).toFloat() else null
        }
    }
}

data class CleanIbi(val t: Long, val ibiMs: Int, val accepted: Boolean, val followsAccepted: Boolean)

/**
 * Removes ectopic beats and missed detections: an interval deviating more than [tolerance] from
 * the median of the recent accepted intervals is rejected. Stateful across epochs.
 */
class IbiCleaner(private val tolerance: Float = 0.2f, private val historySize: Int = 7) {
    private val history = ArrayDeque<Int>()
    private var previousAccepted = false
    private var rejectedStreak = 0

    fun clean(ibis: List<Pair<Long, Int>>): List<CleanIbi> = ibis.map { (t, ibi) ->
        val ok = if (history.size < 3) {
            true
        } else {
            val ref = history.sorted()[history.size / 2]
            abs(ibi - ref) <= tolerance * ref
        }
        val follows = ok && previousAccepted
        if (ok) {
            history.addLast(ibi)
            while (history.size > historySize) history.removeFirst()
        } else if (history.size >= 3 && rejectedStreak++ > 8) {
            // A genuine step change in heart rate: restart the reference instead of rejecting forever.
            history.clear()
            rejectedStreak = 0
        }
        if (ok) rejectedStreak = 0
        previousAccepted = ok
        CleanIbi(t, ibi, ok, follows)
    }
}
