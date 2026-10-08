package id.wrsgempa.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM messages and joins the WRS GEMPA broadcast topic.
 * The backend must send FCM messages to topic "wrs-gempa-alerts"
 * for cloud push notifications to reach subscribed devices.
 */
class WrsFirebaseMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "Peringatan WRS GEMPA"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: message.data["message"]
            ?: "Ada pembaruan informasi gempa. Buka WRS GEMPA untuk melihat detail."
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Peringatan WRS GEMPA", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Peringatan gempa dan tsunami dari WRS GEMPA"
                }
            )
        }
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 7301, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        } catch (_: SecurityException) {
            // Android 13+: notification permission has not been granted.
        }
    }

    companion object {
        private const val TOPIC = "wrs-gempa-alerts"
        private const val CHANNEL_ID = "wrs_gempa_fcm"
    }
}
