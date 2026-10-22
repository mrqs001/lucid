package lucid.core.sim

import lucid.core.model.HeartRateSample
import lucid.core.model.IbiSample
import lucid.core.model.MotionSample
import lucid.core.model.SampleQuality
import lucid.core.model.SensorSample
import lucid.core.model.SensorStatus
import lucid.core.model.SensorStatusSample
import lucid.core.model.SleepStage
import lucid.core.model.SleepStage.DEEP
import lucid.core.model.SleepStage.LIGHT
import lucid.core.model.SleepStage.REM
import lucid.core.model.SleepStage.WAKE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * One simulated second. [samples] is what a watch would report; [truth] is the scripted stage
 * and must only ever reach evaluation/visualisation code, never the estimator.
 */
data class SimFrame(
    val second: Int,
    val t: Long,
    val samples: List<SensorSample>,
    val truth: SleepStage,
    val linkUp: Boolean,
)

/**
 * Turns a [ScenarioScript] into second-by-second watch measurements.
 *
 * The model is intentionally *not* the estimator's model in reverse: heart rate is an AR(1)
 * process pulled toward stage-dependent targets, beats are placed one by one with respiratory
 * sinus arrhythmia and jitter, and the watch's reported HR is a smoothed beat rate. The point is
 * plausible, replayable test input, not physiological truth.
 *
 * Deterministic: the same script, seed and injected cues always produce the same frames.
 */
class PhysiologyGenerator(
    private val script: ScenarioScript,
    seed: Long,
    val startMs: Long,
) {
    private val rnd = Random(seed * 7919 + 17)
    private val responseRnd = Random(seed * 104729 + 3)
    private val profile = script.profile

    private val overrides = mutableListOf<StageSegment>()
    private val injected = mutableListOf<SimEvent>()

    private var second = 0
    private var hrSlow = profile.restingHr + 12f
    private var reportedHr = hrSlow
    private var lastBeatMs = startMs - 800.0
    private var nextBeatMs = startMs + 200.0
    private var respPhase = 0.0
    private var phasicUntil = -1
    private var phasicBpm = 0f
    private var twitchUntil = -1
    private var lastContact = true
    private var pendingCompensatory = 0.0
    private var pendingMerge = 0.0

    // Per-second lookups so the generator stays O(1) per simulated second.
    private val n = script.durationSec
    private val movementAt = arrayOfNulls<SimEvent.Movement>(n)
    private val contactLossAt = BooleanArray(n)
    private val noisyAt = arrayOfNulls<SimEvent.NoisySignal>(n)
    private val remLikeAt = BooleanArray(n)
    private val linkDownAt = BooleanArray(n)
    private val surges = script.eventsOf<SimEvent.AutonomicSurge>()

    init {
        fun SimEvent.seconds() = startSec.coerceIn(0, n) until endSec.coerceIn(0, n)
        for (e in script.events) when (e) {
            is SimEvent.Movement -> for (s in e.seconds()) if (movementAt[s] == null) movementAt[s] = e
            is SimEvent.ContactLoss -> for (s in e.seconds()) contactLossAt[s] = true
            is SimEvent.NoisySignal -> for (s in e.seconds()) noisyAt[s] = e
            is SimEvent.RemLikePhysiology -> for (s in e.seconds()) remLikeAt[s] = true
            is SimEvent.LinkDown -> for (s in e.seconds()) linkDownAt[s] = true
            is SimEvent.AutonomicSurge -> Unit
        }
    }

    val durationSec: Int get() = script.durationSec
    val finished: Boolean get() = second >= script.durationSec
    val currentSecond: Int get() = second

    fun truthAt(sec: Int): SleepStage =
        overrides.lastOrNull { sec in it.startSec until it.endSec }?.stage ?: script.stageAt(sec)

    /**
     * Model the body's response to a vibration cue: depending on stage and intensity, the
     * sleeper may show a movement and heart-rate surge, or briefly wake up.
     * Returns what happened, for logs and synthetic evaluation.
     */
    fun injectCue(atSec: Int, intensity: Float): CueResponse {
        val stage = truthAt(atSec)
        val i = intensity.coerceIn(0f, 1f)
        val pArousal = when (stage) {
            REM -> 0.06 + 0.45 * i * i
            LIGHT -> 0.12 + 0.55 * i
            DEEP -> 0.03 + 0.25 * i
            WAKE -> 0.0
        }
        if (stage == WAKE || responseRnd.nextDouble() >= pArousal) return CueResponse.NONE
        val delay = responseRnd.nextInt(2, 9)
        injected += SimEvent.Movement(atSec + delay, responseRnd.nextInt(2, 7), responseRnd.nextFloat(0.4f, 1f))
        injected += SimEvent.AutonomicSurge(atSec + delay, responseRnd.nextInt(20, 45), responseRnd.nextInt(8, 16).toFloat())
        if (responseRnd.nextDouble() < 0.35 * i + 0.05) {
            overrides += StageSegment(atSec + delay, atSec + delay + responseRnd.nextInt(60, 240), WAKE)
            return CueResponse.AWAKENING
        }
        return CueResponse.AROUSAL
    }

    enum class CueResponse { NONE, AROUSAL, AWAKENING }

    fun next(): SimFrame {
        check(!finished) { "night is over" }
        val sec = second++
        val t = startMs + sec * 1000L
        val stage = truthAt(sec)
        val samples = ArrayList<SensorSample>(4)

        val contact = !contactLossAt[sec]
        val noisy = noisyAt[sec]
        val remLike = remLikeAt[sec]
        val moving = activeMovement(sec)
        val surge = activeSurges(sec)

        if (contact != lastContact) {
            samples += SensorStatusSample(t, if (contact) SensorStatus.ON_WRIST else SensorStatus.OFF_WRIST)
            lastContact = contact
        }

        // --- heart rate dynamics ---
        val effective = if (remLike) REM else stage
        if (effective == REM && sec >= phasicUntil && rnd.nextDouble() < 1.0 / 80) {
            phasicUntil = sec + rnd.nextInt(5, 20)
            phasicBpm = rnd.nextFloat(4f, 12f)
        }
        val phasic = if (sec < phasicUntil && effective == REM) phasicBpm else 0f
        val movementBpm = if (moving != null) 6f + 8f * moving.intensity else 0f
        val target = profile.restingHr + stageOffset(effective) + profile.hrDriftPerHour * sec / 3600f +
            phasic + surge + movementBpm + if (remLike && stage == WAKE) 4f else 0f
        hrSlow += (target - hrSlow) / 40f + (rnd.gaussian(0.0, innovationSd(effective)).toFloat())
        val instantHr = hrSlow.coerceIn(38f, 160f)

        // --- beats within this second ---
        val secondEnd = t + 1000.0
        val respRate = respRateHz(effective)
        val artifactRate = max(profile.baseArtifactRate, noisy?.artifactRate ?: 0f) + if (moving != null) 0.15f else 0f
        while (nextBeatMs < secondEnd) {
            val beatAt = nextBeatMs
            val trueIbi = beatAt - lastBeatMs
            lastBeatMs = beatAt

            // Schedule the following beat.
            val base = 60000.0 / instantHr
            respPhase += 2 * PI * respRate * base / 1000.0
            var ibi = base * (1 + rsaAmplitude(effective) * sin(respPhase)) + rnd.gaussian(0.0, jitterMs(effective))
            if (pendingCompensatory != 0.0) {
                ibi += pendingCompensatory
                pendingCompensatory = 0.0
            } else if (rnd.nextDouble() < artifactRate / 2) {
                // premature (ectopic) beat followed by a compensatory pause
                pendingCompensatory = ibi * 0.4
                ibi *= 0.6
            }
            nextBeatMs = beatAt + ibi.coerceAtLeast(250.0)

            if (!contact) continue
            if (rnd.nextDouble() < artifactRate / 2) {
                // the watch misses this beat, so the next reported interval spans two beats
                pendingMerge += trueIbi
                continue
            }
            val reported = trueIbi + pendingMerge
            pendingMerge = 0.0
            val quality = if (noisy != null || moving != null) SampleQuality.POOR else SampleQuality.GOOD
            samples += IbiSample(beatAt.toLong(), reported.toInt(), quality)
            reportedHr += (60000f / trueIbi.toFloat() - reportedHr) * 0.25f
        }

        // --- reported heart rate (1 Hz) ---
        if (!contact) {
            samples += HeartRateSample(t, 0f, SampleQuality.NO_CONTACT)
        } else if (rnd.nextDouble() >= profile.dropoutRate) {
            val q = when {
                noisy != null -> SampleQuality.POOR
                moving != null -> SampleQuality.FAIR
                else -> SampleQuality.GOOD
            }
            val noise = if (noisy != null) rnd.gaussian(0.0, 6.0).toFloat() else rnd.gaussian(0.0, 0.4).toFloat()
            samples += HeartRateSample(t, (reportedHr + noise).coerceIn(30f, 200f), q)
        }

        // --- motion (1 Hz summary) ---
        samples += motionSample(t, sec, effective, stage, moving, remLike)

        val linkUp = !linkDownAt[sec]
        return SimFrame(sec, t, samples.sortedBy { it.t }, stage, linkUp)
    }

    private fun motionSample(t: Long, sec: Int, effective: SleepStage, stage: SleepStage, moving: SimEvent.Movement?, remLike: Boolean): MotionSample {
        var activity = abs(rnd.gaussian(0.0, 1.5)).toFloat() + 1f
        var angle = abs(rnd.gaussian(0.0, 0.25)).toFloat()
        if (moving != null) {
            activity = moving.intensity * rnd.nextFloat(60f, 350f)
            angle = moving.intensity * rnd.nextFloat(3f, 40f)
        } else if (effective == REM && (sec < twitchUntil || rnd.nextDouble() < 1.0 / 40)) {
            if (sec >= twitchUntil) twitchUntil = sec + 1
            activity = rnd.nextFloat(8f, 25f)
            angle = rnd.nextFloat(0.2f, 1.5f)
        } else if (stage == WAKE && !remLike && rnd.nextDouble() < 1.0 / 20) {
            activity = rnd.nextFloat(15f, 80f)
            angle = rnd.nextFloat(1f, 8f)
        } else if (stage == LIGHT && rnd.nextDouble() < 1.0 / 300) {
            activity = rnd.nextFloat(10f, 40f)
            angle = rnd.nextFloat(0.5f, 4f)
        }
        return MotionSample(t, activity, angle)
    }

    private fun activeMovement(sec: Int): SimEvent.Movement? =
        movementAt[sec] ?: injected.firstOrNull { it is SimEvent.Movement && it.activeAt(sec) } as SimEvent.Movement?

    private fun activeSurges(sec: Int): Float =
        (surges.asSequence() + injected.asSequence().filterIsInstance<SimEvent.AutonomicSurge>())
            .filter { it.activeAt(sec) }
            .sumOf { e ->
                // smooth rise and fall over the surge
                val x = (sec - e.startSec).toDouble() / max(1, e.durationSec)
                e.bpm * sin(PI * min(1.0, x))
            }.toFloat()

    private fun stageOffset(s: SleepStage) = when (s) {
        WAKE -> 14f
        LIGHT -> 1.5f
        DEEP -> -2f
        REM -> 3.5f
    }

    private fun innovationSd(s: SleepStage) = when (s) {
        WAKE -> 0.8
        LIGHT -> 0.35
        DEEP -> 0.2
        REM -> 0.7
    }

    private fun rsaAmplitude(s: SleepStage) = when (s) {
        WAKE -> 0.02
        LIGHT -> 0.045
        DEEP -> 0.05
        REM -> 0.035
    }

    private fun jitterMs(s: SleepStage) = when (s) {
        WAKE -> 15.0
        LIGHT -> 10.0
        DEEP -> 8.0
        REM -> 18.0
    }

    private fun respRateHz(s: SleepStage) = when (s) {
        WAKE -> 16.0 / 60
        LIGHT -> 15.0 / 60
        DEEP -> 14.0 / 60
        REM -> (17.0 + rnd.gaussian(0.0, 2.0)) / 60
    }
}
