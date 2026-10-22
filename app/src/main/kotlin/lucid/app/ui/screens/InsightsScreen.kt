package lucid.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lucid.app.LocalContainer
import lucid.app.data.AppSettings
import lucid.app.data.ExperimentEntity
import lucid.app.data.JournalEntity
import lucid.app.data.Recall
import lucid.app.data.SessionEntity
import lucid.app.data.SessionStatus
import lucid.app.data.arms
import lucid.app.data.drain
import lucid.app.ui.LucidColors
import lucid.app.ui.Nav
import lucid.app.ui.NumberStyle
import lucid.app.ui.components.BarChart
import lucid.app.ui.components.OriginBadge
import lucid.app.ui.components.SectionCard
import lucid.app.ui.components.StatTile
import lucid.app.ui.components.fmtDate
import lucid.app.ui.components.fmtDateTime
import lucid.app.ui.components.fmtMinutes
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val allSessions by c.db.sessions().observeAll().collectAsStateWithLifecycle(emptyList())
    val allJournal by c.db.journal().observeAll().collectAsStateWithLifecycle(emptyList())
    val experiments by c.db.experiments().observeAll().collectAsStateWithLifecycle(emptyList())
    val battery by c.db.battery().observeAll().collectAsStateWithLifecycle(emptyList())
    val includeSim = settings.includeSimulatedInStats && settings.developerMode

    val sessions = allSessions.filter { it.status == SessionStatus.COMPLETED && (includeSim || !it.isSimulated) }
    val simIds = allSessions.filter { it.isSimulated }.map { it.id }.toSet()
    val journal = allJournal.filter { includeSim || it.sessionId !in simIds }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Insights") },
            actions = {
                IconButton(onClick = nav::experiments) { Icon(Icons.Filled.Science, "Experiments") }
                IconButton(onClick = nav::settings) { Icon(Icons.Filled.Settings, "Settings") }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (settings.developerMode) Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = includeSim,
                    onClick = { scope.launch { c.settings.update { it.copy(includeSimulatedInStats = !includeSim) } } },
                    label = { Text("Include simulated nights") },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (includeSim) "Mixing synthetic and real data" else "Real observations only",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (includeSim) LucidColors.Dawn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val nightsWithEntry = journal.mapNotNull { it.sessionId }.toSet()
            val recallNights = journal.filter { it.recall != Recall.NONE }.mapNotNull { it.sessionId }.toSet()
            SectionCard(title = "Overview") {
                Row {
                    StatTile("Nights", "${sessions.size}", Modifier.weight(1f))
                    StatTile("Dream entries", "${journal.size}", Modifier.weight(1f))
                    StatTile("Lucid", "${journal.count { it.lucid }}", Modifier.weight(1f), sub = "reported")
                }
                Row {
                    val recallRate = if (nightsWithEntry.isEmpty()) null else recallNights.size.toFloat() / nightsWithEntry.size
                    StatTile("Recall", recallRate?.let { "%.0f%%".format(it * 100) } ?: "–", Modifier.weight(1f), sub = "of journaled nights")
                    val cues = sessions.sumOf { it.cuesDelivered }
                    StatTile("Cues / night", if (sessions.isEmpty()) "–" else "%.1f".format(cues.toFloat() / sessions.size), Modifier.weight(1f))
                    StatTile("Arousal / cue", if (cues == 0) "–" else "%.0f%%".format(sessions.sumOf { it.suspectedArousals } * 100f / cues), Modifier.weight(1f), sub = "suspected")
                }
                Text(
                    "Lucidity and recall are self-reports. Suspected arousals come from movement / heart-rate changes after a cue, not from EEG.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val recent = sessions.take(14).reversed()
            if (recent.isNotEmpty()) {
                SectionCard(title = "Cues per night") {
                    BarChart(recent.map { fmtDate(it.startedAt).takeLast(6) }, recent.map { it.cuesDelivered.toFloat() }, LucidColors.Rem)
                }
                val drains = recent.mapNotNull { s -> battery.filter { it.sessionId == s.id }.drain("watch")?.let { s to it } }
                if (drains.isNotEmpty()) {
                    SectionCard(title = "Watch battery use (%/h)") {
                        BarChart(drains.map { fmtDate(it.first.startedAt).takeLast(6) }, drains.map { it.second.pctPerHour }, LucidColors.Moon, valueFormat = { "%.1f".format(it) })
                        Text(
                            "Real time, last stretch without charging. A full night at the rate shown uses about ${"%.0f".format(drains.last().second.pctPerHour * 8)}% over 8 h.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SectionCard(title = "Possible sleep disruption") {
                    Text("Suspected arousals after cues, per night", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BarChart(recent.map { fmtDate(it.startedAt).takeLast(6) }, recent.map { it.suspectedArousals.toFloat() }, LucidColors.Wake)
                    Text("Estimated REM per night (minutes)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BarChart(recent.map { fmtDate(it.startedAt).takeLast(6) }, recent.map { it.estRemMin ?: 0f }, LucidColors.Rem)
                }
            }

            if (journal.isNotEmpty()) {
                val weeks = journal.groupBy { weekKey(it.createdAt) }.toSortedMap().entries.toList().takeLast(8)
                SectionCard(title = "Dream recall and lucidity by week") {
                    Text("Entries with recall", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BarChart(weeks.map { "W${it.key % 100}" }, weeks.map { w -> w.value.count { it.recall != Recall.NONE }.toFloat() }, MaterialTheme.colorScheme.secondary)
                    Text("Lucid dreams reported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BarChart(weeks.map { "W${it.key % 100}" }, weeks.map { w -> w.value.count { it.lucid }.toFloat() }, LucidColors.Rem)
                }
            }

            experiments.forEach { e -> ArmComparison(e, allSessions.filter { it.experimentId == e.id && (includeSim || !it.isSimulated) }, allJournal) }

            SectionCard(title = "All nights") {
                if (allSessions.isEmpty()) Text("No nights recorded yet.", style = MaterialTheme.typography.bodySmall)
                allSessions.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { nav.session(s.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fmtDateTime(s.startedAt), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${s.status.name.lowercase()} · sleep ${fmtMinutes(s.estSleepMin)} · REM ${fmtMinutes(s.estRemMin)} · ${s.cuesDelivered} cues" +
                                    (s.scenarioId?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OriginBadge(s.origin)
                    }
                }
            }
        }
    }
}

private fun weekKey(ms: Long): Int {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    return d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
}

/** Per-arm outcomes of one experiment. Small numbers: shows counts, not significance. */
@Composable
fun ArmComparison(e: ExperimentEntity, sessions: List<SessionEntity>, journal: List<JournalEntity>) {
    SectionCard(title = "Experiment: ${e.name}") {
        Text(e.hypothesis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            listOf("Arm", "Nights", "Recall", "Lucid", "Sleep q.", "Arousals").forEachIndexed { i, h ->
                Text(h, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(if (i == 0) 1.6f else 1f))
            }
        }
        val byArm = sessions.groupBy { it.armId }
        e.arms().forEach { arm ->
            val nights = byArm[arm.id].orEmpty()
            val ids = nights.map { it.id }.toSet()
            val entries = journal.filter { it.sessionId in ids }
            val journaled = entries.mapNotNull { it.sessionId }.toSet().size
            fun pct(n: Int, d: Int) = if (d == 0) "–" else "%.0f%%".format(n * 100f / d)
            Row {
                Text(arm.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.6f))
                Text("${nights.size}", style = MaterialTheme.typography.bodySmall.merge(NumberStyle), modifier = Modifier.weight(1f))
                Text(pct(entries.filter { it.recall != Recall.NONE }.mapNotNull { it.sessionId }.toSet().size, journaled), style = MaterialTheme.typography.bodySmall.merge(NumberStyle), modifier = Modifier.weight(1f))
                Text(pct(entries.filter { it.lucid }.mapNotNull { it.sessionId }.toSet().size, journaled), style = MaterialTheme.typography.bodySmall.merge(NumberStyle), modifier = Modifier.weight(1f))
                Text(if (entries.isEmpty()) "–" else "%.1f".format(entries.map { it.sleepQuality }.average()), style = MaterialTheme.typography.bodySmall.merge(NumberStyle), modifier = Modifier.weight(1f))
                Text("${nights.sumOf { it.suspectedArousals }}", style = MaterialTheme.typography.bodySmall.merge(NumberStyle), modifier = Modifier.weight(1f))
            }
        }
        Text(
            "Rates are per journaled night. With a handful of nights per arm, differences are mostly noise; collect at least 10-15 nights per arm before reading anything into them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
