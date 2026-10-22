package lucid.wear

import kotlinx.coroutines.runBlocking
import lucid.core.model.HeartRateSample
import lucid.core.model.SampleBatch
import lucid.core.model.SampleQuality
import lucid.core.model.WireMessage
import lucid.wear.sensors.MotionSummarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UplinkTest {
    private fun hr(t: Long) = HeartRateSample(t, 60f, SampleQuality.GOOD)

    private class FakeLink {
        var up = true
        val delivered = ArrayList<SampleBatch>()
        suspend fun send(m: WireMessage): Boolean {
            if (!up) return false
            delivered += m as SampleBatch
            return true
        }
    }

    @Test
    fun `acknowledged batches are forgotten`() = runBlocking {
        val link = FakeLink()
        var now = 0L
        val u = Uplink("s", link::send, clock = { now }, realTime = { now })
        u.add(listOf(hr(1), hr(2)))
        assertTrue(u.flush(true))
        assertEquals(2, u.buffered)
        u.onAck(link.delivered.last().seq)
        assertEquals(0, u.buffered)
    }

    @Test
    fun `missing acknowledgement requeues data and reports a dead link`() = runBlocking {
        val link = FakeLink()
        var now = 0L
        val u = Uplink("s", link::send, clock = { now }, realTime = { now }, ackTimeoutMs = 15_000)
        u.add(listOf(hr(1), hr(2)))
        u.flush(true)
        now = 16_000
        u.add(listOf(hr(3)))
        assertFalse("half-open link detected", u.flush(true))
        // After reconnecting, everything goes out again in order, the old part flagged as backfill.
        link.delivered.clear()
        assertTrue(u.flush(true))
        assertEquals(listOf(1L, 2L, 3L), link.delivered.flatMap { b -> b.samples.map { it.t } })
        assertTrue(link.delivered.first().backfill)
    }

    @Test
    fun `failed sends keep order across an outage`() = runBlocking {
        val link = FakeLink()
        var now = 0L
        val u = Uplink("s", link::send, clock = { now }, realTime = { now }, chunk = 2)
        link.up = false
        u.add((1L..5L).map(::hr))
        assertFalse("send failure reports a dead link", u.flush(true))
        u.add((6L..7L).map(::hr))
        u.flush(false)
        link.up = true
        u.add(listOf(hr(8)))
        assertTrue(u.flush(true))
        assertEquals((1L..8L).toList(), link.delivered.flatMap { b -> b.samples.map { it.t } })
        assertTrue(link.delivered.all { it.samples.size <= 2 })
        link.delivered.forEach { u.onAck(it.seq) }
        assertEquals(0, u.buffered)
    }

    @Test
    fun `backlog is bounded`() = runBlocking {
        val link = FakeLink()
        val u = Uplink("s", link::send, clock = { 0 }, realTime = { 0 }, capacity = 10)
        u.add((1L..25L).map(::hr))
        u.flush(false)
        assertEquals(10, u.buffered)
        assertEquals(15, u.dropped)
    }

    @Test
    fun `motion summary per second`() {
        val m = MotionSummarizer()
        // one second lying still (1 g on z), then one second with a 0.2 g wobble
        for (i in 0 until 25) assertEquals(null, m.add(i * 40L, 0f, 0f, 9.80665f))
        var first: lucid.core.model.MotionSample? = null
        for (i in 0 until 25) m.add(1000 + i * 40L, 0f, 0f, 9.80665f * if (i % 2 == 0) 1.2f else 0.8f)?.let { first = it }
        val still = first!!
        assertEquals(0L, still.t)
        assertEquals(0f, still.activityMg, 0.5f)
        val moving = m.close()!!
        assertEquals(1000L, moving.t)
        assertEquals(200f, moving.activityMg, 1f)
    }
}
