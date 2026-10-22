package lucid.app.data

/** Battery use of one device over a night. Only computed across a stretch without charging. */
data class BatteryDrain(val device: String, val startPct: Int, val endPct: Int, val hours: Float) {
    val used: Int get() = startPct - endPct
    val pctPerHour: Float get() = if (hours > 0f) used / hours else 0f
}

fun List<BatteryEntity>.drain(device: String): BatteryDrain? {
    val rows = filter { it.device == device }
    if (rows.size < 2) return null
    // Use the last stretch without charging, so plugging in at dawn doesn't hide the night's use.
    val start = rows.indexOfLast { it.charging == true }.let { if (it < 0) 0 else it + 1 }
    val stretch = rows.drop(start)
    if (stretch.size < 2) return null
    val hours = (stretch.last().t - stretch.first().t) / 3_600_000f
    if (hours < 0.1f) return null
    return BatteryDrain(device, stretch.first().pct, stretch.last().pct, hours)
}
