package lucid.app

import lucid.app.training.TrainingCoordinator
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

class ReminderScheduleTest {
    private fun next(now: LocalDateTime, seed: Int) =
        now.plusNanos(TrainingCoordinator.nextReminderDelayMs(now, 9, 21, 8, Random(seed)) * 1_000_000)

    @Test
    fun `reminders stay inside the daily window`() {
        for (h in 0..23) for (seed in 0..20) {
            val now = LocalDateTime.of(2026, 10, 22, h, 30)
            val t = next(now, seed)
            assertTrue("$now -> $t", t.isAfter(now))
            assertTrue("$now -> $t outside window", t.hour in 9..20)
        }
    }

    @Test
    fun `evening reminders roll over to the next morning`() {
        val now = LocalDateTime.of(2026, 10, 22, 22, 0)
        val t = next(now, 1)
        assertTrue(t.toLocalDate() == now.toLocalDate().plusDays(1) && t.hour in 9..10)
    }
}
