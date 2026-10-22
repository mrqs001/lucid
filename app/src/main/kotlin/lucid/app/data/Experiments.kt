package lucid.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import lucid.core.engine.CueMode
import lucid.core.engine.CueingConfig
import kotlin.random.Random

/** One condition of an experiment, e.g. "no cues" vs "triple-knock cues". */
@Serializable
data class ExperimentArm(val id: String, val label: String, val cueing: CueingConfig)

object ExperimentTemplates {
    /** The comparison every first experiment should run: does cueing change anything vs. no cues? */
    fun cueVsBaseline(base: CueingConfig) = listOf(
        ExperimentArm("baseline", "No cues (baseline)", base.copy(mode = CueMode.BASELINE)),
        ExperimentArm("cue", "Vibration cues", base.copy(mode = CueMode.CUE)),
    )

    fun patternComparison(base: CueingConfig) = listOf(
        ExperimentArm("triple", "Triple knock", base.copy(mode = CueMode.CUE, patternId = "tlr-triple")),
        ExperimentArm("wave", "Rising wave", base.copy(mode = CueMode.CUE, patternId = "rising-wave")),
    )
}

private val armsCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun ExperimentEntity.arms(): List<ExperimentArm> = armsCodec.decodeFromString(ListSerializer(ExperimentArm.serializer()), armsJson)

fun encodeArms(arms: List<ExperimentArm>): String = armsCodec.encodeToString(ListSerializer(ExperimentArm.serializer()), arms)

/**
 * Chooses tonight's arm. RANDOM draws from a seeded generator keyed on the night number so the
 * schedule is reproducible; ALTERNATE cycles through arms in order.
 */
fun ExperimentEntity.armForNight(nightNumber: Int): ExperimentArm {
    val a = arms()
    return when (assignment) {
        "ALTERNATE" -> a[nightNumber % a.size]
        else -> a[Random(seed * 31 + nightNumber).nextInt(a.size)]
    }
}
