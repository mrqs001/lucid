package lucid.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    @Test
    fun `every message type survives a round trip`() {
        val source = SourceInfo(DataOrigin.SIMULATED, "sim", "sim", "normal", 1)
        val caps = WatchCapabilities(true, true, true, false, false, true)
        val messages = listOf(
            Hello(source = source, capabilities = caps),
            SampleBatch(
                3, 1000,
                listOf(
                    HeartRateSample(1, 60f, SampleQuality.GOOD),
                    IbiSample(2, 1000, SampleQuality.FAIR),
                    MotionSample(3, 4.5f, 0.2f),
                    SensorStatusSample(4, SensorStatus.OFF_WRIST),
                ),
                backfill = true,
            ),
            WatchStatus(5, true, 80, false, 12, "s1"),
            CueAck("c1", 6, CueOutcome.EMULATED, "x"),
            StartMonitoring("s1", SensorMode.SIMULATED, "normal", 1, 60f),
            StopMonitoring("s1"),
            CueCommand("c1", "s1", VibrationPattern.TlrTriple, 0.4f, 2, 7, 8, CuePurpose.TEST),
            Ping(9),
        )
        for (m in messages) assertEquals(m, Wire.decode(Wire.encode(m)))
        assertTrue(Wire.encode(Ping(1)).contains("\"type\":\"ping\""))
    }

    @Test
    fun `waveform scales amplitude with intensity and repeats`() {
        val (timings, amps) = VibrationPattern.TlrTriple.toWaveform(intensity = 0.5f, repeats = 2)
        assertEquals(1 + 2 * 3 * 2, timings.size)
        assertEquals(timings.size, amps.size)
        assertEquals(127, amps[1])
        assertEquals(0, amps[2])
    }
}
