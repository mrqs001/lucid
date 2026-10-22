package lucid.app.link

import kotlinx.coroutines.flow.StateFlow
import lucid.core.model.Clock
import lucid.core.model.WireMessage

sealed interface LinkState {
    val label: String

    data object Idle : LinkState { override val label = "Idle" }
    data class Waiting(val detail: String) : LinkState { override val label = "Waiting: $detail" }
    data class Connected(val peer: String) : LinkState { override val label = "Connected to $peer" }
    data class Disconnected(val reason: String) : LinkState { override val label = "Disconnected: $reason" }
}

/**
 * The phone's view of a watch. Every transport (in-app simulator, emulator TCP bridge, Wear Data
 * Layer) moves the same [WireMessage]s, so the session pipeline does not know which one it uses.
 */
interface WatchLink {
    val name: String
    val state: StateFlow<LinkState>
    val isConnected: Boolean get() = state.value is LinkState.Connected

    /** "Now" for staleness checks, or null to trust the timestamps the watch reports. */
    val clock: Clock?

    /** Deliver incoming messages to [handler] one at a time until [close]. */
    suspend fun run(handler: suspend (WireMessage) -> Unit)

    /** Returns false if the message could not be handed to the transport. */
    suspend fun send(message: WireMessage): Boolean

    fun close()
}
