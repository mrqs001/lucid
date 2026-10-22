package lucid.core.eval

import lucid.core.engine.CueAction
import lucid.core.engine.CueDecision
import lucid.core.engine.EngineConfig
import lucid.core.engine.EngineEvent
import lucid.core.engine.SessionProcessor
import lucid.core.engine.SessionSummary
import lucid.core.engine.StagePrediction
import lucid.core.model.SleepStage
import lucid.core.sim.PhysiologyGenerator
import lucid.core.sim.Scenario
import lucid.core.sim.SimulatedCueEffect
import lucid.core.sim.SimulatedWatchDevice
import lucid.core.sim.TruthEpoch

/**
 * Runs one simulated night through the real pipeline as fast as possible, with cue commands
 * looped back into the simulated watch (whose simulated body may react to them).
 */
class ClosedLoopNight(
    val scenario: Scenario,
    val seed: Long = scenario.defaultSeed,
    val config: EngineConfig = EngineConfig(),
    startMs: Long = 1_760_000_000_000,
) {
    private var cueSeq = 0
    val device = SimulatedWatchDevice(scenario, seed, startMs)
    val processor = SessionProcessor(
        sessionId = "sim-${scenario.id}-$seed",
        sessionStartMs = startMs,
        config = config,
        clock = device.clock,
        linkConnected = { device.linkUp },
        newCueId = { "cue-${++cueSeq}" },
    )

    fun run(): NightResult {
        val events = ArrayList<EngineEvent>()
        fun handle(batch: List<EngineEvent>) {
            for (e in batch) {
                events += e
                if (e is EngineEvent.CueDecided && e.command != null) device.receive(e.command!!)
            }
        }
        handle(processor.onMessage(device.hello()))
        while (!device.finished) for (m in device.step()) handle(processor.onMessage(m))
        handle(processor.finish())
        return NightResult(scenario, seed, events, processor.summary(), device.truth.toList(), device.cueEffects.toList())
    }
}

data class NightResult(
    val scenario: Scenario,
    val seed: Long,
    val events: List<EngineEvent>,
    val summary: SessionSummary,
    val truth: List<TruthEpoch>,
    val cueEffects: List<SimulatedCueEffect>,
) {
    val predictions: List<StagePrediction> = events.filterIsInstance<EngineEvent.EpochProcessed>().map { it.prediction }
    val decisions: List<CueDecision> = events.filterIsInstance<EngineEvent.CueDecided>().map { it.decision }
    val delivered: List<CueDecision> = decisions.filter { it.action == CueAction.DELIVER }

    /**
     * Agreement with the simulator's own script. This checks the plumbing; it says nothing about
     * accuracy on real people, because the simulator and estimator share assumptions.
     */
    fun syntheticAgreement(): SyntheticAgreement {
        val truthByIndex = truth.associate { it.index to it.stage }
        val n = SleepStage.entries.size
        val m = Array(n) { IntArray(n) }
        for (p in predictions) {
            val t = truthByIndex[p.index] ?: continue
            m[t.ordinal][p.mostLikely.ordinal]++
        }
        val cueStages = cueEffects.groupingBy { it.stageAtCue }.eachCount()
        val responses = cueEffects.groupingBy { it.response }.eachCount()
        return SyntheticAgreement(m, cueStages, responses)
    }
}

data class SyntheticAgreement(
    /** confusion[truth][predicted] in epochs */
    val confusion: Array<IntArray>,
    val cuesByTrueStage: Map<SleepStage, Int>,
    val cueResponses: Map<PhysiologyGenerator.CueResponse, Int>,
) {
    private val r = SleepStage.REM.ordinal
    val remSensitivity: Double get() = confusion[r][r].toDouble() / confusion[r].sum().coerceAtLeast(1)
    val remPrecision: Double get() = confusion[r][r].toDouble() / confusion.sumOf { it[r] }.coerceAtLeast(1)
    val accuracy: Double get() = confusion.indices.sumOf { confusion[it][it] }.toDouble() / confusion.sumOf { it.sum() }.coerceAtLeast(1)
    val cuesInRemFraction: Double
        get() = (cuesByTrueStage[SleepStage.REM] ?: 0).toDouble() / cuesByTrueStage.values.sum().coerceAtLeast(1)

    /** Cohen's kappa over all four stages. */
    val kappa: Double
        get() {
            val total = confusion.sumOf { it.sum() }.toDouble().coerceAtLeast(1.0)
            val po = accuracy
            val pe = confusion.indices.sumOf { i -> confusion[i].sum() / total * confusion.sumOf { it[i] } / total }
            return if (pe >= 1.0) 0.0 else (po - pe) / (1 - pe)
        }
}
