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
     * REM must have been probable for this long before the first cue of a REM period. Tuned on 31
     * lab nights (docs/MODEL.md): 3 min reached a third more late REM periods than 5 min (many REM
     * periods are fragmented and end before 5 min) at about the same share of cues in REM.
     */
    val minSustainedRemMin: Float = 3f,
    /**
     * No cues during the first part of the night. In 31 lab nights the first REM period was short
     * (median 17 min, later ones 20-30 min) and cues were least precise early: about 68 % landed in
     * REM in hours 1-2 after onset, over 80 % from hour 3 on (docs/MODEL.md). 3.5 h still leaves room
     * on short nights. Long sleepers may prefer later: wake-back-to-bed studies found lucidity more
     * likely at 6 h than at 4.5 h (Erlacher 2022).
     */
    val minMinutesAfterSleepOnset: Float = 210f,
    /** Skip epochs where wake is this likely. */
    val maxWakeProbability: Float = 0.20f,
    /**
     * Any movement within this many minutes blocks cueing. Movements cluster where REM ends and at
     * awakenings; with the shorter sustain time, 4 min avoided most of the extra wrong cues.
     */
    val motionLockoutMin: Float = 4f,
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
    /**
     * Epochs whose data is older than this (relative to now) are never cued on. The watch sends
     * every 30 s and heart rate arrives in 30 s flushes, so data is normally 30-70 s old.
     */
    val staleAfterSec: Int = 120,
)
