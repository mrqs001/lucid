package lucid.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lucid.app.LocalContainer
import lucid.app.data.AppSettings
import lucid.app.data.SourceKind
import lucid.app.session.LiveEvent
import lucid.app.ui.LucidColors
import lucid.app.ui.Nav
import lucid.app.ui.NumberStyle
import lucid.app.ui.components.Dot
import lucid.app.ui.components.Hypnogram
import lucid.app.ui.components.OriginBadge
import lucid.app.ui.components.RemProbabilityChart
import lucid.app.ui.components.SectionCard
import lucid.app.ui.components.SeriesChart
import lucid.app.ui.components.StageLegend
import lucid.app.ui.components.StatTile
import lucid.app.ui.components.StatusLabel
import lucid.app.ui.components.fmtTime
import lucid.app.ui.components.qualityStatus
import lucid.core.model.SleepStage
import lucid.core.sim.Scenarios
import lucid.core.sim.SimulationRunner

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LiveScreen(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val live by c.sessions.live.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var message by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Live") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!live.recording && live.sessionId == null && !settings.developerMode) {
                Text("No night is running. Start one from the Tonight tab.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (!live.recording && live.sessionId == null) {
                SimulationSetup(settings, onStart = {
                    scope.launch { message = runCatching { c.sessions.start() }.exceptionOrNull()?.message }
                }, onChange = { t -> scope.launch { c.settings.update(t) } })
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                return@Column
            }

            // ---- source & connection ----
            SectionCard(title = null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OriginBadge(live.origin)
                    Spacer(Modifier.width(8.dp))
                    Text(live.sourceLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(if (live.recording) "REC" else "ENDED", color = if (live.recording) LucidColors.Critical else LucidColors.InkMuted, style = MaterialTheme.typography.labelSmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (live.link is lucid.app.link.LinkState.Connected) LucidColors.Good else LucidColors.Warning)
                    Spacer(Modifier.width(6.dp))
                    Text(live.link.label, style = MaterialTheme.typography.bodySmall)
                }
                live.scenarioTitle?.let { Text("Scenario: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                live.armLabel?.let { Text("Experiment arm: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                live.watchCaps?.let { caps ->
                    Text(
                        "Watch: HR ${caps.heartRate.yn()}, IBI ${caps.interBeatIntervals.yn()}, accel ${caps.accelerometer.yn()}, " +
                            "vibrator ${caps.hasVibrator.yn()}${if (caps.isEmulator) " (emulator: no physical vibration)" else ""}" +
                            (live.battery?.let { ", watch battery $it%" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                live.sim?.let { SimControls(it, onPause = { c.sessions.pause() }, onResume = { c.sessions.resume() }, onSpeed = { s -> c.sessions.setSpeed(s) }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (live.recording) {
                        OutlinedButton(onClick = { c.sessions.stop() }) { Icon(Icons.Filled.Stop, null); Text("Stop night") }
                        if (settings.developerMode) {
                            OutlinedButton(onClick = {
                                scope.launch { message = c.sessions.testCue(settings.engine.cueing.startIntensity, settings.engine.cueing.patternId) }
                            }) { Text("Test cue") }
                        }
                    } else {
                        Button(onClick = { live.sessionId?.let(nav::session) }) { Text("Open night report") }
                        OutlinedButton(onClick = { scope.launch { message = runCatching { c.sessions.start() }.exceptionOrNull()?.message } }) { Text("New night") }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                live.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }

            // ---- current physiology ----
            SectionCard(title = "Now", trailing = { Text(if (live.nowMs > 0) fmtTime(live.nowMs) else "", style = MaterialTheme.typography.labelMedium.merge(NumberStyle)) }) {
                Row {
                    StatTile("Heart rate", live.heartRate?.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "–", Modifier.weight(1f), sub = live.hrQuality?.name?.lowercase())
                    StatTile("Movement", live.motionMg?.let { "%.0f mg".format(it) } ?: "–", Modifier.weight(1f))
                    StatTile("p(REM)", live.prediction?.pRem?.let { "%.2f".format(it) } ?: "–", Modifier.weight(1f), sub = live.prediction?.confidence?.let { "conf %.2f".format(it) })
                }
                live.prediction?.let { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SleepStage.entries.forEach { st ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(LucidColors.stage(st))
                                Spacer(Modifier.width(4.dp))
                                Text("${st.name.take(4).lowercase()} %.2f".format(p.p(st)), style = MaterialTheme.typography.labelSmall.merge(NumberStyle))
                            }
                        }
                    }
                }
                // Quality of the heart-rate/motion data itself (skin contact, valid readings per 30 s), not the Bluetooth link.
                live.features?.let { StatusLabel(qualityStatus(it.quality), "Sensor data quality %.0f%%".format(it.quality * 100) + if (it.contactLost) " · no skin contact" else " (last 30 s)") }
                live.phoneBattery?.let {
                    Text("Phone battery $it%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Samples ${live.samplesReceived} · unusable ${live.samplesRejected} (no contact or invalid) · epochs ${live.epochs}" +
                        (live.sleepOnsetMs?.let { " · asleep since ${fmtTime(it)}" } ?: "") +
                        (if (live.bufferedOnWatch > 0) " · ${live.bufferedOnWatch} buffered on watch" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (live.hrHistory.size > 2) SeriesChart(live.hrHistory, LucidColors.Ink, height = 70)
            }

            // ---- cueing state ----
            SectionCard(title = "Cueing", trailing = { Text("${live.cuesDelivered} sent", style = MaterialTheme.typography.labelMedium) }) {
                val p = live.prediction
                val d = live.lastDecision
                val state = when {
                    p == null -> "Waiting for the first 30 s epoch"
                    p.pRem < live.remThreshold -> "Watching: p(REM) %.2f below threshold %.2f".format(p.pRem, live.remThreshold)
                    d != null && d.epochIndex == live.features?.index -> d.explanation
                    else -> "Evaluating"
                }
                Text(state, style = MaterialTheme.typography.bodyMedium)
                live.cueIntensity?.let { Text("Next intensity %.0f%%".format(it * 100), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (live.remHistory.isNotEmpty()) {
                    val start = live.sessionStartMs + (live.epochs - live.remHistory.size) * 30_000L
                    Hypnogram(live.stageHistory, start)
                    StageLegend()
                    RemProbabilityChart(live.remHistory, start, live.remThreshold, delivered = live.cueMarks.toSet())
                    Text("Last ${live.remHistory.size / 2} min. Triangles mark delivered cues.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---- events ----
            SectionCard(title = "Recent events") {
                if (live.events.isEmpty()) Text("Nothing yet", style = MaterialTheme.typography.bodySmall)
                live.events.take(25).forEach { e ->
                    Row {
                        Text(fmtTime(e.t), style = MaterialTheme.typography.labelSmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                        Text(
                            e.text + if (e.repeats > 1) "  ×${e.repeats}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (e.kind == LiveEvent.Kind.CUE) FontWeight.SemiBold else FontWeight.Normal,
                            color = when (e.kind) {
                                LiveEvent.Kind.CUE -> MaterialTheme.colorScheme.primary
                                LiveEvent.Kind.SUPPRESSED -> MaterialTheme.colorScheme.onSurfaceVariant
                                LiveEvent.Kind.WARNING -> LucidColors.Warning
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun Boolean.yn() = if (this) "yes" else "no"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimControls(state: SimulationRunner.State, onPause: () -> Unit, onResume: () -> Unit, onSpeed: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Simulated %d:%02d of %d:%02d · %s".format(state.elapsedSec / 3600, state.elapsedSec % 3600 / 60, state.durationSec / 3600, state.durationSec % 3600 / 60, state.phase.name.lowercase()),
                style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                modifier = Modifier.weight(1f),
            )
            when (state.phase) {
                SimulationRunner.Phase.RUNNING -> IconButton(onClick = onPause) { Icon(Icons.Filled.Pause, "Pause") }
                SimulationRunner.Phase.PAUSED -> IconButton(onClick = onResume) { Icon(Icons.Filled.PlayArrow, "Resume") }
                else -> Unit
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SimulationRunner.Speed.Choices.forEach { s ->
                FilterChip(selected = state.speed == s, onClick = { onSpeed(s) }, label = { Text(s.speedLabel().replace("real time", "1x").replace("max speed", "max")) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulationSetup(settings: AppSettings, onStart: () -> Unit, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard(title = "No night running") {
        Text("Source: ${settings.source.label}", style = MaterialTheme.typography.bodyMedium)
        if (settings.source == SourceKind.IN_APP_SIMULATOR || settings.simulation.watchSimulates) {
            Text("Scenario", style = MaterialTheme.typography.labelLarge)
            Scenarios.all.forEach { sc ->
                FilterChip(
                    selected = settings.simulation.scenarioId == sc.id,
                    onClick = { onChange { it.copy(simulation = it.simulation.copy(scenarioId = sc.id)) } },
                    label = { Column { Text(sc.title); Text(sc.description, style = MaterialTheme.typography.bodySmall) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Replay speed", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SimulationRunner.Speed.Choices.forEach { s ->
                    FilterChip(
                        selected = settings.simulation.speed == s,
                        onClick = { onChange { it.copy(simulation = it.simulation.copy(speed = s)) } },
                        label = { Text(s.speedLabel().replace("real time", "1x").replace("max speed", "max")) },
                    )
                }
            }
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, null)
            Text("Start")
        }
    }
}
