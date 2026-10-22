package lucid.core.sim

import lucid.core.model.Clock
import lucid.core.model.CueAck
import lucid.core.model.CueCommand
import lucid.core.model.CueOutcome
import lucid.core.model.CuePurpose
import lucid.core.model.DataOrigin
import lucid.core.model.Hello
import lucid.core.model.SampleBatch
import lucid.core.model.SensorSample
import lucid.core.model.SleepStage
import lucid.core.model.SourceInfo
import lucid.core.model.WatchCapabilities
import lucid.core.model.WatchStatus
import lucid.core.model.WireMessage
import java.util.concurrent.ConcurrentLinkedQueue

/** Majority ground-truth stage of one 30 s epoch. Evaluation and charts only. */
data class TruthEpoch(val index: Int, val t: Long, val stage: SleepStage)

/** What the simulated body did after a cue. Evaluation only; the phone never receives this. */
data class SimulatedCueEffect(val cueId: String, val t: Long, val stageAtCue: SleepStage, val response: PhysiologyGenerator.CueResponse)

/**
 * A whole simulated watch: generates measurements, batches them like the real watch app, buffers
 * during link loss and backfills afterwards, and acknowledges cue commands without vibrating.
 *
 * It is stepped one simulated second at a time and returns the messages it would have sent, so it
 * can be driven at any speed and stays deterministic. Commands from the phone go through a
 * thread-safe inbox and take effect on the next step.
 */
class SimulatedWatchDevice(
    val scenario: Scenario,
    val seed: Long,
    startMs: Long,
    private val batchSeconds: Int = 5,
    private val bufferCapacity: Int = 250_000,
    private val epochMs: Long = 30_000,
) {
    val script: ScenarioScript = scenario.build(seed)
    private val generator = PhysiologyGenerator(script, seed, startMs)
    private val inbox = ConcurrentLinkedQueue<WireMessage>()
    private val pending = ArrayList<SensorSample>()
    private val buffer = ArrayDeque<SensorSample>()
    private var seq = 0L
    private var lastSecond = -1
    private val epochVotes = IntArray(SleepStage.entries.size)
    private var epochIndex = 0

    val startMs: Long = startMs
    val truth = ArrayList<TruthEpoch>()
    val cueEffects = ArrayList<SimulatedCueEffect>()
    var droppedSamples = 0
        private set
    var linkUp = true
        private set

    val source = SourceInfo(DataOrigin.SIMULATED, "In-app simulated watch", "sim", scenario.id, seed)
    val capabilities = WatchCapabilities(
        heartRate = true, interBeatIntervals = true, accelerometer = true,
        hasVibrator = false, hasAmplitudeControl = false, isEmulator = false,
    )

    /** Virtual time: the timestamp of the most recently generated second. */
    val clock = Clock { generator.startMs + maxOf(0, generator.currentSecond - 1) * 1000L }
    val finished: Boolean get() = generator.finished
    val durationSec: Int get() = generator.durationSec
    val elapsedSec: Int get() = generator.currentSecond

    private var truthCursor = 0
    private var effectCursor = 0

    fun hello(): WireMessage = Hello(source = source, capabilities = capabilities)

    /** Ground-truth epochs closed since the last call (for persisting the truth overlay). */
    fun takeNewTruth(): List<TruthEpoch> = truth.subList(truthCursor, truth.size).toList().also { truthCursor = truth.size }

    /** Simulated cue effects since the last call. */
    fun takeNewCueEffects(): List<SimulatedCueEffect> =
        cueEffects.subList(effectCursor, cueEffects.size).toList().also { effectCursor = cueEffects.size }

    /** Queue a phone-to-watch message. Safe to call from any thread. */
    fun receive(message: WireMessage) {
        inbox += message
    }

    /** Advance one simulated second and return what the watch sends to the phone. */
    fun step(): List<WireMessage> {
        val out = ArrayList<WireMessage>(2)
        val frame = generator.next()
        lastSecond = frame.second
        handleInbox(frame, out)
        recordTruth(frame)

        pending += frame.samples
        val wasUp = linkUp
        linkUp = frame.linkUp
        if (!linkUp) {
            buffer.addAll(pending)
            pending.clear()
            while (buffer.size > bufferCapacity) {
                buffer.removeFirst()
                droppedSamples++
            }
            return out
        }
        if (!wasUp || buffer.isNotEmpty()) flushBuffer(frame.t, out)
        if ((frame.second + 1) % batchSeconds == 0 || generator.finished) {
            out += SampleBatch(seq++, frame.t, pending.toList())
            pending.clear()
        }
        if (frame.second % 60 == 0) {
            out += WatchStatus(frame.t, monitoring = true, batteryPct = null, charging = null, bufferedSamples = buffer.size)
        }
        return out
    }

    private fun flushBuffer(now: Long, out: MutableList<WireMessage>) {
        while (buffer.isNotEmpty()) {
            val chunk = ArrayList<SensorSample>(minOf(600, buffer.size))
            repeat(minOf(600, buffer.size)) { chunk += buffer.removeFirst() }
            out += SampleBatch(seq++, now, chunk, backfill = true)
        }
    }

    private fun handleInbox(frame: SimFrame, out: MutableList<WireMessage>) {
        while (true) {
            val msg = inbox.poll() ?: break
            if (msg !is CueCommand) continue
            if (frame.t > msg.expiresAt) {
                out += CueAck(msg.cueId, frame.t, CueOutcome.EXPIRED, "command expired before reaching the simulated watch")
                continue
            }
            if (msg.purpose == CuePurpose.SLEEP_CUE) {
                val stage = generator.truthAt(frame.second)
                val response = generator.injectCue(frame.second, msg.intensity)
                cueEffects += SimulatedCueEffect(msg.cueId, frame.t, stage, response)
            }
            out += CueAck(msg.cueId, frame.t, CueOutcome.SIMULATED, "pattern ${msg.pattern.id} @ ${"%.2f".format(msg.intensity)}")
        }
    }

    private fun recordTruth(frame: SimFrame) {
        val idx = ((frame.t - startMs) / epochMs).toInt()
        if (idx != epochIndex) closeEpoch()
        epochIndex = idx
        epochVotes[frame.truth.ordinal]++
        if (generator.finished) closeEpoch()
    }

    private fun closeEpoch() {
        if (epochVotes.sum() == 0) return
        val stage = SleepStage.entries[epochVotes.indices.maxBy { epochVotes[it] }]
        truth += TruthEpoch(epochIndex, startMs + epochIndex * epochMs, stage)
        epochVotes.fill(0)
    }
}
