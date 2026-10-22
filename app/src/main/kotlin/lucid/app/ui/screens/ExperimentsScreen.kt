package lucid.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lucid.app.LocalContainer
import lucid.app.data.AppSettings
import lucid.app.data.ExperimentEntity
import lucid.app.data.ExperimentTemplates
import lucid.app.data.arms
import lucid.app.data.encodeArms
import lucid.app.ui.Nav
import lucid.app.ui.components.SectionCard
import lucid.app.ui.components.fmtDate
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentsScreen(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val experiments by c.db.experiments().observeAll().collectAsStateWithLifecycle(emptyList())
    val sessions by c.db.sessions().observeAll().collectAsStateWithLifecycle(emptyList())
    val journal by c.db.journal().observeAll().collectAsStateWithLifecycle(emptyList())

    var template by remember { mutableStateOf("baseline") }
    var name by remember { mutableStateOf("Cues vs no cues") }
    var assignment by remember { mutableStateOf("RANDOM") }
    var blinded by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Experiments") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val active = experiments.firstOrNull { it.active }
            if (active != null) {
                SectionCard(title = "Running: ${active.name}") {
                    Text(
                        "${active.arms().joinToString(" vs ") { it.label }} · ${active.assignment.lowercase()} assignment" +
                            if (active.blinded) " · blinded" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${sessions.count { it.experimentId == active.id }} nights so far. Each new night is assigned an arm automatically; its full cueing configuration is stored with the night.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        scope.launch { c.db.experiments().upsert(active.copy(active = false, endedAt = System.currentTimeMillis())) }
                    }) { Text("End experiment") }
                }
            } else {
                SectionCard(title = "New experiment") {
                    Text(
                        "Compare strategies across nights instead of trusting single good nights. Start with cues vs. no cues: the no-cue arm still logs when a cue would have been sent, so both arms have the same structure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(template == "baseline", { template = "baseline"; name = "Cues vs no cues" }, label = { Text("Cues vs baseline") })
                        FilterChip(template == "pattern", { template = "pattern"; name = "Triple knock vs rising wave" }, label = { Text("Pattern A vs B") })
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(assignment == "RANDOM", { assignment = "RANDOM" }, label = { Text("Random per night") })
                        FilterChip(assignment == "ALTERNATE", { assignment = "ALTERNATE" }, label = { Text("Alternate") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Blinded")
                            Text("Hide tonight's arm and cue details until the journal entry is written", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(blinded, { blinded = it })
                    }
                    Button(onClick = {
                        scope.launch {
                            val base = settings.engine.cueing
                            val arms = if (template == "pattern") ExperimentTemplates.patternComparison(base) else ExperimentTemplates.cueVsBaseline(base)
                            c.db.experiments().deactivateAll()
                            c.db.experiments().upsert(
                                ExperimentEntity(
                                    id = UUID.randomUUID().toString(), name = name.ifBlank { "Experiment" },
                                    hypothesis = if (template == "pattern") "One vibration pattern is noticed in dreams more often, without more awakenings."
                                    else "Nights with cues produce more lucid dreams than nights without, without worse sleep.",
                                    createdAt = System.currentTimeMillis(), active = true, assignment = assignment, blinded = blinded,
                                    armsJson = encodeArms(arms), seed = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Start experiment") }
                }
            }

            experiments.forEach { e -> ArmComparison(e, sessions.filter { it.experimentId == e.id }, journal) }
            experiments.filter { !it.active }.forEach { e ->
                Text("${e.name}: ${fmtDate(e.createdAt)}${e.endedAt?.let { " to ${fmtDate(it)}" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
