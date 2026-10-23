package lucid.core.engine

import lucid.core.model.CueAck
import lucid.core.model.CueOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CuePolicyTest {
    private val epochMs = 30_000L
    private val onset = 0L

    /** Drives a policy epoch by epoch with configurable physiology. */
    private class Driver(val policy: TlrCuePolicy, val epochMs: Long) {
        var index = 0
        var ids = 0
        val decisions = ArrayList<CueDecision>()
        val assessments = ArrayList<CueResponseAssessment>()

        fun epoch(
            pRem: Float = 0.9f,
            pWake: Float = 0.02f,
            confidence: Float = 0.8f,
            quality: Float = 1f,
            active: Int = 0,
            still: Float? = 30f,
            hr: Float = 60f,
            hours: Float? = 5f,
            link: Boolean = true,
            nowLagMs: Long = 3_000,
        ): CueDecision? {
            val start = index * epochMs
            val f = EpochFeatures(
                index = index, startMs = start, hrMean = hr, hrCoverage = 1f, hrRel = 5f, hrInstability = 3f,
                rmssdMs = 30f, lnRmssdRel = -0.2f, cleanIbiCount = 30, ibiArtifactFraction = 0f, activityMg = 2f,
                activeSeconds = active, maxAngleChangeDeg = 0f, motionCoverage = 1f, minutesSinceMovement = still,
                contactLost = false, baselineReady = true, quality = quality, rejectedSamples = 0,
            )
            val rest = (1f - pRem - pWake).coerceAtLeast(0f)
            val p = StagePrediction(index, start, listOf(pWake, rest, 0f, pRem), confidence, emptyList(), "test")
            val out = policy.onEpoch(CueInput(f, p, hours, start + epochMs, start + epochMs + nowLagMs, link)) { "c${++ids}" }
            index++
            out.decision?.let { decisions += it }
            assessments += out.assessments
            return out.decision
        }

        fun run(n: Int, block: Driver.() -> CueDecision?) = (0 until n).mapNotNull { block() }
    }

    private fun driver(config: CueingConfig = CueingConfig()) = Driver(TlrCuePolicy(config), epochMs)

    @Test
    fun `below threshold produces no decision record`() {
        val d = driver()
        assertNull(d.epoch(pRem = 0.5f))
    }

    @Test
    fun `cue only after REM has been sustained`() {
        val d = driver(CueingConfig(minSustainedRemMin = 3f))
        val first = (0 until 6).map { d.epoch() }
        assertTrue(first.take(5).all { it!!.action == CueAction.SUPPRESS && it.failures.any { f -> f.gate == CueGate.REM_NOT_SUSTAINED } })
        assertEquals(CueAction.DELIVER, first[5]!!.action)
    }

    @Test
    fun `a movement restarts the sustained-REM timer`() {
        val d = driver(CueingConfig(minSustainedRemMin = 3f, motionLockoutMin = 0.5f))
        repeat(5) { d.epoch() }
        d.epoch(active = 3, still = 0f)
        val after = (0 until 5).map { d.epoch(still = 1f + it * 0.5f)!! }
        assertTrue(after.take(4).all { it.failures.any { f -> f.gate == CueGate.REM_NOT_SUSTAINED } })
        assertEquals(CueAction.DELIVER, (after + d.epoch(still = 4f)!!).last { it.failures.isEmpty() || it.action == CueAction.DELIVER }.action)
    }

    @Test
    fun `REM carried through epochs without heart-rate data must re-establish`() {
        val d = driver(CueingConfig(minSustainedRemMin = 3f))
        repeat(5) { d.epoch() }
        repeat(2) { d.epoch(quality = 0f, confidence = 0f) } // the estimate is only the model's memory
        val after = (0 until 6).map { d.epoch()!! }
        assertTrue(after.take(5).all { it.failures.any { f -> f.gate == CueGate.REM_NOT_SUSTAINED } })
        assertEquals(CueAction.DELIVER, after[5].action)
    }

    @Test
    fun `every failing rule is listed`() {
        val d = driver()
        val dec = d.epoch(hours = 1f, confidence = 0.1f, quality = 0.1f, pWake = 0.05f, still = 0.5f, link = false)!!
        val gates = dec.failures.map { it.gate }.toSet()
        assertTrue(gates.containsAll(listOf(CueGate.TOO_EARLY, CueGate.LOW_CONFIDENCE, CueGate.LOW_SIGNAL_QUALITY, CueGate.RECENT_MOVEMENT, CueGate.WATCH_DISCONNECTED, CueGate.REM_NOT_SUSTAINED)))
        assertTrue(dec.explanation.startsWith("Blocked"))
    }

    @Test
    fun `sleep onset unknown and stale data block cues`() {
        val d = driver(CueingConfig(minSustainedRemMin = 0f))
        val a = d.epoch(hours = null)!!
        assertTrue(a.failures.any { it.gate == CueGate.SLEEP_ONSET_UNKNOWN })
        val b = d.epoch(nowLagMs = 10 * 60_000)!!
        assertTrue(b.failures.any { it.gate == CueGate.STALE_DATA })
    }

    @Test
    fun `spacing, per-period and nightly caps hold`() {
        val cfg = CueingConfig(minSustainedRemMin = 0f, minCueSpacingSec = 90, maxCuesPerRemPeriod = 3, maxCuesPerNight = 4)
        val d = driver(cfg)
        repeat(30) { d.epoch() }
        val delivered = d.decisions.filter { it.action == CueAction.DELIVER }
        assertEquals(3, delivered.size)
        delivered.zipWithNext().forEach { (a, b) -> assertTrue((b.epochIndex - a.epochIndex) * 30 >= 90) }
        assertTrue(d.decisions.any { it.failures.any { f -> f.gate == CueGate.MAX_PER_REM_PERIOD } })
        // REM period ends after 10 low epochs; the nightly cap then stops cue number 5.
        repeat(12) { d.epoch(pRem = 0.1f) }
        repeat(30) { d.epoch() }
        assertEquals(4, d.decisions.count { it.action == CueAction.DELIVER })
        assertTrue(d.decisions.last().failures.any { it.gate == CueGate.MAX_PER_NIGHT })
    }

    @Test
    fun `baseline night logs would-deliver and never sends`() {
        val d = driver(CueingConfig(mode = CueMode.BASELINE, minSustainedRemMin = 0f))
        repeat(20) { d.epoch() }
        assertTrue(d.decisions.none { it.action == CueAction.DELIVER })
        val would = d.decisions.filter { it.action == CueAction.WOULD_DELIVER }
        assertTrue(would.isNotEmpty())
        assertTrue(would.all { it.cueId == null })
    }

    @Test
    fun `arousal after a cue lowers intensity and pauses cueing`() {
        val cfg = CueingConfig(minSustainedRemMin = 0f, startIntensity = 0.5f, intensityStep = 0.1f, arousalBackoffMin = 10f)
        val d = driver(cfg)
        val cue = d.epoch()!!
        assertEquals(CueAction.DELIVER, cue.action)
        d.policy.onAck(CueAck(cue.cueId!!, 0, CueOutcome.SIMULATED))
        d.epoch(active = 4, still = 0f)
        val a = d.assessments.single()
        assertEquals(CueResponse.AROUSAL_SUSPECTED, a.response)
        assertEquals(0.3f, a.nextIntensity, 1e-5f)
        val blocked = d.epoch()!!
        assertTrue(blocked.failures.any { it.gate == CueGate.AROUSAL_BACKOFF })
    }

    @Test
    fun `no reaction steps intensity up to the cap`() {
        val cfg = CueingConfig(minSustainedRemMin = 0f, startIntensity = 0.7f, maxIntensity = 0.8f, minCueSpacingSec = 60)
        val d = driver(cfg)
        repeat(12) {
            d.epoch()?.cueId?.let { id -> d.policy.onAck(CueAck(id, 0, CueOutcome.SIMULATED)) }
        }
        assertTrue(d.assessments.all { it.response == CueResponse.NO_REACTION })
        assertEquals(0.8f, d.policy.currentIntensity, 1e-5f)
    }

    @Test
    fun `failed delivery does not raise intensity`() {
        val d = driver(CueingConfig(minSustainedRemMin = 0f, startIntensity = 0.4f))
        val cue = d.epoch()!!
        d.policy.onAck(CueAck(cue.cueId!!, 0, CueOutcome.EXPIRED))
        repeat(3) { d.epoch(pRem = 0.2f) }
        assertTrue(d.assessments.isEmpty())
        assertEquals(0.4f, d.policy.currentIntensity, 1e-5f)
    }
}
