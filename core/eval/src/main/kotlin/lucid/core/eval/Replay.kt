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
import lucid.core.engine.StagePrediction
import lucid.core.model.SampleBatch
import lucid.core.model.SensorSample
import lucid.core.model.Wire
import java.io.File

/**
 * Re-runs a recorded (or edited) night through the current pipeline.
 *
 *   ./gradlew :core:eval:run --args="replay input.json output.json"
 *
 * Input: {"sessionStartMs": ..., "config": EngineConfig, "samples": [SensorSample, ...]} with samples
 * in the wire format ({"type":"hr","t":...,"bpm":...,"quality":"GOOD"}, "ibi", "mot", "st").
 * Output: every epoch's features and prediction, every cue decision, and the night summary.
 */
@Serializable
data class ReplayInput(val sessionStartMs: Long, val config: EngineConfig = EngineConfig(), val samples: List<SensorSample>)

@Serializable
data class ReplayEpoch(val features: EpochFeatures, val prediction: StagePrediction)

@Serializable
data class ReplayOutput(
    val sleepOnsetMs: Long?,
    val epochs: List<ReplayEpoch>,
    val decisions: List<CueDecision>,
    val assessments: List<CueResponseAssessment>,
    val summary: SessionSummary,
)

private val json = Json(Wire.json) { encodeDefaults = true; ignoreUnknownKeys = true }

fun replay(input: ReplayInput, batchMs: Long = 5_000): ReplayOutput {
    // clock = null: "now" is the time the watch sent each batch, exactly as for a live watch.
    val processor = SessionProcessor("replay", input.sessionStartMs, input.config, clock = null)
    val events = ArrayList<EngineEvent>()
    val sorted = input.samples.sortedBy { it.t }
    var seq = 0L
    var i = 0
    while (i < sorted.size) {
        val windowEnd = (sorted[i].t / batchMs + 1) * batchMs
        val batch = ArrayList<SensorSample>()
        while (i < sorted.size && sorted[i].t < windowEnd) batch += sorted[i++]
        events += processor.onMessage(SampleBatch(seq++, batch.last().t, batch, sessionId = "replay"))
    }
    events += processor.finish()
    return ReplayOutput(
        sleepOnsetMs = processor.sleepOnsetMs,
        epochs = events.filterIsInstance<EngineEvent.EpochProcessed>().map { ReplayEpoch(it.features, it.prediction) },
        decisions = events.filterIsInstance<EngineEvent.CueDecided>().map { it.decision },
        assessments = events.filterIsInstance<EngineEvent.CueAssessed>().map { it.assessment },
        summary = processor.summary(),
    )
}

fun runReplay(inPath: String, outPath: String) {
    val input = json.decodeFromString(ReplayInput.serializer(), File(inPath).readText())
    val out = replay(input)
    File(outPath).writeText(json.encodeToString(ReplayOutput.serializer(), out))
    println("replayed ${input.samples.size} samples -> ${out.epochs.size} epochs, ${out.decisions.size} cue candidates -> $outPath")
}
