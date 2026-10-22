package lucid.core.engine

import lucid.core.model.HeartRateSample
import lucid.core.model.IbiSample
import lucid.core.model.MotionSample
import lucid.core.model.SampleQuality
import lucid.core.model.SensorSample
import lucid.core.model.SensorStatus
import lucid.core.model.SensorStatusSample
import kotlinx.serialization.Serializable

@Serializable
enum class Rejection { BEFORE_SESSION, FUTURE, LATE, OUT_OF_RANGE, DUPLICATE, NO_CONTACT, INVALID }

/** Checks a single sample for plausibility. Stateless apart from duplicate detection. */
class SampleValidator(private val sessionStartMs: Long) {
    private val lastT = LongArray(4) { Long.MIN_VALUE }

    fun check(s: SensorSample): Rejection? {
        if (s.t < sessionStartMs - 1_000) return Rejection.BEFORE_SESSION
        val slot = when (s) {
            is HeartRateSample -> 0
            is IbiSample -> 1
            is MotionSample -> 2
            is SensorStatusSample -> 3
        }
        // Per channel, timestamps strictly increase; anything at or before the last one is a resend.
        if (s.t <= lastT[slot]) return Rejection.DUPLICATE
        val problem = when (s) {
            is HeartRateSample -> when {
                s.quality == SampleQuality.NO_CONTACT -> Rejection.NO_CONTACT
                s.bpm.isNaN() || s.bpm !in 30f..220f -> Rejection.OUT_OF_RANGE
                else -> null
            }
            is IbiSample -> when {
                s.quality == SampleQuality.NO_CONTACT -> Rejection.NO_CONTACT
                s.ibiMs !in 280..2200 -> Rejection.OUT_OF_RANGE
                else -> null
            }
            is MotionSample -> if (s.activityMg.isNaN() || s.activityMg < 0f || s.angleChangeDeg.isNaN()) Rejection.INVALID else null
            is SensorStatusSample -> null
        }
        // Remember the timestamp even for rejected NO_CONTACT samples so repeats count as duplicates.
        lastT[slot] = s.t
        return problem
    }
}

/** Raw material for one epoch after validation. */
class EpochData(val index: Int, val startMs: Long, val endMs: Long) {
    val hr = ArrayList<HeartRateSample>(32)
    val ibi = ArrayList<IbiSample>(48)
    val motion = ArrayList<MotionSample>(32)
    var noContactSeconds = 0
    var offWrist = false
    val rejections = IntArray(Rejection.entries.size)

    val rejectedCount: Int get() = rejections.sum()
}

/**
 * Buckets samples into fixed epochs aligned to the session start and releases epochs in order
 * once every active channel has moved past their end plus [latenessMs].
 *
 * Channels arrive at different delays on real hardware (the Galaxy Watch batches heart rate in the
 * sensor hub while motion summaries go out every second; the Samsung SDK flushes each tracker
 * separately). So the release point is the slowest active channel, not the newest sample. A
 * channel that has fallen more than [stallMs] behind the newest one (e.g. heart rate during lost
 * skin contact) stops holding epochs back. Gaps produce empty epochs; samples for released
 * epochs are LATE.
 */
class EpochAccumulator(
    private val sessionStartMs: Long,
    private val epochMs: Long,
    private val latenessMs: Long,
    private val stallMs: Long = 120_000,
) {
    private val open = sortedMapOf<Int, EpochData>()
    private var nextIndex = 0

    /**
     * Heart rate and motion are expected from the first second, so they hold epochs back even before
     * their first sample arrives (a batched heart-rate delivery would otherwise find the first epochs
     * already closed). Inter-beat intervals are optional and only count once seen.
     */
    private val channelLatest = LongArray(4) { Long.MIN_VALUE }.also {
        it[0] = sessionStartMs
        it[2] = sessionStartMs
    }

    fun indexOf(t: Long): Int = Math.floorDiv(t - sessionStartMs, epochMs).toInt().coerceAtLeast(0)

    private fun bucket(index: Int) = open.getOrPut(index) {
        EpochData(index, sessionStartMs + index * epochMs, sessionStartMs + (index + 1) * epochMs)
    }

    private fun channel(s: SensorSample) = when (s) {
        is HeartRateSample -> 0
        is IbiSample -> 1
        is MotionSample -> 2
        is SensorStatusSample -> 3
    }

    private var anySample = false

    /** Point in time up to which all active channels are complete. */
    private fun watermark(): Long {
        if (!anySample) return Long.MIN_VALUE
        val newest = channelLatest.max()
        // Status samples are sparse and never hold epochs back.
        return (0..2).map { channelLatest[it] }.filter { it != Long.MIN_VALUE && newest - it <= stallMs }.minOrNull() ?: newest
    }

    /** Returns false if the sample's epoch was already released. */
    fun add(s: SensorSample, rejection: Rejection?): Boolean {
        val idx = indexOf(s.t)
        if (idx < nextIndex) return false
        val ch = channel(s)
        channelLatest[ch] = maxOf(channelLatest[ch], s.t)
        anySample = true
        val e = bucket(idx)
        if (rejection != null) {
            e.rejections[rejection.ordinal]++
            if (rejection == Rejection.NO_CONTACT && s is HeartRateSample) e.noContactSeconds++
            return true
        }
        when (s) {
            is HeartRateSample -> e.hr += s
            is IbiSample -> e.ibi += s
            is MotionSample -> e.motion += s
            is SensorStatusSample -> if (s.status == SensorStatus.OFF_WRIST) e.offWrist = true
        }
        return true
    }

    /** Release every epoch that can no longer receive samples. */
    fun release(): List<EpochData> {
        val w = watermark()
        if (w == Long.MIN_VALUE) return emptyList()
        return releaseThrough(indexOf(w - latenessMs) - 1)
    }

    /** Release everything up to and including the epoch of the newest sample (end of session). */
    fun flush(): List<EpochData> = if (!anySample) emptyList() else releaseThrough(indexOf(channelLatest.max()))

    private fun releaseThrough(last: Int): List<EpochData> {
        if (last < nextIndex) return emptyList()
        val out = ArrayList<EpochData>(last - nextIndex + 1)
        for (i in nextIndex..last) {
            out += open.remove(i) ?: EpochData(i, sessionStartMs + i * epochMs, sessionStartMs + (i + 1) * epochMs)
        }
        nextIndex = last + 1
        return out
    }
}
