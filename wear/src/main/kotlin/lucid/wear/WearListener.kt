package lucid.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import lucid.core.model.Wire

/**
 * Receives Data Layer messages while the watch app is not in the foreground (e.g. the phone
 * starts a night) and hands them to the controller.
 */
class WearListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Wire.DATA_LAYER_PATH) return
        val controller = (application as WearApp).controller
        if (controller.useDevBridge) return
        // Only messages from a directly connected phone (see DataLayerPhoneLink).
        val nearby = runCatching {
            com.google.android.gms.tasks.Tasks.await(
                com.google.android.gms.wearable.Wearable.getNodeClient(this).connectedNodes,
            ).any { it.id == event.sourceNodeId && it.isNearby }
        }.getOrDefault(false)
        if (!nearby) return
        val msg = runCatching { Wire.decodeBytes(event.data) }.getOrNull() ?: return
        controller.scope.launch { controller.handle(msg) }
    }
}
