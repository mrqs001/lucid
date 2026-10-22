package lucid.core.engine

import kotlinx.serialization.Serializable

/** Everything that shapes how a night is processed. Stored with every session for reproducibility. */
@Serializable
data class EngineConfig(
    val epochMs: Long = 30_000,
    /** How long to wait for out-of-order samples before an epoch is finalised. */
    val latenessMs: Long = 3_000,
    /** Personal resting-sleep HR from earlier nights; used until tonight's baseline settles. */
    val priorHrBaseline: Float? = null,
    val cueing: CueingConfig = CueingConfig(),
)

@Serializable
enum class CueMode(val label: String) {
    /** Deliver cues when every rule passes. */
    CUE("Cue"),

    /** Baseline / control night: evaluate every rule, log "would deliver", never vibrate. */
    BASELINE("Baseline (no cues)"),
}

/**
 * Rules for delivering vibration cues during probable REM.
 * Defaults are deliberately conservative: protecting sleep matters more than cue count.
 */
@Serializable
data class CueingConfig(
    val mode: CueMode = CueMode.CUE,
    /** REM probability an epoch must reach before a cue is even considered. */
    val remThreshold: Float = 0.70f,
    /** Minimum estimator confidence (data support x certainty). */
    val minConfidence: Float = 0.45f,
    /** Minimum signal quality of the latest epochs. */
    val minSignalQuality: Float = 0.5f,
    /**
     * REM must have been probable for this long before the first cue of a REM period
     * (Paul et al. 2014 waited 5-10 min of REM; a wrist estimate needs at least as much margin).
     */
    val minSustainedRemMin: Float = 5f,
    /**
     * No cues during the first part of the night: deep-sleep-heavy, short REM periods, and lucidity
     * induction works better late (Erlacher 2022: 4.5 h worse than 6 h).
     */
    val minMinutesAfterSleepOnset: Float = 300f,
    /** Skip epochs where wake is this likely. */
    val maxWakeProbability: Float = 0.20f,
    /** Any movement within this many minutes blocks cueing. */
    val motionLockoutMin: Float = 2f,
    /** Lab TLR studies cue every 10-60 s inside REM; one cue per minute is the conservative end. */
    val minCueSpacingSec: Int = 60,
    val maxCuesPerNight: Int = 20,
    /** Konkoly et al. 2021 capped at 10 per REM period; wrist vibration wakes people easily, so fewer. */
    val maxCuesPerRemPeriod: Int = 6,
    /** After a suspected arousal, pause cueing for this long, then resume one step lower. */
    val arousalBackoffMin: Float = 15f,
    /** Cue intensity titration: start low, step up when there is no reaction, step down after an arousal. */
    val startIntensity: Float = 0.3f,
    val minIntensity: Float = 0.15f,
    val maxIntensity: Float = 0.85f,
    val intensityStep: Float = 0.1f,
    val patternId: String = "pulse-200",
    /** How many times the pattern repeats within one cue. */
    val patternRepeats: Int = 1,
    /** Watch must drop commands older than this (e.g. after a reconnect). */
    val commandTtlSec: Int = 20,
    /** Epochs whose data is older than this (relative to now) are never cued on. */
    val staleAfterSec: Int = 90,
)
