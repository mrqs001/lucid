package lucid.app.link

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import lucid.core.model.Clock
import lucid.core.model.Wire
import lucid.core.model.WireMessage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Emulator-only transport: newline-delimited JSON over TCP. The phone listens on
 * [Wire.DEV_BRIDGE_PORT]; `adb forward` exposes it on the host and the watch emulator dials the
 * host at 10.0.2.2. Same messages as the Data Layer, no Google account or pairing needed.
 * Release builds lack the INTERNET permission, so this cannot work there by construction.
 */
class DevBridgeWatchLink(
    override val clock: Clock?,
    private val port: Int = Wire.DEV_BRIDGE_PORT,
) : WatchLink {
    override val name = "Dev bridge :$port"
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state

    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    private val writeLock = Mutex()
    @Volatile private var closed = false

    override suspend fun run(handler: suspend (WireMessage) -> Unit): Unit = withContext(Dispatchers.IO) {
        val ss = try {
            ServerSocket().also { it.reuseAddress = true; it.bind(InetSocketAddress(port)) }
        } catch (e: Exception) {
            _state.value = LinkState.Disconnected("cannot listen on :$port (${e.message})")
            return@withContext
        }
        server = ss
        _state.value = LinkState.Waiting("listening on :$port for the watch emulator")
        // Messages from whichever connection is current are handled one at a time, in order.
        val inbox = Channel<WireMessage>(Channel.UNLIMITED)
        val consumer = launch { for (m in inbox) handler(m) }
        try {
            while (isActive && !closed) {
                val socket = try { ss.accept() } catch (e: SocketException) { break }
                // A new connection replaces the old one: after a network drop the old socket can stay
                // half-open on this side long after the watch has reconnected.
                runCatching { client?.close() }
                client = socket
                writer = socket.getOutputStream().bufferedWriter()
                _state.value = LinkState.Connected("watch emulator ${socket.inetAddress.hostAddress}")
                launch { readLoop(socket, inbox) }
            }
        } finally {
            inbox.close()
            consumer.join()
            runCatching { ss.close() }
        }
    }

    private suspend fun readLoop(socket: Socket, inbox: Channel<WireMessage>) {
        try {
            val reader: BufferedReader = socket.getInputStream().bufferedReader()
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                runCatching { Wire.decode(line) }
                    .onSuccess { inbox.send(it) }
                    .onFailure { Log.w(TAG, "bad frame: ${it.message}") }
            }
        } catch (e: Exception) {
            Log.i(TAG, "watch connection ended: ${e.message}")
        } finally {
            runCatching { socket.close() }
            if (client === socket) {
                client = null
                writer = null
                if (!closed) _state.value = LinkState.Disconnected("watch emulator went away")
            }
        }
    }

    override suspend fun send(message: WireMessage): Boolean = withContext(Dispatchers.IO) {
        val w = writer ?: return@withContext false
        writeLock.withLock {
            runCatching {
                w.write(Wire.encode(message))
                w.write("\n")
                w.flush()
            }.isSuccess
        }
    }

    override fun close() {
        closed = true
        runCatching { client?.close() }
        runCatching { server?.close() }
        _state.value = LinkState.Idle
    }

    private companion object {
        const val TAG = "DevBridge"
    }
}
