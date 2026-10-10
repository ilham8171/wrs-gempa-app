package id.wrsgempa.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.Locale

/**
 * Receives WRS GEMPA push messages. When a payload contains event parameters,
 * tapping its notification opens that earthquake detail; warning-only pushes
 * open the official tsunami dashboard instead.
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
        val data = message.data.toMutableMap()
        // Some senders package event fields into one JSON-valued data key.
        data["quake"]?.let { raw ->
            runCatching {
                val objectData = JSONObject(raw)
                objectData.keys().forEach { key ->
                    if (!data.containsKey(key)) data[key] = objectData.optString(key)
                }
            }
        }

        val title = message.notification?.title
            ?: data["title"]
            ?: "Peringatan WRS GEMPA"
        val body = message.notification?.body
            ?: data["body"]
            ?: data["message"]
            ?: "Ada pembaruan informasi gempa. Buka WRS GEMPA untuk melihat detail."
        showNotification(title, body, data)
        announceAndVibrate(title, body, data)
    }

    private fun announceAndVibrate(title: String, body: String, data: Map<String, String>) {
        val category = first(data, "category", "type").lowercase(Locale.ROOT)
        val mag = first(data, "magnitude", "Magnitude", "mag").toDoubleOrNull() ?: 0.0
        val tsunami = category.contains("tsunami") ||
            title.contains("tsunami", ignoreCase = true) ||
            body.contains("peringatan dini tsunami", ignoreCase = true) ||
            body.contains("warning tsunami", ignoreCase = true)

        // Vibration runs for both foreground and background FCM delivery.
        val pattern = when {
            tsunami -> longArrayOf(0, 900, 180, 900, 180, 1200, 250, 1200)
            mag >= 6.0 -> longArrayOf(0, 700, 150, 700, 150, 900)
            mag >= 4.5 -> longArrayOf(0, 450, 180, 450, 180, 650)
            mag > 0.0 -> longArrayOf(0, 180, 150, 180)
            else -> longArrayOf(0, 250, 150, 250)
        }
        runCatching {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(pattern, -1)
                }
            }
        }

        // Speak alert details aloud; Android TTS uses the Indonesian voice if installed.
        runCatching {
            val prefs = getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("tts_enabled", true)) return@runCatching
            val spoken = if (tsunami) {
                "Peringatan tsunami. Ikuti instruksi resmi BMKG dan arahan evakuasi. $body"
            } else {
                val magnitudeText = if (mag > 0) "Magnitudo $mag. " else ""
                "Peringatan gempa. $magnitudeText$body"
            }
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val languageResult = engine.setLanguage(Locale("id", "ID"))
                    if (languageResult >= TextToSpeech.LANG_AVAILABLE) {
                        engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "wrs-alert-${System.currentTimeMillis()}")
                    }
                }
            }
        }
    }

    private fun showNotification(title: String, body: String, data: Map<String, String>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(QUAKE_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(QUAKE_CHANNEL_ID, "Notifikasi Gempa", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Pembaruan gempa dari WRS GEMPA"
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 250, 120, 250)
                    }
                )
            }
            if (manager.getNotificationChannel(TSUNAMI_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(TSUNAMI_CHANNEL_ID, "Peringatan Tsunami", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Peringatan tsunami. Ikuti instruksi resmi BMKG/InaTEWS."
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 900)
                        setSound(
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                    }
                )
            }
        }

        val category = first(data, "category", "type").lowercase(Locale.ROOT)
        val tsunamiAlert = category.contains("tsunami") ||
            title.lowercase(Locale.ROOT).contains("tsunami") ||
            body.lowercase(Locale.ROOT).contains("peringatan dini tsunami") ||
            body.lowercase(Locale.ROOT).contains("warning tsunami")
        val channelId = if (tsunamiAlert) TSUNAMI_CHANNEL_ID else QUAKE_CHANNEL_ID
        val eventId = first(data, "quake_id", "event_id", "eventId", "eventid", "key", "id", "fingerprint")
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (eventId.isNotBlank()) putExtra("quake_id", eventId)
            copyExtra(this, data, "quake_time", "time", "DateTime", "datetime", "timestamp")
            copyExtra(this, data, "quake_magnitude", "magnitude", "Magnitude", "mag")
            copyExtra(this, data, "quake_place", "place", "location", "Wilayah", "wilayah")
            copyExtra(this, data, "quake_latitude", "lat", "latitude")
            copyExtra(this, data, "quake_longitude", "lon", "longitude")
            copyExtra(this, data, "quake_depth", "depth", "Kedalaman")
            copyExtra(this, data, "quake_potential", "potential", "Potensi", "headline")
            copyExtra(this, data, "quake_felt", "felt", "Dirasakan")
            copyExtra(this, data, "quake_shakemap", "shakemap", "Shakemap")
            copyExtra(this, data, "quake_source", "source", "status")

            val category = first(data, "category", "type").lowercase(Locale.ROOT)
            val tsunamiAlert = category.contains("tsunami") ||
                title.lowercase(Locale.ROOT).contains("tsunami") ||
                body.lowercase(Locale.ROOT).contains("peringatan tsunami")
            if (tsunamiAlert && eventId.isBlank()) putExtra("open_tsunami_dashboard", true)
        }

        val notificationKey = channelId + "|" + if (eventId.isNotBlank()) eventId else System.currentTimeMillis().toString()
        val notificationId = notificationKey.hashCode().let { if (it == 0) 7301 else it }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (_: SecurityException) {
            // Android 13+: notification permission has not been granted.
        }
    }

    private fun first(data: Map<String, String>, vararg keys: String): String =
        keys.firstNotNullOfOrNull { key -> data[key]?.takeIf { it.isNotBlank() } }.orEmpty()

    private fun copyExtra(intent: Intent, data: Map<String, String>, target: String, vararg keys: String) {
        first(data, *keys).takeIf { it.isNotBlank() }?.let { intent.putExtra(target, it) }
    }

    companion object {
        private const val TOPIC = "wrs-gempa-alerts"
        private const val QUAKE_CHANNEL_ID = "wrs_gempa_fcm_v2"
        private const val TSUNAMI_CHANNEL_ID = "wrs_tsunami_fcm_v2"
    }
}
