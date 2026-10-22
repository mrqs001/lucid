package lucid.wear.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import lucid.core.model.Wire
import lucid.core.model.WireMessage
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.Socket

/** The watch's view of the phone. Same messages over every transport. */
interface PhoneLink {
    val name: String
    val connected: StateFlow<Boolean>
    val status: StateFlow<String>

    /** [onConnected] runs each time a connection (re)opens, e.g. to say hello. */
    fun start(scope: CoroutineScope, onMessage: suspend (WireMessage) -> Unit, onConnected: suspend () -> Unit)

    suspend fun send(message: WireMessage): Boolean

    /** Drop the current connection (e.g. it went half-open) and let the link re-establish it. */
    fun reconnect() = Unit

    fun stop()
}

/**
 * Emulator transport: TCP client to the phone emulator through the host (10.0.2.2), which
 * `adb forward tcp:47100 tcp:47100` connects to the phone. Reconnects forever.
 *
 * When a Wi-Fi/Ethernet network is available it is requested explicitly (Wear OS otherwise prefers
 * the Bluetooth companion proxy); the emulator only exposes a cellular-type default network, which
 * is used as is.
 */
class DevBridgePhoneLink(
    context: Context,
    private val host: String = "10.0.2.2",
    private val port: Int = Wire.DEV_BRIDGE_PORT,
) : PhoneLink {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var network: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { network = n }
        override fun onLost(n: Network) { if (network == n) network = null }
    }

    override val name = "Dev bridge $host:$port"
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected
    private val _status = MutableStateFlow("not started")
    override val status: StateFlow<String> = _status
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var socket: Socket? = null
    private val lock = Mutex()
    private var job: Job? = null

    override fun start(scope: CoroutineScope, onMessage: suspend (WireMessage) -> Unit, onConnected: suspend () -> Unit) {
        if (job != null) return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        runCatching { connectivity.requestNetwork(request, networkCallback) }
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val net = network
                    _status.value = if (net == null) "waiting for a Wi-Fi/Ethernet network" else "connecting to $host:$port"
                    // Not inside apply{}: there `port` would resolve to Socket.port (0 before connecting).
                    val target = InetSocketAddress(host, port)
                    val s = net?.socketFactory?.createSocket() ?: Socket()
                    s.connect(target, 3_000)
                    s.tcpNoDelay = true
                    socket = s
                    writer = s.getOutputStream().bufferedWriter()
                    // A forwarded port accepts the TCP connection even when the phone app isn't listening;
                    // the phone only counts as connected once it talks back, but sending works either way.
                    _connected.value = true
                    _status.value = "connected to phone via $host:$port"
                    onConnected()
                    val reader = s.getInputStream().bufferedReader()
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        runCatching { Wire.decode(line) }.onSuccess { onMessage(it) }.onFailure { Log.w(TAG, "bad frame: ${it.message}") }
                    }
                } catch (e: Exception) {
                    Log.i(TAG, "dev bridge connect/read failed: $e")
                    _status.value = "phone unreachable (${e.javaClass.simpleName}); retrying"
                } finally {
                    _connected.value = false
                    writer = null
                    runCatching { socket?.close() }
                }
                delay(3_000)
            }
        }
    }

    override suspend fun send(message: WireMessage): Boolean = withContext(Dispatchers.IO) {
        val w = writer ?: return@withContext false
        lock.withLock {
            runCatching { w.write(Wire.encode(message)); w.write("\n"); w.flush() }.isSuccess
        }
    }

    override fun reconnect() {
        runCatching { socket?.close() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        _connected.value = false
        _status.value = "stopped"
    }

    private companion object {
        const val TAG = "DevBridge"
    }
}

/**
 * Production transport: Wear Data Layer MessageClient to the phone advertising `lucid_phone`.
 * Nearby (directly connected) phone only: no relaying of physiological data through Google's
 * cloud when Bluetooth is down; the uplink buffers instead.
 */
class DataLayerPhoneLink(context: Context) : PhoneLink {
    override val name = "Wear Data Layer"
    private val messages = Wearable.getMessageClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected
    private val _status = MutableStateFlow("not started")
    override val status: StateFlow<String> = _status
    @Volatile private var phoneNode: String? = null
    private var job: Job? = null
    private var listener: MessageClient.OnMessageReceivedListener? = null

    override fun start(scope: CoroutineScope, onMessage: suspend (WireMessage) -> Unit, onConnected: suspend () -> Unit) {
        if (job != null) return
        val l = MessageClient.OnMessageReceivedListener { e ->
            if (e.path == Wire.DATA_LAYER_PATH && (phoneNode == null || e.sourceNodeId == phoneNode)) {
                runCatching { Wire.decodeBytes(e.data) }.onSuccess { m -> scope.launch { onMessage(m) } }
            }
        }
        listener = l
        messages.addListener(l)
        job = scope.launch {
            while (isActive) {
                val node = runCatching {
                    capabilities.getCapability(Wire.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes.firstOrNull { it.isNearby }
                }.getOrNull()
                val was = _connected.value
                phoneNode = node?.id
                _connected.value = node != null
                _status.value = node?.let { "phone: ${it.displayName}" } ?: "no reachable phone with Lucid"
                if (node != null && !was) onConnected()
                delay(15_000)
            }
        }
    }

    override suspend fun send(message: WireMessage): Boolean {
        val node = phoneNode ?: return false
        return runCatching { messages.sendMessage(node, Wire.DATA_LAYER_PATH, Wire.encodeBytes(message)).await() }.isSuccess
    }

    override fun stop() {
        job?.cancel()
        job = null
        listener?.let { messages.removeListener(it) }
        _connected.value = false
    }
}
