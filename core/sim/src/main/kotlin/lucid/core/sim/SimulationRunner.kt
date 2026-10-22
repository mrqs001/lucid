package lucid.core.sim

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.yield
import lucid.core.model.WireMessage

/**
 * Paces a [SimulatedWatchDevice] against real time.
 *
 * Every message the device emits is handed to [deliver] and awaited before the simulation moves
 * on, so the phone-side pipeline sees exactly the order a real watch would produce and the run is
 * deterministic at any speed. [Speed.MAX] runs as fast as the consumer can keep up.
 */
class SimulationRunner(
    private val device: SimulatedWatchDevice,
    private val deliver: suspend (WireMessage) -> Unit,
    initialSpeed: Float = 60f,
) {
    enum class Phase { IDLE, RUNNING, PAUSED, FINISHED, STOPPED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val speed: Float = 60f,
        val elapsedSec: Int = 0,
        val durationSec: Int = 0,
    ) {
        val progress: Float get() = if (durationSec == 0) 0f else elapsedSec.toFloat() / durationSec
    }

    object Speed {
        const val MAX = Float.POSITIVE_INFINITY
        val Choices = listOf(1f, 10f, 60f, 240f, 600f, MAX)
    }

    private val _state = MutableStateFlow(State(speed = initialSpeed, durationSec = device.durationSec))
    val state: StateFlow<State> = _state.asStateFlow()

    fun setSpeed(speed: Float) = _state.update { it.copy(speed = speed.coerceAtLeast(0.1f)) }
    fun pause() = _state.update { if (it.phase == Phase.RUNNING) it.copy(phase = Phase.PAUSED) else it }
    fun resume() = _state.update { if (it.phase == Phase.PAUSED) it.copy(phase = Phase.RUNNING) else it }
    fun stop() = _state.update { if (it.phase == Phase.RUNNING || it.phase == Phase.PAUSED) it.copy(phase = Phase.STOPPED) else it }

    /** Runs until the night ends or [stop] is called. Call from a coroutine you own. */
    suspend fun run() {
        _state.update { it.copy(phase = Phase.RUNNING) }
        deliver(device.hello())
        var owed = 0.0
        var lastTick = System.nanoTime()
        while (!device.finished) {
            val s = _state.value
            when (s.phase) {
                Phase.STOPPED -> return
                Phase.PAUSED -> {
                    state.first { it.phase != Phase.PAUSED }
                    lastTick = System.nanoTime()
                    continue
                }
                else -> Unit
            }
            val steps = if (s.speed.isInfinite()) {
                120
            } else {
                val now = System.nanoTime()
                owed += (now - lastTick) / 1e9 * s.speed
                lastTick = now
                owed.toInt().also { owed -= it }
            }
            repeat(steps) {
                if (device.finished) return@repeat
                for (m in device.step()) deliver(m)
            }
            _state.update { it.copy(elapsedSec = device.elapsedSec) }
            if (s.speed.isInfinite()) yield() else delay(TICK_MS)
        }
        _state.update { it.copy(phase = Phase.FINISHED, elapsedSec = device.elapsedSec) }
    }

    private companion object {
        const val TICK_MS = 50L
    }
}
