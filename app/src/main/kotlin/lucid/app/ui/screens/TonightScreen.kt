package lucid.app.ui.screens

import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lucid.app.LocalContainer
import lucid.app.data.AppSettings
import lucid.app.data.SessionStatus
import lucid.app.data.SourceKind
import lucid.app.data.drain
import lucid.app.ui.Nav
import lucid.app.ui.components.Hypnogram
import lucid.app.ui.components.OriginBadge
import lucid.app.ui.components.RemProbabilityChart
import lucid.app.ui.components.SectionCard
import lucid.app.ui.components.StageLegend
import lucid.app.ui.components.StatTile
import lucid.app.ui.components.StatusLabel
import lucid.app.ui.components.fmtDateTime
import lucid.app.ui.components.fmtMinutes
import lucid.app.ui.components.qualityStatus
import lucid.core.engine.CueAction
import lucid.core.sim.Scenarios

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonightScreen(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val live by c.sessions.live.collectAsStateWithLifecycle()
    val sessions by c.db.sessions().observeAll().collectAsStateWithLifecycle(emptyList())
    val last = sessions.firstOrNull { it.status != SessionStatus.RECORDING }
    val night = rememberNight(last?.id)
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Tonight") },
            actions = { IconButton(onClick = nav::settings) { Icon(Icons.Filled.Settings, "Settings") } },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (live.recording) {
                RecordingCard(live, onDetails = { nav.live() }, onStop = { c.sessions.stop() })
            } else {
                SectionCard(title = "Start the night") {
                    val cueLine = if (settings.engine.cueing.mode == lucid.core.engine.CueMode.CUE) {
                        "Cues on, from %.1f h after you fall asleep".format(settings.engine.cueing.minMinutesAfterSleepOnset / 60)
                    } else {
                        "Baseline night: recording only, no vibration"
                    }
                    val sourceLine = when {
                        settings.source == SourceKind.IN_APP_SIMULATOR ->
                            "Simulated night · ${Scenarios.all.firstOrNull { it.id == settings.simulation.scenarioId }?.title} · ${settings.simulation.speed.speedLabel()}"
                        settings.simulation.watchSimulates -> "Watch with simulated data"
                        else -> "Your watch"
                    }
                    Text("$sourceLine · $cueLine", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = {
                            scope.launch {
                                error = runCatching { c.sessions.start() }.exceptionOrNull()?.message
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text(if (settings.source == SourceKind.IN_APP_SIMULATOR) "Start simulated night" else "Start night")
                    }
                    Text(
                        "Wear the watch, keep the phone nearby (on the charger is best), tap Start night and go to sleep. Tap Stop when you get up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    BatteryOptimisationHint()
                }
            }

            SectionCard(title = "Tonight's protocol") {
                Text(
                    "1. In bed, run the pre-sleep cue training: the vibration is paired with noticing whether you are dreaming.\n" +
                        "2. Optional but best supported: wake after ~5 h (WBTB), stay up 30-60 min, repeat the training and set the intention, then fall asleep within ~10 min.\n" +
                        "3. Cues are only considered more than ${(settings.engine.cueing.minMinutesAfterSleepOnset / 60).let { "%.1f".format(it) }} h after sleep onset, in sustained probable REM.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = nav::presleep) { Text("Pre-sleep training") }
                    OutlinedButton(onClick = {
                        val t = java.time.LocalTime.now().plusHours(5)
                        context.startActivity(
                            Intent(AlarmClock.ACTION_SET_ALARM)
                                .putExtra(AlarmClock.EXTRA_HOUR, t.hour)
                                .putExtra(AlarmClock.EXTRA_MINUTES, t.minute)
                                .putExtra(AlarmClock.EXTRA_MESSAGE, "Lucid: wake-back-to-bed")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) {
                        Icon(Icons.Filled.Alarm, null)
                        Text("WBTB +5 h")
                    }
                }
            }

            val s = night.session
            if (s == null) {
                SectionCard(title = "No nights yet") {
                    Text(
                        "After your first night, the report appears here: how long you slept, when REM was likely, and any cues.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                SectionCard(
                    title = "Last night",
                    trailing = { OriginBadge(s.origin) },
                ) {
                    Text(
                        fmtDateTime(s.startedAt) + (s.armLabel?.let { if (night.hidden) " · experiment arm hidden" else " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        StatTile("Recorded", fmtMinutes(s.endedAt?.let { (it - s.startedAt) / 60_000f }), Modifier.weight(1f))
                        StatTile("Est. sleep", fmtMinutes(s.estSleepMin), Modifier.weight(1f))
                        StatTile("Est. REM", fmtMinutes(s.estRemMin), Modifier.weight(1f))
                    }
                    Row {
                        if (night.hidden) {
                            StatTile("Cues", "hidden", Modifier.weight(1f), sub = "journal first")
                        } else {
                            StatTile("Cues", "${s.cuesDelivered}", Modifier.weight(1f), sub = if (s.cuesWouldDeliver > 0) "${s.cuesWouldDeliver} would-cue" else null)
                            StatTile("Arousals?", "${s.suspectedArousals}", Modifier.weight(1f), sub = "after cues")
                        }
                        StatTile("Lucid", "${night.journal.count { it.lucid }}", Modifier.weight(1f), sub = "reported")
                    }
                    night.battery.drain("watch")?.let { d ->
                        StatusLabel(
                            if (d.pctPerHour <= 4f) lucid.app.ui.components.Status.GOOD else lucid.app.ui.components.Status.WARNING,
                            "Watch battery ${d.summary()}",
                        )
                    }
                    StatusLabel(qualityStatus(s.meanQuality), "Sensor data quality ${s.meanQuality?.let { "%.0f%%".format(it * 100) } ?: "–"} (night average)")
                    if (night.epochs.isNotEmpty()) {
                        Hypnogram(night.stages, night.startMs, truth = night.truth)
                        StageLegend()
                        RemProbabilityChart(
                            night.pRem, night.startMs, night.config?.cueing?.remThreshold ?: 0.7f,
                            delivered = if (night.hidden) emptySet() else night.epochIndexes(CueAction.DELIVER),
                            wouldDeliver = if (night.hidden) emptySet() else night.epochIndexes(CueAction.WOULD_DELIVER),
                        )
                        Text(
                            "Stages and REM probability are estimates from an uncalibrated experimental model, not a medical measurement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (night.journal.isEmpty()) {
                            Button(onClick = { nav.journalNew(s.id) }) {
                                Icon(Icons.Filled.Edit, null)
                                Text("Write dream")
                            }
                        }
                        TextButton(onClick = { nav.session(s.id) }) { Text("Open night") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

fun Float.speedLabel(): String = if (isInfinite()) "max speed" else if (this <= 1f) "real time" else "${toInt()}x"

/** Compact view of a running night; details live on the Live screen. */
@Composable
private fun RecordingCard(live: lucid.app.session.LiveState, onDetails: () -> Unit, onStop: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SectionCard(title = "Recording", trailing = { OriginBadge(live.origin) }) {
        val connected = live.link is lucid.app.link.LinkState.Connected
        StatusLabel(
            if (connected) lucid.app.ui.components.Status.GOOD else lucid.app.ui.components.Status.WARNING,
            if (connected) "Watch connected" else live.link.label,
        )
        Row {
            StatTile("Heart rate", live.heartRate?.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "–", Modifier.weight(1f))
            StatTile("Sensor data", live.features?.let { "%.0f%%".format(it.quality * 100) } ?: "–", Modifier.weight(1f))
            StatTile("Cues", "${live.cuesDelivered}", Modifier.weight(1f))
        }
        listOfNotNull(
            live.sleepOnsetMs?.let { "Asleep since ${lucid.app.ui.components.fmtTime(it)} (estimate)" },
            live.battery?.let { "Watch battery $it%" },
        ).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDetails) { Text("Details") }
            Button(onClick = { confirm = true }) {
                Icon(Icons.Filled.Stop, null)
                Text("Stop night")
            }
        }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("End the night?") },
            text = { Text("Recording stops and the night report is saved.") },
            confirmButton = { TextButton(onClick = { confirm = false; onStop() }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep recording") } },
        )
    }
}

/** Overnight recording needs the battery-optimisation exemption; offer the system dialog if missing. */
@Composable
private fun BatteryOptimisationHint() {
    val context = LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (exempt) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusLabel(lucid.app.ui.components.Status.WARNING, "Battery optimisation may stop recording at night", Modifier.weight(1f))
        TextButton(onClick = {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("Fix") }
    }
}
