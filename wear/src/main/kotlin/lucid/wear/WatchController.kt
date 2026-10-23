package lucid.wear

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import lucid.core.model.BatchAck
import lucid.core.model.CommandLimits
import lucid.core.model.CueAck
import lucid.core.model.CueCommand
import lucid.core.model.CuePurpose
import lucid.core.model.Hello
import lucid.core.model.HeartRateSample
import lucid.core.model.SensorMode
import lucid.core.model.StartMonitoring
import lucid.core.model.StopMonitoring
import lucid.core.model.WatchStatus
import lucid.core.model.WireMessage
import lucid.wear.link.DataLayerPhoneLink
import lucid.wear.link.DevBridgePhoneLink
import lucid.wear.link.PhoneLink
import lucid.wear.sensors.PlatformSensorSource
import lucid.wear.sensors.SamsungHealth
import lucid.wear.sensors.SensorSource
import lucid.wear.sensors.SimulatedSensorSource

data class CueLogEntry(val t: Long, val purpose: CuePurpose, val outcome: String)

data class WatchUi(
    val transport: String = "",
    val linkStatus: String = "",
    val connected: Boolean = false,
    val monitoring: Boolean = false,
    val sessionId: String? = null,
    val sourceLabel: String = "",
    val simulated: Boolean = false,
    val heartRate: Float? = null,
    val battery: Int? = null,
    val buffered: Int = 0,
    val simulatedLinkDown: Boolean = false,
    val cues: List<CueLogEntry> = emptyList(),
)

/**
 * Everything the watch app does, independent of UI: holds the phone link, runs the sensor source
 * while monitoring, ships samples through the [Uplink], and executes cue commands.
 */
class WatchController(private val app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = app.getSharedPreferences("watch", Context.MODE_PRIVATE)
    val cues = CueExecutor(app)
    private val _ui = MutableStateFlow(WatchUi())
    val ui: StateFlow<WatchUi> = _ui

    private var link: PhoneLink = createLink()
    private var source: SensorSource? = null
    private var pump: Job? = null
    @Volatile private var simulatedLinkDown = false
    /** One uplink per session: unacknowledged data of an old session must never reach a new one. */
    @Volatile private var uplink = newUplink(null)

    private fun newUplink(sessionId: String?) =
        Uplink(sessionId, send = { link.send(it) }, clock = { source?.now() ?: System.currentTimeMillis() })

    /** Dev bridge by default on emulators, Data Layer on real watches. */
    var useDevBridge: Boolean
        get() = prefs.getBoolean("dev_bridge", cues.isEmulator)
        set(v) {
            prefs.edit().putBoolean("dev_bridge", v).apply()
            link.stop()
            link = createLink()
            connect()
        }

    private fun createLink(): PhoneLink = if (prefs.getBoolean("dev_bridge", cues.isEmulator)) DevBridgePhoneLink(app) else DataLayerPhoneLink(app)

    private var watchers: List<Job> = emptyList()

    fun connect() {
        val l = link
        l.start(scope, onMessage = ::handle, onConnected = { l.send(hello()) })
        watchers.forEach { it.cancel() }
        watchers = listOf(
            scope.launch { l.status.collect { s -> _ui.update { it.copy(linkStatus = s, transport = l.name) } } },
            scope.launch { l.connected.collect { c -> _ui.update { it.copy(connected = c) } } },
        )
    }

    private fun hello(): WireMessage {
        val s = source
        val info = s?.info ?: lucid.core.model.SourceInfo(lucid.core.model.DataOrigin.REAL_DEVICE, android.os.Build.MODEL, "idle")
        val caps = (s?.capabilities ?: lucid.wear.sensors.SensorCapabilities(heartRate = true, interBeatIntervals = SamsungHealth.COMPILED_IN, accelerometer = true))
            .toWatch(cues.hasVibrator, cues.hasAmplitudeControl, cues.isEmulator)
        return Hello(source = info, capabilities = caps)
    }

    suspend fun handle(msg: WireMessage) {
        when (msg) {
            is StartMonitoring -> if (isStale(msg.sentWallMs, CommandLimits.START_MAX_AGE_MS)) {
                Log.w(TAG, "ignoring stale StartMonitoring for ${msg.sessionId}")
            } else if (_ui.value.sessionId != msg.sessionId && pendingStart?.sessionId != msg.sessionId) {
                pendingStart = msg
                ContextCompat.startForegroundService(app, Intent(app, MonitoringService::class.java))
            } else {
                sendStatus()
            }
            is BatchAck -> {
                uplink.onAck(msg.seq)
                lastAckWallMs = System.currentTimeMillis()
            }
            is StopMonitoring -> if (_ui.value.sessionId == msg.sessionId || msg.sessionId.isEmpty()) {
                app.stopService(Intent(app, MonitoringService::class.java))
            }
            is CueCommand -> {
                // On a real watch the Data Layer delivers each message twice while the app runs (live
                // listener + WearableListenerService). A cue must play exactly once.
                synchronized(handledCues) {
                    if (!handledCues.add(msg.cueId)) return
                    if (handledCues.size > 200) handledCues.remove(handledCues.first())
                }
                val now = source?.now() ?: System.currentTimeMillis()
                val otherSession = msg.sessionId != null && msg.sessionId != _ui.value.sessionId
                val ack = if (isStale(msg.sentWallMs, CommandLimits.CUE_MAX_AGE_MS)) {
                    CueAck(msg.cueId, now, lucid.core.model.CueOutcome.EXPIRED, "delivered ${(System.currentTimeMillis() - msg.sentWallMs!!) / 1000} s after it was sent")
                } else if (otherSession || (msg.purpose == CuePurpose.SLEEP_CUE && !_ui.value.monitoring)) {
                    CueAck(msg.cueId, now, lucid.core.model.CueOutcome.NOT_MONITORING, "watch is not monitoring this session")
                } else {
                    cues.execute(msg, now)
                }
                if (msg.purpose == CuePurpose.SLEEP_CUE && ack.outcome.name in setOf("VIBRATED", "EMULATED")) source?.onCueDelivered(msg.intensity)
                _ui.update { it.copy(cues = (listOf(CueLogEntry(now, msg.purpose, ack.outcome.label)) + it.cues).take(6)) }
                link.send(ack)
            }
            else -> Unit
        }
    }

    @Volatile var pendingStart: StartMonitoring? = null
    @Volatile private var lastAckWallMs = 0L

    private fun isStale(sentWallMs: Long?, maxAgeMs: Long) =
        sentWallMs != null && System.currentTimeMillis() - sentWallMs > maxAgeMs
    private val handledCues = LinkedHashSet<String>()

    /** Called by [MonitoringService] once it is in the foreground. */
    fun beginMonitoring(manual: StartMonitoring? = null) {
        val cmd = manual ?: pendingStart ?: return
        pendingStart = null
        endMonitoring()
        val src: SensorSource = when (cmd.sensorMode) {
            SensorMode.SIMULATED -> SimulatedSensorSource(
                cmd.scenarioId ?: "normal", cmd.seed, cmd.startAtMs ?: System.currentTimeMillis(),
                // Above ~60x, transport latency in virtual time makes cue commands expire.
                speed = cmd.speed.coerceIn(1f, 60f), scope = scope,
                onFinished = {
                    // Give the uplink a moment to deliver the last batch, then stop like a real stop.
                    scope.launch {
                        delay(3_000)
                        app.stopService(Intent(app, MonitoringService::class.java))
                    }
                },
            )
            SensorMode.REAL -> SamsungHealth.create(app, scope) { reason ->
                // Samsung service unusable: keep the night going on the standard Android sensors.
                Log.w(TAG, "$reason: falling back to Android sensors")
                scope.launch { switchSource(PlatformSensorSource(app)) }
            } ?: PlatformSensorSource(app)
        }
        source = src
        uplink = newUplink(cmd.sessionId)
        lastAckWallMs = System.currentTimeMillis()
        simulatedLinkDown = false
        _ui.update {
            it.copy(monitoring = true, sessionId = cmd.sessionId, sourceLabel = "${src.info.provider}: ${src.info.device}", simulated = src.info.isSimulated)
        }
        val sink: (List<lucid.core.model.SensorSample>) -> Unit = { samples ->
            uplink.add(samples)
            samples.lastOrNull { it is HeartRateSample }?.let { h -> _ui.update { it.copy(heartRate = (h as HeartRateSample).bpm) } }
        }
        sampleSink = sink
        src.start(
            onSamples = sink,
            onLinkSimulation = { up ->
                simulatedLinkDown = !up
                _ui.update { it.copy(simulatedLinkDown = !up) }
            },
        )
        // Real sensors: send every 30 s and report status every 5 min. Measured overnight on a Galaxy
        // Watch7, sending every 5 s with status every 15 s kept Bluetooth and the CPU busy all night.
        val flushEvery = if (src.info.isSimulated) 100L else 30_000L
        val statusEvery = if (src.info.isSimulated) 15_000L else 300_000L
        pump = scope.launch {
            link.send(hello())
            var lastStatus = 0L
            while (isActive) {
                delay(flushEvery)
                val alive = uplink.flush(link.connected.value && !simulatedLinkDown)
                if (!alive) {
                    Log.w(TAG, "no acknowledgement from the phone: reconnecting")
                    link.reconnect()
                }
                // Orphaned session: the link works but the phone has not acknowledged anything for
                // minutes, so it is no longer running this night (its stop message was lost).
                // During a real outage sends fail instead, and the watch keeps buffering.
                if (link.connected.value && !simulatedLinkDown &&
                    System.currentTimeMillis() - lastAckWallMs > ORPHAN_AFTER_MS
                ) {
                    Log.w(TAG, "phone stopped acknowledging session ${cmd.sessionId}: ending it")
                    app.stopService(Intent(app, MonitoringService::class.java))
                    break
                }
                _ui.update { it.copy(buffered = uplink.buffered) }
                if (System.currentTimeMillis() - lastStatus > statusEvery && !simulatedLinkDown) {
                    sendStatus()
                    lastStatus = System.currentTimeMillis()
                }
            }
        }
        Log.i(TAG, "monitoring ${cmd.sessionId} with ${src.info.provider}")
    }

    @Volatile private var sampleSink: ((List<lucid.core.model.SensorSample>) -> Unit)? = null

    /** Replace the running source mid-session (e.g. Samsung SDK failed); same uplink and session. */
    private fun switchSource(next: SensorSource) {
        val sink = sampleSink ?: return
        val old = source ?: return
        if (old.info.provider == next.info.provider) return
        old.stop()
        source = next
        _ui.update { it.copy(sourceLabel = "${next.info.provider}: ${next.info.device}") }
        next.start(onSamples = sink)
        scope.launch { link.send(hello()) }
    }

    fun endMonitoring() {
        pump?.cancel()
        pump = null
        source?.stop()
        val last = source
        source = null
        if (last != null) scope.launch { uplink.flush(link.connected.value) }
        _ui.update { it.copy(monitoring = false, sessionId = null, simulatedLinkDown = false) }
        scope.launch { sendStatus() }
    }

    private suspend fun sendStatus() {
        val bm = app.getSystemService(BatteryManager::class.java)
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        _ui.update { it.copy(battery = battery) }
        link.send(
            WatchStatus(
                t = source?.now() ?: System.currentTimeMillis(), monitoring = _ui.value.monitoring, batteryPct = battery,
                charging = bm?.isCharging, bufferedSamples = uplink.buffered, sessionId = _ui.value.sessionId,
            ),
        )
    }

    /** Local test from the watch UI. */
    fun testVibration() {
        val now = source?.now() ?: System.currentTimeMillis()
        val cmd = CueCommand("local-$now", null, lucid.core.model.VibrationPattern.Pulse200, 0.6f, 1, now, now + 10_000, CuePurpose.TEST)
        val ack = cues.execute(cmd, now)
        _ui.update { it.copy(cues = (listOf(CueLogEntry(now, CuePurpose.TEST, ack.outcome.label)) + it.cues).take(6)) }
    }

    companion object {
        private const val TAG = "WatchController"
        private const val ORPHAN_AFTER_MS = 5 * 60_000L
    }
}
