package lucid.core.engine

import lucid.core.model.Clock
import lucid.core.model.CueAck
import lucid.core.model.CueCommand
import lucid.core.model.CuePurpose
import lucid.core.model.Hello
import lucid.core.model.SampleBatch
import lucid.core.model.SensorSample
import lucid.core.model.SleepStage
import lucid.core.model.VibrationPattern
import lucid.core.model.WatchStatus
import lucid.core.model.WireMessage

/** Everything the pipeline reports. The app persists these and renders them. */
sealed interface EngineEvent {
    data class SourceIdentified(val hello: Hello) : EngineEvent
    data class StatusReceived(val status: WatchStatus) : EngineEvent
    data class EpochProcessed(val features: EpochFeatures, val prediction: StagePrediction) : EngineEvent
    data class SleepOnsetDetected(val t: Long) : EngineEvent
    data class CueDecided(val decision: CueDecision, val command: CueCommand?) : EngineEvent
    data class CueAcknowledged(val ack: CueAck) : EngineEvent
    data class CueAssessed(val assessment: CueResponseAssessment) : EngineEvent
    data class SamplesRejected(val count: Int) : EngineEvent
}

/**
 * The processing pipeline for one night:
 * samples -> validation -> epoching -> features -> stage estimate -> cue decision.
 *
 * Synchronous and deterministic: identical input gives identical output, which is what makes
 * simulated nights replayable and testable. Threading is the caller's job.
 *
 * @param clock "now" for staleness checks. Pass null to trust the watch's own clock (the
 *   `sentAt` of incoming batches), which is right for a watch-side accelerated simulation.
 * @param linkConnected whether a cue command could reach the watch right now.
 */
class SessionProcessor(
    val sessionId: String,
    val sessionStartMs: Long,
    val config: EngineConfig = EngineConfig(),
    val estimator: StageEstimator = HmmStageEstimator(),
    val policy: CuePolicy = TlrCuePolicy(config.cueing),
    private val clock: Clock? = null,
    private val linkConnected: () -> Boolean = { true },
    private val newCueId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val validator = SampleValidator(sessionStartMs)
    private val epochs = EpochAccumulator(sessionStartMs, config.epochMs, config.latenessMs)
    private val extractor = FeatureExtractor(config.epochMs, config.priorHrBaseline)
    private val pattern = VibrationPattern.byId(config.cueing.patternId)
    private var watchNow = Long.MIN_VALUE

    private var sleepRun = 0
    private var sleepRunStartMs = 0L
    var sleepOnsetMs: Long? = null
        private set

    private val summary = SummaryBuilder(sessionStartMs, config.epochMs)
    val epochCount: Int get() = summary.epochs

    fun now(): Long = clock?.nowMs() ?: watchNow

    fun onMessage(message: WireMessage): List<EngineEvent> = when (message) {
        is SampleBatch -> {
            if (message.sessionId != null && message.sessionId != sessionId) {
                listOf(EngineEvent.SamplesRejected(message.samples.size))
            } else {
                watchNow = maxOf(watchNow, message.sentAt)
                onSamples(message.samples)
            }
        }
        is CueAck -> {
            policy.onAck(message)
            listOf(EngineEvent.CueAcknowledged(message))
        }
        is Hello -> listOf(EngineEvent.SourceIdentified(message))
        is WatchStatus -> {
            watchNow = maxOf(watchNow, message.t)
            listOf(EngineEvent.StatusReceived(message))
        }
        else -> emptyList()
    }

    fun onSamples(samples: List<SensorSample>): List<EngineEvent> {
        var rejected = 0
        val now = now()
        for (s in samples.sortedBy { it.t }) {
            // A sample from the future (e.g. a watch-side simulation's virtual clock leaking into a
            // real night) would poison every later timestamp check, so it never gets in.
            val rejection = if (now != Long.MIN_VALUE && s.t > now + MAX_FUTURE_MS) Rejection.FUTURE else validator.check(s)
            if (rejection != null) rejected++
            // Only samples that really happened now may advance epoch time; a duplicate, a sample
            // from the future or one from before the session must not move the watermark.
            val observed = rejection == null || rejection == Rejection.NO_CONTACT ||
                rejection == Rejection.OUT_OF_RANGE || rejection == Rejection.INVALID
            if (observed && !epochs.add(s, rejection)) rejected++
        }
        val out = ArrayList<EngineEvent>()
        if (rejected > 0) out += EngineEvent.SamplesRejected(rejected)
        for (e in epochs.release()) process(e, out)
        return out
    }

    /** End of session: process whatever is still buffered. */
    fun finish(): List<EngineEvent> {
        val out = ArrayList<EngineEvent>()
        for (e in epochs.flush()) process(e, out)
        return out
    }

    fun summary(): SessionSummary = summary.build(sleepOnsetMs)

    private fun process(e: EpochData, out: MutableList<EngineEvent>) {
        val features = extractor.extract(e)
        val hours = sleepOnsetMs?.let { (e.startMs - it) / 3_600_000f }
        val prediction = estimator.update(features, NightContext(hours))
        out += EngineEvent.EpochProcessed(features, prediction)
        summary.add(features, prediction)
        trackSleepOnset(e, prediction, out)

        val result = policy.onEpoch(
            CueInput(
                features = features,
                prediction = prediction,
                hoursSinceOnset = sleepOnsetMs?.let { (e.endMs - it) / 3_600_000f },
                epochEndMs = e.endMs,
                nowMs = now().takeIf { it != Long.MIN_VALUE } ?: e.endMs,
                linkConnected = linkConnected(),
            ),
            newCueId,
        )
        result.assessments.forEach {
            summary.addAssessment(it)
            out += EngineEvent.CueAssessed(it)
        }
        val decision = result.decision ?: return
        summary.addDecision(decision)
        val command = if (decision.action == CueAction.DELIVER && decision.cueId != null) {
            CueCommand(
                cueId = decision.cueId,
                sessionId = sessionId,
                pattern = pattern,
                intensity = decision.intensity,
                repeats = config.cueing.patternRepeats,
                issuedAt = decision.t,
                expiresAt = decision.t + config.cueing.commandTtlSec * 1000L,
                purpose = CuePurpose.SLEEP_CUE,
                sentWallMs = System.currentTimeMillis(),
            )
        } else {
            null
        }
        out += EngineEvent.CueDecided(decision, command)
    }

    /** Sleep onset = start of the first 10 minutes in which every epoch has p(wake) < 0.5. */
    private fun trackSleepOnset(e: EpochData, p: StagePrediction, out: MutableList<EngineEvent>) {
        if (sleepOnsetMs != null) return
        if (p.pWake < 0.5f && p.mostLikely != SleepStage.WAKE) {
            if (sleepRun == 0) sleepRunStartMs = e.startMs
            sleepRun++
            if (sleepRun * config.epochMs >= ONSET_RUN_MS) {
                sleepOnsetMs = sleepRunStartMs
                out += EngineEvent.SleepOnsetDetected(sleepRunStartMs)
            }
        } else {
            sleepRun = 0
        }
    }

    private companion object {
        const val ONSET_RUN_MS = 10 * 60_000L
        const val MAX_FUTURE_MS = 2 * 60_000L
    }
}
