package lucid.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import lucid.core.model.SensorMode
import lucid.core.model.StartMonitoring
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as WearApp).controller
        setContent { MaterialTheme { WatchScreen(controller) } }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
private val Amber = Color(0xFFF2B880)

@Composable
private fun WatchScreen(c: WatchController) {
    val ui by c.ui.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberScalingLazyListState()

    fun startLocal(mode: SensorMode) {
        // Stand-alone start from the watch (no phone command): handy for emulator checks.
        c.pendingStart = StartMonitoring("local-${System.currentTimeMillis()}", mode, scenarioId = "normal", speed = 1f)
        ContextCompat.startForegroundService(context, Intent(context, MonitoringService::class.java))
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startLocal(SensorMode.REAL) }
    val heartPermission = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

    AppScaffold(timeText = { TimeText() }) {
        ScreenScaffold(scrollState = listState) { padding ->
            ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
                item { ListHeader { Text("Lucid") } }
                item {
                    Text(
                        if (ui.monitoring) "Monitoring" + if (ui.simulated) " · SIMULATED" else " · sensors" else "Idle",
                        color = if (ui.simulated) Amber else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Text(
                        ui.heartRate?.takeIf { ui.monitoring && it > 0 }?.let { "%.0f bpm".format(it) } ?: "– bpm",
                        style = MaterialTheme.typography.displaySmall,
                    )
                }
                item {
                    Text(
                        (if (ui.connected) "● " else "○ ") + ui.linkStatus + if (ui.simulatedLinkDown) " (simulated outage)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                item {
                    Text(
                        listOfNotNull(ui.battery?.let { "battery $it%" }, "buffered ${ui.buffered}", ui.sourceLabel.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
                if (ui.monitoring) {
                    item {
                        Button(onClick = { context.stopService(Intent(context, MonitoringService::class.java)) }, modifier = Modifier.fillMaxWidth(), label = { Text("Stop") })
                    }
                } else {
                    item { Button(onClick = { startLocal(SensorMode.SIMULATED) }, modifier = Modifier.fillMaxWidth(), label = { Text("Start simulated") }) }
                    item {
                        Button(
                            onClick = { permissions.launch(arrayOf(heartPermission, Manifest.permission.ACTIVITY_RECOGNITION)) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            label = { Text("Start sensors") },
                        )
                    }
                }
                item {
                    Button(onClick = { c.testVibration() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors(), label = { Text("Test vibration") })
                }
                item {
                    Button(
                        onClick = { c.useDevBridge = !c.useDevBridge },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        label = { Text(if (c.useDevBridge) "Link: dev bridge" else "Link: Data Layer") },
                    )
                }
                if (ui.cues.isNotEmpty()) item { ListHeader { Text("Cues") } }
                ui.cues.forEach { e ->
                    item {
                        Text(
                            "${Instant.ofEpochMilli(e.t).atZone(ZoneId.systemDefault()).format(timeFmt)} ${e.purpose.name.lowercase()}: ${e.outcome}",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                if (c.cues.isEmulator) {
                    item { Text("Emulator: vibration calls succeed but nothing physical happens.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}
