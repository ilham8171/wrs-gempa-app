package id.wrsgempa.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class EarthquakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val connection = URL("https://data.bmkg.go.id/DataMKG/TEWS/autogempa.json").openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val quake = try {
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    .getJSONObject("Infogempa").getJSONObject("gempa")
            } finally {
                connection.disconnect()
            }
            val magnitude = quake.optString("Magnitude", "0").replace(",", ".").toDoubleOrNull() ?: 0.0
            if (magnitude < 5.0) return Result.success()

            val prefs = applicationContext.getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE)
            val fingerprint = quake.optString("Tanggal") + "|" + quake.optString("Jam") + "|" + quake.optString("Magnitude")
            if (prefs.getString("last_alert_fingerprint", "") == fingerprint) return Result.success()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return Result.success()

            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel("wrs_gempa_alerts", "Peringatan Gempa", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Peringatan gempa besar dari data BMKG"
                    }
                )
            }
            val title = "Gempa BMKG M " + magnitude
            val body = quake.optString("Wilayah", "Lokasi tidak tersedia") + " • " +
                quake.optString("Kedalaman", "Kedalaman tidak tersedia") + "\n" +
                quake.optString("Potensi", "Periksa informasi resmi BMKG")
            val notification = NotificationCompat.Builder(applicationContext, "wrs_gempa_alerts")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(5107, notification)
            prefs.edit().putString("last_alert_fingerprint", fingerprint).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
