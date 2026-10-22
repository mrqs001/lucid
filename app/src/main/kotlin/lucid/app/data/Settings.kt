package lucid.app.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lucid.core.engine.EngineConfig
import lucid.core.sim.Scenarios
import java.io.InputStream
import java.io.OutputStream

@Serializable
enum class SourceKind(val label: String, val detail: String) {
    IN_APP_SIMULATOR("In-app simulator", "No watch needed. Generates a night on the phone and replays it fast."),
    WATCH_DATA_LAYER("Watch (Wear Data Layer)", "A paired Wear OS watch running the Lucid watch app."),
    WATCH_DEV_BRIDGE("Watch emulator (dev bridge)", "Wear OS emulator over TCP: debug builds only, see docs/EMULATORS.md."),
}

@Serializable
data class SimulationSettings(
    val scenarioId: String = Scenarios.Normal.id,
    /** null = the scenario's own default seed. */
    val seed: Long? = null,
    val speed: Float = 240f,
    /** When a watch is connected, ask it to generate simulated data instead of using its sensors. */
    val watchSimulates: Boolean = false,
)

@Serializable
data class TrainingSettings(
    val remindersEnabled: Boolean = false,
    val remindersPerDay: Int = 8,
    val windowStartHour: Int = 9,
    val windowEndHour: Int = 21,
    val presleepPairings: Int = 8,
)

@Serializable
data class AppSettings(
    val engine: EngineConfig = EngineConfig(),
    val source: SourceKind = SourceKind.WATCH_DATA_LAYER,
    val simulation: SimulationSettings = SimulationSettings(),
    val training: TrainingSettings = TrainingSettings(),
    /** Statistics exclude simulated nights unless this is on. */
    val includeSimulatedInStats: Boolean = false,
    /** Shows the simulator, data-source choices and other development tools. */
    val developerMode: Boolean = false,
    /** End the night when the phone (not charging) falls to this battery level. 0 = never. */
    val stopAtPhoneBatteryPct: Int = 20,
)

private val settingsJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private object SettingsSerializer : Serializer<AppSettings> {
    override val defaultValue = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        settingsJson.decodeFromString(AppSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable settings", e)
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(settingsJson.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}

private val Context.settingsStore: DataStore<AppSettings> by dataStore("settings.json", SettingsSerializer)

class SettingsRepository(context: Context) {
    private val store = context.settingsStore
    val settings: Flow<AppSettings> = store.data

    suspend fun current(): AppSettings = store.data.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }
}
