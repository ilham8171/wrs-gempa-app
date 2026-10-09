package id.wrsgempa.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessaging
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Navy = Color(0xFF0A1730)
private val Blue = Color(0xFF1769E0)
private val Muted = Color(0xFF66758A)
private val Pale = Color(0xFFF3F6FB)
private val TsunamiRed = Color(0xFFB91C1C)
private val TsunamiYellow = Color(0xFFFFF4CC)

private fun wibClockText(): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale("id", "ID"))
        .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta") }
        .format(java.util.Date())

@Composable
private fun LiveWibClock(fg: Color) {
    var now by remember { mutableStateOf(wibClockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = wibClockText()
            val remainder = System.currentTimeMillis() % 1000L
            delay((1000L - remainder).coerceAtLeast(1L))
        }
    }
    Text("$now WIB", color = fg, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
}

data class Quake(
    val date: String,
    val time: String,
    val magnitude: String,
    val depth: String,
    val location: String,
    val coordinates: String,
    val latitude: Double,
    val longitude: Double,
    val tsunami: String,
    val felt: String = "",
    val shakemap: String = "",
    val id: String = "",
    val source: String = "WRS GEMPA",
    val warningEnded: Boolean? = null,
    val warningEventId: String? = null,
    val warningUpdatedAt: String? = null
) {
    val magnitudeValue: Double get() = magnitude.replace(",", ".").toDoubleOrNull() ?: 0.0
}

private data class FeedResults(
    val wrs: Result<JSONObject>,
    val latest: Result<List<Quake>>,
    val m5: Result<List<Quake>>,
    val felt: Result<List<Quake>>
)

class MainActivity : ComponentActivity() {
    private val incomingNotificationIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingNotificationIntent.value = intent.takeIf(::hasNotificationRoute)
        FirebaseMessaging.getInstance().subscribeToTopic("wrs-gempa-alerts")
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE)
                .edit().putString("fcm_token", token).apply()
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5107)
        }
        val request = PeriodicWorkRequestBuilder<EarthquakeWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "wrs_gempa_alerts", ExistingPeriodicWorkPolicy.KEEP, request
        )
        setContent {
            WrsGempaApp(
                incomingIntent = incomingNotificationIntent.value,
                onIncomingIntentHandled = { incomingNotificationIntent.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingNotificationIntent.value = intent.takeIf(::hasNotificationRoute)
    }

    private fun hasNotificationRoute(intent: Intent): Boolean =
        intent.hasExtra("quake_id") ||
            intent.hasExtra("quake_time") ||
            intent.getBooleanExtra("open_tsunami_dashboard", false)
}

private suspend fun fetchWrsData(): JSONObject = withContext(Dispatchers.IO) {
    val url = URL("https://wrsgempa.netlify.app/.netlify/functions/earthquakes?t=" + System.currentTimeMillis())
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 15000
    connection.readTimeout = 20000
    connection.setRequestProperty("User-Agent", "WRS-GEMPA-Android")
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("Cache-Control", "no-cache")
    try {
        if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally { connection.disconnect() }
}

private suspend fun fetchBmkgQuakes(fileName: String): List<Quake> = withContext(Dispatchers.IO) {
    val url = URL("https://data.bmkg.go.id/DataMKG/TEWS/" + fileName + "?t=" + System.currentTimeMillis())
    val connection = url.openConnection() as HttpURLConnection
    connection.connectTimeout = 12000
    connection.readTimeout = 15000
    connection.setRequestProperty("User-Agent", "WRS-GEMPA-Android")
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("Cache-Control", "no-cache")
    try {
        if (connection.responseCode !in 200..299) error("BMKG HTTP " + connection.responseCode)
        val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val value = root.optJSONObject("Infogempa")?.opt("gempa")
        when (value) {
            is JSONObject -> listOfNotNull(parseQuake(value))
            is JSONArray -> (0 until value.length()).mapNotNull { i -> value.optJSONObject(i)?.let(::parseQuake) }
            else -> emptyList()
        }
    } finally { connection.disconnect() }
}

private fun parseWrsQuake(obj: JSONObject): Quake? {
    val lat = obj.optDouble("lat", Double.NaN)
    val lon = obj.optDouble("lon", Double.NaN)
    val magnitude = obj.optDouble("magnitude", Double.NaN)
    if (!magnitude.isFinite() || magnitude <= 0.0 || magnitude > 10.0 ||
        !lat.isFinite() || !lon.isFinite() ||
        lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

    val place = obj.optString("place", "").trim()
    if (place.isBlank()) return null
    val rawTime = obj.optString("time", "").trim()
    if (rawTime.isBlank()) return null
    val dateObj = runCatching { java.util.Date.from(java.time.Instant.parse(rawTime)) }.getOrNull()
        ?: runCatching { java.util.Date.from(java.time.OffsetDateTime.parse(rawTime).toInstant()) }.getOrNull()
        ?: runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
                isLenient = false
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(rawTime.replace("T", " ").take(19))
        }.getOrNull()
        ?: return null

    val dateFmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale("id", "ID")).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta") }
    val timeFmt = java.text.SimpleDateFormat("HH:mm:ss 'WIB'", java.util.Locale("id", "ID")).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta") }
    val latText = String.format(java.util.Locale.US, "%.3f", lat)
    val lonText = String.format(java.util.Locale.US, "%.3f", lon)
    val rawDepth = obj.optString("depth", "—").trim()
    val depth = when {
        rawDepth.isBlank() || rawDepth == "—" -> "—"
        rawDepth.contains("km", true) -> rawDepth
        else -> "$rawDepth km"
    }
    return Quake(dateFmt.format(dateObj), timeFmt.format(dateObj), String.format(java.util.Locale.US, "%.1f", magnitude), depth,
        place, "$latText, $lonText", lat, lon,
        obj.optString("potential", "—"), obj.optString("felt", ""), obj.optString("shakemap", ""),
        obj.optString("key", rawTime + "|" + magnitude + "|" + lat + "|" + lon),
        obj.optString("source", "WRS GEMPA").ifBlank { "WRS GEMPA" },
        if (obj.has("warningEnded") && !obj.isNull("warningEnded")) obj.optBoolean("warningEnded") else null,
        obj.optString("warningEventId").takeIf { it.isNotBlank() },
        obj.optString("warningUpdatedAt").takeIf { it.isNotBlank() })
}
private fun parseCoordinate(raw: String): Double? {
    val cleaned = raw.trim()
        .replace(" LS", "", ignoreCase = true)
        .replace(" LU", "", ignoreCase = true)
        .replace(" BT", "", ignoreCase = true)
        .replace(" BB", "", ignoreCase = true)
        .replace(",", ".")
        .filter { it.isDigit() || it == '.' || it == '-' }
        .toDoubleOrNull() ?: return null
    return when {
        raw.contains("LS", true) || raw.contains("BB", true) -> -kotlin.math.abs(cleaned)
        raw.contains("LU", true) || raw.contains("BT", true) -> kotlin.math.abs(cleaned)
        else -> cleaned
    }
}

private fun parseQuake(obj: JSONObject): Quake? {
    val magnitudeText = obj.optString("Magnitude", "").replace(",", ".").trim()
    val magnitude = magnitudeText.toDoubleOrNull() ?: return null
    val coord = obj.optString("Coordinates", "").trim()
    val coordParts = coord.split(",")
    val lat = parseCoordinate(obj.optString("Lintang", "")) ?: coordParts.getOrNull(0)?.trim()?.toDoubleOrNull()
        ?: return null
    val lon = parseCoordinate(obj.optString("Bujur", "")) ?: coordParts.getOrNull(1)?.trim()?.toDoubleOrNull()
        ?: return null
    if (!magnitude.isFinite() || magnitude !in 0.0..10.0 ||
        !lat.isFinite() || lat !in -90.0..90.0 ||
        !lon.isFinite() || lon !in -180.0..180.0) return null

    val date = obj.optString("Tanggal", "").trim()
    val time = obj.optString("Jam", "").trim()
    val location = obj.optString("Wilayah", "").trim()
    if (date.isBlank() || time.isBlank() || location.isBlank()) return null

    val rawDepth = obj.optString("Kedalaman", "—").trim()
    val depth = when {
        rawDepth.isBlank() -> "—"
        rawDepth.contains("km", true) -> rawDepth
        else -> "$rawDepth km"
    }
    val displayCoordinates = coord.ifBlank { "%.3f, %.3f".format(java.util.Locale.US, lat, lon) }
    return Quake(
        date = date,
        time = time,
        magnitude = String.format(java.util.Locale.US, "%.1f", magnitude),
        depth = depth,
        location = location,
        coordinates = displayCoordinates,
        latitude = lat,
        longitude = lon,
        tsunami = obj.optString("Potensi", ""),
        felt = obj.optString("Dirasakan", ""),
        shakemap = obj.optString("Shakemap", ""),
        id = obj.optString("ID", obj.optString("DateTime", "")),
        source = "BMKG"
    )
}

private fun quakeArray(root: JSONObject): List<Quake> {
    val value = root.optJSONObject("Infogempa")?.opt("gempa") ?: return emptyList()
    return when (value) {
        is JSONObject -> listOfNotNull(parseQuake(value))
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it)?.let(::parseQuake) }
        else -> emptyList()
    }
}

private fun quakeKey(q: Quake): String = if (q.warningEventId != null) {
    listOf("InaTEWS", q.warningEventId, q.warningUpdatedAt.orEmpty().ifBlank { q.date + " " + q.time })
        .joinToString("|")
} else {
    listOf(q.date, q.time, q.magnitude, q.location, q.coordinates).joinToString("|")
}

private fun quakeJson(q: Quake): JSONObject = JSONObject().apply {
    put("date", q.date)
    put("time", q.time)
    put("magnitude", q.magnitude)
    put("depth", q.depth)
    put("location", q.location)
    put("coordinates", q.coordinates)
    put("latitude", q.latitude)
    put("longitude", q.longitude)
    put("tsunami", q.tsunami)
    put("felt", q.felt)
    put("shakemap", q.shakemap)
    put("id", q.id)
    put("source", q.source)
    q.warningEnded?.let { put("warningEnded", it) }
    q.warningEventId?.let { put("warningEventId", it) }
    q.warningUpdatedAt?.let { put("warningUpdatedAt", it) }
}

private fun quakeFromNotificationIntent(intent: Intent): Quake? {
    val rawTime = intent.getStringExtra("quake_time").orEmpty()
    val magnitude = intent.getStringExtra("quake_magnitude").orEmpty().replace(",", ".").toDoubleOrNull() ?: return null
    val latitude = intent.getStringExtra("quake_latitude")?.toDoubleOrNull() ?: return null
    val longitude = intent.getStringExtra("quake_longitude")?.toDoubleOrNull() ?: return null
    val place = intent.getStringExtra("quake_place").orEmpty()
    if (rawTime.isBlank() || place.isBlank()) return null
    val payload = JSONObject().apply {
        put("key", intent.getStringExtra("quake_id").orEmpty().ifBlank { rawTime + "|" + magnitude + "|" + latitude + "|" + longitude })
        put("time", rawTime)
        put("magnitude", magnitude)
        put("lat", latitude)
        put("lon", longitude)
        put("place", place)
        put("depth", intent.getStringExtra("quake_depth").orEmpty().ifBlank { "—" })
        put("potential", intent.getStringExtra("quake_potential").orEmpty().ifBlank { "Status tsunami: periksa BMKG/InaTEWS" })
        put("felt", intent.getStringExtra("quake_felt").orEmpty())
        put("shakemap", intent.getStringExtra("quake_shakemap").orEmpty())
        put("source", intent.getStringExtra("quake_source").orEmpty().ifBlank { "WRS GEMPA" })
    }
    return parseWrsQuake(payload)
}

private fun quakeFromJson(obj: JSONObject): Quake? {
    val latitude = obj.optDouble("latitude", Double.NaN)
    val longitude = obj.optDouble("longitude", Double.NaN)
    val magnitude = obj.optString("magnitude", "").replace(",", ".").toDoubleOrNull() ?: return null
    val date = obj.optString("date", "").trim()
    val time = obj.optString("time", "").trim()
    val location = obj.optString("location", "").trim()
    if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
        !longitude.isFinite() || longitude !in -180.0..180.0 ||
        !magnitude.isFinite() || magnitude <= 0.0 || magnitude > 10.0 ||
        date.isBlank() || time.isBlank() || location.isBlank()) return null

    return Quake(
        date = date,
        time = time,
        magnitude = String.format(java.util.Locale.US, "%.1f", magnitude),
        depth = obj.optString("depth", "—"),
        location = location,
        coordinates = obj.optString("coordinates").ifBlank { "%.3f, %.3f".format(java.util.Locale.US, latitude, longitude) },
        latitude = latitude,
        longitude = longitude,
        tsunami = obj.optString("tsunami"),
        felt = obj.optString("felt"),
        shakemap = obj.optString("shakemap"),
        id = obj.optString("id"),
        source = obj.optString("source", "WRS GEMPA").ifBlank { "WRS GEMPA" },
        warningEnded = if (obj.has("warningEnded") && !obj.isNull("warningEnded")) obj.optBoolean("warningEnded") else null,
        warningEventId = obj.optString("warningEventId").takeIf { it.isNotBlank() },
        warningUpdatedAt = obj.optString("warningUpdatedAt").takeIf { it.isNotBlank() }
    )
}

private fun loadHistory(prefs: android.content.SharedPreferences): List<Quake> {
    return try {
        val array = JSONArray(prefs.getString("quake_history", "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::quakeFromJson) }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun saveHistory(prefs: android.content.SharedPreferences, items: List<Quake>) {
    val arr = JSONArray()
    items.distinctBy(::quakeKey).take(250).forEach { arr.put(quakeJson(it)) }
    prefs.edit().putString("quake_history", arr.toString()).apply()
}

private fun isTsunamiPotential(q: Quake): Boolean {
    val s = q.tsunami.lowercase(java.util.Locale.ROOT)
    val explicitlyNegative = s.contains("tidak berpotensi tsunami") ||
        s.contains("tidak ada peringatan") || s.contains("no tsunami")
    if (explicitlyNegative) return false
    return (s.contains("berpotensi tsunami") || s.contains("warning tsunami") ||
        s.contains("peringatan dini tsunami"))
}

private fun latestTsunamiBulletins(items: List<Quake>): List<Quake> =
    items.filter { it.warningEventId != null }
        .groupBy { it.warningEventId?.takeIf(String::isNotBlank) ?: it.id.ifBlank { quakeKey(it) } }
        .values
        .mapNotNull { bulletins ->
            // timesent is an ISO timestamp from the CAP feed and sorts chronologically.
            // Fall back to event time only for older cached records without issue time.
            bulletins.maxByOrNull { it.warningUpdatedAt?.takeIf { stamp -> stamp.isNotBlank() } ?: (it.date + " " + it.time) }
        }

private fun tsunamiStatus(q: Quake?): Pair<String, Color> {
    if (q == null) return "BELUM ADA DATA" to Muted
    val sourceText = q.tsunami.trim()
    if (sourceText.isBlank() || sourceText == "—") return "STATUS TIDAK TERSEDIA" to Muted
    if (isTsunamiPotential(q)) return "POTENSI TSUNAMI" to TsunamiRed
    if (sourceText.lowercase(java.util.Locale.ROOT).contains("tidak berpotensi tsunami")) {
        return "TIDAK BERPOTENSI" to Color(0xFF147A51)
    }
    return "PERIKSA INaTEWS" to Muted
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun WrsGempaApp(
    incomingIntent: Intent? = null,
    onIncomingIntentHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var subPage by remember { mutableIntStateOf(-1) }
    var latest by remember { mutableStateOf<Quake?>(null) }
    var quakes by remember { mutableStateOf<List<Quake>>(emptyList()) }
    var feltQuakes by remember { mutableStateOf<List<Quake>>(emptyList()) }
    var m5Quakes by remember { mutableStateOf<List<Quake>>(emptyList()) }
    var history by remember { mutableStateOf(loadHistory(prefs)) }
    var selected by remember { mutableStateOf<Quake?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshCompleted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var lastUpdated by remember { mutableStateOf("Belum diperbarui") }
    var filter by remember { mutableStateOf("Realtime") }
    var bigAlerts by remember { mutableStateOf(prefs.getBoolean("big_alerts", true)) }
    var feltAlerts by remember { mutableStateOf(prefs.getBoolean("felt_alerts", true)) }
    var tsunamiAlerts by remember { mutableStateOf(prefs.getBoolean("tsunami_alerts", true)) }
    var nearbyAlerts by remember { mutableStateOf(prefs.getBoolean("nearby_alerts", false)) }
    var minMagnitude by remember { mutableStateOf(prefs.getString("min_magnitude", "4.0") ?: "4.0") }
    var radius by remember { mutableStateOf(prefs.getString("radius", "200 km") ?: "200 km") }

    LaunchedEffect(incomingIntent, latest, quakes, feltQuakes, m5Quakes, history, loading, refreshCompleted) {
        val pending = incomingIntent ?: return@LaunchedEffect
        val eventId = pending.getStringExtra("quake_id").orEmpty()
        val wantsTsunami = pending.getBooleanExtra("open_tsunami_dashboard", false)
        val payloadHasValidEvent = !pending.getStringExtra("quake_time").isNullOrBlank() &&
            !pending.getStringExtra("quake_place").isNullOrBlank() &&
            pending.getStringExtra("quake_magnitude")?.replace(",", ".")?.toDoubleOrNull() != null &&
            (pending.getStringExtra("quake_latitude")?.toDoubleOrNull()?.let { it in -90.0..90.0 } == true) &&
            (pending.getStringExtra("quake_longitude")?.toDoubleOrNull()?.let { it in -180.0..180.0 } == true)
        if (wantsTsunami && !payloadHasValidEvent) {
            selected = null
            tab = 4
            subPage = 6
            onIncomingIntentHandled()
        } else if (eventId.isNotBlank() || pending.hasExtra("quake_time")) {
            val candidates = (listOfNotNull(latest) + quakes + feltQuakes + m5Quakes + history).distinctBy(::quakeKey)
            val target = candidates.firstOrNull { q ->
                (eventId.isNotBlank() && (q.id == eventId || quakeKey(q) == eventId)) ||
                    (pending.getStringExtra("quake_time").orEmpty().isNotBlank() &&
                        q.id == pending.getStringExtra("quake_time")) ||
                    (pending.getStringExtra("quake_place").orEmpty().isNotBlank() &&
                        q.location.equals(pending.getStringExtra("quake_place"), ignoreCase = true) &&
                        q.magnitude == pending.getStringExtra("quake_magnitude") &&
                        q.time == pending.getStringExtra("quake_time"))
            }
            val fromPayload = target ?: quakeFromNotificationIntent(pending)
            if (fromPayload != null) {
                selected = fromPayload
                subPage = -1
                onIncomingIntentHandled()
            } else if (refreshCompleted) {
                // Event-only payloads may refer to records not retained in the local cache.
                // Never leave a notification tap hanging on an unhandled intent.
                if (wantsTsunami) {
                    selected = null
                    tab = 4
                    subPage = 6
                } else {
                    selected = null
                    tab = 2
                    subPage = -1
                }
                onIncomingIntentHandled()
            }
        }
    }


    val refreshMutex = remember { Mutex() }

    suspend fun refresh(showProgress: Boolean = false) {
        if (!refreshMutex.tryLock()) return
        if (showProgress) {
            loading = true
            error = ""
        }
        try {
            // Fetch official and backend sources in parallel. Netlify outages must not block BMKG.
            val feeds = coroutineScope {
                val wrsTask = async { runCatching { fetchWrsData() } }
                val latestTask = async { runCatching { fetchBmkgQuakes("autogempa.json") } }
                val m5Task = async { runCatching { fetchBmkgQuakes("gempaterkini.json") } }
                val feltTask = async { runCatching { fetchBmkgQuakes("gempadirasakan.json") } }
                FeedResults(wrsTask.await(), latestTask.await(), m5Task.await(), feltTask.await())
            }
            if (!feeds.wrs.isSuccess && !feeds.latest.isSuccess && !feeds.m5.isSuccess && !feeds.felt.isSuccess) {
                // Retain last good content; only show the error when there is nothing cached.
                if (latest == null && quakes.isEmpty()) {
                    error = "Data belum tersedia. Periksa koneksi internet lalu coba perbarui."
                }
                return
            }

            val wrs = feeds.wrs.getOrNull() ?: JSONObject()
            val recentJson = wrs.optJSONArray("recent") ?: JSONArray()
            val parsedRecent = (0 until recentJson.length()).mapNotNull { i ->
                recentJson.optJSONObject(i)?.let(::parseWrsQuake)
            }

            val bmkgLatest = feeds.latest.getOrDefault(emptyList())
            val bmkgM5 = feeds.m5.getOrDefault(emptyList())
            val bmkgFelt = feeds.felt.getOrDefault(emptyList())
            val preferredLatest = (wrs.optJSONObject("official") ?: wrs.optJSONObject("latest"))?.let(::parseWrsQuake)

            val newLatest = bmkgLatest.firstOrNull() ?: preferredLatest ?: parsedRecent.firstOrNull() ?: latest
            val fetchedQuakes = (parsedRecent + bmkgLatest).distinctBy(::quakeKey)
            // Keep the last good category cache if an upstream feed is temporarily unavailable
            // or returns an unusable empty result; never blank a working screen during polling.
            val newQuakes = fetchedQuakes.ifEmpty { quakes }
            val newM5 = if (feeds.m5.isSuccess && bmkgM5.isNotEmpty()) {
                bmkgM5.filter { it.magnitudeValue >= 5.0 }.distinctBy(::quakeKey)
            } else {
                m5Quakes
            }
            val freshFelt = (bmkgFelt + parsedRecent.filter { it.felt.isNotBlank() }).distinctBy(::quakeKey)
            val newFelt = if (feeds.felt.isSuccess && freshFelt.isNotEmpty()) freshFelt else feltQuakes

            val tsunamiJson = wrs.optJSONArray("tsunamiHistory") ?: JSONArray()
            val tsunamiItems = (0 until tsunamiJson.length()).mapNotNull { i ->
                val item = tsunamiJson.optJSONObject(i) ?: return@mapNotNull null
                val latitude = item.optDouble("lat", Double.NaN)
                val longitude = item.optDouble("lon", Double.NaN)
                val magnitude = item.optDouble("magnitude", Double.NaN)
                val eventTime = item.optString("time", item.optString("timesent", "")).trim()

                // Alert-only records without valid quake parameters belong to the tsunami dashboard,
                // not the earthquake archive (avoids phantom M0 entries or invented coordinates).
                if (!latitude.isFinite() || !longitude.isFinite() || !magnitude.isFinite() ||
                    magnitude <= 0.0 || magnitude > 10.0 ||
                    latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || eventTime.isBlank()) {
                    return@mapNotNull null
                }
                val normalized = JSONObject().apply {
                    put("key", item.optString("key", item.optString("eventid", "tsunami-" + i)))
                    put("magnitude", magnitude)
                    put("lat", latitude)
                    put("lon", longitude)
                    put("place", item.optString("place", "Wilayah peringatan tsunami"))
                    put("depth", item.optString("depth", "—"))
                    put("time", eventTime)
                    put("potential", item.optString("headline", item.optString("subject", item.optString("potential", "Peringatan tsunami InaTEWS"))))
                    put("source", "InaTEWS")
                    put("warningEnded", item.optBoolean("ended", false))
                    put("warningEventId", item.optString("eventid").ifBlank { item.optString("key", "tsunami-" + i) })
                    put("warningUpdatedAt", item.optString("timesent"))
                    put("felt", item.optString("description", item.optString("instruction", "")))
                    put("shakemap", item.optString("shakemap", ""))
                }
                parseWrsQuake(normalized)
            }

            latest = newLatest
            quakes = newQuakes
            m5Quakes = newM5
            feltQuakes = newFelt
            history = (tsunamiItems + listOfNotNull(newLatest) + newM5 + newFelt + newQuakes + history).distinctBy(::quakeKey).take(250)
            saveHistory(prefs, history)
            lastUpdated = wibClockText()
            error = if (newLatest == null && newQuakes.isEmpty()) {
                "Feed berhasil dihubungi, tetapi belum ada data gempa yang valid."
            } else {
                ""
            }
        } catch (_: Exception) {
            // A temporary network or parsing issue must never clear the last successful content.
            if (latest == null && quakes.isEmpty()) {
                error = "Data belum tersedia. Periksa koneksi internet lalu coba perbarui."
            }
        } finally {
            refreshCompleted = true
            if (showProgress) loading = false
            refreshMutex.unlock()
        }
    }

    LaunchedEffect(Unit) {
        refresh(showProgress = true)
        while (true) {
            delay(60_000L)
            refresh(showProgress = false)
        }
    }

    val bg = if (dark) Color(0xFF07111F) else Pale
    val fg = if (dark) Color.White else Navy
    val card = if (dark) Color(0xFF13233A) else Color.White

    val scope = rememberCoroutineScope()
    val refreshAction: () -> Unit = { scope.launch { refresh(showProgress = true) } }
    val pullState = rememberPullRefreshState(loading, refreshAction)

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF76A9FF), background = bg, surface = card)
        else lightColorScheme(primary = Blue, background = bg, surface = card)
    ) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = card) {
                    val labels = listOf("Beranda", "Peta", "Gempa", "Alert", "Lainnya")
                    val icons = listOf(Icons.Default.Home, Icons.Default.Map, Icons.Default.ShowChart, Icons.Default.Notifications, Icons.Default.MoreHoriz)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index && subPage == -1,
                            onClick = { tab = index; subPage = -1; selected = null },
                            icon = { Icon(icons[index], contentDescription = label) },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().pullRefresh(pullState)) {
                when {
                    selected != null -> DetailPage(selected!!, fg, card, { selected = null }, padding)
                    subPage == 5 -> NearbyPage(quakes + listOfNotNull(latest), fg, card, padding, { selected = it })
                    subPage == 6 -> TsunamiPage(latest, history.filter { it.warningEventId != null }, fg, card, padding)
                    subPage == 7 -> InfoPage(fg, card, padding)
                    subPage == 8 -> HistoryPage(history, fg, card, padding, { selected = it })
                    subPage == 9 -> ShakeMapsPage(history.filter { it.shakemap.isNotBlank() }, fg, card, padding, { selected = it })
                    else -> when (tab) {
                        0 -> HomePage(latest, quakes, feltQuakes, m5Quakes.size, loading, error, lastUpdated, fg, card, dark, { dark = !dark }, { refreshAction() }, { selected = it }, { tab = 1 }, { subPage = 6 }, padding)
                        1 -> MapPage(lastUpdated, fg, padding, { subPage = 8 }, { subPage = 9 })
                        2 -> QuakeListPage(quakes, feltQuakes, m5Quakes, history.filter(::isTsunamiPotential), filter, { filter = it }, fg, card, padding, { selected = it })
                        3 -> NotificationPage(latest, quakes, bigAlerts, { bigAlerts = it; prefs.edit().putBoolean("big_alerts", it).apply() }, feltAlerts, { feltAlerts = it; prefs.edit().putBoolean("felt_alerts", it).apply() }, tsunamiAlerts, { tsunamiAlerts = it; prefs.edit().putBoolean("tsunami_alerts", it).apply() }, nearbyAlerts, { nearbyAlerts = it; prefs.edit().putBoolean("nearby_alerts", it).apply() }, minMagnitude, { minMagnitude = it; prefs.edit().putString("min_magnitude", it).apply() }, radius, { radius = it; prefs.edit().putString("radius", it).apply() }, fg, card, padding, { selected = it })
                        else -> MorePage(fg, card, dark, { dark = !dark }, padding, { page -> subPage = page })
                    }
                }
                PullRefreshIndicator(loading, pullState, Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun Header(fg: Color, title: String, subtitle: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(48.dp).background(Blue, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.CrisisAlert, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = fg, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            if (subtitle.isNotBlank()) Text(subtitle, color = fg.copy(alpha = .65f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun HomePage(
    latest: Quake?,
    quakes: List<Quake>,
    feltQuakes: List<Quake>,
    m5Count: Int,
    loading: Boolean,
    error: String,
    lastUpdated: String,
    fg: Color,
    card: Color,
    dark: Boolean,
    toggleDark: () -> Unit,
    refresh: () -> Unit,
    open: (Quake) -> Unit,
    openMap: () -> Unit,
    openTsunami: () -> Unit,
    padding: PaddingValues
) {
    val tsunami = latest?.let(::isTsunamiPotential) == true
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Header(fg, "WRS GEMPA", "Realtime Earthquake & Tsunami Monitoring")
                TextButton(onClick = toggleDark) { Text(if (dark) "Terang" else "Gelap") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = Color(0xFFDDF7EA), shape = RoundedCornerShape(18.dp)) {
                    Text("● LIVE BMKG", color = Color(0xFF11774A), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("JAM SEKARANG", color = fg.copy(alpha = .6f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    LiveWibClock(fg)
                }
            }
            Text("Sinkronisasi data berjalan diam-diam setiap 60 detik. Halaman dan peta tidak dimuat ulang otomatis.", color = fg.copy(alpha = .62f), fontSize = 10.sp)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = latest != null) { latest?.let(open) }) {
                Column(Modifier.padding(20.dp)) {
                    Text("GEMPA TERBARU", color = Color(0xFFB9D4FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(62.dp).background(Color(0x22FF5252), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CrisisAlert, null, tint = Color(0xFFFF6262), modifier = Modifier.size(36.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("M " + (latest?.magnitude ?: "—"), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                            Text(latest?.location ?: if (loading) "Memuat BMKG…" else "Data belum tersedia", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text((latest?.depth ?: "—") + " • " + (latest?.date ?: "") + " " + (latest?.time ?: ""), color = Color(0xFFB9D4FF), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(latest?.tsunami ?: "Belum ada informasi potensi tsunami", color = Color(0xFFB9D4FF), fontSize = 12.sp)
                    Text("Ketuk untuk detail lengkap →", color = Color.White.copy(alpha = .82f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (tsunami) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = TsunamiRed), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().clickable { openTsunami() }) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("POTENSI TSUNAMI", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                            Text("Fokus ke informasi resmi InaTEWS: peta, tinggi muka laut, wilayah terdampak, status peringatan.", color = Color.White.copy(alpha = .92f), fontSize = 12.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = Color.White)
                    }
                }
            }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = TsunamiYellow), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { openTsunami() }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("STATUS TSUNAMI", color = Color(0xFF6B4B00), fontWeight = FontWeight.Bold)
                        Text(latest?.tsunami?.takeIf { it.isNotBlank() && it != "—" } ?: "Status tsunami tidak tercantum pada data kejadian ini. Periksa InaTEWS.", color = Color(0xFF5E5131), fontSize = 13.sp)
                        Text("Buka dashboard InaTEWS →", color = Color(0xFF7A5A00), fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("M 5+", m5Count.toString(), "15 kejadian BMKG", card, fg, Modifier.weight(1f))
                StatCard("Dirasakan", feltQuakes.size.toString(), "Katalog BMKG", card, fg, Modifier.weight(1f))
                StatCard("Update", lastUpdated, "WIB", card, fg, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { openMap() }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Public, null, tint = Blue)
                        Spacer(Modifier.width(8.dp))
                        Text("Realtime Monitoring Map", color = fg, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(125.dp).background(Navy, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Map, null, tint = Color(0xFF72C6B2), modifier = Modifier.size(40.dp))
                            Text("Peta interaktif realtime", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Ketuk untuk membuka monitoring WRS GEMPA", color = Color(0xFFB9D4FF), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
        item { Text("Gempa terbaru", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(quakes.take(5)) { q -> QuakeRow(q, card, fg) { open(q) } }
        item {
            Button(onClick = refresh, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "Memperbarui BMKG…" else "Perbarui sekarang")
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFD32F2F), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        item { Text("Data terpadu WRS GEMPA • sumber kejadian ditampilkan sesuai data yang diterima. Untuk keselamatan, ikuti peringatan resmi.", color = fg.copy(alpha = .65f), fontSize = 11.sp) }
    }
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String, card: Color, fg: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, color = fg.copy(alpha = .65f), fontSize = 10.sp)
            Text(value, color = fg, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = fg.copy(alpha = .55f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun QuakeRow(quake: Quake, card: Color, fg: Color, open: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).background(
                    when {
                        quake.magnitudeValue >= 5 -> Color(0xFFFF5555)
                        quake.magnitudeValue >= 4 -> Color(0xFFFFB52E)
                        else -> Color(0xFF49B78A)
                    },
                    CircleShape
                )
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("M " + quake.magnitude + " • " + quake.location, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(quake.depth + " • " + quake.date + " " + quake.time, color = fg.copy(alpha = .65f), fontSize = 11.sp)
                if (quake.felt.isNotBlank()) Text("Dirasakan: " + quake.felt, color = fg.copy(alpha = .65f), fontSize = 10.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = fg.copy(alpha = .45f))
        }
    }
}

@Composable
private fun QuakeListPage(quakes: List<Quake>, felt: List<Quake>, m5: List<Quake>, tsunami: List<Quake>, filter: String, setFilter: (String) -> Unit, fg: Color, card: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
        Header(fg, "Informasi Gempa", "Sumber sesuai kejadian • semua waktu WIB")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Realtime", "Dirasakan", "M ≥ 5.0", "Tsunami").forEach { item ->
                FilterChip(selected = filter == item, onClick = { setFilter(item) }, label = { Text(item, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val source = when (filter) { "Dirasakan" -> felt; "Tsunami" -> tsunami; else -> quakes }
        val shown = if (filter == "M ≥ 5.0") m5.filter { it.magnitudeValue >= 5.0 } else source
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (shown.isEmpty()) item { Text("Belum ada data pada kategori ini. Pembaruan berjalan otomatis tanpa memuat ulang halaman.", color = Muted, modifier = Modifier.padding(20.dp)) }
            items(shown) { QuakeRow(it, card, fg) { open(it) } }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapPage(lastUpdated: String, fg: Color, padding: PaddingValues, openHistory: () -> Unit, openShakemap: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Header(fg, "Monitoring Gempa Realtime", "Peta langsung dari WRS GEMPA milikmu")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFDDF7EA), shape = RoundedCornerShape(20.dp)) {
                    Text("LIVE • WRS GEMPA", color = Color(0xFF11774A), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Jam sekarang", color = fg.copy(alpha = .6f), fontSize = 9.sp)
                    LiveWibClock(fg)
                    Text("Data terakhir: " + lastUpdated + " WIB", color = fg.copy(alpha = .68f), fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = openHistory, modifier = Modifier.weight(1f)) { Icon(Icons.Default.History, null); Spacer(Modifier.width(4.dp)); Text("Riwayat") }
            OutlinedButton(onClick = openShakemap, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Spacer(Modifier.width(4.dp)); Text("ShakeMaps") }
        }
        Spacer(Modifier.height(8.dp))
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = false
                    loadUrl("https://wrsgempa.netlify.app/")
                }
            }
        )
        Text("Monitoring WRS GEMPA • Data dari backend Netlify milikmu", color = fg.copy(alpha = .55f), fontSize = 9.sp, modifier = Modifier.padding(8.dp))
    }
}

@Composable
private fun DetailPage(quake: Quake, fg: Color, card: Color, back: () -> Unit, padding: PaddingValues) {
    val context = LocalContext.current
    var toast by remember { mutableStateOf("") }
    val statusPair = tsunamiStatus(quake)
    val status = statusPair.first
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, null, tint = fg) }
                Column(Modifier.weight(1f)) {
                    Text("Detail Gempa", color = fg, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Detail kejadian dari sistem WRS GEMPA", color = fg.copy(alpha = .6f), fontSize = 11.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(78.dp).background(if (quake.magnitudeValue >= 5) Color(0xFFB91C1C) else Blue, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                            Text(String.format(java.util.Locale.US, "%.1f", quake.magnitudeValue), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("M " + quake.magnitude, color = fg, fontSize = 25.sp, fontWeight = FontWeight.Black)
                            Text(quake.location, color = fg, fontWeight = FontWeight.Bold)
                            Text(quake.date + " " + quake.time, color = fg.copy(alpha = .65f), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            ShareUtils.shareBitmap(context, ShareUtils.quakeBitmap(quake), "wrs_gempa", "Bagikan info gempa", "WRS GEMPA • Gempa M ${quake.magnitude} — ${quake.location}\nWaktu: ${quake.date} ${quake.time}\nKoordinat: ${quake.coordinates}\nKedalaman: ${quake.depth}\nPotensi: ${quake.tsunami}\n\nData terpadu WRS GEMPA.")
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Bagikan")
                        }
                        OutlinedButton(onClick = {
                            val ok = ShareUtils.saveBitmap(context, ShareUtils.quakeBitmap(quake), "wrs_gempa")
                            Toast.makeText(context, if (ok) "Gambar disimpan ke Pictures/WRS GEMPA" else "Gagal menyimpan gambar", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(4.dp)); Text("Simpan")
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    DetailLine("Lokasi", quake.location, fg)
                    DetailLine("Waktu", quake.date + " • " + quake.time, fg)
                    DetailLine("Koordinat", quake.coordinates, fg)
                    DetailLine("Kedalaman", quake.depth, fg)
                    DetailLine("Magnitudo", "M " + quake.magnitude, fg)
                    DetailLine("Dirasakan", quake.felt.ifBlank { "Tidak ada data dirasakan" }, fg)
                    DetailLine("Potensi tsunami", quake.tsunami.ifBlank { "Tidak ada keterangan" }, fg)
                    DetailLine("Sumber data", quake.source, fg)
                    Text(status, color = statusPair.second, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (quake.shakemap.isNotBlank()) {
            item {
                Text("ShakeMap BMKG", color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(8.dp)) {
                        RemoteImage(if (quake.shakemap.startsWith("http")) quake.shakemap else "https://bmkg-content-inatews.storage.googleapis.com/" + quake.shakemap)
                        Text("Peta guncangan BMKG • " + quake.shakemap, color = fg.copy(alpha = .6f), fontSize = 10.sp, modifier = Modifier.padding(6.dp))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = if (isTsunamiPotential(quake)) Color(0xFFFFE8E8) else Color(0xFFEAF8F1)), shape = RoundedCornerShape(16.dp)) {
                Text(
                    quake.tsunami.ifBlank { "Periksa informasi resmi InaTEWS untuk status tsunami terbaru." },
                    color = if (isTsunamiPotential(quake)) Color(0xFF7F1D1D) else Color(0xFF24533B),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        item { if (toast.isNotBlank()) Text(toast, color = fg.copy(alpha = .7f), fontSize = 11.sp) }
    }
}

@Composable
private fun RemoteImage(url: String) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        failed = false
        bitmap = withContext(Dispatchers.IO) {
            try {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                failed = true
                null
            }
        }
    }
    when {
        bitmap != null -> Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        failed -> Text("ShakeMap tidak dapat dimuat. Buka lagi saat koneksi tersedia.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(18.dp))
        else -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
private fun DetailLine(label: String, value: String, fg: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = fg.copy(alpha = .58f), fontSize = 11.sp)
        Text(value.ifBlank { "—" }, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NotificationPage(
    latest: Quake?,
    quakes: List<Quake>,
    big: Boolean,
    setBig: (Boolean) -> Unit,
    felt: Boolean,
    setFelt: (Boolean) -> Unit,
    tsunami: Boolean,
    setTsunami: (Boolean) -> Unit,
    nearby: Boolean,
    setNearby: (Boolean) -> Unit,
    minMagnitude: String,
    setMinMagnitude: (String) -> Unit,
    radius: String,
    setRadius: (String) -> Unit,
    fg: Color,
    card: Color,
    padding: PaddingValues,
    open: (Quake) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Notifikasi & Alert", "Magnitudo, tsunami, dirasakan, dan sekitar saya") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    ToggleRow("Gempa sesuai ambang magnitudo", big, setBig, fg)
                    ToggleRow("Gempa dirasakan", felt, setFelt, fg)
                    ToggleRow("Potensi tsunami", tsunami, setTsunami, fg)
                    ToggleRow("Gempa dekat saya", nearby, setNearby, fg)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Ambang magnitudo", color = fg, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    listOf("1.0", "1.5", "2.0", "2.5", "3.0", "4.0", "5.0").chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { v ->
                                FilterChip(selected = minMagnitude == v, onClick = { setMinMagnitude(v) }, label = { Text("M " + v, fontSize = 10.sp) })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = minMagnitude,
                        onValueChange = { setMinMagnitude(it.filter { c -> c.isDigit() || c == '.' }.take(4)) },
                        label = { Text("Custom 1.0–9.9") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Radius gempa dekat saya", color = fg, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("50 km", "100 km", "200 km", "500 km").forEach { v ->
                            FilterChip(selected = radius == v, onClick = { setRadius(v) }, label = { Text(v, fontSize = 10.sp) })
                        }
                    }
                    Text("Alert latar belakang dijalankan berkala oleh Android. Interval minimum WorkManager adalah 15 menit dan dapat dipengaruhi penghemat baterai.", color = fg.copy(alpha = .65f), fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item { Text("Gempa terbaru", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        items(quakes.take(8)) { QuakeRow(it, card, fg) { open(it) } }
        if (latest != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = if (isTsunamiPotential(latest)) Color(0xFFFFE8E8) else card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Status tsunami", color = fg, fontWeight = FontWeight.Bold)
                    Text(latest.tsunami.ifBlank { "Belum ada informasi." }, color = fg.copy(alpha = .75f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, fg: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.NotificationsActive, null, tint = Blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun MorePage(fg: Color, card: Color, dark: Boolean, toggleDark: () -> Unit, padding: PaddingValues, navigate: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Lainnya", "Semua fitur WRS GEMPA") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    SettingsRow(Icons.Default.DarkMode, "Mode tampilan", if (dark) "Gelap" else "Terang", fg) { toggleDark() }
                    SettingsRow(Icons.Default.Notifications, "Notifikasi & alert", "Magnitudo/tsunami/area", fg) { navigate(3) }
                    SettingsRow(Icons.Default.History, "Riwayat", "Kejadian tersimpan lokal", fg) { navigate(8) }
                    SettingsRow(Icons.Default.Image, "ShakeMaps", "Peta guncangan BMKG", fg) { navigate(9) }
                    SettingsRow(Icons.Default.Warning, "Dashboard tsunami", "InaTEWS + status", fg) { navigate(6) }
                    SettingsRow(Icons.Default.LocationOn, "Sekitar saya", "Jarak dari perangkat", fg) { navigate(5) }
                    SettingsRow(Icons.Default.Info, "Informasi", "Sumber dan peringatan", fg) { navigate(7) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("WRS GEMPA 1.6.0", color = fg, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Monitoring gempa bumi, potensi tsunami, riwayat, ShakeMap, notifikasi, dan berbagi gambar informasi.", color = fg.copy(alpha = .75f), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Monitoring & katalog: WRS GEMPA Netlify • Sumber data: BMKG / InaTEWS / USGS sesuai kejadian. Peta tsunami resmi ditampilkan terpisah.", color = fg.copy(alpha = .6f), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, fg: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Blue)
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, color = fg.copy(alpha = .58f), fontSize = 10.sp)
        Icon(Icons.Default.ChevronRight, null, tint = fg.copy(alpha = .45f))
    }
}

@Composable
private fun HistoryPage(history: List<Quake>, fg: Color, card: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val source = if (tab == 0) history else history.filter(::isTsunamiPotential)
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Header(fg, "Riwayat", "Data kejadian disimpan otomatis di perangkat") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Semua gempa") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Potensi tsunami") })
            }
        }
        items(source) { QuakeRow(it, card, fg) { open(it) } }
        if (source.isEmpty()) item { Text("Belum ada riwayat tersimpan. Buka aplikasi dan tunggu sinkronisasi BMKG.", color = Muted) }
    }
}

@Composable
private fun ShakeMapsPage(items: List<Quake>, fg: Color, card: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "ShakeMaps BMKG", "Peta guncangan untuk kejadian yang memiliki produk ShakeMap") }
        items(items) { q ->
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { open(q) }) {
                Column(Modifier.padding(8.dp)) {
                    Text("M " + q.magnitude + " • " + q.location, color = fg, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                    RemoteImage(if (q.shakemap.startsWith("http")) q.shakemap else "https://bmkg-content-inatews.storage.googleapis.com/" + q.shakemap)
                }
            }
        }
        if (items.isEmpty()) item { Text("Belum ada ShakeMap di riwayat.", color = Muted) }
    }
}

@Composable
private fun TsunamiPage(latest: Quake?, history: List<Quake>, fg: Color, card: Color, padding: PaddingValues) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    val newestBulletins = latestTsunamiBulletins(history)
    val activeBulletins = newestBulletins.filter { it.warningEnded == false }
    val endedBulletins = newestBulletins.filter { it.warningEnded == true }
    val statusPair = when {
        activeBulletins.isNotEmpty() -> "PERINGATAN BELUM DINYATAKAN BERAKHIR" to TsunamiRed
        history.isEmpty() -> "STATUS AKTIF BELUM TERVERIFIKASI" to Muted
        else -> "BUKA INATEWS UNTUK STATUS TERKINI" to Muted
    }
    val statusText = statusPair.first
    val detail = when {
        activeBulletins.isNotEmpty() ->
            "${activeBulletins.size} buletin InaTEWS pada data tersimpan belum ditandai berakhir. Ini perlu segera diverifikasi pada halaman resmi; status dari arsip tidak selalu sama dengan status saat ini."
        history.isEmpty() ->
            "Belum ada buletin tsunami InaTEWS yang tersedia di arsip perangkat. Status peringatan aktif belum dapat dipastikan dari data gempa terbaru saja."
        else ->
            "${endedBulletins.size} buletin InaTEWS pada arsip ditandai berakhir. Hal ini bukan jaminan tidak ada peringatan baru; periksa halaman resmi untuk status terkini."
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(18.dp)) {
            Header(fg, "Tsunami Dashboard", "Fokus peringatan dan dampak dari InaTEWS BMKG")
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = if (activeBulletins.isNotEmpty()) Color(0xFFFFE8E8) else card), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = statusPair.second, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("STATUS", color = fg.copy(alpha = .55f), fontSize = 10.sp)
                            Text(statusText, color = statusPair.second, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(detail, color = fg, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            ShareUtils.shareBitmap(context, ShareUtils.tsunamiBitmap("Dashboard Tsunami WRS GEMPA", statusText, detail), "wrs_tsunami", "Bagikan info tsunami", "WRS GEMPA • $statusText\n$detail\n\nPeriksa arahan resmi BMKG/InaTEWS untuk keputusan keselamatan.")
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Bagikan")
                        }
                        OutlinedButton(onClick = {
                            val ok = ShareUtils.saveBitmap(context, ShareUtils.tsunamiBitmap("Tsunami Dashboard BMKG", statusText, detail), "wrs_tsunami")
                            Toast.makeText(context, if (ok) "Gambar tsunami disimpan" else "Gagal menyimpan", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(4.dp)); Text("Simpan")
                        }
                    }
                }
            }
            if (latest != null) {
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Gempa terbaru (bukan status peringatan aktif)", color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        DetailLine("Kejadian", "M ${latest.magnitude} • ${latest.location}", fg)
                        DetailLine("Waktu", "${latest.date} • ${latest.time}", fg)
                        DetailLine("Keterangan potensi pada kejadian", latest.tsunami.ifBlank { "Tidak tercantum; periksa InaTEWS." }, fg)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Peta resmi InaTEWS", color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text("Di bawah ini memuat halaman resmi yang menampilkan peta perkiraan tinggi muka laut maksimum, wilayah yang berpotensi tsunami, dan status/saran peringatan ketika event tersedia.", color = fg.copy(alpha = .7f), fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(620.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    loadUrl("https://inatews.bmkg.go.id/web/tsunami")
                }
            }
        )
        Column(Modifier.padding(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    webView?.let {
                        val b = ShareUtils.captureView(it)
                        if (b != null) ShareUtils.shareBitmap(context, b, "wrs_tsunami_map", "Bagikan peta tsunami", "Peta tsunami • WRS GEMPA\nInformasi peta resmi InaTEWS yang dimuat dalam aplikasi.")
                    }
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Bagikan peta") }
                OutlinedButton(onClick = {
                    webView?.let {
                        val b = ShareUtils.captureView(it)
                        val ok = b != null && ShareUtils.saveBitmap(context, b, "inatews_tsunami")
                        Toast.makeText(context, if (ok) "Peta disimpan" else "Gagal menyimpan peta", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.SaveAlt, null); Spacer(Modifier.width(4.dp)); Text("Simpan peta") }
            }
            Spacer(Modifier.height(14.dp))
            Text("Event buletin InaTEWS unik di cache: ${newestBulletins.size} • belum ditandai berakhir: ${activeBulletins.size} • ditandai berakhir: ${endedBulletins.size}. Status aktual selalu diverifikasi pada InaTEWS.", color = fg.copy(alpha = .65f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun NearbyPage(quakes: List<Quake>, fg: Color, card: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE) }
    var userLocation by remember { mutableStateOf<Location?>(null) }
    var message by remember { mutableStateOf("Izinkan lokasi untuk menghitung jarak gempa.") }

    fun readLocation() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            message = "Izin lokasi belum diberikan."
            return
        }
        try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).mapNotNull {
                try { manager.getLastKnownLocation(it) } catch (_: Exception) { null }
            }.maxByOrNull { it.time }
            userLocation = loc
            loc?.let { prefs.edit().putString("user_lat", it.latitude.toString()).putString("user_lon", it.longitude.toString()).apply() }
            message = if (loc == null) "Lokasi belum tersedia. Aktifkan GPS lalu coba lagi." else "Lokasi: %.4f, %.4f".format(loc.latitude, loc.longitude)
        } catch (_: Exception) {
            message = "Lokasi gagal dibaca."
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        readLocation()
    }
    LaunchedEffect(Unit) { readLocation() }
    val sorted = if (userLocation == null) emptyList() else quakes.map { q ->
        val d = FloatArray(1)
        Location.distanceBetween(userLocation!!.latitude, userLocation!!.longitude, q.latitude, q.longitude, d)
        q to d[0] / 1000.0
    }.sortedBy { it.second }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Sekitar Saya", "Jarak gempa dari lokasi perangkat") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(message, color = fg, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.LocationOn, null); Spacer(Modifier.width(6.dp)); Text("Izinkan / perbarui lokasi")
                    }
                }
            }
        }
        item { Text("Gempa terdekat", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        items(sorted.take(20)) { pair ->
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { open(pair.first) }) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("M " + pair.first.magnitude + " • " + pair.first.location, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(pair.first.depth + " • " + pair.first.date + " " + pair.first.time, color = fg.copy(alpha = .65f), fontSize = 11.sp)
                    }
                    Text(if (pair.second < 1) "<1 km" else "%.0f km".format(pair.second), color = Blue, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InfoPage(fg: Color, card: Color, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Informasi", "Sumber data dan batasan aplikasi") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Sumber resmi", color = fg, fontWeight = FontWeight.Bold)
                    DetailLine("Gempa", "BMKG — data.bmkg.go.id", fg)
                    DetailLine("Realtime", "InaTEWS BMKG — inatews.bmkg.go.id/web/realtime", fg)
                    DetailLine("Tsunami", "InaTEWS BMKG — inatews.bmkg.go.id", fg)
                    DetailLine("Peta dasar", "OpenStreetMap / CARTO pada laman InaTEWS", fg)
                    DetailLine("Versi", "1.6.0", fg)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E6)), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, null, tint = Color(0xFFB85C00))
                    Spacer(Modifier.width(8.dp))
                    Text("Aplikasi ini membantu pemantauan dan arsip. Untuk keputusan keselamatan, ikuti peringatan resmi BMKG/InaTEWS dan petugas berwenang.", color = Color(0xFF7A3F00), fontSize = 12.sp)
                }
            }
        }
    }
}
