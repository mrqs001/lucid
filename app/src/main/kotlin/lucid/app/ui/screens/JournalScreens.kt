package lucid.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lucid.app.LocalContainer
import lucid.app.data.CueRecognition
import lucid.app.data.JournalEntity
import lucid.app.data.Recall
import lucid.app.ui.LucidColors
import lucid.app.ui.Nav
import lucid.app.ui.components.LabeledSlider
import lucid.app.ui.components.OriginBadge
import lucid.app.ui.components.SectionCard
import lucid.app.ui.components.fmtDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(nav: Nav) {
    val c = LocalContainer.current
    val entries by c.db.journal().observeAll().collectAsStateWithLifecycle(emptyList())
    val sessions by c.db.sessions().observeAll().collectAsStateWithLifecycle(emptyList())
    val byId = sessions.associateBy { it.id }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Dream journal") })
            if (entries.isEmpty()) {
                Text(
                    "Write as soon as you wake, before checking your phone for anything else. Even fragments count: recall improves with practice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.id }) { j ->
                    Card(
                        Modifier.fillMaxWidth().clickable { nav.journalEdit(j.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(fmtDateTime(j.createdAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                if (j.lucid) {
                                    Icon(Icons.Filled.AutoAwesome, null, tint = LucidColors.Rem, modifier = Modifier.height(16.dp))
                                    Text(" Lucid", style = MaterialTheme.typography.labelMedium, color = LucidColors.Rem)
                                }
                            }
                            Text(j.title.ifBlank { j.body.lineSequence().firstOrNull()?.take(60)?.ifBlank { null } ?: j.recall.label }, style = MaterialTheme.typography.titleMedium)
                            if (j.body.isNotBlank()) Text(j.body.take(160), style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                j.sessionId?.let { byId[it] }?.let { OriginBadge(it.origin) }
                                if (j.tags.isNotBlank()) Text(j.tags, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { nav.journalNew(sessions.firstOrNull()?.id) },
            icon = { Icon(Icons.Filled.Add, null) },
            text = { Text("New entry") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JournalEditScreen(nav: Nav, id: Long, sessionId: String?) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf<JournalEntity?>(null) }
    val sessions by c.db.sessions().observeAll().collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(id) {
        val now = System.currentTimeMillis()
        entry = if (id > 0) c.db.journal().get(id) else JournalEntity(createdAt = now, updatedAt = now, sessionId = sessionId)
    }
    val e = entry ?: return
    fun set(t: (JournalEntity) -> JournalEntity) { entry = t(e) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (id > 0) "Edit entry" else "New entry") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (id > 0) IconButton(onClick = { scope.launch { c.db.journal().delete(id); nav.back() } }) { Icon(Icons.Filled.Delete, "Delete") }
                TextButton(onClick = {
                    scope.launch {
                        val saved = e.copy(updatedAt = System.currentTimeMillis())
                        if (saved.id == 0L) c.db.journal().insert(saved) else c.db.journal().update(saved)
                        nav.back()
                    }
                }) { Text("Save") }
            },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(e.title, { v -> set { it.copy(title = v) } }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                e.body, { v -> set { it.copy(body = v) } },
                label = { Text("What happened? Places, people, feelings, anything odd") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            SectionCard(title = "Recall") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Recall.entries.forEach { r -> FilterChip(e.recall == r, { set { it.copy(recall = r) } }, label = { Text(r.label) }) }
                }
            }
            SectionCard(title = "Lucidity") {
                Row(
                    Modifier.fillMaxWidth().toggleable(
                        value = e.lucid,
                        role = Role.Switch,
                        onValueChange = { v -> set { it.copy(lucid = v, lucidity = if (v) maxOf(1, it.lucidity) else 0) } },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("I knew I was dreaming", modifier = Modifier.weight(1f))
                    Switch(e.lucid, onCheckedChange = null)
                }
                if (e.lucid) {
                    val levels = listOf("Brief / unstable", "Clear", "Clear, with deliberate control")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        levels.forEachIndexed { i, l -> FilterChip(e.lucidity == i + 1, { set { it.copy(lucidity = i + 1) } }, label = { Text(l) }) }
                    }
                }
                LabeledSlider("Perceived control", e.control.toFloat(), 1f..5f, { "%.0f / 5".format(it) }, { v -> set { it.copy(control = v.toInt()) } }, steps = 3)
                LabeledSlider("Vividness", e.vividness.toFloat(), 1f..5f, { "%.0f / 5".format(it) }, { v -> set { it.copy(vividness = v.toInt()) } }, steps = 3)
            }
            SectionCard(title = "The cue") {
                Text("Did you notice the vibration during the night?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CueRecognition.entries.forEach { r ->
                    FilterChip(e.cueRecognition == r, { set { it.copy(cueRecognition = r) } }, label = { Text(r.label) }, modifier = Modifier.fillMaxWidth())
                }
            }
            SectionCard(title = "Sleep") {
                LabeledSlider("Sleep quality", e.sleepQuality.toFloat(), 1f..5f, { "%.0f / 5".format(it) }, { v -> set { it.copy(sleepQuality = v.toInt()) } }, steps = 3)
                LabeledSlider("Times I woke up", e.awakenings.toFloat(), 0f..10f, { "%.0f".format(it) }, { v -> set { it.copy(awakenings = v.toInt()) } }, steps = 9)
            }
            OutlinedTextField(e.tags, { v -> set { it.copy(tags = v) } }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            SectionCard(title = "Night") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(e.sessionId == null, { set { it.copy(sessionId = null) } }, label = { Text("None") })
                    sessions.take(6).forEach { s ->
                        FilterChip(e.sessionId == s.id, { set { it.copy(sessionId = s.id) } }, label = { Text(fmtDateTime(s.startedAt) + if (s.isSimulated) " (sim)" else "") })
                    }
                }
            }
            Text(
                "Voice notes: the data model reserves a slot for audio; recording and transcription are not implemented yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
