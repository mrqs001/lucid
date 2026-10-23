package lucid.core.eval

import lucid.core.engine.CueMode
import lucid.core.engine.EngineConfig
import lucid.core.engine.EngineEvent
import lucid.core.model.SleepStage
import lucid.core.sim.Scenarios
import java.io.File

/**
 * Developer tool: replays simulated nights through the real pipeline and prints what happened.
 *
 *   ./gradlew :core:eval:run --args="--scenario all"
 *   ./gradlew :core:eval:run --args="--scenario normal --seed 42 --baseline --csv /tmp/night.csv"
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "replay") {
        require(args.size == 3) { "usage: replay <input.json> <output.json>" }
        return runReplay(args[1], args[2])
    }
    val opts = args.toList().windowed(2, 1, partialWindows = true).associate { it[0] to it.getOrNull(1) }
    val which = opts["--scenario"] ?: "all"
    val seed = opts["--seed"]?.toLong()
    val baseline = "--baseline" in args
    val config = EngineConfig().let { if (baseline) it.copy(cueing = it.cueing.copy(mode = CueMode.BASELINE)) else it }
    val scenarios = if (which == "all") Scenarios.all else listOf(Scenarios.byId(which))

    println("SYNTHETIC DATA ONLY: agreement with the simulator script is a plumbing check, not REM-detection accuracy.\n")
    println(
        "%-16s %6s %6s %6s %6s %6s %6s %5s %5s %6s %8s".format(
            "scenario", "hours", "REMtru", "REMest", "sens", "prec", "kappa", "cues", "would", "inREM", "arousals",
        ),
    )
    for (sc in scenarios) {
        val started = System.nanoTime()
        val result = ClosedLoopNight(sc, seed ?: sc.defaultSeed, config).run()
        val ms = (System.nanoTime() - started) / 1e6
        val a = result.syntheticAgreement()
        val s = result.summary
        val trueRem = result.truth.count { it.stage == SleepStage.REM } * 0.5
        println(
            "%-16s %6.1f %6.0f %6.0f %6.2f %6.2f %6.2f %5d %5d %6.2f %8d   (%.0f ms)".format(
                sc.id, s.recordedMin / 60, trueRem, s.estimatedMin[SleepStage.REM] ?: 0f,
                a.remSensitivity, a.remPrecision, a.kappa, s.cuesDelivered, s.cuesWouldDeliver,
                a.cuesInRemFraction, s.suspectedArousals, ms,
            ),
        )
        if (scenarios.size == 1) {
            println("\nsuppressed candidates by rule: ${s.suppressedByGate.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }}")
            println("simulated cue effects: ${a.cueResponses}")
            println("\nconfusion (rows = simulator truth, cols = estimate) W L D R:")
            SleepStage.entries.forEachIndexed { i, st -> println("  %-6s %s".format(st.name, a.confusion[i].joinToString(" ") { "%5d".format(it) })) }
            val ep = result.events.filterIsInstance<EngineEvent.EpochProcessed>()
            val truthBy = result.truth.associate { it.index to it.stage }
            SleepStage.entries.forEach { st ->
                val fs = ep.filter { truthBy[it.features.index] == st }.map { it.features }
                fun avg(sel: (lucid.core.engine.EpochFeatures) -> Float?) = fs.mapNotNull(sel).average()
                println("  truth %-5s hrRel %5.1f  instabRel %5.2f  lnRmssdRel %5.2f  active %4.2f".format(
                    st.name, avg { it.hrRel }, avg { it.hrInstabilityRel }, avg { it.lnRmssdRel }, avg { it.activeSeconds.toFloat() }))
            }
            println("\ndecisions:")
            result.decisions.filter { it.action.name != "SUPPRESS" }.forEach {
                println("  epoch %4d  %-13s %s".format(it.epochIndex, it.action, it.explanation))
            }
        }
        opts["--csv"]?.let { path -> writeCsv(File(path), result) }
    }
}

private fun writeCsv(file: File, result: NightResult) {
    val truth = result.truth.associate { it.index to it.stage }
    file.printWriter().use { w ->
        w.println("epoch,start_ms,hr_mean,hr_rel,hr_instability,rmssd,active_s,quality,p_wake,p_light,p_deep,p_rem,confidence,sim_truth")
        for (e in result.events.filterIsInstance<EngineEvent.EpochProcessed>()) {
            val f = e.features
            val p = e.prediction.probabilities
            w.println(
                listOf(
                    f.index, f.startMs, f.hrMean, f.hrRel, f.hrInstability, f.rmssdMs, f.activeSeconds, f.quality,
                    p[0], p[1], p[2], p[3], e.prediction.confidence, truth[f.index],
                ).joinToString(",") { it?.toString() ?: "" },
            )
        }
    }
    println("wrote ${file.absolutePath}")
}
