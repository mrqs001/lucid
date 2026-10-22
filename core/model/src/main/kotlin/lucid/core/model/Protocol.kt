package lucid.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Messages exchanged between the watch and the phone. The same JSON encoding is used by every
 * transport (Wear OS Data Layer, the emulator dev bridge, and the in-app simulated watch), so a
 * transport only moves opaque bytes.
 */
@Serializable
sealed interface WireMessage

// ---- watch -> phone ----

@Serializable
@SerialName("hello")
data class Hello(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val source: SourceInfo,
    val capabilities: WatchCapabilities,
) : WireMessage

@Serializable
data class WatchCapabilities(
    val heartRate: Boolean,
    val interBeatIntervals: Boolean,
    val accelerometer: Boolean,
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val isEmulator: Boolean,
)

/**
 * A batch of samples. [sentAt] is the watch clock when the batch left (virtual time for a
 * simulated watch); the phone uses it to judge how fresh the data is. [backfill] marks samples
 * that were buffered on the watch during a disconnection and are delivered late.
 */
@Serializable
@SerialName("batch")
data class SampleBatch(
    val seq: Long,
    val sentAt: Long,
    val samples: List<SensorSample>,
    val backfill: Boolean = false,
    /** Session the samples belong to; the phone drops batches from any other session. */
    val sessionId: String? = null,
) : WireMessage

@Serializable
@SerialName("status")
data class WatchStatus(
    val t: Long,
    val monitoring: Boolean,
    val batteryPct: Int?,
    val charging: Boolean?,
    val bufferedSamples: Int,
    val sessionId: String? = null,
) : WireMessage

@Serializable
@SerialName("cue_ack")
data class CueAck(
    val cueId: String,
    val executedAt: Long,
    val outcome: CueOutcome,
    val detail: String = "",
) : WireMessage

@Serializable
enum class CueOutcome(val label: String) {
    /** Platform vibrator accepted the waveform on physical hardware. */
    VIBRATED("Vibrated"),

    /** Vibrator API accepted the call on an emulator: nothing physical happened. */
    EMULATED("Emulator vibrator call"),

    /** Simulated watch: no vibrator involved, the cue was only recorded. */
    SIMULATED("Simulated (recorded only)"),
    NO_VIBRATOR("No vibrator"),
    EXPIRED("Arrived too late"),
    NOT_MONITORING("Watch not monitoring"),
    ERROR("Error"),
}

// ---- phone -> watch ----

/**
 * Receipt for one [SampleBatch]. The watch keeps a batch until it is acknowledged and resends it
 * after a reconnection, because a write into a half-open connection "succeeds" without arriving.
 */
@Serializable
@SerialName("batch_ack")
data class BatchAck(val seq: Long) : WireMessage

@Serializable
enum class SensorMode { SIMULATED, REAL }

@Serializable
@SerialName("start")
data class StartMonitoring(
    val sessionId: String,
    val sensorMode: SensorMode,
    val scenarioId: String? = null,
    val seed: Long? = null,
    /** Replay speed for a watch-side simulation (1 = real time). */
    val speed: Float = 1f,
    /** Phone's session start; a watch-side simulation uses it as its virtual start time. */
    val startAtMs: Long? = null,
    /** Phone wall-clock time when sent. The Data Layer can deliver minutes late; stale starts are ignored. */
    val sentWallMs: Long? = null,
) : WireMessage

@Serializable
@SerialName("stop")
data class StopMonitoring(val sessionId: String) : WireMessage

@Serializable
@SerialName("cue")
data class CueCommand(
    val cueId: String,
    val sessionId: String?,
    val pattern: VibrationPattern,
    val intensity: Float,
    val repeats: Int,
    val issuedAt: Long,
    /** The watch must not vibrate after this time (stale command after a reconnect). */
    val expiresAt: Long,
    val purpose: CuePurpose = CuePurpose.SLEEP_CUE,
    /**
     * Phone wall-clock time when sent. [issuedAt]/[expiresAt] may be in a simulation's virtual time,
     * so this real-time stamp is what stops a delayed command from vibrating long after the fact.
     */
    val sentWallMs: Long? = null,
) : WireMessage

/** Real-time limits for phone commands, checked on the watch against its own wall clock. */
object CommandLimits {
    const val START_MAX_AGE_MS = 60_000L
    const val CUE_MAX_AGE_MS = 30_000L
}

@Serializable
enum class CuePurpose { SLEEP_CUE, TRAINING, TEST }

@Serializable
@SerialName("ping")
data class Ping(val t: Long) : WireMessage

const val PROTOCOL_VERSION = 1

object Wire {
    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: WireMessage): String = json.encodeToString(WireMessage.serializer(), message)

    fun decode(text: String): WireMessage = json.decodeFromString(WireMessage.serializer(), text)

    fun encodeBytes(message: WireMessage): ByteArray = encode(message).encodeToByteArray()

    fun decodeBytes(bytes: ByteArray): WireMessage = decode(bytes.decodeToString())

    /** Data Layer message path and capability names (must match on phone and watch). */
    const val DATA_LAYER_PATH = "/lucid/v1/wire"
    const val PHONE_CAPABILITY = "lucid_phone"
    const val WATCH_CAPABILITY = "lucid_watch"

    /** Emulator dev bridge: phone listens here; the watch emulator dials the host at 10.0.2.2. */
    const val DEV_BRIDGE_PORT = 47100
}
