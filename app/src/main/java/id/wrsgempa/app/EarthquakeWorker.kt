package id.wrsgempa.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class EarthquakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private data class Alert(
        val category: String,
        val fingerprint: String,
        val title: String,
        val body: String,
        val event: JSONObject? = null,
        val openTsunamiDashboard: Boolean = false
    )

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

    private fun parseEventTime(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(value.replace("T", " ").take(19))?.time
            }.getOrNull()
    }

    private fun isRecentEvent(q: JSONObject, maxAgeMinutes: Long = 30L): Boolean {
        val eventTime = parseEventTime(q.optString("time", "")) ?: return false
        val age = System.currentTimeMillis() - eventTime
        return age in 0L..(maxAgeMinutes * 60_000L)
    }

    private fun timeWib(raw: String): String {
        val millis = parseEventTime(raw) ?: return raw
        return SimpleDateFormat("dd MMM yyyy HH:mm:ss 'WIB'", Locale("id", "ID")).apply {
            timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        }.format(Date(millis))
    }

    private fun quakeBody(q: JSONObject): String {
        val mag = q.optString("magnitude", "—")
        val place = q.optString("place", "Lokasi tidak tersedia")
        val rawDepth = q.optString("depth", "—")
        val depth = if (rawDepth.isBlank() || rawDepth == "—" || rawDepth.contains("km", true)) rawDepth else "$rawDepth km"
        val time = timeWib(q.optString("time", ""))
        val potential = q.optString("potential", "Periksa sumber resmi")
        return "M $mag • $place\nKedalaman: $depth\nWaktu: $time\n$potential"
    }

    private fun fingerprint(q: JSONObject): String {
        val value = q.optString("key", "").trim()
        return value.ifBlank { q.optString("time") + "|" + q.optString("magnitude") + "|" + q.optString("place") }
    }

    private fun wasNotified(prefs: android.content.SharedPreferences, category: String, fingerprint: String): Boolean {
        val records = runCatching { JSONArray(prefs.getString("notified_$category", "[]")) }.getOrDefault(JSONArray())
        return (0 until records.length()).any { records.optString(it) == fingerprint }
    }

    private fun rememberNotified(prefs: android.content.SharedPreferences, category: String, fingerprint: String) {
        val records = runCatching { JSONArray(prefs.getString("notified_$category", "[]")) }.getOrDefault(JSONArray())
        val seen = (0 until records.length()).map { records.optString(it) }.filter { it.isNotBlank() && it != fingerprint }
        val updated = JSONArray()
        (seen.takeLast(99) + fingerprint).forEach { updated.put(it) }
        prefs.edit().putString("notified_$category", updated.toString()).apply()
    }

    private fun notificationPendingIntent(alert: Alert, notificationId: Int): PendingIntent {
        val event = alert.event
        val eventId = event?.let {
            it.optString("key", it.optString("eventid", it.optString("id", alert.fingerprint)))
        }.orEmpty()
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (eventId.isNotBlank()) putExtra("quake_id", eventId)
            if (event != null) {
                val time = event.optString("time", event.optString("DateTime", ""))
                val magnitude = event.optString("magnitude", event.optString("Magnitude", ""))
                val place = event.optString("place", event.optString("Wilayah", ""))
                val latitude = event.optString("lat", event.optString("latitude", ""))
                val longitude = event.optString("lon", event.optString("longitude", ""))
                if (time.isNotBlank()) putExtra("quake_time", time)
                if (magnitude.isNotBlank()) putExtra("quake_magnitude", magnitude)
                if (place.isNotBlank()) putExtra("quake_place", place)
                if (latitude.isNotBlank()) putExtra("quake_latitude", latitude)
                if (longitude.isNotBlank()) putExtra("quake_longitude", longitude)
                val depth = event.optString("depth", event.optString("Kedalaman", ""))
                if (depth.isNotBlank()) putExtra("quake_depth", depth)
                val potential = event.optString("potential", event.optString("Potensi", ""))
                if (potential.isNotBlank()) putExtra("quake_potential", potential)
                val felt = event.optString("felt", event.optString("Dirasakan", ""))
                if (felt.isNotBlank()) putExtra("quake_felt", felt)
                val shakemap = event.optString("shakemap", event.optString("Shakemap", ""))
                if (shakemap.isNotBlank()) putExtra("quake_shakemap", shakemap)
            }
            if (alert.openTsunamiDashboard) putExtra("open_tsunami_dashboard", true)
        }
        return PendingIntent.getActivity(
            applicationContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
    }

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
                if (magnitude >= threshold && isRecentEvent(official)) {
                    val fp = fingerprint(official)
                    alerts.add(Alert("large", fp, "Gempa WRS GEMPA M " + official.optString("magnitude"), quakeBody(official), official))
                }
            }

            if (prefs.getBoolean("tsunami_alerts", true)) {
                val tsunamiList = root.optJSONArray("tsunamiHistory") ?: JSONArray()
                for (i in 0 until tsunamiList.length()) {
                    val item = tsunamiList.optJSONObject(i) ?: continue
                    if (item.optBoolean("ended", false)) continue
                    val text = (item.optString("subject") + " " + item.optString("headline") + " " + item.optString("potential")).lowercase(Locale.ROOT)
                    val explicitlyNegative = text.contains("tidak berpotensi tsunami") ||
                        text.contains("tidak ada peringatan") || text.contains("no tsunami")
                    val confirmedWarning = !explicitlyNegative &&
                        (text.contains("warning tsunami") || text.contains("peringatan dini tsunami"))
                    val positivePotential = text.contains("berpotensi tsunami") && !explicitlyNegative
                    if (confirmedWarning || positivePotential) {
                        val fp = fingerprint(item)
                        val message = item.optString("headline").ifBlank { item.optString("subject") }.ifBlank { item.optString("place", "Periksa peringatan resmi") }
                        val instruction = item.optString("instruction", "Ikuti arahan resmi BMKG/InaTEWS.")
                        alerts.add(Alert("tsunami", fp, "PERINGATAN TSUNAMI • WRS GEMPA", "$message\n$instruction", item, true))
                        break
                    }
                }
                if (official != null) {
                    val potential = official.optString("potential", "").lowercase(Locale.ROOT)
                    if (isRecentEvent(official) && potential.contains("berpotensi tsunami") && !potential.contains("tidak berpotensi tsunami")) {
                        val fp = fingerprint(official)
                        alerts.add(Alert("tsunami", fp, "Potensi tsunami • WRS GEMPA", quakeBody(official), official))
                    }
                }
            }

            if (prefs.getBoolean("felt_alerts", true)) {
                for (i in 0 until recent.length()) {
                    val q = recent.optJSONObject(i) ?: continue
                    if (q.optString("felt").isBlank() || !isRecentEvent(q)) continue
                    val fp = fingerprint(q)
                    alerts.add(Alert("felt", fp, "Gempa dirasakan • M " + q.optString("magnitude"), quakeBody(q), q))
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
                        if (!lat.isFinite() || !lon.isFinite() || !isRecentEvent(q)) continue
                        val result = FloatArray(1)
                        Location.distanceBetween(userLat, userLon, lat, lon, result)
                        val km = result[0] / 1000.0
                        if (km <= radiusKm && km < nearestKm) { nearest = q; nearestKm = km }
                    }
                    nearest?.let { q ->
                        val fp = fingerprint(q)
                        alerts.add(Alert("nearby", fp, "Gempa dekat Anda • %.0f km".format(Locale.US, nearestKm), quakeBody(q), q))
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel("wrs_gempa_alerts", "Peringatan WRS GEMPA", NotificationManager.IMPORTANCE_HIGH).apply { description = "Peringatan gempa dan tsunami dari backend WRS GEMPA" })
            }
            for (alert in alerts.distinctBy { it.category + "|" + it.fingerprint }) {
                if (wasNotified(prefs, alert.category, alert.fingerprint)) continue
                val notificationId = (alert.category + alert.fingerprint).hashCode()
                val notification = NotificationCompat.Builder(applicationContext, "wrs_gempa_alerts")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(alert.title)
                    .setContentText(alert.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(notificationPendingIntent(alert, notificationId))
                    .build()
                NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
                rememberNotified(prefs, alert.category, alert.fingerprint)
            }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}