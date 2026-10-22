package lucid.core.eval

import lucid.core.engine.CueAction
import lucid.core.engine.CueGate
import lucid.core.engine.CueMode
import lucid.core.engine.EngineConfig
import lucid.core.engine.EngineEvent
import lucid.core.model.SleepStage
import lucid.core.sim.Scenarios
import lucid.core.sim.SimEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end: simulated watch -> pipeline -> cue decisions -> simulated watch. */
class ClosedLoopTest {
    @Test
    fun `whole night is deterministic`() {
        val a = ClosedLoopNight(Scenarios.Normal).run()
        val b = ClosedLoopNight(Scenarios.Normal).run()
        assertEquals(a.decisions, b.decisions)
        assertEquals(a.predictions, b.predictions)
    }

    @Test
    fun `cueing rules hold on every scenario`() {
        val cfg = EngineConfig()
        for (sc in Scenarios.all) {
            val night = ClosedLoopNight(sc, config = cfg)
            val r = night.run()
            val c = cfg.cueing
            assertTrue("${sc.id}: one epoch per 30 s", r.predictions.size >= r.summary.recordedMin * 2 - 1)
            assertTrue("${sc.id}: cap", r.delivered.size <= c.maxCuesPerNight)
            r.delivered.zipWithNext().forEach { (x, y) ->
                assertTrue("${sc.id}: spacing", (y.epochIndex - x.epochIndex) * 30 >= c.minCueSpacingSec)
            }
            r.delivered.forEach {
                assertTrue("${sc.id}: late enough", it.hoursSinceOnset!! * 60 >= c.minMinutesAfterSleepOnset)
                assertTrue("${sc.id}: threshold", it.pRem >= c.remThreshold)
                assertTrue("${sc.id}: no wake", it.pWake <= c.maxWakeProbability)
            }
            // Every delivered cue reaches the simulated watch, except one decided in the night's
            // final seconds (the watch has stopped stepping by then).
            val reached = r.cueEffects.map { it.cueId }.toSet()
            val lastMs = night.device.startMs + night.device.durationSec * 1000L
            r.delivered.filter { it.cueId !in reached }.forEach {
                assertTrue("${sc.id}: cue ${it.cueId} at ${(lastMs - it.t) / 1000} s before the end never arrived", lastMs - it.t <= 60_000)
            }
        }
    }

    @Test
    fun `normal night delivers cues mostly inside simulated REM`() {
        val r = ClosedLoopNight(Scenarios.Normal).run()
        assertTrue(r.delivered.size >= 3)
        assertTrue(r.syntheticAgreement().cuesInRemFraction >= 0.7)
    }

    @Test
    fun `baseline night sends nothing but logs would-be cues`() {
        val cfg = EngineConfig().let { it.copy(cueing = it.cueing.copy(mode = CueMode.BASELINE)) }
        val r = ClosedLoopNight(Scenarios.Normal, config = cfg).run()
        assertTrue(r.delivered.isEmpty())
        assertTrue(r.cueEffects.isEmpty())
        assertTrue(r.decisions.any { it.action == CueAction.WOULD_DELIVER })
    }

    @Test
    fun `no cue goes out while the watch is unreachable`() {
        val night = ClosedLoopNight(Scenarios.Disconnection)
        val r = night.run()
        val outages = night.device.script.eventsOf<SimEvent.LinkDown>()
        val start = night.device.startMs
        for (cue in r.cueEffects) {
            val sec = ((cue.t - start) / 1000).toInt()
            assertTrue("cue executed during an outage at $sec", outages.none { sec in it.startSec until it.endSec })
        }
        // Epochs from the backfill are processed, but late, so they must be rejected as stale or disconnected.
        val blocked = r.decisions.filter { d -> d.failures.any { it.gate == CueGate.STALE_DATA || it.gate == CueGate.WATCH_DISCONNECTED } }
        val epochsInOutage = r.predictions.count { p -> outages.any { ((p.startMs - start) / 1000).toInt() in it.startSec until it.endSec } }
        assertTrue("backfilled epochs were processed", epochsInOutage > 60)
        assertTrue(r.decisions.none { it.action == CueAction.DELIVER && it.failures.isNotEmpty() })
        assertTrue(blocked.isNotEmpty() || r.decisions.none { d -> outages.any { ((d.epochIndex * 30)) in it.startSec until it.endSec } })
    }

    @Test
    fun `poor contact lowers quality and blocks cues on bad epochs`() {
        val r = ClosedLoopNight(Scenarios.PoorContact).run()
        assertTrue(r.summary.contactLostMin > 20)
        val lost = r.events.filterIsInstance<EngineEvent.EpochProcessed>().filter { it.features.contactLost }
        assertTrue(lost.all { it.features.quality == 0f })
        assertTrue(r.delivered.none { it.quality < EngineConfig().cueing.minSignalQuality })
    }

    @Test
    fun `estimated sleep onset is near the scripted one`() {
        val night = ClosedLoopNight(Scenarios.Normal)
        val r = night.run()
        val scripted = night.device.script.hypnogram.first { it.stage != SleepStage.WAKE }.startSec * 1000L + night.device.startMs
        val estimated = r.summary.sleepOnsetMs!!
        assertTrue("onset off by ${(estimated - scripted) / 60000} min", kotlin.math.abs(estimated - scripted) < 20 * 60_000)
    }
}
