package lucid.wear

import lucid.core.model.SampleBatch
import lucid.core.model.SensorSample
import lucid.core.model.WireMessage

/**
 * Batches samples for the phone and guarantees they arrive.
 *
 * Every batch stays "in flight" until the phone acknowledges its sequence number. A failed send,
 * or no acknowledgement within [ackTimeoutMs] (a half-open connection), moves the unacknowledged
 * samples back into the backlog and reports the link as dead so the caller can reconnect. After a
 * reconnection the backlog goes first, flagged as backfill. The phone drops anything it already
 * has (timestamps must strictly increase per channel), so a resend never double counts.
 *
 * The backlog is in memory only: if the watch app process dies, unsent data is lost.
 */
class Uplink(
    private val sessionId: String?,
    private val send: suspend (WireMessage) -> Boolean,
    private val clock: () -> Long,
    private val realTime: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 250_000,
    private val chunk: Int = 600,
    private val ackTimeoutMs: Long = 15_000,
    private val maxInFlight: Int = 64,
) {
    private class InFlight(val seq: Long, val sentAt: Long, val samples: List<SensorSample>)

    private val pending = ArrayList<SensorSample>()
    private val backlog = ArrayDeque<SensorSample>()
    private val inFlight = ArrayDeque<InFlight>()
    private var seq = 0L
    var dropped = 0
        private set

    val buffered: Int get() = synchronized(this) { backlog.size + pending.size + inFlight.sumOf { it.samples.size } }

    fun add(samples: List<SensorSample>) = synchronized(this) { pending += samples }

    /** The phone confirmed [seq]; batches are delivered in order, so everything up to it arrived. */
    fun onAck(seq: Long) = synchronized(this) {
        while (inFlight.isNotEmpty() && inFlight.first().seq <= seq) inFlight.removeFirst()
    }

    /**
     * Try to deliver. Returns false when the link should be considered dead (send failure or ack
     * timeout); unacknowledged data is then back in the backlog, in order.
     */
    suspend fun flush(linkUsable: Boolean): Boolean {
        var oldRemaining: Int
        synchronized(this) {
            val live = pending.toList()
            pending.clear()
            val oldest = inFlight.firstOrNull()
            val timedOut = oldest != null && realTime() - oldest.sentAt > ackTimeoutMs
            if (!linkUsable || timedOut) requeueInFlight()
            oldRemaining = backlog.size
            park(live)
            if (!linkUsable) return true
            if (timedOut) return false
        }
        while (true) {
            val part = synchronized(this) {
                if (backlog.isEmpty() || inFlight.size >= maxInFlight) null else List(minOf(chunk, backlog.size)) { backlog.removeFirst() }
            } ?: return true
            val backfill = oldRemaining > 0
            oldRemaining -= part.size
            val s = synchronized(this) { seq++.also { inFlight.addLast(InFlight(it, realTime(), part)) } }
            if (!send(SampleBatch(s, clock(), part, backfill, sessionId))) {
                synchronized(this) { requeueInFlight() }
                return false
            }
        }
    }

    /** Unacknowledged batches go back to the front of the backlog, oldest first. */
    private fun requeueInFlight() {
        val samples = inFlight.flatMap { it.samples }
        inFlight.clear()
        backlog.addAll(0, samples)
        trim()
    }

    private fun park(samples: List<SensorSample>) {
        backlog.addAll(samples)
        trim()
    }

    private fun trim() {
        while (backlog.size > capacity) {
            backlog.removeFirst()
            dropped++
        }
    }
}
