package lucid.app.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import lucid.app.data.AppSettings
import lucid.app.data.BatteryEntity
import lucid.app.data.CueEventEntity
import lucid.app.data.EpochEntity
import lucid.app.data.LucidDatabase
import lucid.app.data.SampleEntity
import lucid.app.data.SessionEntity
import lucid.app.data.SessionEventEntity
import lucid.app.data.SessionStatus
import lucid.app.data.SettingsRepository
import lucid.app.data.SimTruthEntity
import lucid.app.data.SourceKind
import lucid.app.data.armForNight
import lucid.app.link.DataLayerWatchLink
import lucid.app.link.DevBridgeWatchLink
import lucid.app.link.InAppSimulatedWatchLink
import lucid.app.link.LinkState
import lucid.app.link.WatchMessenger
import lucid.app.link.WatchLink
import lucid.core.engine.CueAction
import lucid.core.engine.EngineConfig
import lucid.core.engine.EngineEvent
import lucid.core.engine.FeatureContribution
import lucid.core.engine.GateFailure
import lucid.core.engine.SessionProcessor
import lucid.core.engine.SessionSummary
import lucid.core.model.BatchAck
import lucid.core.model.Clock
import lucid.core.model.CueCommand
import lucid.core.model.CuePurpose
import lucid.core.model.DataOrigin
import lucid.core.model.HeartRateSample
import lucid.core.model.IbiSample
import lucid.core.model.MotionSample
import lucid.core.model.SampleBatch
import lucid.core.model.SensorMode
import lucid.core.model.SensorStatusSample
import lucid.core.model.StartMonitoring
import lucid.core.model.StopMonitoring
import lucid.core.model.VibrationPattern
import lucid.core.model.WatchStatus
import lucid.core.model.WireMessage
import lucid.core.sim.Scenarios
import lucid.core.sim.SimulatedWatchDevice
import lucid.core.sim.SimulationRunner
import java.util.UUID

/**
 * Owns the running night: picks the transport, feeds every message through the
 * [SessionProcessor], sends cue commands back, persists everything and publishes [live].
 *
 * Simulated and real nights take exactly the same path; only the [WatchLink] differs.
 */
class SessionManager(
    private val context: Context,
    private val db: LucidDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val watch: WatchMessenger,
) {
    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()

    private val lock = Mutex()
    @Volatile private var running: Running? = null

    private class Running(
        val sessionId: String,
        val link: WatchLink,
        val processor: SessionProcessor,
        val sim: InAppSimulatedWatchLink?,
        val persist: Persister,
    ) {
        lateinit var job: Job
        /** Start command for the watch; re-sent whenever the watch app restarts mid-night. */
        @Volatile var startMessage: StartMonitoring? = null
        @Volatile var watchSimulates = false
        @Volatile var lastHeardMs = System.currentTimeMillis()
        val lastBattery = HashMap<String, Pair<Long, Int>>()
        @Volatile var lastStatusKey: String? = null

        /**
         * Usable for cue commands: transport up and the watch heard from recently. A half-open TCP
         * connection or a silent Data Layer node looks "connected" to the transport.
         */
        val reachable: Boolean
            get() = link.isConnected && (sim != null || System.currentTimeMillis() - lastHeardMs < SILENCE_LIMIT_MS)
        @Volatile var watchConfirmed = false
        @Volatile var stopping = false
    }

    val isRecording: Boolean get() = running != null

    suspend fun start(): String = lock.withLock {
        check(running == null) { "a session is already running" }
        val s = settings.current()
        val id = UUID.randomUUID().toString()
        val startMs = System.currentTimeMillis() / 1000 * 1000

        val experiment = db.experiments().active()
        val arm = experiment?.let { e -> e.armForNight(db.sessions().all().count { it.experimentId == e.id }) }
        val engine = s.engine.copy(cueing = arm?.cueing ?: s.engine.cueing)

        val sim = s.simulation
        val scenario = Scenarios.all.firstOrNull { it.id == sim.scenarioId } ?: Scenarios.Normal
        val seed = sim.seed ?: scenario.defaultSeed
        val simulatedOrigin = s.source == SourceKind.IN_APP_SIMULATOR || sim.watchSimulates
        val link: WatchLink = when (s.source) {
            SourceKind.IN_APP_SIMULATOR -> InAppSimulatedWatchLink(SimulatedWatchDevice(scenario, seed, startMs), sim.speed)
            // A watch-side simulation runs on its own virtual clock; real sensors share wall time.
            SourceKind.WATCH_DATA_LAYER -> DataLayerWatchLink(context, clock = if (sim.watchSimulates) null else Clock.System)
            SourceKind.WATCH_DEV_BRIDGE -> DevBridgeWatchLink(clock = if (sim.watchSimulates) null else Clock.System)
        }
        val inApp = link as? InAppSimulatedWatchLink

        var runningRef: Running? = null
        val processor = SessionProcessor(
            sessionId = id,
            sessionStartMs = startMs,
            config = engine,
            clock = link.clock,
            linkConnected = { runningRef?.reachable ?: link.isConnected },
        )
        db.sessions().insert(
            SessionEntity(
                id = id,
                startedAt = startMs,
                origin = if (simulatedOrigin) DataOrigin.SIMULATED else DataOrigin.REAL_DEVICE,
                device = if (inApp != null) "In-app simulated watch" else "Waiting for watch",
                provider = if (inApp != null) "sim" else "unknown",
                scenarioId = if (simulatedOrigin) scenario.id else null,
                seed = if (simulatedOrigin) seed else null,
                linkKind = s.source.name,
                configJson = json.encodeToString(EngineConfig.serializer(), engine),
                estimatorId = processor.estimator.id,
                policyId = processor.policy.id,
                experimentId = experiment?.id,
                armId = arm?.id,
                armLabel = arm?.label,
            ),
        )

        val r = Running(id, link, processor, inApp, Persister(db, id))
        runningRef = r
        running = r
        _live.value = LiveState(
            sessionId = id,
            recording = true,
            origin = if (simulatedOrigin) DataOrigin.SIMULATED else null,
            sourceLabel = s.source.label,
            linkName = link.name,
            link = link.state.value,
            scenarioTitle = if (simulatedOrigin) "${scenario.title} (seed $seed)" else null,
            armLabel = if (experiment?.blinded == true) "hidden (blinded)" else arm?.label,
            remThreshold = engine.cueing.remThreshold,
            sessionStartMs = startMs,
            nowMs = startMs,
            sim = inApp?.runner?.state?.value,
        )
        RecordingService.start(context)
        r.persist.event(startMs, "start", "source=${s.source}, watchSimulates=${s.simulation.watchSimulates}, cueMode=${engine.cueing.mode}, arm=${arm?.id}")

        r.job = scope.launch {
            val watchers = launch {
                launch {
                    // Every link change is stored, so a night can be audited afterwards (drops, reconnects).
                    var last: String? = null
                    link.state.collect { st ->
                        _live.update { it.copy(link = st) }
                        if (st.label != last) {
                            last = st.label
                            r.persist.event(System.currentTimeMillis(), "link", st.label)
                        }
                    }
                }
                inApp?.let { l -> launch { l.runner.state.collect { st -> _live.update { it.copy(sim = st) } } } }
                if (inApp == null) launch { keepWatchStarted(r, s, scenario.id, seed, startMs) }
                if (inApp == null) launch {
                    // Watchdog: a crashed or killed watch app sends nothing at all. After 2 minutes of
                    // silence, re-send the start command; it relaunches the watch app in the background.
                    while (true) {
                        delay(60_000)
                        if (r.watchConfirmed && !r.stopping && System.currentTimeMillis() - r.lastHeardMs > SILENCE_LIMIT_MS) {
                            resumeWatch(r, "no data from the watch for ${(System.currentTimeMillis() - r.lastHeardMs) / 1000} s")
                        }
                    }
                }
                launch { while (true) { delay(2_000); r.persist.flush() } }
                launch {
                    while (true) {
                        phoneBattery()?.let { (pct, charging) ->
                            recordBattery(r, "phone", pct, charging)
                            _live.update { it.copy(phoneBattery = pct) }
                            val limit = settings.current().stopAtPhoneBatteryPct
                            if (limit > 0 && !charging && pct <= limit && !r.stopping) {
                                r.persist.event(System.currentTimeMillis(), "low_battery_stop", "phone battery $pct% <= $limit% and not charging")
                                _live.update { it.copy(events = it.events.withNew(listOf(LiveEvent(System.currentTimeMillis(), LiveEvent.Kind.WARNING, "Phone battery $pct%: night ended to save battery")), MAX_EVENTS)) }
                                stop()
                            }
                        }
                        delay(60_000)
                    }
                }
            }
            try {
                link.run { msg -> handle(r, msg) }
            } catch (e: Exception) {
                Log.e(TAG, "session $id failed", e)
                _live.update { it.copy(error = e.message ?: e.toString()) }
                runCatching { r.persist.event(System.currentTimeMillis(), "error", e.stackTraceToString().take(2000)) }
            } finally {
                watchers.cancel()
                withContext(NonCancellable) { finish(r) }
            }
        }
        id
    }

    /** Ask the watch to start (and keep asking until it confirms) whenever the link is up. */
    private suspend fun keepWatchStarted(r: Running, s: AppSettings, scenarioId: String, seed: Long, startMs: Long) {
        r.watchSimulates = s.simulation.watchSimulates
        val start = StartMonitoring(
            sessionId = r.sessionId,
            sensorMode = if (s.simulation.watchSimulates) SensorMode.SIMULATED else SensorMode.REAL,
            scenarioId = scenarioId,
            seed = seed,
            speed = if (s.simulation.watchSimulates) s.simulation.speed else 1f,
            startAtMs = startMs,
        )
        r.startMessage = start
        while (!r.watchConfirmed && !r.stopping) {
            if (r.link.isConnected) r.link.send(start.copy(sentWallMs = System.currentTimeMillis()))
            delay(5_000)
        }
    }

    private suspend fun resumeWatch(r: Running, reason: String) {
        val start = r.startMessage ?: return
        r.persist.event(System.currentTimeMillis(), "resume", reason)
        r.link.send(start.copy(sentWallMs = System.currentTimeMillis()))
    }

    fun stop() {
        val r = running ?: return
        r.stopping = true
        scope.launch { r.persist.event(System.currentTimeMillis(), "stop", "night stopped on the phone") }
        scope.launch {
            if (r.sim == null) r.link.send(StopMonitoring(r.sessionId))
            r.link.close()
        }
    }

    fun pause() = running?.sim?.runner?.pause()
    fun resume() = running?.sim?.runner?.resume()
    fun setSpeed(speed: Float) = running?.sim?.runner?.setSpeed(speed)

    /** Send a test vibration through the active link. Returns a human-readable result. */
    suspend fun testCue(intensity: Float, patternId: String): String {
        val r = running ?: return if (watch.cue(patternId, intensity, CuePurpose.TEST)) {
            "Test cue sent to the watch"
        } else {
            "No watch in reach: is it connected to this phone and is the Lucid watch app installed?"
        }
        val now = r.processor.now().takeIf { it != Long.MIN_VALUE } ?: System.currentTimeMillis()
        val cmd = CueCommand(
            cueId = "test-${UUID.randomUUID()}", sessionId = r.sessionId, pattern = VibrationPattern.byId(patternId),
            intensity = intensity, repeats = 1, issuedAt = now, expiresAt = now + 20_000, purpose = CuePurpose.TEST,
            sentWallMs = System.currentTimeMillis(),
        )
        return if (r.link.send(cmd)) "Test cue sent through ${r.link.name}" else "Could not reach the watch (${r.link.state.value.label})"
    }

    /** Training cue through the active watch link. Returns false when no session/link is up. */
    suspend fun trainingCue(patternId: String, intensity: Float): Boolean {
        val r = running ?: return watch.cue(patternId, intensity, CuePurpose.TRAINING)
        val now = r.processor.now().takeIf { it != Long.MIN_VALUE } ?: System.currentTimeMillis()
        return r.link.send(
            CueCommand(
                cueId = "train-${UUID.randomUUID()}", sessionId = r.sessionId, pattern = VibrationPattern.byId(patternId),
                intensity = intensity, repeats = 1, issuedAt = now, expiresAt = now + 20_000, purpose = CuePurpose.TRAINING,
                sentWallMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun handle(r: Running, msg: WireMessage) {
        r.lastHeardMs = System.currentTimeMillis()
        if (msg is SampleBatch && msg.backfill) {
            r.persist.event(System.currentTimeMillis(), "backfill", "${msg.samples.size} samples buffered on the watch, from ${msg.samples.firstOrNull()?.t ?: 0} to ${msg.samples.lastOrNull()?.t ?: 0}")
        }
        if (msg is SampleBatch && msg.sessionId != null && msg.sessionId != r.sessionId) {
            r.persist.event(System.currentTimeMillis(), "foreign_batch", "dropped ${msg.samples.size} samples from session ${msg.sessionId}")
        }
        // Batches from another session (e.g. the previous night's last flush) are never stored.
        if (msg is SampleBatch && (msg.sessionId == null || msg.sessionId == r.sessionId)) r.persist.addSamples(msg)
        val events = r.processor.onMessage(msg)
        if (msg is SampleBatch && r.sim == null) r.link.send(BatchAck(msg.seq))
        val liveEvents = ArrayList<LiveEvent>()
        val now = r.processor.now().takeIf { it != Long.MIN_VALUE }

        for (e in events) when (e) {
            is EngineEvent.SourceIdentified -> {
                val src = e.hello.source
                // A hello mid-night means the watch app (re)started: tell it to pick the night back up.
                if (r.watchConfirmed && !r.stopping) resumeWatch(r, "watch app restarted")
                db.sessions().get(r.sessionId)?.let {
                    db.sessions().update(it.copy(origin = src.origin, device = src.device, provider = src.provider))
                }
                r.persist.event(now ?: 0, "hello", "${src.device} via ${src.provider} (${src.origin.label})")
                _live.update { it.copy(origin = src.origin, watchCaps = e.hello.capabilities, sourceLabel = src.device) }
                liveEvents += LiveEvent(now ?: 0, LiveEvent.Kind.INFO, "Watch: ${src.device}, ${src.provider}")
            }
            is EngineEvent.StatusReceived -> {
                e.status.batteryPct?.let { recordBattery(r, "watch", it, e.status.charging) }
                val s = e.status
                // Only log changes that matter: monitoring state, and a real backlog (minutes of data,
                // not the normal in-flight batch).
                val key = "monitoring=${s.monitoring} session=${s.sessionId == r.sessionId} backlog=${s.bufferedSamples > 2_000}"
                if (key != r.lastStatusKey) {
                    r.lastStatusKey = key
                    r.persist.event(System.currentTimeMillis(), "watch_status", "monitoring=${s.monitoring}, this night=${s.sessionId == r.sessionId}, buffered=${s.bufferedSamples}, battery=${s.batteryPct}")
                }
                if (e.status.monitoring && e.status.sessionId == r.sessionId) r.watchConfirmed = true
                if (!e.status.monitoring && r.watchConfirmed && r.sim == null && !r.stopping) {
                    if (r.watchSimulates) {
                        // A watch-side simulation ran to the end of its script.
                        liveEvents += LiveEvent(now ?: 0, LiveEvent.Kind.INFO, "Simulated night finished on the watch: ending the night")
                        stop()
                    } else {
                        // Real sensors never stop on their own: the watch app restarted or lost the night.
                        resumeWatch(r, "watch not monitoring")
                        liveEvents += LiveEvent(now ?: 0, LiveEvent.Kind.WARNING, "Watch had stopped recording: asked it to resume")
                    }
                }
                _live.update { it.copy(battery = e.status.batteryPct, bufferedOnWatch = e.status.bufferedSamples) }
            }
            is EngineEvent.EpochProcessed -> {
                r.persist.addEpoch(e)
                _live.update { s ->
                    s.copy(
                        features = e.features,
                        prediction = e.prediction,
                        remHistory = (s.remHistory + e.prediction.pRem).takeLast(HISTORY_EPOCHS),
                        stageHistory = (s.stageHistory + e.prediction.mostLikely.ordinal).takeLast(HISTORY_EPOCHS),
                        // Cue marks index into remHistory; shift them once the window starts sliding.
                        cueMarks = if (s.remHistory.size < HISTORY_EPOCHS) s.cueMarks else s.cueMarks.map { it - 1 }.filter { it >= 0 },
                        epochs = s.epochs + 1,
                    )
                }
            }
            is EngineEvent.SleepOnsetDetected -> {
                r.persist.event(e.t, "sleep_onset", "estimated sleep onset")
                _live.update { it.copy(sleepOnsetMs = e.t) }
                liveEvents += LiveEvent(e.t, LiveEvent.Kind.INFO, "Sleep onset detected (estimate)")
            }
            is EngineEvent.CueDecided -> {
                val d = e.decision
                db.cues().insert(
                    CueEventEntity(
                        sessionId = r.sessionId, t = d.t, epochIdx = d.epochIndex, action = d.action.name,
                        failuresJson = json.encodeToString(failuresSerializer, d.failures), explanation = d.explanation,
                        pRem = d.pRem, pWake = d.pWake, confidence = d.confidence, quality = d.quality,
                        intensity = d.intensity, patternId = d.patternId, cueId = d.cueId, cueNumber = d.cueNumber,
                    ),
                )
                val cmd = e.command
                if (cmd != null && !r.link.send(cmd)) {
                    db.cues().setOutcome(cmd.cueId, "SEND_FAILED", now ?: d.t, "link unavailable: ${r.link.state.value.label}")
                }
                _live.update { s ->
                    s.copy(
                        lastDecision = d,
                        cueCandidates = s.cueCandidates + 1,
                        cuesDelivered = s.cuesDelivered + if (d.action == CueAction.DELIVER) 1 else 0,
                        cueMarks = if (d.action == CueAction.DELIVER) s.cueMarks + (s.remHistory.size - 1) else s.cueMarks,
                        cueIntensity = d.intensity,
                    )
                }
                liveEvents += when (d.action) {
                    CueAction.DELIVER -> LiveEvent(d.t, LiveEvent.Kind.CUE, d.explanation)
                    CueAction.WOULD_DELIVER -> LiveEvent(d.t, LiveEvent.Kind.CUE, d.explanation)
                    CueAction.SUPPRESS -> LiveEvent(d.t, LiveEvent.Kind.SUPPRESSED, d.failures.joinToString { it.gate.label })
                }
            }
            is EngineEvent.CueAcknowledged -> {
                db.cues().setOutcome(e.ack.cueId, e.ack.outcome.name, e.ack.executedAt, e.ack.detail)
                liveEvents += LiveEvent(e.ack.executedAt, LiveEvent.Kind.INFO, "Watch: ${e.ack.outcome.label}. ${e.ack.detail}")
            }
            is EngineEvent.CueAssessed -> {
                db.cues().setResponse(e.assessment.cueId, e.assessment.response.name, e.assessment.detail)
                liveEvents += LiveEvent(e.assessment.t, LiveEvent.Kind.RESPONSE, "${e.assessment.response.label}: ${e.assessment.detail}")
            }
            is EngineEvent.SamplesRejected -> _live.update { it.copy(samplesRejected = it.samplesRejected + e.count) }
        }

        r.sim?.device?.takeNewTruth()?.let { r.persist.addTruth(it.map { t -> SimTruthEntity(r.sessionId, t.index, t.stage.ordinal) }) }

        _live.update { s ->
            var hr = s.heartRate
            var q = s.hrQuality
            var motion = s.motionMg
            var hrHistory = s.hrHistory
            var received = s.samplesReceived
            if (msg is SampleBatch) {
                received += msg.samples.size
                msg.samples.lastOrNull { it is HeartRateSample }?.let { (it as HeartRateSample); hr = it.bpm; q = it.quality }
                msg.samples.lastOrNull { it is MotionSample }?.let { motion = (it as MotionSample).activityMg }
                msg.samples.filterIsInstance<HeartRateSample>().filter { it.t / 1000 % 5 == 0L && it.bpm > 0 }
                    .forEach { hrHistory = hrHistory + it.bpm }
                hrHistory = hrHistory.takeLast(HR_HISTORY)
            }
            s.copy(
                heartRate = hr, hrQuality = q, motionMg = motion, hrHistory = hrHistory, samplesReceived = received,
                nowMs = now ?: s.nowMs,
                events = if (liveEvents.isEmpty()) s.events else s.events.withNew(liveEvents, MAX_EVENTS),
            )
        }
    }

    /** Phone battery % and whether it is plugged in, from the sticky ACTION_BATTERY_CHANGED broadcast. */
    private fun phoneBattery(): Pair<Int, Boolean>? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (level * 100 / scale) to plugged
    }

    /** Battery is physical, so it is stamped with wall time even during an accelerated simulation. */
    private suspend fun recordBattery(r: Running, device: String, pct: Int, charging: Boolean?) {
        val now = System.currentTimeMillis()
        val last = r.lastBattery[device]
        if (last != null && last.second == pct && now - last.first < BATTERY_EVERY_MS) return
        r.lastBattery[device] = now to pct
        db.battery().insert(BatteryEntity(sessionId = r.sessionId, t = now, device = device, pct = pct, charging = charging))
    }

    private suspend fun finish(r: Running) {
        try {
            for (e in r.processor.finish()) if (e is EngineEvent.EpochProcessed) r.persist.addEpoch(e)
            r.sim?.device?.takeNewTruth()?.let { r.persist.addTruth(it.map { t -> SimTruthEntity(r.sessionId, t.index, t.stage.ordinal) }) }
            r.persist.flush()
            phoneBattery()?.let { (pct, charging) -> r.lastBattery.remove("phone"); recordBattery(r, "phone", pct, charging) }
            val summary = r.processor.summary()
            db.sessions().get(r.sessionId)?.let { s ->
                val completed = r.sim?.runner?.state?.value?.phase == SimulationRunner.Phase.FINISHED || r.stopping || r.sim == null
                db.sessions().update(
                    s.copy(
                        endedAt = r.processor.now().takeIf { it != Long.MIN_VALUE } ?: System.currentTimeMillis(),
                        status = if (completed && summary.epochs > 0) SessionStatus.COMPLETED else SessionStatus.ABORTED,
                        summaryJson = json.encodeToString(SessionSummary.serializer(), summary),
                        sleepOnsetAt = summary.sleepOnsetMs,
                        estSleepMin = summary.estimatedSleepMin,
                        estRemMin = summary.estimatedMin[lucid.core.model.SleepStage.REM],
                        cuesDelivered = summary.cuesDelivered,
                        cuesWouldDeliver = summary.cuesWouldDeliver,
                        suspectedArousals = summary.suspectedArousals,
                        meanQuality = summary.meanQuality,
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "finishing session ${r.sessionId} failed", e)
        } finally {
            running = null
            RecordingService.stop(context)
            _live.update { it.copy(recording = false, link = LinkState.Idle) }
        }
    }

    /** Buffers database writes so an accelerated night does not issue thousands of tiny transactions. */
    private class Persister(private val db: LucidDatabase, private val sessionId: String) {
        private val lock = Mutex()
        private val samples = ArrayList<SampleEntity>(4096)
        private val epochs = ArrayList<EpochEntity>(64)
        private val truth = ArrayList<SimTruthEntity>(64)

        suspend fun addSamples(b: SampleBatch) = lock.withLock {
            for (s in b.samples) samples += when (s) {
                is HeartRateSample -> SampleEntity(0, sessionId, s.t, SampleEntity.HR, s.bpm, 0f, s.quality.ordinal, b.backfill)
                is IbiSample -> SampleEntity(0, sessionId, s.t, SampleEntity.IBI, s.ibiMs.toFloat(), 0f, s.quality.ordinal, b.backfill)
                is MotionSample -> SampleEntity(0, sessionId, s.t, SampleEntity.MOTION, s.activityMg, s.angleChangeDeg, 0, b.backfill)
                is SensorStatusSample -> SampleEntity(0, sessionId, s.t, SampleEntity.STATUS, s.status.ordinal.toFloat(), 0f, 0, b.backfill)
            }
            if (samples.size > 5000) flushLocked()
        }

        suspend fun addEpoch(e: EngineEvent.EpochProcessed) = lock.withLock {
            val f = e.features
            val p = e.prediction
            epochs += EpochEntity(
                sessionId = sessionId, idx = f.index, startMs = f.startMs, hrMean = f.hrMean, hrRel = f.hrRel,
                hrInstability = f.hrInstability, rmssd = f.rmssdMs, activityMg = f.activityMg, activeSeconds = f.activeSeconds,
                quality = f.quality, contactLost = f.contactLost, pWake = p.probabilities[0], pLight = p.probabilities[1],
                pDeep = p.probabilities[2], pRem = p.probabilities[3], confidence = p.confidence, stage = p.mostLikely.ordinal,
                contributionsJson = json.encodeToString(contributionsSerializer, p.contributions),
            )
        }

        suspend fun addTruth(rows: List<SimTruthEntity>) = lock.withLock { truth += rows }

        suspend fun event(t: Long, type: String, detail: String) = db.events().insert(SessionEventEntity(sessionId = sessionId, t = t, type = type, detail = detail))

        suspend fun flush() = lock.withLock { flushLocked() }

        private suspend fun flushLocked() {
            if (samples.isNotEmpty()) db.samples().insertAll(samples.toList()).also { samples.clear() }
            if (epochs.isNotEmpty()) db.epochs().upsertAll(epochs.toList()).also { epochs.clear() }
            if (truth.isNotEmpty()) db.truth().upsertAll(truth.toList()).also { truth.clear() }
        }
    }

    companion object {
        private const val TAG = "SessionManager"
        private const val HISTORY_EPOCHS = 240
        private const val HR_HISTORY = 360
        private const val MAX_EVENTS = 60
        private const val SILENCE_LIMIT_MS = 120_000L
        private const val BATTERY_EVERY_MS = 10 * 60_000L
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val failuresSerializer = ListSerializer(GateFailure.serializer())
        val contributionsSerializer = ListSerializer(FeatureContribution.serializer())
    }
}
