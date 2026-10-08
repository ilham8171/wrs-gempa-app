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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class EarthquakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private fun fetch(path: String): JSONObject {
        val connection = URL("https://data.bmkg.go.id/DataMKG/TEWS/$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        return try {
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun firstQuake(root: JSONObject): JSONObject? {
        val value = root.optJSONObject("Infogempa")?.opt("gempa")
        return when (value) {
            is JSONObject -> value
            is JSONArray -> value.optJSONObject(0)
            else -> null
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE)
            val latest = firstQuake(fetch("autogempa.json"))
            val candidates = mutableListOf<Triple<String, String, JSONObject>>()

            if (latest != null && prefs.getBoolean("big_alerts", true)) {
                val magnitude = latest.optString("Magnitude", "0").replace(",", ".").toDoubleOrNull() ?: 0.0
                val threshold = prefs.getString("min_magnitude", "4.0")?.toDoubleOrNull() ?: 4.0
                if (magnitude >= threshold) {
                    val fingerprint = latest.optString("Tanggal") + "|" + latest.optString("Jam") + "|" + latest.optString("Magnitude")
                    candidates.add(Triple("Gempa BMKG M $magnitude", "large|$fingerprint", latest))
                }
            }

            if (prefs.getBoolean("tsunami_alerts", true) && latest != null) {
                val potential = latest.optString("Potensi", "")
                val normalized = potential.lowercase()
                if (normalized.contains("berpotensi tsunami") && !normalized.contains("tidak berpotensi tsunami")) {
                    val fingerprint = latest.optString("Tanggal") + "|" + latest.optString("Jam") + "|" + potential
                    candidates.add(Triple("Periksa informasi potensi tsunami", "tsunami|$fingerprint", latest))
                }
            }

            if (prefs.getBoolean("felt_alerts", true)) {
                val felt = firstQuake(fetch("gempadirasakan.json"))
                if (felt != null) {
                    val fingerprint = felt.optString("Tanggal") + "|" + felt.optString("Jam") + "|" +
                        felt.optString("Magnitude") + "|" + felt.optString("Wilayah")
                    candidates.add(Triple("Gempa dirasakan • M " + felt.optString("Magnitude"), "felt|$fingerprint", felt))
                }
            }

            val candidate = candidates.firstOrNull { prefs.getString("last_${it.second.substringBefore('|')}_fingerprint", "") != it.second }
                ?: return Result.success()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return Result.success()

            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel("wrs_gempa_alerts", "Peringatan Gempa", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Informasi gempa dan potensi tsunami dari data BMKG"
                    }
                )
            }
            val quake = candidate.third
            val body = quake.optString("Wilayah", "Lokasi tidak tersedia") + " • " +
                quake.optString("Kedalaman", "Kedalaman tidak tersedia") + "\n" +
                quake.optString("Potensi", "Periksa informasi resmi BMKG")
            val notification = NotificationCompat.Builder(applicationContext, "wrs_gempa_alerts")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(candidate.first)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(applicationContext).notify(candidate.second.hashCode(), notification)
            val category = candidate.second.substringBefore('|')
            prefs.edit().putString("last_${category}_fingerprint", candidate.second).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
