package lucid.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import lucid.app.MainActivity
import lucid.app.R

/**
 * Keeps the process in the foreground while a night is recorded. The session itself lives in
 * [SessionManager]; this service only holds the foreground slot and shows the ongoing notice.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Night recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Recording the night")
            .setContentText("Processing watch data and deciding on cues")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
