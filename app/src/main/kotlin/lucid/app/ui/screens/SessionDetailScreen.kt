package lucid.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lucid.app.LocalContainer
import lucid.app.data.Exporter
import lucid.app.data.drain
import lucid.app.session.SessionManager
import lucid.app.ui.LucidColors
import lucid.app.ui.Nav
import lucid.app.ui.NumberStyle
import lucid.app.ui.components.Hypnogram
import lucid.app.ui.components.OriginBadge
import lucid.app.ui.components.RemProbabilityChart
import lucid.app.ui.components.SectionCard
import lucid.app.ui.components.SeriesChart
import lucid.app.ui.components.StageLegend
import lucid.app.ui.components.StatTile
import lucid.app.ui.components.fmtDateTime
import lucid.app.ui.components.fmtMinutes
import lucid.app.ui.components.fmtTime
import lucid.core.engine.CueAction
import lucid.core.engine.FeatureContribution
import lucid.core.engine.GateFailure
import lucid.core.engine.SessionSummary
import lucid.core.model.SleepStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(nav: Nav, sessionId: String) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val night = rememberNight(sessionId)
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSuppressed by rememberSaveable { mutableStateOf(false) }
    val s = night.session

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s?.let { fmtDateTime(it.startedAt) } ?: "Night") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { scope.launch { Exporter(context, c.db).let { it.share(it.exportSession(sessionId)) } } }) {
                    Icon(Icons.Filled.IosShare, "Export CSV")
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
            },
        )
        if (s == null) return@Column
        val summary = s.summaryJson?.let { runCatching { SessionManager.json.decodeFromString(SessionSummary.serializer(), it) }.getOrNull() }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = s.device, trailing = { OriginBadge(s.origin) }) {
                Text(
                    "${s.provider} via ${s.linkKind.lowercase().replace('_', ' ')} · estimator ${s.estimatorId} · policy ${s.policyId}" +
                        (s.scenarioId?.let { " · scenario $it, seed ${s.seed}" } ?: "") +
                        (s.armLabel?.let { if (night.hidden) " · arm hidden" else " · arm: $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    StatTile("Est. sleep", fmtMinutes(s.estSleepMin), Modifier.weight(1f))
                    StatTile("Est. REM", fmtMinutes(s.estRemMin), Modifier.weight(1f))
                    StatTile("Onset", s.sleepOnsetAt?.let(::fmtTime) ?: "–", Modifier.weight(1f))
                }
                if (summary != null) {
                    Row {
                        StatTile("Deep", fmtMinutes(summary.estimatedMin[SleepStage.DEEP]), Modifier.weight(1f))
                        StatTile("No contact", fmtMinutes(summary.contactLostMin), Modifier.weight(1f))
                        StatTile("Peak p(REM)", "%.2f".format(summary.maxPRem), Modifier.weight(1f))
                    }
                }
                if (night.truth != null) {
                    Text(
                        "Simulator ground truth is shown under the hypnogram. Agreement with it only shows the pipeline works on synthetic data; it is not evidence of real REM detection accuracy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LucidColors.Dawn,
                    )
                }
            }

            SectionCard(title = "Timeline") {
                Text("Tap or drag on any chart to inspect an epoch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Hypnogram(night.stages, night.startMs, truth = night.truth, selected = selected, onSelect = { selected = it })
                StageLegend()
                RemProbabilityChart(
                    night.pRem, night.startMs, night.config?.cueing?.remThreshold ?: 0.7f,
                    delivered = if (night.hidden) emptySet() else night.epochIndexes(CueAction.DELIVER),
                    wouldDeliver = if (night.hidden) emptySet() else night.epochIndexes(CueAction.WOULD_DELIVER),
                    suppressed = if (night.hidden) emptySet() else night.epochIndexes(CueAction.SUPPRESS),
                    selected = selected, onSelect = { selected = it },
                )
                Text("Heart rate (bpm, per epoch)", style = MaterialTheme.typography.labelMedium)
                SeriesChart(night.epochs.map { it.hrMean }, LucidColors.Ink, startMs = night.startMs, selected = selected, onSelect = { selected = it })
                Text("Movement (active seconds per epoch)", style = MaterialTheme.typography.labelMedium)
                SeriesChart(night.epochs.map { it.activeSeconds.toFloat() }, LucidColors.Wake, startMs = night.startMs, selected = selected, onSelect = { selected = it }, height = 60, floor = 0f)
                Text("Sensor data quality (skin contact, valid readings per epoch)", style = MaterialTheme.typography.labelMedium)
                SeriesChart(night.epochs.map { it.quality }, MaterialTheme.colorScheme.secondary, startMs = night.startMs, selected = selected, onSelect = { selected = it }, height = 50, floor = 0f)
            }

            SectionCard(title = "Battery") {
                listOf("watch" to "Watch", "phone" to "Phone").forEach { (key, label) ->
                    val d = night.battery.drain(key)
                    val readings = night.battery.filter { it.device == key }
                    Text(
                        "$label: " + when {
                            d != null -> d.summary()
                            readings.isNotEmpty() -> "${readings.last().pct}% (too short to estimate use)"
                            key == "watch" && s.isSimulated && s.linkKind == "IN_APP_SIMULATOR" -> "no battery (simulated watch)"
                            else -> "no readings"
                        },
                        style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                    )
                    if (readings.size > 2) SeriesChart(readings.map { it.pct.toFloat() }, if (key == "watch") LucidColors.Moon else LucidColors.InkMuted, height = 50)
                }
                Text(
                    "Battery is measured in real time. During an accelerated simulation the night lasts minutes, so the drain rate reflects the test, not a night.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val i = selected
            val e = i?.let { night.epochs.getOrNull(it) }
            if (i != null && e != null) {
                SectionCard(title = "Epoch at ${fmtTime(e.startMs)}") {
                    Row {
                        SleepStage.entries.forEach { st ->
                            val p = when (st) { SleepStage.WAKE -> e.pWake; SleepStage.LIGHT -> e.pLight; SleepStage.DEEP -> e.pDeep; SleepStage.REM -> e.pRem }
                            StatTile(st.name.lowercase(), "%.2f".format(p), Modifier.weight(1f))
                        }
                    }
                    night.truth?.getOrNull(i)?.takeIf { it >= 0 }?.let {
                        Text("Simulator truth: ${SleepStage.entries[it].label}", style = MaterialTheme.typography.bodySmall, color = LucidColors.Dawn)
                    }
                    Text(
                        "HR ${e.hrMean?.let { "%.1f".format(it) } ?: "–"} (rel ${e.hrRel?.let { "%+.1f".format(it) } ?: "–"}) · instability ${e.hrInstability?.let { "%.2f".format(it) } ?: "–"} · " +
                            "RMSSD ${e.rmssd?.let { "%.0f ms".format(it) } ?: "–"} · active ${e.activeSeconds} s · quality %.2f · confidence %.2f".format(e.quality, e.confidence) +
                            if (e.contactLost) " · NO CONTACT" else "",
                        style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                    )
                    val contributions = runCatching { SessionManager.json.decodeFromString(SessionManager.contributionsSerializer, e.contributionsJson) }.getOrDefault(emptyList())
                    if (contributions.isNotEmpty()) {
                        Text("Why: evidence for (+) or against (−) REM", style = MaterialTheme.typography.labelMedium)
                        contributions.forEach { ContributionRow(it) }
                    }
                    if (!night.hidden) {
                        night.cues.filter { it.epochIdx == e.idx }.forEach { cue ->
                            Text(cue.explanation, style = MaterialTheme.typography.bodySmall, color = if (cue.action == CueAction.SUPPRESS.name) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                            cue.outcome?.let { Text("Watch: $it ${cue.ackDetail ?: ""}", style = MaterialTheme.typography.bodySmall) }
                            cue.response?.let { Text("Response: $it, ${cue.responseDetail}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            if (night.hidden) {
                SectionCard(title = "Cue details hidden") {
                    Text("This night belongs to a blinded experiment. Write your journal entry first so knowing the cue schedule can't colour your report.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { nav.journalNew(sessionId) }) { Text("Write journal entry") }
                }
            } else {
                SectionCard(title = "Cue decisions") {
                    val sent = night.cues.filter { it.action != CueAction.SUPPRESS.name }
                    val suppressed = night.cues.filter { it.action == CueAction.SUPPRESS.name }
                    if (night.cues.isEmpty()) Text("No epoch reached the REM threshold, so no cue was considered.", style = MaterialTheme.typography.bodySmall)
                    sent.forEach { cue ->
                        Column {
                            Text("${fmtTime(cue.t)} · ${if (cue.action == CueAction.DELIVER.name) "Cue #${cue.cueNumber}" else "Would cue #${cue.cueNumber}"} · ${"%.0f%%".format(cue.intensity * 100)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(cue.explanation, style = MaterialTheme.typography.bodySmall)
                            Text(
                                listOfNotNull(cue.outcome?.let { "watch: ${it.lowercase()}" }, cue.response?.let { "response: ${it.lowercase().replace('_', ' ')}" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (suppressed.isNotEmpty()) {
                        val byGate = suppressed.flatMap { runCatching { SessionManager.json.decodeFromString(SessionManager.failuresSerializer, it.failuresJson) }.getOrDefault(emptyList<GateFailure>()) }
                            .groupingBy { it.gate.label }.eachCount().entries.sortedByDescending { it.value }
                        Text("${suppressed.size} candidates suppressed", style = MaterialTheme.typography.labelLarge)
                        byGate.forEach { (g, n) -> Text("$g: $n", style = MaterialTheme.typography.bodySmall.merge(NumberStyle)) }
                        FilterChip(selected = showSuppressed, onClick = { showSuppressed = !showSuppressed }, label = { Text("Show each suppressed candidate") })
                        if (showSuppressed) {
                            suppressed.forEach { Text("${fmtTime(it.t)} ${it.explanation}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }

            SectionCard(title = "Dream reports") {
                if (night.journal.isEmpty()) {
                    TextButton(onClick = { nav.journalNew(sessionId) }) { Text("Write a journal entry for this night") }
                }
                night.journal.forEach { j ->
                    TextButton(onClick = { nav.journalEdit(j.id) }) {
                        Text((if (j.lucid) "Lucid · " else "") + j.title.ifBlank { j.body.take(60).ifBlank { "Untitled" } })
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this night?") },
            text = { Text("Raw samples, epochs, cue decisions and simulator truth for this night are removed. Journal entries stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        c.db.samples().deleteFor(sessionId); c.db.epochs().deleteFor(sessionId); c.db.cues().deleteFor(sessionId)
                        c.db.truth().deleteFor(sessionId); c.db.events().deleteFor(sessionId); c.db.battery().deleteFor(sessionId)
                        c.db.sessions().delete(sessionId)
                        nav.back()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ContributionRow(c: FeatureContribution) {
    Row {
        Text(c.feature, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp))
        Text(c.value?.let { "%.2f".format(it) } ?: "–", style = MaterialTheme.typography.bodySmall.merge(NumberStyle), modifier = Modifier.width(60.dp))
        Text(
            "%+.2f".format(c.remLogLr),
            style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
            color = if (c.remLogLr >= 0) LucidColors.Rem else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
