package lucid.wear.sensors

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lucid.core.model.DataOrigin
import lucid.core.model.SensorSample
import lucid.core.model.SourceInfo
import lucid.core.sim.PhysiologyGenerator
import lucid.core.sim.Scenarios

/**
 * Generates a simulated night on the watch itself, at real time or accelerated, so the real
 * watch-side pipeline (batching, buffering, transport, vibration) runs without body sensors.
 */
class SimulatedSensorSource(
    scenarioId: String,
    seed: Long?,
    startMs: Long,
    private val speed: Float,
    private val scope: CoroutineScope,
    /** Called once when the scripted night is over. */
    private val onFinished: () -> Unit = {},
) : SensorSource {
    private val scenario = Scenarios.all.firstOrNull { it.id == scenarioId } ?: Scenarios.Normal
    private val actualSeed = seed ?: scenario.defaultSeed
    private val generator = PhysiologyGenerator(scenario.build(actualSeed), actualSeed, startMs)
    private var job: Job? = null
    /** Cue reactions are applied on the generator thread, never concurrently with next(). */
    private val pendingCues = java.util.concurrent.ConcurrentLinkedQueue<Float>()

    override val info = SourceInfo(DataOrigin.SIMULATED, "Watch-side simulator (${android.os.Build.MODEL})", "sim", scenario.id, actualSeed)
    override val capabilities = SensorCapabilities(heartRate = true, interBeatIntervals = true, accelerometer = true)

    @Volatile private var virtualNow = startMs
    override fun now() = virtualNow

    override fun start(onSamples: (List<SensorSample>) -> Unit, onLinkSimulation: (Boolean) -> Unit) {
        job = scope.launch(Dispatchers.Default) {
            var owed = 0.0
            var last = System.nanoTime()
            var linkUp = true
            while (isActive && !generator.finished) {
                val now = System.nanoTime()
                owed += (now - last) / 1e9 * speed
                last = now
                val steps = owed.toInt()
                owed -= steps
                val out = ArrayList<SensorSample>()
                while (true) generator.injectCue(generator.currentSecond, pendingCues.poll() ?: break)
                repeat(steps) {
                    if (generator.finished) return@repeat
                    val f = generator.next()
                    virtualNow = f.t
                    if (f.linkUp != linkUp) {
                        if (out.isNotEmpty()) { onSamples(out.toList()); out.clear() }
                        linkUp = f.linkUp
                        onLinkSimulation(linkUp)
                    }
                    out += f.samples
                }
                if (out.isNotEmpty()) onSamples(out)
                delay(100)
            }
            if (generator.finished) onFinished()
        }
    }

    override fun stop() {
        job?.cancel()
    }

    override fun onCueDelivered(intensity: Float) {
        pendingCues += intensity
    }
}
