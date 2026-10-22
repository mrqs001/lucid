package lucid.app.session

import lucid.app.link.LinkState
import lucid.core.engine.CueDecision
import lucid.core.engine.EpochFeatures
import lucid.core.engine.StagePrediction
import lucid.core.model.DataOrigin
import lucid.core.model.SampleQuality
import lucid.core.model.WatchCapabilities
import lucid.core.sim.SimulationRunner

data class LiveEvent(val t: Long, val kind: Kind, val text: String, val repeats: Int = 1) {
    enum class Kind { INFO, CUE, SUPPRESSED, RESPONSE, WARNING }
}

/** Prepend [new] events (oldest first), folding a repeat of the newest event into a counter. */
fun List<LiveEvent>.withNew(new: List<LiveEvent>, max: Int): List<LiveEvent> {
    var out = this
    for (e in new) {
        val top = out.firstOrNull()
        out = if (top != null && top.kind == e.kind && top.text == e.text) {
            listOf(top.copy(t = e.t, repeats = top.repeats + 1)) + out.drop(1)
        } else {
            listOf(e) + out
        }
    }
    return out.take(max)
}

/** Snapshot of the running session for the live screen. Times are virtual for simulations. */
data class LiveState(
    val sessionId: String? = null,
    val recording: Boolean = false,
    val origin: DataOrigin? = null,
    val sourceLabel: String = "",
    val linkName: String = "",
    val link: LinkState = LinkState.Idle,
    val sim: SimulationRunner.State? = null,
    val scenarioTitle: String? = null,
    val armLabel: String? = null,
    val remThreshold: Float = 0.7f,
    val sessionStartMs: Long = 0,
    val nowMs: Long = 0,
    val heartRate: Float? = null,
    val hrQuality: SampleQuality? = null,
    val motionMg: Float? = null,
    val features: EpochFeatures? = null,
    val prediction: StagePrediction? = null,
    /** p(REM) per epoch, most recent last (up to 2 h). */
    val remHistory: List<Float> = emptyList(),
    /** Most likely stage ordinal per epoch, aligned with [remHistory]. */
    val stageHistory: List<Int> = emptyList(),
    /** Epoch offsets (relative to [remHistory]) of delivered cues. */
    val cueMarks: List<Int> = emptyList(),
    /** Heart rate every 5 s (up to 30 min). */
    val hrHistory: List<Float> = emptyList(),
    val sleepOnsetMs: Long? = null,
    val cuesDelivered: Int = 0,
    val cueCandidates: Int = 0,
    val lastDecision: CueDecision? = null,
    val cueIntensity: Float? = null,
    val samplesReceived: Long = 0,
    val samplesRejected: Long = 0,
    val epochs: Int = 0,
    val watchCaps: WatchCapabilities? = null,
    val battery: Int? = null,
    val phoneBattery: Int? = null,
    val bufferedOnWatch: Int = 0,
    val events: List<LiveEvent> = emptyList(),
    val error: String? = null,
) {
    val isSimulated: Boolean get() = origin == DataOrigin.SIMULATED
}
