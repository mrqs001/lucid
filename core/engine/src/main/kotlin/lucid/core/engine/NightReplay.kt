package lucid.core.engine

import lucid.core.model.SampleBatch
import lucid.core.model.SensorSample

/** Everything a fresh pipeline produced from a recorded night. */
class NightReplay(
    val events: List<EngineEvent>,
    /** Watch time at which each epoch (in order of [EngineEvent.EpochProcessed]) was finalised. */
    val epochProcessedAt: List<Long>,
    val sleepOnsetMs: Long?,
    val summary: SessionSummary,
)

/**
 * Re-runs recorded samples through a fresh pipeline, delivered in [batchMs] batches the way a
 * watch sends them. Used to re-estimate stored nights with the current model and by the
 * evaluation tools.
 */
fun replayNight(
    sessionStartMs: Long,
    config: EngineConfig,
    samples: List<SensorSample>,
    estimator: StageEstimator = HmmStageEstimator(),
    batchMs: Long = 5_000,
): NightReplay {
    // clock = null: "now" is the time the watch sent each batch, exactly as for a live watch.
    val processor = SessionProcessor("replay", sessionStartMs, config, estimator, clock = null)
    val events = ArrayList<EngineEvent>()
    val processedAt = ArrayList<Long>()
    fun collect(batch: List<EngineEvent>) {
        events += batch
        repeat(batch.count { it is EngineEvent.EpochProcessed }) { processedAt += processor.now() }
    }
    val sorted = samples.sortedBy { it.t }
    var seq = 0L
    var i = 0
    while (i < sorted.size) {
        val windowEnd = (sorted[i].t / batchMs + 1) * batchMs
        val batch = ArrayList<SensorSample>()
        while (i < sorted.size && sorted[i].t < windowEnd) batch += sorted[i++]
        collect(processor.onMessage(SampleBatch(seq++, batch.last().t, batch, sessionId = "replay")))
    }
    collect(processor.finish())
    return NightReplay(events, processedAt, processor.sleepOnsetMs, processor.summary())
}
