package lucid.app

import lucid.app.data.BatteryEntity
import lucid.app.data.drain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryDrainTest {
    private fun b(minute: Int, pct: Int, charging: Boolean? = false, device: String = "watch") =
        BatteryEntity(sessionId = "s", t = minute * 60_000L, device = device, pct = pct, charging = charging)

    @Test
    fun `drain per hour over the night`() {
        val d = listOf(b(0, 90), b(120, 84), b(480, 66), b(0, 50, device = "phone")).drain("watch")!!
        assertEquals(24, d.used)
        assertEquals(3f, d.pctPerHour, 1e-4f)
    }

    @Test
    fun `charging at dawn does not hide the night`() {
        val d = listOf(b(0, 50, charging = true), b(10, 52, charging = true), b(20, 52), b(380, 40)).drain("watch")!!
        assertEquals(52, d.startPct)
        assertEquals(2f, d.pctPerHour, 1e-4f)
    }

    @Test
    fun `summary text renders real battery numbers`() {
        // Regression: the first real night crashed the app because "80% →" was passed to format().
        val d = listOf(b(0, 80), b(474, 52)).drain("watch")!!
        assertEquals("80% → 52% over 7.9 h · 3.5 %/h", d.summary())
        assertEquals("100% → 0% over 10.0 h · 10.0 %/h", listOf(b(0, 100), b(600, 0)).drain("watch")!!.summary())
    }

    @Test
    fun `too little data gives no estimate`() {
        assertNull(listOf(b(0, 90)).drain("watch"))
        assertNull(listOf(b(0, 90), b(3, 90)).drain("watch"))
    }
}
