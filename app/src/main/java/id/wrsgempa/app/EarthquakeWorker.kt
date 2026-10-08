package id.wrsgempa.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
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
    private data class Alert(val category: String, val fingerprint: String, val title: String, val body: String)

    private fun fetchWrs(): JSONObject {
        val connection = URL("https://wrsgempa.netlify.app/.netlify/functions/earthquakes?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 18000
        connection.setRequestProperty("User-Agent", "WRS-GEMPA-Android")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Cache-Control", "no-cache")
        return try {
            if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun quakeBody(q: JSONObject): String {
        val mag = q.optString("magnitude", "—")
        val place = q.optString("place", "Lokasi tidak tersedia")
        val depth = q.optString("depth", "—")
        val time = q.optString("time", "")
        val potential = q.optString("potential", "Periksa sumber resmi")
        return "M $mag • $place\nKedalaman: $depth km\nWaktu: $time\n$potential"
    }

    private fun fingerprint(q: JSONObject): String = q.optString("key", q.optString("time") + "|" + q.optString("magnitude") + "|" + q.optString("place"))

    override suspend fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE)
            val root = fetchWrs()
            val official = root.optJSONObject("official") ?: root.optJSONObject("latest")
            val recent = root.optJSONArray("recent") ?: JSONArray()
            val alerts = mutableListOf<Alert>()

            if (prefs.getBoolean("big_alerts", true) && official != null) {
                val magnitude = official.optDouble("magnitude", 0.0)
                val threshold = prefs.getString("min_magnitude", "4.0")?.toDoubleOrNull() ?: 4.0
                if (magnitude >= threshold) {
                    val fp = fingerprint(official)
                    alerts.add(Alert("large", fp, "Gempa WRS GEMPA M " + official.optString("magnitude"), quakeBody(official)))
                }
            }

            if (prefs.getBoolean("tsunami_alerts", true)) {
                val tsunamiList = root.optJSONArray("tsunamiHistory") ?: JSONArray()
                for (i in 0 until tsunamiList.length()) {
                    val item = tsunamiList.optJSONObject(i) ?: continue
                    if (item.optBoolean("ended", false)) continue
                    val text = (item.optString("subject") + " " + item.optString("headline") + " " + item.optString("potential")).lowercase()
                    if (text.contains("warning tsunami") || text.contains("peringatan dini tsunami") || text.contains("berpotensi tsunami")) {
                        val fp = fingerprint(item)
                        alerts.add(Alert("tsunami", fp, "PERINGATAN TSUNAMI • WRS GEMPA", item.optString("headline", item.optString("subject", item.optString("place", "Periksa peringatan resmi"))) + "\n" + item.optString("instruction", "Ikuti arahan resmi BMKG/InaTEWS.")))
                        break
                    }
                }
                if (official != null) {
                    val potential = official.optString("potential", "").lowercase()
                    if (potential.contains("berpotensi tsunami") && !potential.contains("tidak berpotensi tsunami")) {
                        val fp = fingerprint(official)
                        alerts.add(Alert("tsunami", fp, "Potensi tsunami • WRS GEMPA", quakeBody(official)))
                    }
                }
            }

            if (prefs.getBoolean("felt_alerts", true)) {
                for (i in 0 until recent.length()) {
                    val q = recent.optJSONObject(i) ?: continue
                    if (q.optString("felt").isBlank()) continue
                    val fp = fingerprint(q)
                    alerts.add(Alert("felt", fp, "Gempa dirasakan • M " + q.optString("magnitude"), quakeBody(q)))
                    break
                }
            }

            if (prefs.getBoolean("nearby_alerts", false)) {
                val userLat = prefs.getString("user_lat", null)?.toDoubleOrNull()
                val userLon = prefs.getString("user_lon", null)?.toDoubleOrNull()
                if (userLat != null && userLon != null) {
                    val radiusKm = prefs.getString("radius", "200 km")?.filter { it.isDigit() }?.toDoubleOrNull() ?: 200.0
                    var nearest: JSONObject? = null
                    var nearestKm = Double.MAX_VALUE
                    for (i in 0 until recent.length()) {
                        val q = recent.optJSONObject(i) ?: continue
                        val lat = q.optDouble("lat", Double.NaN)
                        val lon = q.optDouble("lon", Double.NaN)
                        if (!lat.isFinite() || !lon.isFinite()) continue
                        val result = FloatArray(1)
                        Location.distanceBetween(userLat, userLon, lat, lon, result)
                        val km = result[0] / 1000.0
                        if (km <= radiusKm && km < nearestKm) { nearest = q; nearestKm = km }
                    }
                    nearest?.let { q ->
                        val fp = fingerprint(q)
                        alerts.add(Alert("nearby", fp, "Gempa dekat Anda • %.0f km".format(nearestKm), quakeBody(q)))
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel("wrs_gempa_alerts", "Peringatan WRS GEMPA", NotificationManager.IMPORTANCE_HIGH).apply { description = "Peringatan gempa dan tsunami dari backend WRS GEMPA" })
            }
            for (alert in alerts.distinctBy { it.category + "|" + it.fingerprint }) {
                val prefKey = "last_${alert.category}_fingerprint"
                if (prefs.getString(prefKey, "") == alert.fingerprint) continue
                val notification = NotificationCompat.Builder(applicationContext, "wrs_gempa_alerts")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(alert.title)
                    .setContentText(alert.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                NotificationManagerCompat.from(applicationContext).notify((alert.category + alert.fingerprint).hashCode(), notification)
                prefs.edit().putString(prefKey, alert.fingerprint).apply()
            }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}