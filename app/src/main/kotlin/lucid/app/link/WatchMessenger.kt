package lucid.app.link

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import lucid.core.model.CueCommand
import lucid.core.model.CuePurpose
import lucid.core.model.VibrationPattern
import lucid.core.model.Wire
import lucid.core.model.WireMessage
import java.util.UUID

/**
 * One-shot messages to the watch outside a recorded night (training cues, reminders, tests).
 * Same rules as the night link: Data Layer, nearby watch only, nothing relayed through the cloud.
 */
class WatchMessenger(context: Context) {
    private val messages = Wearable.getMessageClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)

    suspend fun send(message: WireMessage): Boolean = runCatching {
        val node = capabilities.getCapability(Wire.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.firstOrNull { it.isNearby } ?: return false
        messages.sendMessage(node.id, Wire.DATA_LAYER_PATH, Wire.encodeBytes(message)).await()
        true
    }.getOrDefault(false)

    /** Vibrate the cue pattern on the wrist. Returns false when no watch is in reach. */
    suspend fun cue(patternId: String, intensity: Float, purpose: CuePurpose): Boolean {
        val now = System.currentTimeMillis()
        return send(
            CueCommand(
                cueId = "${purpose.name.lowercase()}-${UUID.randomUUID()}", sessionId = null,
                pattern = VibrationPattern.byId(patternId), intensity = intensity, repeats = 1,
                issuedAt = now, expiresAt = now + 20_000, purpose = purpose, sentWallMs = now,
            ),
        )
    }
}
