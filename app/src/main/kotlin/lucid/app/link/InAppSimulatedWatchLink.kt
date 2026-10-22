package lucid.app.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import lucid.core.model.Clock
import lucid.core.model.WireMessage
import lucid.core.sim.SimulatedWatchDevice
import lucid.core.sim.SimulationRunner

/**
 * A simulated watch living inside the phone process. Runs the same pipeline as a real watch would
 * feed, at any replay speed. Its simulated link outages show up as a disconnected state.
 */
class InAppSimulatedWatchLink(
    val device: SimulatedWatchDevice,
    initialSpeed: Float,
) : WatchLink {
    override val name = "In-app simulator"
    private val _state = MutableStateFlow<LinkState>(LinkState.Connected("simulated watch"))
    override val state: StateFlow<LinkState> = _state
    override val clock: Clock = device.clock

    private var handler: (suspend (WireMessage) -> Unit)? = null
    val runner = SimulationRunner(device, deliver = { msg ->
        _state.value = if (device.linkUp) LinkState.Connected("simulated watch") else LinkState.Disconnected("simulated link loss")
        handler?.invoke(msg)
    }, initialSpeed = initialSpeed)

    override suspend fun run(handler: suspend (WireMessage) -> Unit) {
        this.handler = handler
        runner.run()
    }

    override suspend fun send(message: WireMessage): Boolean {
        if (!device.linkUp) return false
        device.receive(message)
        return true
    }

    override fun close() = runner.stop()
}
