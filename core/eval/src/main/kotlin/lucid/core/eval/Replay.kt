package lucid.core.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lucid.core.engine.CueDecision
import lucid.core.engine.CueResponseAssessment
import lucid.core.engine.EngineConfig
import lucid.core.engine.EngineEvent
import lucid.core.engine.EpochFeatures
import lucid.core.engine.SessionProcessor
import lucid.core.engine.SessionSummary
import lucid.core.engine.HmmSpec
import lucid.core.engine.HmmStageEstimator
import lucid.core.engine.StageEstimator
import lucid.core.engine.StagePrediction
import lucid.core.engine.replayNight
import lucid.core.model.SensorSample
import lucid.core.model.Wire
import java.io.File

/**
 * Re-runs a recorded (or edited) night through the current pipeline.
 *
 *   ./gradlew :core:eval:run --args="replay input.json output.json [model.json]"
 *
 * Input: {"sessionStartMs": ..., "config": EngineConfig, "samples": [SensorSample, ...]} with samples
 * in the wire format ({"type":"hr","t":...,"bpm":...,"quality":"GOOD"}, "ibi", "mot", "st").
 * Output: every epoch's features and prediction, every cue decision, and the night summary.
 * model.json: an [HmmSpec] to use instead of the app's default stage model.
 */
@Serializable
data class ReplayInput(val sessionStartMs: Long, val config: EngineConfig = EngineConfig(), val samples: List<SensorSample>)

/** [processedAtMs]: watch time when the epoch was finalised (what the cue rules saw as "now"). */
@Serializable
data class ReplayEpoch(val features: EpochFeatures, val prediction: StagePrediction, val processedAtMs: Long)

@Serializable
data class ReplayOutput(
    val sleepOnsetMs: Long?,
    val epochs: List<ReplayEpoch>,
    val decisions: List<CueDecision>,
    val assessments: List<CueResponseAssessment>,
    val summary: SessionSummary,
)

private val json = Json(Wire.json) { encodeDefaults = true; ignoreUnknownKeys = true }

fun replay(input: ReplayInput, batchMs: Long = 5_000, estimator: StageEstimator = HmmStageEstimator()): ReplayOutput {
    val r = replayNight(input.sessionStartMs, input.config, input.samples, estimator, batchMs)
    val epochs = r.events.filterIsInstance<EngineEvent.EpochProcessed>()
        .mapIndexed { i, e -> ReplayEpoch(e.features, e.prediction, r.epochProcessedAt[i]) }
    return output(r.sleepOnsetMs, r.events, epochs, r.summary)
}

/**
 * Same as [replay] for another stage model or cue configuration, reusing the features of an earlier
 * replay of the same night (features do not depend on either). Much faster; results are identical.
 */
fun replayFeatures(sessionStartMs: Long, features: ReplayOutput, config: EngineConfig, estimator: StageEstimator): ReplayOutput {
    val processor = SessionProcessor("replay", sessionStartMs, config, estimator, clock = null)
    val events = ArrayList<EngineEvent>()
    val epochs = ArrayList<ReplayEpoch>()
    for (e in features.epochs) {
        val batch = processor.onFeatures(e.features, e.processedAtMs)
        events += batch
        batch.filterIsInstance<EngineEvent.EpochProcessed>().forEach { epochs += ReplayEpoch(it.features, it.prediction, e.processedAtMs) }
    }
    return output(processor.sleepOnsetMs, events, epochs, processor.summary())
}

private fun output(sleepOnsetMs: Long?, events: List<EngineEvent>, epochs: List<ReplayEpoch>, summary: SessionSummary) = ReplayOutput(
    sleepOnsetMs = sleepOnsetMs,
    epochs = epochs,
    decisions = events.filterIsInstance<EngineEvent.CueDecided>().map { it.decision },
    assessments = events.filterIsInstance<EngineEvent.CueAssessed>().map { it.assessment },
    summary = summary,
)

fun runReplay(inPath: String, outPath: String, specPath: String? = null) {
    val input = json.decodeFromString(ReplayInput.serializer(), File(inPath).readText())
    val estimator = specPath?.let { HmmStageEstimator(HmmSpec.parse(File(it).readText()).toParams()) } ?: HmmStageEstimator()
    val out = replay(input, estimator = estimator)
    File(outPath).writeText(json.encodeToString(ReplayOutput.serializer(), out))
    println("replayed ${input.samples.size} samples -> ${out.epochs.size} epochs, ${out.decisions.size} cue candidates -> $outPath")
}
