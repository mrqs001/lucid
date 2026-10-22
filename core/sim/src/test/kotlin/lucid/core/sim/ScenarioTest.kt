package lucid.core.sim

import lucid.core.model.HeartRateSample
import lucid.core.model.IbiSample
import lucid.core.model.SampleBatch
import lucid.core.model.SampleQuality
import lucid.core.model.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenarioTest {
    private fun fingerprint(scenario: Scenario, seed: Long): Int {
        val gen = PhysiologyGenerator(scenario.build(seed), seed, 0)
        var h = 17
        while (!gen.finished) {
            val f = gen.next()
            h = 31 * h + f.samples.hashCode()
            h = 31 * h + f.truth.ordinal
        }
        return h
    }

    @Test
    fun `same scenario and seed replay identically`() {
        for (sc in Scenarios.all) assertEquals(sc.id, fingerprint(sc, 11), fingerprint(sc, 11))
    }

    @Test
    fun `different seeds give different nights`() {
        assertNotEquals(fingerprint(Scenarios.Normal, 1), fingerprint(Scenarios.Normal, 2))
    }

    @Test
    fun `hypnograms are contiguous and cover the night`() {
        for (sc in Scenarios.all) for (seed in 1L..20L) {
            val s = sc.build(seed) // init block validates contiguity
            assertTrue(sc.id, s.durationSec >= 8 * 3600)
        }
    }

    @Test
    fun `normal night has plausible architecture`() {
        for (seed in 1L..20L) {
            val s = Scenarios.Normal.build(seed)
            val remPeriods = s.hypnogram.count { it.stage == SleepStage.REM }
            assertTrue("REM periods: $remPeriods", remPeriods in 3..7)
            val remFraction = s.totalSec(SleepStage.REM).toDouble() / s.durationSec
            assertTrue("REM fraction $remFraction", remFraction in 0.12..0.32)
            val firstDeep = s.hypnogram.first { it.stage == SleepStage.DEEP }
            val lastDeep = s.hypnogram.last { it.stage == SleepStage.DEEP }
            assertTrue("deep sleep is front-loaded", firstDeep.durationSec >= lastDeep.durationSec)
            val rems = s.hypnogram.filter { it.stage == SleepStage.REM }
            assertTrue("REM lengthens", rems.drop(1).maxOf { it.durationSec } > rems.first().durationSec)
        }
    }

    @Test
    fun `poor contact scenario produces no-contact samples and no beats during losses`() {
        val script = Scenarios.PoorContact.build()
        val gen = PhysiologyGenerator(script, 4, 0)
        val loss = script.eventsOf<SimEvent.ContactLoss>().first()
        var noContact = 0
        var beatsDuringLoss = 0
        while (!gen.finished) {
            val f = gen.next()
            if (f.second in loss.startSec until loss.endSec) {
                noContact += f.samples.count { it is HeartRateSample && it.quality == SampleQuality.NO_CONTACT }
                beatsDuringLoss += f.samples.count { it is IbiSample }
            }
        }
        assertEquals(loss.endSec - loss.startSec, noContact)
        assertEquals(0, beatsDuringLoss)
    }

    @Test
    fun `simulated heart physiology follows the scripted stage`() {
        val script = Scenarios.Normal.build()
        val gen = PhysiologyGenerator(script, 1, 0)
        val hr = SleepStage.entries.associateWith { ArrayList<Float>() }
        while (!gen.finished) {
            val f = gen.next()
            f.samples.filterIsInstance<HeartRateSample>().forEach { hr.getValue(f.truth) += it.bpm }
        }
        val mean = hr.mapValues { it.value.average() }
        assertTrue("REM HR above deep: $mean", mean.getValue(SleepStage.REM) > mean.getValue(SleepStage.DEEP) + 3)
        assertTrue("wake HR highest: $mean", mean.getValue(SleepStage.WAKE) > mean.getValue(SleepStage.REM))
    }

    @Test
    fun `device buffers during link loss and backfills in order`() {
        val device = SimulatedWatchDevice(Scenarios.Disconnection, 7, startMs = 0)
        val down = device.script.eventsOf<SimEvent.LinkDown>().first()
        val delivered = ArrayList<Long>()
        var backfillBatches = 0
        var sentDuringOutage = 0
        while (!device.finished) {
            val msgs = device.step()
            val sec = device.elapsedSec - 1
            for (m in msgs) if (m is SampleBatch) {
                if (sec in down.startSec until down.endSec) sentDuringOutage++
                if (m.backfill) backfillBatches++
                delivered += m.samples.map { it.t }
            }
        }
        assertEquals(0, sentDuringOutage)
        assertTrue(backfillBatches > 0)
        assertEquals("timestamps stay ordered across the backfill", delivered.sorted(), delivered)
        assertEquals(0, device.droppedSamples)
    }

    @Test
    fun `stage override splits and merges segments`() {
        val s = ScenarioScript(
            100,
            listOf(StageSegment(0, 40, SleepStage.LIGHT), StageSegment(40, 100, SleepStage.REM)),
            emptyList(),
        )
        val o = s.withStageOverride(30, 50, SleepStage.WAKE)
        assertEquals(
            listOf(StageSegment(0, 30, SleepStage.LIGHT), StageSegment(30, 50, SleepStage.WAKE), StageSegment(50, 100, SleepStage.REM)),
            o.hypnogram,
        )
    }
}
