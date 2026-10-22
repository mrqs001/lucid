package lucid.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import lucid.app.BuildConfig
import lucid.app.LocalContainer
import lucid.app.data.AppSettings
import lucid.app.data.Exporter
import lucid.app.data.SourceKind
import lucid.app.ui.LucidColors
import lucid.app.ui.Nav
import lucid.app.ui.components.LabeledSlider
import lucid.app.ui.components.SectionCard
import lucid.core.engine.CueMode
import lucid.core.engine.CueingConfig
import lucid.core.model.VibrationPattern
import lucid.core.sim.Scenarios
import lucid.core.sim.SimulationRunner

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val cue = settings.engine.cueing
    var message by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(t) }
    fun cueing(t: (CueingConfig) -> CueingConfig) = update { it.copy(engine = it.engine.copy(cueing = t(it.engine.cueing))) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "Night cues") {
                SwitchRow(
                    title = "Vibrate during REM",
                    subtitle = if (cue.mode == CueMode.CUE) {
                        "On: the watch gives soft cues in probable REM late in the night."
                    } else {
                        "Off (baseline): the night is recorded, nothing vibrates. Start with a few of these."
                    },
                    checked = cue.mode == CueMode.CUE,
                    onChange = { on -> cueing { it.copy(mode = if (on) CueMode.CUE else CueMode.BASELINE) } },
                )
                LabeledSlider(
                    "Cue strength", cue.startIntensity, 0.05f..1f, { "%.0f%%".format(it * 100) },
                    { v -> cueing { it.copy(startIntensity = v, maxIntensity = maxOf(it.maxIntensity, v)) } },
                    help = "Lying in bed, pick the weakest level you can just feel. The app nudges it up when you don't react and down if a cue seems to wake you.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { message = c.sessions.testCue(cue.startIntensity, cue.patternId) } }) { Text("Try on watch") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                LabeledSlider(
                    "No cues before", cue.minMinutesAfterSleepOnset / 60f, 2f..7f, { "%.1f h asleep".format(it) },
                    { v -> cueing { it.copy(minMinutesAfterSleepOnset = v * 60f) } }, steps = 9,
                    help = "REM periods are longest late in the night, and early cues mostly disturb deep sleep.",
                )
            }

            SectionCard(title = "Battery") {
                LabeledSlider(
                    "Stop the night when the phone reaches",
                    settings.stopAtPhoneBatteryPct.toFloat(), 0f..50f,
                    { if (it < 1f) "never" else "%.0f%%".format(it) },
                    { v -> update { it.copy(stopAtPhoneBatteryPct = (v / 5).toInt() * 5) } }, steps = 9,
                    help = "Only when it isn't charging. The night is saved and the watch stops recording. The watch itself may run flat.",
                )
            }

            SectionCard(
                title = "Advanced",
                trailing = { Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) },
                modifier = Modifier.clickable { advanced = !advanced },
            ) {
                if (!advanced) {
                    Text("Detection thresholds, safety limits and the cue pattern.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(advanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LabeledSlider("Cue pattern", VibrationPattern.Presets.indexOfFirst { it.id == cue.patternId }.coerceAtLeast(0).toFloat(), 0f..(VibrationPattern.Presets.size - 1).toFloat(),
                            { VibrationPattern.Presets[it.toInt()].name }, { v -> cueing { it.copy(patternId = VibrationPattern.Presets[v.toInt()].id) } }, steps = VibrationPattern.Presets.size - 2)
                        LabeledSlider("Maximum strength", cue.maxIntensity, 0.1f..1f, { "%.0f%%".format(it * 100) }, { v -> cueing { it.copy(maxIntensity = v) } })
                        LabeledSlider("REM probability needed", cue.remThreshold, 0.4f..0.95f, { "%.2f".format(it) }, { v -> cueing { it.copy(remThreshold = v) } })
                        LabeledSlider("REM sustained before cueing", cue.minSustainedRemMin, 0f..15f, { "%.1f min".format(it) }, { v -> cueing { it.copy(minSustainedRemMin = v) } })
                        LabeledSlider("Minimum estimator confidence", cue.minConfidence, 0f..0.9f, { "%.2f".format(it) }, { v -> cueing { it.copy(minConfidence = v) } })
                        LabeledSlider("Minimum sensor data quality", cue.minSignalQuality, 0f..0.9f, { "%.2f".format(it) }, { v -> cueing { it.copy(minSignalQuality = v) } })
                        LabeledSlider("Skip if p(wake) above", cue.maxWakeProbability, 0.05f..0.6f, { "%.2f".format(it) }, { v -> cueing { it.copy(maxWakeProbability = v) } })
                        LabeledSlider("Pause after movement", cue.motionLockoutMin, 0f..10f, { "%.1f min".format(it) }, { v -> cueing { it.copy(motionLockoutMin = v) } })
                        LabeledSlider("Time between cues", cue.minCueSpacingSec.toFloat(), 30f..600f, { "%.0f s".format(it) }, { v -> cueing { it.copy(minCueSpacingSec = v.toInt()) } })
                        LabeledSlider("Max cues per REM period", cue.maxCuesPerRemPeriod.toFloat(), 1f..15f, { "%.0f".format(it) }, { v -> cueing { it.copy(maxCuesPerRemPeriod = v.toInt()) } }, steps = 13)
                        LabeledSlider("Max cues per night", cue.maxCuesPerNight.toFloat(), 1f..40f, { "%.0f".format(it) }, { v -> cueing { it.copy(maxCuesPerNight = v.toInt()) } })
                        LabeledSlider("Pause after a suspected awakening", cue.arousalBackoffMin, 5f..90f, { "%.0f min".format(it) }, { v -> cueing { it.copy(arousalBackoffMin = v) } })
                        OutlinedButton(onClick = { cueing { CueingConfig(mode = cue.mode) } }) { Text("Reset to recommended") }
                    }
                }
            }

            SectionCard(title = "Your data") {
                Text("Everything stays on this phone. Nothing is uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { scope.launch { Exporter(context, c.db).let { it.share(listOf(it.exportAll())) } } }) { Text("Export journal and nights") }
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete all nights") }
            }

            SectionCard(title = "About") {
                Text(
                    "Lucid estimates REM sleep from your watch's heart rate and movement and can give a soft cue so you notice you're dreaming. " +
                        "It's an experiment, not a medical device:\n" +
                        "• the REM estimate is not validated against a sleep lab yet;\n" +
                        "• cues can wake you, so start gently and watch the awakening counts;\n" +
                        "• a cue doesn't make a dream lucid by itself: the daytime and pre-sleep training is what links the cue to awareness.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SwitchRow(
                    title = "Developer tools",
                    subtitle = "Simulator, data sources and test controls",
                    checked = settings.developerMode,
                    onChange = { on -> update { it.copy(developerMode = on) } },
                )
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (settings.developerMode) DeveloperSettings(settings, ::update)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all nights?") },
            text = { Text("Removes every recorded night with its measurements and cue history. Journal entries and training history stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { message = runCatching { c.deleteAllNights() }.fold({ "All nights deleted" }, { it.message }) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeveloperSettings(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    SectionCard(title = "Developer: data source") {
        SourceKind.entries.forEach { k ->
            FilterChip(
                selected = settings.source == k,
                onClick = { update { it.copy(source = k) } },
                label = { Column { Text(k.label); Text(k.detail, style = MaterialTheme.typography.bodySmall) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (settings.source != SourceKind.IN_APP_SIMULATOR) {
            SwitchRow(
                "Watch generates simulated data", "Off = real sensors",
                settings.simulation.watchSimulates,
            ) { v -> update { it.copy(simulation = it.simulation.copy(watchSimulates = v)) } }
        }
        if (settings.source == SourceKind.WATCH_DEV_BRIDGE && !BuildConfig.DEBUG) {
            Text("The dev bridge only works in debug builds.", color = LucidColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
    }
    SectionCard(title = "Developer: simulation") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Scenarios.all.forEach { sc ->
                FilterChip(settings.simulation.scenarioId == sc.id, { update { it.copy(simulation = it.simulation.copy(scenarioId = sc.id)) } }, label = { Text(sc.title) })
            }
        }
        Text(Scenarios.all.firstOrNull { it.id == settings.simulation.scenarioId }?.description ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var seedText by remember(settings.simulation.seed) { mutableStateOf(settings.simulation.seed?.toString() ?: "") }
        OutlinedTextField(
            seedText,
            { v -> seedText = v.filter(Char::isDigit).take(12); update { it.copy(simulation = it.simulation.copy(seed = seedText.toLongOrNull())) } },
            label = { Text("Seed (empty = scenario default)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SimulationRunner.Speed.Choices.forEach { s ->
                FilterChip(settings.simulation.speed == s, { update { it.copy(simulation = it.simulation.copy(speed = s)) } }, label = { Text(s.speedLabel()) })
            }
        }
        SwitchRow("Include simulated nights in statistics", "Off keeps Insights to real nights", settings.includeSimulatedInStats) { v ->
            update { it.copy(includeSimulatedInStats = v) }
        }
    }
}
