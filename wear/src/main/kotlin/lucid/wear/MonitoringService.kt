package lucid.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status

/**
 * Foreground service (type "health") that keeps sensor collection alive overnight. Galaxy Watch
 * pauses apps without one about a minute after the screen turns off. Holds a partial wake lock
 * so batching/flush timers keep running; its battery cost must be measured on the real watch.
 */
class MonitoringService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sleep monitoring", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Lucid is monitoring")
            .setContentText("Heart rate and movement go to your phone")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
        OngoingActivity.Builder(this, ID, builder)
            .setStaticIcon(R.drawable.ic_moon)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("Monitoring sleep").build())
            .build()
            .apply(this)
        ServiceCompat.startForeground(this, ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lucid:monitoring").apply {
                acquire(12 * 60 * 60 * 1000L)
            }
        }
        (application as WearApp).controller.beginMonitoring()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        (application as WearApp).controller.endMonitoring()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "monitoring"
        const val ID = 1
    }
}
