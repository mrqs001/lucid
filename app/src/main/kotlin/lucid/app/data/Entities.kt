package lucid.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import lucid.core.model.DataOrigin

enum class SessionStatus { RECORDING, COMPLETED, ABORTED }

/** One night (or one simulated night). Summary columns are denormalised for lists and stats. */
@Entity(tableName = "session", indices = [Index("startedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val origin: DataOrigin,
    val device: String,
    val provider: String,
    val scenarioId: String? = null,
    val seed: Long? = null,
    val linkKind: String,
    /** EngineConfig used for this night (JSON), so every night can be reproduced and compared. */
    val configJson: String,
    val estimatorId: String,
    val policyId: String,
    val experimentId: String? = null,
    val armId: String? = null,
    val armLabel: String? = null,
    val status: SessionStatus = SessionStatus.RECORDING,
    val summaryJson: String? = null,
    val sleepOnsetAt: Long? = null,
    val estSleepMin: Float? = null,
    val estRemMin: Float? = null,
    val cuesDelivered: Int = 0,
    val cuesWouldDeliver: Int = 0,
    val suspectedArousals: Int = 0,
    val meanQuality: Float? = null,
) {
    val isSimulated: Boolean get() = origin == DataOrigin.SIMULATED
}

/** Raw measurements exactly as received (kind: 0 HR, 1 IBI, 2 motion, 3 sensor status). */
@Entity(tableName = "sample", indices = [Index("sessionId", "t")])
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val t: Long,
    val kind: Int,
    val v1: Float,
    val v2: Float,
    val quality: Int,
    val backfill: Boolean,
) {
    companion object {
        const val HR = 0
        const val IBI = 1
        const val MOTION = 2
        const val STATUS = 3
    }
}

/** Features and stage probabilities per 30 s epoch. */
@Entity(tableName = "epoch", primaryKeys = ["sessionId", "idx"])
data class EpochEntity(
    val sessionId: String,
    val idx: Int,
    val startMs: Long,
    val hrMean: Float?,
    val hrRel: Float?,
    val hrInstability: Float?,
    val rmssd: Float?,
    val activityMg: Float?,
    val activeSeconds: Int,
    val quality: Float,
    val contactLost: Boolean,
    val pWake: Float,
    val pLight: Float,
    val pDeep: Float,
    val pRem: Float,
    val confidence: Float,
    /** Most likely stage (SleepStage ordinal). */
    val stage: Int,
    val contributionsJson: String,
)

/** Every cue candidate: delivered, suppressed (with reasons) or would-deliver on baseline nights. */
@Entity(tableName = "cue_event", indices = [Index("sessionId"), Index("cueId")])
data class CueEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val t: Long,
    val epochIdx: Int,
    val action: String,
    val failuresJson: String,
    val explanation: String,
    val pRem: Float,
    val pWake: Float,
    val confidence: Float,
    val quality: Float,
    val intensity: Float,
    val patternId: String,
    val cueId: String? = null,
    val cueNumber: Int? = null,
    val outcome: String? = null,
    val ackAt: Long? = null,
    val ackDetail: String? = null,
    val response: String? = null,
    val responseDetail: String? = null,
)

/** Simulator ground truth per epoch. Only ever written for simulated sessions. */
@Entity(tableName = "sim_truth", primaryKeys = ["sessionId", "idx"])
data class SimTruthEntity(val sessionId: String, val idx: Int, val stage: Int)

/** Battery level of the watch or the phone during a night (stored when it changes, and every 10 min). */
@Entity(tableName = "battery", indices = [Index("sessionId", "t")])
data class BatteryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val t: Long,
    /** "watch" or "phone" */
    val device: String,
    val pct: Int,
    val charging: Boolean?,
)

/** Timeline of non-measurement events: link changes, sleep onset, watch status, errors. */
@Entity(tableName = "session_event", indices = [Index("sessionId")])
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val t: Long,
    val type: String,
    val detail: String,
)

enum class Recall(val label: String) { NONE("Nothing"), FRAGMENTS("Fragments"), FULL("Whole dream") }

enum class CueRecognition(val label: String) {
    NOT_APPLICABLE("No cue tonight / not sure if cued"),
    NO("Didn't notice"),
    INCORPORATED("Felt it as part of the dream"),
    RECOGNIZED("Recognised it as the cue"),
    WOKE_ME("It woke me up"),
}

@Entity(tableName = "journal", indices = [Index("createdAt"), Index("sessionId")])
data class JournalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val sessionId: String? = null,
    val title: String = "",
    val body: String = "",
    val recall: Recall = Recall.FULL,
    val lucid: Boolean = false,
    /** 0 none, 1 brief/unstable, 2 clear, 3 clear with deliberate control. */
    val lucidity: Int = 0,
    val cueRecognition: CueRecognition = CueRecognition.NOT_APPLICABLE,
    val vividness: Int = 3,
    val control: Int = 1,
    val sleepQuality: Int = 3,
    val awakenings: Int = 0,
    val tags: String = "",
    /** Reserved for voice notes (future transcription). */
    val audioPath: String? = null,
)

enum class TrainingType(val label: String) {
    REALITY_CHECK("Reality check"),
    PRESLEEP_SESSION("Pre-sleep cue training"),
    REMINDER_SHOWN("Reminder shown"),
    MILD_INTENTION("Sleep intention"),
}

@Entity(tableName = "training", indices = [Index("t")])
data class TrainingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val t: Long,
    val type: TrainingType,
    val detail: String = "",
    val durationSec: Int = 0,
    /** "reminder", "manual" or "presleep". */
    val source: String = "manual",
)

@Entity(tableName = "experiment")
data class ExperimentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val hypothesis: String,
    val createdAt: Long,
    val active: Boolean,
    /** RANDOM or ALTERNATE */
    val assignment: String,
    /** Hide the night's arm and cue details until the morning journal entry is saved. */
    val blinded: Boolean,
    val armsJson: String,
    val seed: Long,
    val endedAt: Long? = null,
)
