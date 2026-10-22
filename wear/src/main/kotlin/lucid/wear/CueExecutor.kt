package lucid.wear

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import lucid.core.model.CueAck
import lucid.core.model.CueCommand
import lucid.core.model.CueOutcome
import kotlin.math.roundToInt

/**
 * Plays cue commands on the watch's vibrator and reports exactly what happened. On the emulator
 * the vibrator API accepts calls but nothing physical happens: reported as EMULATED.
 */
class CueExecutor(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    val hasVibrator: Boolean = vibrator?.hasVibrator() == true
    val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true
    val isEmulator: Boolean = Build.FINGERPRINT.contains("generic") || Build.HARDWARE in setOf("ranchu", "goldfish") ||
        Build.PRODUCT.contains("sdk")

    fun execute(cmd: CueCommand, now: Long): CueAck {
        if (now > cmd.expiresAt) return CueAck(cmd.cueId, now, CueOutcome.EXPIRED, "arrived ${(now - cmd.expiresAt) / 1000} s after expiry")
        val v = vibrator
        if (v == null || !hasVibrator) return CueAck(cmd.cueId, now, CueOutcome.NO_VIBRATOR, "device reports no vibrator")
        // Without amplitude control, intensity is expressed as cue length (more pulse repeats).
        val repeats = if (hasAmplitudeControl) cmd.repeats else cmd.repeats * (1 + (cmd.intensity * 2).roundToInt())
        val (timings, amplitudes) = cmd.pattern.toWaveform(cmd.intensity, repeats)
        return runCatching {
            val effect = if (hasAmplitudeControl) VibrationEffect.createWaveform(timings, amplitudes, -1) else VibrationEffect.createWaveform(timings, -1)
            if (Build.VERSION.SDK_INT >= 33) {
                // Alarm usage so bedtime / do-not-disturb modes are less likely to swallow the cue (verify on hardware).
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION") v.vibrate(effect)
            }
            val detail = "${cmd.pattern.id} x$repeats, ${timings.sum()} ms, " +
                (if (hasAmplitudeControl) "amplitude ${(cmd.intensity * 100).roundToInt()}%" else "no amplitude control: on/off pattern")
            CueAck(cmd.cueId, now, if (isEmulator) CueOutcome.EMULATED else CueOutcome.VIBRATED, detail)
        }.getOrElse { CueAck(cmd.cueId, now, CueOutcome.ERROR, it.message ?: it.toString()) }
    }
}
