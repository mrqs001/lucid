package lucid.app.link

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import lucid.core.model.Clock
import lucid.core.model.Wire
import lucid.core.model.WireMessage

/**
 * Wear OS Data Layer transport (MessageClient). This is the production path to a paired Galaxy
 * Watch. Requires Google Play services on both devices and the same applicationId + signing key
 * for the phone and watch apps. Messages are best effort; the protocol tolerates loss.
 *
 * Only a **nearby** watch (direct Bluetooth/Wi-Fi link) is used. When the devices are apart, the
 * Data Layer can otherwise relay messages through Google's cloud: that would send physiological
 * data off the devices, and it delivers commands minutes late. Out of range = disconnected; the
 * watch buffers and backfills when it is near again.
 */
class DataLayerWatchLink(
    context: Context,
    override val clock: Clock?,
) : WatchLink {
    override val name = "Wear Data Layer"
    private val messages: MessageClient = Wearable.getMessageClient(context)
    private val capabilities: CapabilityClient = Wearable.getCapabilityClient(context)
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state
    private val inbox = Channel<WireMessage>(Channel.UNLIMITED)
    @Volatile private var node: Node? = null
    @Volatile private var closed = false

    private val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path != Wire.DATA_LAYER_PATH) return@OnMessageReceivedListener
        // Accept only what came from the nearby watch we talk to.
        val expected = node
        if (expected != null && event.sourceNodeId != expected.id) return@OnMessageReceivedListener
        runCatching { Wire.decodeBytes(event.data) }
            .onSuccess { inbox.trySend(it) }
            .onFailure { Log.w(TAG, "bad message from ${event.sourceNodeId}: ${it.message}") }
    }

    override suspend fun run(handler: suspend (WireMessage) -> Unit): Unit = coroutineScope {
        messages.addListener(listener)
        val discovery = launch {
            while (!closed) {
                node = findWatch()
                _state.value = node?.let { LinkState.Connected(it.displayName) }
                    ?: LinkState.Waiting("no reachable watch with the Lucid app")
                delay(15_000)
            }
        }
        try {
            for (msg in inbox) handler(msg)
        } finally {
            discovery.cancel()
            messages.removeListener(listener)
        }
    }

    private suspend fun findWatch(): Node? = runCatching {
        capabilities.getCapability(Wire.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.firstOrNull { it.isNearby }
    }.getOrElse {
        Log.w(TAG, "capability lookup failed: ${it.message}")
        null
    }

    override suspend fun send(message: WireMessage): Boolean {
        val target = node?.takeIf { it.isNearby } ?: findWatch()?.also { node = it } ?: return false
        return runCatching {
            messages.sendMessage(target.id, Wire.DATA_LAYER_PATH, Wire.encodeBytes(message)).await()
        }.isSuccess
    }

    override fun close() {
        closed = true
        inbox.close()
        _state.value = LinkState.Idle
    }

    private companion object {
        const val TAG = "DataLayerLink"
    }
}
