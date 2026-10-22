package lucid.app.training

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import lucid.app.LucidApp
import lucid.app.R
import lucid.app.data.LucidDatabase
import lucid.app.data.SettingsRepository
import lucid.app.data.TrainingEntity
import lucid.app.data.TrainingType
import lucid.app.link.WatchMessenger
import lucid.core.model.VibrationPattern
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Waking training: plays the same vibration cue used at night and logs practice.
 *
 * The evidence-backed part is the pre-sleep pairing session (Konkoly et al. 2024: cue followed by
 * guidance to notice thoughts, body and breath, repeated before sleep). Daytime reminders are
 * optional: in Aspy 2020, adding daytime reality testing did not improve lucidity on its own.
 */
class TrainingCoordinator(
    private val context: Context,
    private val db: LucidDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val watch: WatchMessenger,
) {
    fun vibrateOnPhone(patternId: String, intensity: Float = 0.7f) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        val (timings, amplitudes) = VibrationPattern.byId(patternId).toWaveform(intensity)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings, amplitudes, -1)
        } else {
            VibrationEffect.createWaveform(timings, -1)
        }
        vibrator.vibrate(effect)
    }

    fun log(type: TrainingType, detail: String = "", durationSec: Int = 0, source: String = "manual") {
        scope.launch { db.training().insert(TrainingEntity(t = System.currentTimeMillis(), type = type, detail = detail, durationSec = durationSec, source = source)) }
    }

    suspend fun rescheduleReminders() {
        val wm = WorkManager.getInstance(context)
        val t = settings.current().training
        if (!t.remindersEnabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val delayMs = nextReminderDelayMs(LocalDateTime.now(), t.windowStartHour, t.windowEndHour, t.remindersPerDay, Random.Default)
        wm.enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build(),
        )
    }

    /**
     * A daytime reality-check reminder. The cue vibrates on the wrist (the same place and pattern as
     * at night), and the phone shows the prompt. If no watch is in reach, the phone vibrates instead.
     */
    suspend fun remind(patternId: String) {
        val onWatch = watch.cue(patternId, 0.7f, lucid.core.model.CuePurpose.TRAINING)
        postReminder(patternId, vibratePhone = !onWatch)
    }

    fun postReminder(patternId: String, vibratePhone: Boolean = true) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val (timings, _) = VibrationPattern.byId(patternId).toWaveform(1f)
        // Channel vibration is fixed at creation, so the channel id carries the pattern id.
        val channelId = if (vibratePhone) "cue_reminder_$patternId" else "cue_reminder_silent"
        nm.createNotificationChannel(
            if (vibratePhone) {
                NotificationChannel(channelId, "Cue reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = timings
                    description = "The night cue, during the day, as a prompt to check whether you are dreaming"
                }
            } else {
                // The watch already vibrated the cue; the phone just shows the prompt.
                NotificationChannel(channelId, "Cue reminders (watch vibrates)", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    enableVibration(false)
                    setSound(null, null)
                }
            },
        )
        val done = PendingIntent.getBroadcast(
            context, 0, Intent(context, RealityCheckReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("That's the cue. Am I dreaming?")
            .setContentText("Look at your hands, read something twice, notice your thoughts and body.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "This vibration is the same one you'll feel tonight. Pause: is anything odd? " +
                        "Read text twice, check your hands, notice your breath and thoughts. Then tap Done.",
                ),
            )
            .addAction(0, "Done", done)
            .setAutoCancel(true)
            // Not mirrored to the watch: the wrist gets the real cue pattern, not a generic buzz.
            .setLocalOnly(true)
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val WORK = "cue_reminder"
        const val NOTIFICATION_ID = 20

        /**
         * Random delay to the next reminder inside the daily window, averaging [perDay] reminders.
         * Pure function so it can be unit tested.
         */
        fun nextReminderDelayMs(now: LocalDateTime, startHour: Int, endHour: Int, perDay: Int, rnd: Random): Long {
            val windowMin = ((endHour - startHour).coerceAtLeast(1)) * 60
            val meanGapMin = windowMin.toDouble() / perDay.coerceAtLeast(1)
            var candidate = now.plusMinutes((meanGapMin * (0.5 + rnd.nextDouble())).toLong().coerceAtLeast(10))
            if (candidate.hour >= endHour || candidate.toLocalDate() != now.toLocalDate()) {
                val nextDay = now.toLocalDate().plusDays(if (now.hour < startHour) 0 else 1)
                candidate = nextDay.atTime(startHour, 0).plusMinutes(rnd.nextLong(0, (meanGapMin.toLong()).coerceAtLeast(1)))
            } else if (candidate.hour < startHour) {
                candidate = now.toLocalDate().atTime(startHour, 0).plusMinutes(rnd.nextLong(0, (meanGapMin.toLong()).coerceAtLeast(1)))
            }
            val zone = ZoneId.systemDefault()
            return (candidate.atZone(zone).toInstant().toEpochMilli() - now.atZone(zone).toInstant().toEpochMilli()).coerceAtLeast(60_000)
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as LucidApp).container
        val s = c.settings.current()
        if (!s.training.remindersEnabled) return Result.success()
        c.training.remind(s.engine.cueing.patternId)
        c.training.log(TrainingType.REMINDER_SHOWN, source = "reminder")
        c.training.rescheduleReminders()
        return Result.success()
    }
}

/** "Done" on a reminder notification: logs a completed reality check. */
class RealityCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as LucidApp).container
        c.training.log(TrainingType.REALITY_CHECK, "from reminder", source = "reminder")
        NotificationManagerCompat.from(context).cancel(TrainingCoordinator.NOTIFICATION_ID)
    }
}
