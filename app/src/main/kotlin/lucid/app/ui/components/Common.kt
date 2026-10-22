package lucid.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lucid.app.ui.LucidColors
import lucid.app.ui.NumberStyle
import lucid.core.model.DataOrigin
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every place that shows measurements says where they came from. Icon + label, never colour alone. */
@Composable
fun OriginBadge(origin: DataOrigin?, modifier: Modifier = Modifier) {
    val (label, color, icon) = when (origin) {
        DataOrigin.SIMULATED -> Triple("SIMULATED", LucidColors.Dawn, Icons.Filled.Science)
        DataOrigin.REPLAYED -> Triple("REPLAY", LucidColors.Dawn, Icons.Filled.Science)
        DataOrigin.REAL_DEVICE -> Triple("REAL WATCH", MaterialTheme.colorScheme.secondary, Icons.Filled.Watch)
        null -> Triple("SOURCE UNKNOWN", LucidColors.InkMuted, Icons.Filled.Watch)
    }
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

enum class Status { GOOD, WARNING, CRITICAL }

@Composable
fun StatusLabel(status: Status, text: String, modifier: Modifier = Modifier) {
    val (color, icon) = when (status) {
        Status.GOOD -> LucidColors.Good to Icons.Filled.CheckCircle
        Status.WARNING -> LucidColors.Warning to Icons.Filled.Warning
        Status.CRITICAL -> LucidColors.Critical to Icons.Filled.Error
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

fun qualityStatus(q: Float?): Status = when {
    q == null -> Status.WARNING
    q >= 0.75f -> Status.GOOD
    q >= 0.5f -> Status.WARNING
    else -> Status.CRITICAL
}

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall.merge(NumberStyle), fontWeight = FontWeight.Normal)
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StageLegend(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Wake" to LucidColors.Wake, "REM" to LucidColors.Rem, "Light" to LucidColors.Light, "Deep" to LucidColors.Deep).forEach { (l, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(8.dp).background(c, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    help: String? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) = Spacer(modifier.size(8.dp).background(color, CircleShape))

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")

fun fmtTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
fun fmtDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFmt)
fun fmtDateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTimeFmt)
fun fmtMinutes(min: Float?): String = if (min == null) "–" else "%dh %02dm".format((min / 60).toInt(), (min % 60).toInt())
