package lucid.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lucid.app.AppContainer
import lucid.app.LocalContainer
import lucid.app.data.AppSettings
import lucid.app.data.TrainingType
import lucid.app.ui.LucidColors
import lucid.app.ui.Nav
import lucid.app.ui.components.LabeledSlider
import lucid.app.ui.components.SectionCard
import lucid.app.ui.components.StatTile
import lucid.app.ui.components.fmtDateTime
import kotlin.random.Random

/** Play the night cue now on the wrist; the phone vibrates only if no watch is in reach. */
private suspend fun playCue(c: AppContainer, s: AppSettings, intensity: Float = 0.7f) {
    if (!c.sessions.trainingCue(s.engine.cueing.patternId, intensity)) c.training.vibrateOnPhone(s.engine.cueing.patternId, intensity)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(nav: Nav) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val weekAgo = remember { System.currentTimeMillis() - 7 * 86_400_000L }
    val history by c.db.training().observeSince(weekAgo).collectAsStateWithLifecycle(emptyList())
    var checking by remember { mutableStateOf(false) }
    val t = settings.training

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Training") })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "How it works") {
                Text(
                    "Targeted lucidity reactivation: while awake, you pair one distinctive vibration with the habit of asking \"am I dreaming?\" " +
                        "and noticing your thoughts, body and breath. Late in the night the watch replays that vibration softly during probable REM, " +
                        "hoping the cue reaches the dream and triggers the same question.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Evidence: cueing raised lucid dreaming in lab and app studies, but home results are mixed, and the only wrist-vibration " +
                        "study produced no lucid dreams from wrist cues and frequent awakenings. Treat this as an experiment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "This week") {
                Row {
                    StatTile("Pre-sleep", "${history.count { it.type == TrainingType.PRESLEEP_SESSION }}", Modifier.weight(1f), sub = "sessions")
                    StatTile("Checks", "${history.count { it.type == TrainingType.REALITY_CHECK }}", Modifier.weight(1f), sub = "reality checks")
                    StatTile("Reminders", "${history.count { it.type == TrainingType.REMINDER_SHOWN }}", Modifier.weight(1f), sub = "shown")
                }
            }

            SectionCard(title = "Before sleep (most important)") {
                Text("About 15 minutes in bed: 4 guided pairings, then quiet pairings while you drift off. Repeat after a wake-back-to-bed.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = nav::presleep, modifier = Modifier.fillMaxWidth()) { Text("Start pre-sleep training") }
            }

            SectionCard(title = "Reality check now") {
                if (!checking) {
                    FilledTonalButton(onClick = {
                        checking = true
                        scope.launch { playCue(c, settings) }
                    }) { Text("Feel the cue and check") }
                } else {
                    listOf(
                        "That vibration is your dream signal.",
                        "Ask seriously: am I dreaming right now?",
                        "Read a line of text, look away, read it again. Did it change?",
                        "Look at your hands. Count your fingers.",
                        "Notice your thoughts, your body, your breathing. How is this different from a dream?",
                    ).forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { c.training.log(TrainingType.REALITY_CHECK, "manual"); checking = false }) { Text("Done") }
                        OutlinedButton(onClick = { checking = false }) { Text("Cancel") }
                    }
                }
            }

            SectionCard(title = "During the day") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Reality-check reminders")
                        Text(
                            "At random times your watch plays the night cue and the phone asks: am I dreaming?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(t.remindersEnabled, { v ->
                        scope.launch {
                            c.settings.update { it.copy(training = it.training.copy(remindersEnabled = v)) }
                            c.training.rescheduleReminders()
                        }
                    })
                }
                if (t.remindersEnabled) {
                    LabeledSlider("Per day", t.remindersPerDay.toFloat(), 2f..15f, { "%.0f".format(it) }, { v ->
                        scope.launch { c.settings.update { it.copy(training = it.training.copy(remindersPerDay = v.toInt())) } }
                    }, steps = 12)
                    LabeledSlider("From", t.windowStartHour.toFloat(), 6f..14f, { "%02.0f:00".format(it) }, { v ->
                        scope.launch { c.settings.update { it.copy(training = it.training.copy(windowStartHour = v.toInt())) } }
                    }, steps = 7)
                    LabeledSlider("Until", t.windowEndHour.toFloat(), 15f..23f, { "%02.0f:00".format(it) }, { v ->
                        scope.launch { c.settings.update { it.copy(training = it.training.copy(windowEndHour = v.toInt())) } }
                    }, steps = 7)
                }
                Text(
                    "Optional, and light on battery (a handful of scheduled jobs a day). Daytime checks alone didn't raise lucidity in the largest study (Aspy 2020); their job here is to make the cue familiar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "History") {
                if (history.isEmpty()) Text("No practice logged this week.", style = MaterialTheme.typography.bodySmall)
                history.take(20).forEach {
                    Text("${fmtDateTime(it.t)} · ${it.type.label}" + (if (it.detail.isNotBlank()) " · ${it.detail}" else ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private enum class Phase { INTRO, GUIDED, QUIET, DONE }

/**
 * Pre-sleep pairing session after Konkoly et al. 2024: four cues each followed by guidance and a
 * 45 s pause, then quiet cues every 45-120 s while the user falls asleep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreSleepScreen(nav: Nav) {
    val c = LocalContainer.current
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var phase by remember { mutableStateOf(Phase.INTRO) }
    var pairing by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    val startedAt = remember { mutableStateOf(0L) }
    val guidedCount = 4
    val quietCount = settings.training.presleepPairings

    // Screen on only while there is something to read. During the quiet pairings you are falling
    // asleep: the screen may turn off (cues keep coming), and a lit screen would disturb sleep.
    val view = LocalView.current
    DisposableEffect(phase) {
        view.keepScreenOn = phase == Phase.INTRO || phase == Phase.GUIDED
        onDispose { view.keepScreenOn = false }
    }

    fun finish() {
        if (startedAt.value > 0) {
            c.training.log(
                TrainingType.PRESLEEP_SESSION, "$pairing pairings", ((System.currentTimeMillis() - startedAt.value) / 1000).toInt(), "presleep",
            )
        }
        phase = Phase.DONE
    }

    LaunchedEffect(phase) {
        when (phase) {
            Phase.GUIDED -> {
                startedAt.value = System.currentTimeMillis()
                for (i in 1..guidedCount) {
                    pairing = i
                    playCue(c, settings)
                    for (s in 45 downTo 1) { countdown = s; delay(1000) }
                }
                phase = Phase.QUIET
            }
            Phase.QUIET -> {
                for (i in 1..quietCount) {
                    val wait = Random.nextInt(45, 121)
                    for (s in wait downTo 1) { countdown = s; delay(1000) }
                    pairing = guidedCount + i
                    playCue(c, settings, intensity = 0.5f)
                }
                finish()
            }
            else -> Unit
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Pre-sleep training") },
            navigationIcon = { IconButton(onClick = { if (phase == Phase.GUIDED || phase == Phase.QUIET) finish(); nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                when (phase) {
                    Phase.INTRO -> {
                        Text("Lie down, lights off.", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Each time you feel the vibration, ask yourself whether you are dreaming, then notice your thoughts, your body and your breathing.\n\n" +
                                "Set the intention: \"Next time I'm dreaming, I will feel the signal and remember that I'm dreaming.\"",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "The cue plays on your watch, exactly as it will at night. If the watch isn't in reach, the phone vibrates instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { c.training.log(TrainingType.MILD_INTENTION, "pre-sleep", source = "presleep"); phase = Phase.GUIDED }) { Text("Begin") }
                    }
                    Phase.GUIDED -> {
                        Text("Pairing $pairing of $guidedCount", style = MaterialTheme.typography.labelLarge, color = LucidColors.Rem)
                        Text(
                            "As you notice the signal, you become lucid.\nAm I dreaming?\nNotice your thoughts… your body… your breath.\nHow is this different from a dream?",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text("$countdown s", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Phase.QUIET -> {
                        Text("Quiet pairings", style = MaterialTheme.typography.labelLarge, color = LucidColors.InkMuted)
                        Text("Put the phone down and close your eyes. Keep noticing each signal; it's fine to drift off. The screen can turn off.", textAlign = TextAlign.Center, color = LucidColors.InkFaint)
                        Text("${pairing - guidedCount} of $quietCount · next in $countdown s", style = MaterialTheme.typography.bodySmall, color = LucidColors.InkFaint)
                        OutlinedButton(onClick = { finish() }) { Text("Stop") }
                    }
                    Phase.DONE -> {
                        Text("Done. Sleep well.", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { nav.back() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
