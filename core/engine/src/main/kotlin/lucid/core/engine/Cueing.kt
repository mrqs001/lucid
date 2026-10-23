package lucid.core.engine

import kotlinx.serialization.Serializable
import lucid.core.model.CueAck
import lucid.core.model.CueOutcome

@Serializable
enum class CueAction { DELIVER, SUPPRESS, WOULD_DELIVER }

/** Every rule that can block a cue. A decision lists all rules that failed, not just the first. */
@Serializable
enum class CueGate(val label: String) {
    STALE_DATA("Data too old"),
    WATCH_DISCONNECTED("Watch disconnected"),
    SLEEP_ONSET_UNKNOWN("Sleep onset not detected yet"),
    TOO_EARLY("Too early in the night"),
    LOW_CONFIDENCE("Low estimator confidence"),
    LOW_SIGNAL_QUALITY("Poor signal quality"),
    REM_NOT_SUSTAINED("REM not sustained long enough"),
    WAKE_LIKELY("Wake likely"),
    RECENT_MOVEMENT("Recent movement"),
    AROUSAL_BACKOFF("Pausing after a suspected arousal"),
    SPACING("Too soon after the previous cue"),
    MAX_PER_NIGHT("Nightly cue limit reached"),
    MAX_PER_REM_PERIOD("Cue limit for this REM period reached"),
}

@Serializable
data class GateFailure(val gate: CueGate, val detail: String)

/**
 * The outcome of evaluating one candidate epoch (REM probability at or above the threshold).
 * Stored for every candidate, delivered or not, so a night can be audited afterwards.
 */
@Serializable
data class CueDecision(
    val t: Long,
    val epochIndex: Int,
    val action: CueAction,
    val failures: List<GateFailure>,
    val explanation: String,
    val pRem: Float,
    val pWake: Float,
    val confidence: Float,
    val quality: Float,
    val remSustainedMin: Float,
    val hoursSinceOnset: Float?,
    val intensity: Float,
    val patternId: String,
    val cueId: String?,
    /** 1-based count of cues (or would-be cues on a baseline night) including this one. */
    val cueNumber: Int?,
)

@Serializable
enum class CueResponse(val label: String) {
    AROUSAL_SUSPECTED("Arousal suspected"),
    NO_REACTION("No visible reaction"),
}

@Serializable
data class CueResponseAssessment(
    val cueId: String,
    val t: Long,
    val response: CueResponse,
    val detail: String,
    val nextIntensity: Float,
)

data class CueInput(
    val features: EpochFeatures,
    val prediction: StagePrediction,
    val hoursSinceOnset: Float?,
    val epochEndMs: Long,
    val nowMs: Long,
    val linkConnected: Boolean,
)

data class CueOutput(val decision: CueDecision?, val assessments: List<CueResponseAssessment>)

/** Decides when to cue. Swappable so different strategies can be compared in experiments. */
interface CuePolicy {
    val id: String

    fun onEpoch(input: CueInput, newCueId: () -> String): CueOutput

    fun onAck(ack: CueAck)
}

/**
 * Targeted-lucidity-reactivation style policy: cue only inside probable, sustained, late-night
 * REM, with movement/wake lockouts, spacing and caps, and per-cue intensity titration that
 * backs off after any sign of arousal.
 */
class TlrCuePolicy(private val c: CueingConfig) : CuePolicy {
    override val id = "tlr-v1"

    private var remRunStartMs: Long? = null
    private var remRunGraceUsed = false
    private var inRemPeriod = false
    private var lowRemEpochs = 0
    private var cuesThisPeriod = 0
    private var cueCount = 0
    private var lastCueMs: Long? = null
    private var backoffUntilMs = Long.MIN_VALUE
    private var intensity = c.startIntensity
    private val recentQuality = ArrayDeque<Float>()
    private val pending = ArrayList<PendingCue>()

    private class PendingCue(val cueId: String, val deliveredAtMs: Long, val preHr: Float?, var executed: Boolean? = null)

    val currentIntensity: Float get() = intensity

    override fun onAck(ack: CueAck) {
        val p = pending.firstOrNull { it.cueId == ack.cueId } ?: return
        val ok = ack.outcome == CueOutcome.VIBRATED || ack.outcome == CueOutcome.EMULATED || ack.outcome == CueOutcome.SIMULATED
        if (ok) p.executed = true else pending.remove(p)
    }

    override fun onEpoch(input: CueInput, newCueId: () -> String): CueOutput {
        val f = input.features
        val pred = input.prediction
        recentQuality.addLast(f.quality)
        while (recentQuality.size > 2) recentQuality.removeFirst()

        val assessments = assessResponses(input)
        trackRem(input)

        if (pred.pRem < c.remThreshold) return CueOutput(null, assessments)

        val fails = ArrayList<GateFailure>()
        val age = input.nowMs - input.epochEndMs
        if (age > c.staleAfterSec * 1000L) fails += GateFailure(CueGate.STALE_DATA, "epoch ended ${age / 1000} s ago")
        if (!input.linkConnected) fails += GateFailure(CueGate.WATCH_DISCONNECTED, "no link to the watch")
        val h = input.hoursSinceOnset
        if (h == null) {
            fails += GateFailure(CueGate.SLEEP_ONSET_UNKNOWN, "no sustained sleep detected yet")
        } else if (h * 60f < c.minMinutesAfterSleepOnset) {
            fails += GateFailure(CueGate.TOO_EARLY, "%.1f h after onset < %.1f h".format(h, c.minMinutesAfterSleepOnset / 60f))
        }
        if (pred.confidence < c.minConfidence) {
            fails += GateFailure(CueGate.LOW_CONFIDENCE, "%.2f < %.2f".format(pred.confidence, c.minConfidence))
        }
        val q = recentQuality.minOrNull() ?: 0f
        if (q < c.minSignalQuality) fails += GateFailure(CueGate.LOW_SIGNAL_QUALITY, "%.2f < %.2f".format(q, c.minSignalQuality))
        val sustainedMin = remRunStartMs?.let { (input.epochEndMs - it) / 60_000f } ?: 0f
        if (sustainedMin < c.minSustainedRemMin) {
            fails += GateFailure(CueGate.REM_NOT_SUSTAINED, "%.1f min < %.1f min".format(sustainedMin, c.minSustainedRemMin))
        }
        if (pred.pWake > c.maxWakeProbability) {
            fails += GateFailure(CueGate.WAKE_LIKELY, "p(wake) %.2f > %.2f".format(pred.pWake, c.maxWakeProbability))
        }
        val still = f.minutesSinceMovement
        if (still == null || still < c.motionLockoutMin) {
            fails += GateFailure(CueGate.RECENT_MOVEMENT, if (still == null) "no motion data" else "moved %.1f min ago".format(still))
        }
        if (input.nowMs < backoffUntilMs) {
            fails += GateFailure(CueGate.AROUSAL_BACKOFF, "%d min left".format((backoffUntilMs - input.nowMs) / 60_000 + 1))
        }
        lastCueMs?.let {
            val since = (input.epochEndMs - it) / 1000
            if (since < c.minCueSpacingSec) fails += GateFailure(CueGate.SPACING, "$since s < ${c.minCueSpacingSec} s")
        }
        if (cueCount >= c.maxCuesPerNight) fails += GateFailure(CueGate.MAX_PER_NIGHT, "$cueCount/${c.maxCuesPerNight}")
        if (cuesThisPeriod >= c.maxCuesPerRemPeriod) {
            fails += GateFailure(CueGate.MAX_PER_REM_PERIOD, "$cuesThisPeriod/${c.maxCuesPerRemPeriod}")
        }

        val metrics = "p(REM) %.2f for %.1f min, confidence %.2f, quality %.2f".format(pred.pRem, sustainedMin, pred.confidence, q)
        if (fails.isNotEmpty()) {
            return CueOutput(
                CueDecision(
                    t = input.nowMs, epochIndex = f.index, action = CueAction.SUPPRESS, failures = fails,
                    explanation = "Blocked: " + fails.joinToString("; ") { "${it.gate.label} (${it.detail})" } + ". $metrics",
                    pRem = pred.pRem, pWake = pred.pWake, confidence = pred.confidence, quality = q,
                    remSustainedMin = sustainedMin, hoursSinceOnset = h, intensity = intensity, patternId = c.patternId,
                    cueId = null, cueNumber = null,
                ),
                assessments,
            )
        }

        val baseline = c.mode == CueMode.BASELINE
        cueCount++
        cuesThisPeriod++
        lastCueMs = input.epochEndMs
        val cueId = if (baseline) null else newCueId()
        if (cueId != null) pending += PendingCue(cueId, input.epochEndMs, f.hrMean)
        val action = if (baseline) CueAction.WOULD_DELIVER else CueAction.DELIVER
        val verb = if (baseline) "Would cue (baseline night, nothing sent)" else "Cue"
        return CueOutput(
            CueDecision(
                t = input.nowMs, epochIndex = f.index, action = action, failures = emptyList(),
                explanation = "$verb #$cueCount at ${(intensity * 100).toInt()}% intensity. $metrics, " +
                    "still for %.1f min, %.1f h after onset.".format(still ?: 0f, h ?: 0f),
                pRem = pred.pRem, pWake = pred.pWake, confidence = pred.confidence, quality = q,
                remSustainedMin = sustainedMin, hoursSinceOnset = h, intensity = intensity, patternId = c.patternId,
                cueId = cueId, cueNumber = cueCount,
            ),
            assessments,
        )
    }

    private fun trackRem(input: CueInput) {
        val p = input.prediction.pRem
        if (input.features.quality < FRESH_DATA_MIN_QUALITY) {
            // No usable heart rate: the estimate is the model carrying its last state forward, not new
            // evidence. REM has to re-establish itself on fresh data before it counts as sustained.
            remRunStartMs = null
            remRunGraceUsed = false
            return trackRemPeriod(p)
        }
        if (input.features.activeSeconds >= 2) {
            // Body movements cluster at stage transitions (often the end of a REM period), while the
            // windowed estimate lags by minutes. Restart the sustain timer: REM must re-establish itself.
            remRunStartMs = null
            remRunGraceUsed = false
            if (p >= c.remThreshold) remRunStartMs = input.epochEndMs
            return trackRemPeriod(p)
        }
        if (p >= c.remThreshold) {
            if (remRunStartMs == null) remRunStartMs = input.features.startMs
            remRunGraceUsed = false
        } else if (remRunStartMs != null) {
            // Tolerate one dipping epoch so a single noisy epoch does not restart the sustain timer.
            if (remRunGraceUsed) remRunStartMs = null else remRunGraceUsed = true
        }
        trackRemPeriod(p)
    }

    private fun trackRemPeriod(p: Float) {
        if (p >= c.remThreshold) {
            inRemPeriod = true
            lowRemEpochs = 0
        } else if (inRemPeriod && p < 0.35f) {
            if (++lowRemEpochs >= REM_PERIOD_END_EPOCHS) {
                inRemPeriod = false
                cuesThisPeriod = 0
            }
        }
    }

    private fun assessResponses(input: CueInput): List<CueResponseAssessment> {
        if (pending.isEmpty()) return emptyList()
        val f = input.features
        val out = ArrayList<CueResponseAssessment>()
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (f.startMs < p.deliveredAtMs) continue
            val hrJump = if (p.preHr != null && f.hrMean != null) f.hrMean - p.preHr else null
            val reasons = buildList {
                if (f.activeSeconds >= 2) add("movement ${f.activeSeconds} s")
                if (input.prediction.pWake >= 0.5f) add("p(wake) %.2f".format(input.prediction.pWake))
                if (hrJump != null && hrJump >= AROUSAL_HR_JUMP) add("HR +%.0f bpm".format(hrJump))
            }
            if (reasons.isNotEmpty()) {
                intensity = (intensity - 2 * c.intensityStep).coerceAtLeast(c.minIntensity)
                backoffUntilMs = input.nowMs + (c.arousalBackoffMin * 60_000).toLong()
                out += CueResponseAssessment(p.cueId, f.startMs, CueResponse.AROUSAL_SUSPECTED, reasons.joinToString(", "), intensity)
                it.remove()
            } else if (input.epochEndMs >= p.deliveredAtMs + RESPONSE_WINDOW_MS) {
                if (p.executed != false) intensity = (intensity + c.intensityStep).coerceAtMost(c.maxIntensity)
                out += CueResponseAssessment(p.cueId, f.startMs, CueResponse.NO_REACTION, "no movement, wake or HR jump within 60 s", intensity)
                it.remove()
            }
        }
        return out
    }

    private companion object {
        const val RESPONSE_WINDOW_MS = 60_000L
        const val AROUSAL_HR_JUMP = 8f
        const val REM_PERIOD_END_EPOCHS = 10
        /** Below this epoch quality there is essentially no fresh heart-rate evidence. */
        const val FRESH_DATA_MIN_QUALITY = 0.25f
    }
}
