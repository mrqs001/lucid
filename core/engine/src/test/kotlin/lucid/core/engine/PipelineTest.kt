package lucid.core.engine

import lucid.core.model.HeartRateSample
import lucid.core.model.IbiSample
import lucid.core.model.MotionSample
import lucid.core.model.SampleQuality
import lucid.core.model.SleepStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PipelineTest {
    @Test
    fun `validator rejects implausible and duplicate samples`() {
        val v = SampleValidator(sessionStartMs = 10_000)
        assertEquals(Rejection.BEFORE_SESSION, v.check(HeartRateSample(1_000, 60f, SampleQuality.GOOD)))
        assertNull(v.check(HeartRateSample(10_000, 60f, SampleQuality.GOOD)))
        assertEquals(Rejection.DUPLICATE, v.check(HeartRateSample(10_000, 61f, SampleQuality.GOOD)))
        assertEquals(Rejection.OUT_OF_RANGE, v.check(HeartRateSample(11_000, 250f, SampleQuality.GOOD)))
        assertEquals(Rejection.NO_CONTACT, v.check(HeartRateSample(12_000, 0f, SampleQuality.NO_CONTACT)))
        assertEquals(Rejection.OUT_OF_RANGE, v.check(IbiSample(12_000, 150, SampleQuality.GOOD)))
        assertEquals(Rejection.INVALID, v.check(MotionSample(12_000, Float.NaN, 0f)))
    }

    @Test
    fun `leftovers from another session or the future cannot poison a night`() {
        // Regression: a watch-side simulation's unacknowledged batch (virtual clock 33 min ahead)
        // was resent into the next, real night and made every real sample look like a duplicate.
        val start = 1_000_000L
        val p = SessionProcessor("real", start, clock = null)
        val leftover = (0 until 10).map { HeartRateSample(start + 33 * 60_000L + it * 1000, 60f, SampleQuality.GOOD) }
        val foreign = p.onMessage(lucid.core.model.SampleBatch(0, start, leftover, sessionId = "old-sim"))
        assertEquals(10, (foreign.single() as EngineEvent.SamplesRejected).count)
        val untagged = p.onMessage(lucid.core.model.SampleBatch(1, start + 1000, leftover))
        assertEquals(10, (untagged.single() as EngineEvent.SamplesRejected).count)
        // Real data keeps flowing: two minutes of 1 Hz HR give finished epochs with values.
        val real = (0 until 120).flatMap { listOf(HeartRateSample(start + it * 1000L, 70f, SampleQuality.GOOD), MotionSample(start + it * 1000L, 2f, 0f)) }
        val events = p.onMessage(lucid.core.model.SampleBatch(2, start + 120_000, real, sessionId = "real"))
        val epochs = events.filterIsInstance<EngineEvent.EpochProcessed>()
        assertEquals(3, epochs.size)
        assertTrue(epochs.all { it.features.hrMean == 70f })
        assertTrue(events.none { it is EngineEvent.SamplesRejected })
    }

    @Test
    fun `epochs release in order, fill gaps, and reject late samples`() {
        val acc = EpochAccumulator(sessionStartMs = 0, epochMs = 30_000, latenessMs = 3_000)
        // A watch always sends heart rate and motion together.
        fun both(t: Long) {
            acc.add(HeartRateSample(t, 60f, SampleQuality.GOOD), null)
            acc.add(MotionSample(t, 2f, 0f), null)
        }
        both(1_000)
        assertTrue(acc.release().isEmpty())
        both(31_000)
        assertTrue("epoch 0 waits for the lateness window", acc.release().isEmpty())
        both(33_500)
        assertEquals(listOf(0), acc.release().map { it.index })
        // jump ahead four minutes: the gap becomes empty epochs
        both(4 * 60_000 + 5_000L)
        val released = acc.release()
        assertEquals((1..7).toList(), released.map { it.index })
        assertTrue(released.drop(1).all { it.hr.isEmpty() })
        assertTrue("sample for a released epoch is late", !acc.add(HeartRateSample(40_000, 60f, SampleQuality.GOOD), null))
    }

    @Test
    fun `heart rate batched behind motion is not dropped as late`() {
        // Galaxy Watch: motion summaries arrive every second, heart rate in 10 s hub batches.
        val acc = EpochAccumulator(sessionStartMs = 0, epochMs = 30_000, latenessMs = 3_000)
        var late = 0
        for (sec in 0 until 120) {
            acc.add(MotionSample(sec * 1000L, 2f, 0f), null)
            if (sec % 10 == 9) for (h in sec - 9..sec) if (!acc.add(HeartRateSample(h * 1000L, 60f, SampleQuality.GOOD), null)) late++
            acc.release()
        }
        assertEquals(0, late)
        // Session start: the first heart-rate batch arrives 40 s late and must still count.
        val start = EpochAccumulator(sessionStartMs = 0, epochMs = 30_000, latenessMs = 3_000)
        for (sec in 0 until 40) { start.add(MotionSample(sec * 1000L, 2f, 0f), null); start.release() }
        assertTrue("first heart-rate batch accepted", (0 until 40).all { start.add(HeartRateSample(it * 1000L, 60f, SampleQuality.GOOD), null) })
        // A channel that stops (no skin contact) must not hold epochs back for ever.
        val stalled = EpochAccumulator(sessionStartMs = 0, epochMs = 30_000, latenessMs = 3_000, stallMs = 60_000)
        stalled.add(HeartRateSample(1000, 60f, SampleQuality.GOOD), null)
        for (sec in 0 until 200) stalled.add(MotionSample(sec * 1000L, 2f, 0f), null)
        assertTrue(stalled.release().size >= 5)
    }

    @Test
    fun `ibi cleaner removes ectopic beats`() {
        val ibis = MutableList(20) { 1000 }
        ibis[10] = 600 // premature
        ibis[11] = 1400 // compensatory pause
        val out = IbiCleaner().clean(ibis.mapIndexed { i, v -> i * 1000L to v })
        assertEquals(listOf(10, 11), out.withIndex().filter { !it.value.accepted }.map { it.index })
        assertTrue("pair after a rejected beat is not a successive difference", !out[12].followsAccepted)
    }

    @Test
    fun `rmssd matches the textbook definition`() {
        val values = List(61) { if (it % 2 == 0) 1000 else 1040 }
        val clean = values.mapIndexed { i, v -> CleanIbi(i.toLong(), v, accepted = true, followsAccepted = i > 0) }
        val r = FeatureExtractor.rmssd(clean)!!
        assertEquals(40f, r, 0.001f)
        val irregular = listOf(1000, 1020, 990, 1050, 1000)
        val expected = sqrt(listOf(20, -30, 60, -50).map { it * it }.average()).toFloat()
        val c2 = irregular.mapIndexed { i, v -> CleanIbi(i.toLong(), v, true, i > 0) }
        assertEquals(expected, FeatureExtractor.rmssd(c2, minPairs = 1)!!, 0.001f)
    }

    private fun features(
        index: Int = 0,
        hrRel: Float? = 0f,
        instability: Float? = 1.5f,
        lnRmssdRel: Float? = 0f,
        active: Int = 0,
        still: Float? = 20f,
        quality: Float = 1f,
    ) = EpochFeatures(
        index = index, startMs = index * 30_000L, hrMean = 60f, hrCoverage = 1f, hrRel = hrRel,
        hrInstability = instability, hrInstabilityRel = instability?.let { kotlin.math.ln(it / 2f) }, rmssdMs = 40f, lnRmssdRel = lnRmssdRel, cleanIbiCount = 30,
        ibiArtifactFraction = 0f, activityMg = 2f, activeSeconds = active, maxAngleChangeDeg = 0f,
        motionCoverage = 1f, minutesSinceMovement = still, contactLost = false, baselineReady = true,
        quality = quality, rejectedSamples = 0,
    )

    @Test
    fun `probabilities stay normalised and react to REM-like input`() {
        val est = HmmStageEstimator()
        val ctx = NightContext(hoursSinceOnset = 5f)
        repeat(40) { est.update(features(it, hrRel = 0.5f, instability = 1.2f, lnRmssdRel = 0.25f), ctx) } // deep-like
        val deep = est.update(features(40, hrRel = 0.5f, instability = 1.2f, lnRmssdRel = 0.25f), ctx)
        assertEquals(1f, deep.probabilities.sum(), 1e-4f)
        assertTrue("deep-like input: $deep", deep.pRem < 0.2f)
        var rem = deep
        for (i in 41..60) rem = est.update(features(i, hrRel = 6.5f, instability = 3.8f, lnRmssdRel = -0.25f), ctx)
        assertEquals(1f, rem.probabilities.sum(), 1e-4f)
        assertTrue("REM-like input should dominate: ${rem.probabilities}", rem.pRem > 0.8f)
        assertTrue(rem.contributions.first { it.feature == "hrInstability" }.remLogLr > 0f)
    }

    @Test
    fun `missing data lowers confidence and does not invent evidence`() {
        val est = HmmStageEstimator()
        val ctx = NightContext(hoursSinceOnset = 5f)
        var p = est.update(features(0), ctx)
        repeat(10) { p = est.update(features(it + 1, hrRel = 6.5f, instability = 3.8f, lnRmssdRel = -0.25f), ctx) }
        val confident = p.confidence
        val empty = features(20, hrRel = null, instability = null, lnRmssdRel = null, still = null, quality = 0f)
        val noData = est.update(empty.copy(motionCoverage = 0f), ctx)
        assertTrue(noData.confidence < 0.2f && noData.confidence < confident)
        assertTrue(noData.contributions.isEmpty())
        assertEquals(1f, noData.probabilities.sum(), 1e-4f)
    }

    @Test
    fun `transition matrices are stochastic across the night`() {
        for (h in listOf(null, 0f, 1f, 3f, 6f, 9f)) {
            val m = HmmParams.Default.transitions(h)
            for (row in m) {
                assertEquals(1.0, row.sum(), 1e-9)
                assertTrue(row.all { it in 0.0..1.0 })
            }
        }
        val early = HmmParams.Default.transitions(0.5f)
        val late = HmmParams.Default.transitions(6f)
        val l = SleepStage.LIGHT.ordinal
        val r = SleepStage.REM.ordinal
        val d = SleepStage.DEEP.ordinal
        assertTrue("REM entry more likely late", late[l][r] > early[l][r] * 3)
        assertTrue("deep entry more likely early", early[l][d] > late[l][d] * 3)
        assertTrue("REM periods longer late", late[r][r] > early[r][r])
    }

    @Test
    fun `quality is zero when contact is lost`() {
        val ex = FeatureExtractor(30_000, priorHrBaseline = null)
        val e = EpochData(0, 0, 30_000).apply { noContactSeconds = 30 }
        val f = ex.extract(e)
        assertTrue(f.contactLost)
        assertEquals(0f, f.quality)
        assertNull(f.hrMean)
    }

    @Test
    fun `features on a clean synthetic epoch`() {
        val ex = FeatureExtractor(30_000, priorHrBaseline = 55f)
        var last: EpochFeatures? = null
        for (epoch in 0 until 6) {
            val e = EpochData(epoch, epoch * 30_000L, (epoch + 1) * 30_000L)
            for (s in 0 until 30) {
                val t = epoch * 30_000L + s * 1000
                e.hr += HeartRateSample(t, 60f + (s % 2), SampleQuality.GOOD)
                e.ibi += IbiSample(t, if (s % 2 == 0) 1000 else 1030, SampleQuality.GOOD)
                e.motion += MotionSample(t, 2f, 0.1f)
            }
            last = ex.extract(e)
        }
        val f = assertNotNull(last).let { last!! }
        assertEquals(60.5f, f.hrMean!!, 0.01f)
        assertEquals(5.5f, f.hrRel!!, 0.01f)
        assertEquals(30f, f.rmssdMs!!, 0.5f)
        assertEquals(0, f.activeSeconds)
        assertTrue(abs(f.quality - 1f) < 1e-6)
    }
}
