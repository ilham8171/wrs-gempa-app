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
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.nativeCanvas
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
    val lat = parseCoordinate(obj.optString("Lintang", "")) ?: coordParts.getOrNull(0)?.let(::parseCoordinate)
        ?: return null
    val lon = parseCoordinate(obj.optString("Bujur", "")) ?: coordParts.getOrNull(1)?.let(::parseCoordinate)
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
                    val labels = listOf("Beranda", "Cuaca", "Data", "Peringatan", "Menu")
                    val icons = listOf(Icons.Default.Home, Icons.Default.Cloud, Icons.Default.Public, Icons.Default.NotificationsActive, Icons.Default.Menu)
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
                    subPage == 10 -> WeatherPage(fg, card, padding, prefs)
                    else -> when (tab) {
                        0 -> HomePage(latest, quakes, feltQuakes, m5Quakes.size, history.any { it.warningEventId != null && it.warningEnded == false }, loading, error, lastUpdated, fg, card, dark, { dark = !dark }, { refreshAction() }, { selected = it }, { tab = 2 }, { subPage = 6 }, { subPage = 10 }, padding)
                        1 -> WeatherPage(fg, card, padding, prefs)
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
    latest: Quake?, quakes: List<Quake>, feltQuakes: List<Quake>, m5Count: Int,
    activeTsunamiWarning: Boolean, loading: Boolean, error: String, lastUpdated: String,
    fg: Color, card: Color, dark: Boolean, toggleDark: () -> Unit, refresh: () -> Unit,
    open: (Quake) -> Unit, openMap: () -> Unit, openTsunami: () -> Unit,
    openWeather: () -> Unit, padding: PaddingValues
) {
    val context = LocalContext.current
    val weatherPrefs = remember(context) { context.getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE) }
    val weatherEntries = remember { runCatching { val arr = JSONArray(weatherPrefs.getString("weather_cache", "[]")); (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.getOrDefault(emptyList()) }
    val weatherPlace = weatherPrefs.getString("weather_place", "")?.takeIf { it.isNotBlank() }
    val weatherUpdatedAt = weatherPrefs.getLong("weather_cache_at", 0L)
    val weatherFresh = weatherUpdatedAt > 0L && System.currentTimeMillis() - weatherUpdatedAt < 6 * 60 * 60 * 1000L
    val nextWeather = weatherEntries.firstOrNull()
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WRS GEMPA", color = fg, fontSize = 25.sp, fontWeight = FontWeight.Black)
                    Text("Pantau gempa dan cuaca Indonesia", color = fg.copy(alpha = .64f), fontSize = 12.sp)
                }
                Surface(color = Color(0xFFDDF7EA), shape = RoundedCornerShape(50)) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(Color(0xFF15945E), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("LIVE", color = Color(0xFF11774A), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                IconButton(onClick = toggleDark) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Ganti tema", tint = fg) }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(26.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("GEMPA TERKINI", color = Color(0xFFB9D4FF), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("M " + (latest?.magnitude ?: "—"), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
                            Text(latest?.location ?: if (loading) "Mengambil data BMKG…" else "Data belum tersedia", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(Modifier.size(76.dp).background(Color(0x22FF5B63), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CrisisAlert, null, tint = Color(0xFFFF6970), modifier = Modifier.size(48.dp))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = .15f))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = Color(0xFFB9D4FF), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text((latest?.date ?: "Waktu menunggu") + " " + (latest?.time ?: ""), color = Color(0xFFDCE8FF), fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("Kedalaman " + (latest?.depth ?: "—"), color = Color(0xFFDCE8FF), fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { latest?.let(open) }, enabled = latest != null, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Navy)) {
                        Text("Lihat detail gempa", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("M ≥ 5", m5Count.toString(), "Katalog BMKG", card, fg, Modifier.weight(1f))
                StatCard("Dirasakan", feltQuakes.size.toString(), "Katalog BMKG", card, fg, Modifier.weight(1f))
                StatCard("Data masuk", lastUpdated, "Waktu WIB", card, fg, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().clickable { openWeather() }) {
                Column(Modifier.padding(17.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(Color(0xFFE5F2FF), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Cloud, null, tint = Blue, modifier = Modifier.size(27.dp)) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Cuaca BMKG", color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text(weatherPlace ?: "Pilih lokasi prakiraan", color = fg.copy(alpha = .62f), fontSize = 11.sp)
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = fg.copy(alpha = .6f))
                    }
                    Spacer(Modifier.height(14.dp))
                    if (nextWeather != null && weatherPlace != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(nextWeather.optString("weather_desc", "Prakiraan berikutnya"), color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(nextWeather.optString("local_datetime", "Periode berikutnya"), color = fg.copy(alpha = .62f), fontSize = 11.sp)
                                Spacer(Modifier.height(7.dp))
                                Text("Kelembapan " + nextWeather.optString("hu", "—") + "% • Angin " + nextWeather.optString("ws", "—") + " km/j", color = fg.copy(alpha = .7f), fontSize = 10.sp)
                            }
                            Text(nextWeather.optString("t", "—") + "°", color = Blue, fontSize = 34.sp, fontWeight = FontWeight.Black)
                        }
                        Text(if (weatherFresh) "Cache diperbarui " + java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(weatherUpdatedAt)) else "Data tersimpan mungkin sudah lama — ketuk untuk cek", color = if (weatherFresh) Color(0xFF16845B) else Color(0xFFB7791F), fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
                    } else {
                        Text("Prakiraan otomatis mengikuti lokasi perangkat. Buka untuk mengaktifkan GPS dan memuat data BMKG.", color = fg.copy(alpha = .72f), fontSize = 12.sp)
                        Text("Atur lokasi cuaca →", color = Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(top = 9.dp))
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = if (activeTsunamiWarning) TsunamiRed else card), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().clickable { openTsunami() }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = if (activeTsunamiWarning) Color.White else Color(0xFFE29B21), modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (activeTsunamiWarning) "PERINGATAN TSUNAMI TERCATAT" else "Pusat Informasi Tsunami", color = if (activeTsunamiWarning) Color.White else fg, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        Text(if (activeTsunamiWarning) "Buka rincian dan instruksi dari sumber peringatan." else "Periksa status dan riwayat peringatan resmi InaTEWS BMKG.", color = if (activeTsunamiWarning) Color.White.copy(alpha = .9f) else fg.copy(alpha = .68f), fontSize = 11.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = if (activeTsunamiWarning) Color.White else fg.copy(alpha = .55f))
                }
            }
        }
        item {
            Text("Akses cepat", color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text("Fitur utama dalam satu sentuhan", color = fg.copy(alpha = .62f), fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.Default.Public, "Data gempa", "Katalog terbaru", card, fg, Modifier.weight(1f), openMap)
                QuickAction(Icons.Default.CloudQueue, "Prakiraan", "Cuaca BMKG", card, fg, Modifier.weight(1f), openWeather)
                QuickAction(Icons.Default.NotificationsActive, "Peringatan", "Info tsunami", card, fg, Modifier.weight(1f), openTsunami)
            }
        }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Peta aktivitas gempa", color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Text("Titik merah berdenyut • ketuk kejadian untuk detail", color = fg.copy(alpha = .62f), fontSize = 11.sp)
                    }
                    TextButton(onClick = openMap) { Text("Data gempa") }
                }
                Spacer(Modifier.height(8.dp))
                QuakePulseMap(quakes = (listOfNotNull(latest) + quakes).distinctBy(::quakeKey).take(12), card = card, fg = fg, open = open)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gempa terbaru", color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("Data kejadian yang berhasil diterima", color = fg.copy(alpha = .62f), fontSize = 11.sp)
                }
                TextButton(onClick = openMap) { Text("Lihat semua") }
            }
        }
        if (quakes.isEmpty() && latest == null && loading) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Menghubungkan ke sumber data gempa…", color = fg, fontSize = 12.sp)
                    }
                }
            }
        } else if (quakes.isEmpty() && latest == null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Data belum dapat ditampilkan", color = fg, fontWeight = FontWeight.Bold)
                        Text(error.ifBlank { "Periksa koneksi lalu coba perbarui data." }, color = fg.copy(alpha = .7f), fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(quakes.distinctBy(::quakeKey).take(5)) { q -> QuakeRow(q, card, fg) { open(q) } }
        }
        item {
            OutlinedButton(onClick = refresh, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "Memperbarui data…" else "Perbarui data sekarang")
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFD32F2F), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Text("Data mengikuti sumber yang berhasil diterima. Untuk keputusan keselamatan, utamakan pengumuman resmi BMKG.", color = fg.copy(alpha = .58f), fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}


@Composable
private fun QuakePulseMap(quakes: List<Quake>, card: Color, fg: Color, open: (Quake) -> Unit) {
    val transition = rememberInfiniteTransition(label = "quake-pulse")
    val pulse by transition.animateFloat(initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1500), repeatMode = RepeatMode.Reverse),
        label = "quake-pulse-alpha")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = if (card == Color.White) Color(0xFFEAF2FC) else card),
            shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().height(190.dp).clickable { quakes.firstOrNull()?.let(open) }) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width; val h = size.height
                    for (i in 1..5) {
                        val x = w * i / 6f
                        drawLine(Color(0xFF9CB6D3).copy(alpha = .22f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, h), 1.dp.toPx())
                    }
                    for (i in 1..3) {
                        val y = h * i / 4f
                        drawLine(Color(0xFF9CB6D3).copy(alpha = .22f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(w, y), 1.dp.toPx())
                    }
                    val land = Color(0xFF83B9A7).copy(alpha = .42f)
                    val islands = listOf(
                        listOf(.08f to .42f, .18f to .38f, .31f to .43f, .35f to .48f, .22f to .51f, .10f to .48f),
                        listOf(.36f to .50f, .44f to .47f, .54f to .51f, .48f to .55f, .38f to .54f),
                        listOf(.51f to .61f, .61f to .57f, .73f to .63f, .68f to .68f, .56f to .66f),
                        listOf(.73f to .45f, .81f to .42f, .87f to .48f, .82f to .54f, .75f to .51f),
                        listOf(.62f to .76f, .72f to .73f, .79f to .78f, .71f to .82f, .63f to .80f)
                    )
                    islands.forEach { points ->
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(points.first().first * w, points.first().second * h)
                            points.drop(1).forEach { lineTo(it.first * w, it.second * h) }
                            close()
                        }
                        drawPath(path, land)
                    }
                    quakes.forEach { q ->
                        if (!q.latitude.isFinite() || !q.longitude.isFinite()) return@forEach
                        val x = ((((q.longitude + 180.0) / 360.0).toFloat()) * .86f + .07f) * w
                        val y = (((5.0 - q.latitude) / 25.0).toFloat().coerceIn(.08f, .92f)) * h
                        val radius = 7f + q.magnitudeValue.coerceIn(1.0, 8.0).toFloat() * 1.4f
                        drawCircle(Color(0xFFFF3434).copy(alpha = .12f + .20f * pulse), radius = radius * 2.3f, center = androidx.compose.ui.geometry.Offset(x, y))
                        drawCircle(Color(0xFFFF3434).copy(alpha = .20f + .32f * pulse), radius = radius * 1.45f, center = androidx.compose.ui.geometry.Offset(x, y))
                        drawCircle(Color(0xFFE32636).copy(alpha = .9f), radius = radius.coerceAtMost(13f), center = androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
                Surface(color = Color(0xDD0A1730), shape = RoundedCornerShape(bottomEnd = 14.dp), modifier = Modifier.align(Alignment.TopStart)) {
                    Text("INDONESIA • AKTIVITAS GEMPA", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
                }
                Surface(color = Color.White.copy(alpha = .92f), shape = RoundedCornerShape(50.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(Color(0xFFE32636), CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text("${quakes.size} kejadian", color = Navy, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (quakes.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quakes.take(8).forEach { q ->
                    Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(15.dp), modifier = Modifier.width(220.dp).clickable { open(q) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(11.dp).background(Color(0xFFE32636).copy(alpha = pulse), CircleShape))
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text("M ${q.magnitude}", color = fg, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                                Text(q.location, color = fg.copy(alpha = .72f), fontSize = 11.sp, maxLines = 2)
                                Text("${q.date} ${q.time}", color = fg.copy(alpha = .55f), fontSize = 9.sp)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = fg.copy(alpha = .5f), modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }
        } else {
            Text("Titik kejadian akan tampil saat data gempa berhasil dimuat.", color = fg.copy(alpha = .65f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, card: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp), modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Box(Modifier.size(38.dp).background(Color(0xFFE5F2FF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Blue, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(title, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
            Text(subtitle, color = fg.copy(alpha = .62f), fontSize = 10.sp, maxLines = 1)
        }
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

@Composable
private fun MapPage(lastUpdated: String, quakes: List<Quake>, fg: Color, card: Color, padding: PaddingValues, openHistory: () -> Unit, openShakemap: () -> Unit, openWeather: () -> Unit) {
    val markersJson = remember(quakes) {
        JSONArray().apply {
            quakes.filter { it.latitude in -12.0..8.0 && it.longitude in 94.0..142.0 }.take(120).forEach { q ->
                put(JSONObject()
                    .put("lat", q.latitude)
                    .put("lon", q.longitude)
                    .put("mag", q.magnitude)
                    .put("place", q.location)
                    .put("depth", q.depth)
                    .put("time", q.time))
            }
        }.toString()
    }
    val mapHtml = remember(markersJson) {
        """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"/>
        <style>
          html,body,#map{height:100%;width:100%;margin:0;background:#e8f0f5;font-family:Arial,sans-serif}
          .leaflet-popup-content-wrapper{border-radius:12px}.leaflet-popup-content{margin:12px;font-size:13px;line-height:1.5}
          .quake-dot{border:2px solid white;border-radius:50%;box-shadow:0 1px 8px #182b45aa;text-align:center;color:white;font-weight:800;font-size:11px;display:flex;align-items:center;justify-content:center}
          .map-title{background:#071b31ed;color:white;padding:10px 13px;border-radius:12px;font-size:12px;box-shadow:0 2px 12px #0003}
          .map-title small{display:block;color:#c8ddf4;font-weight:400;margin-top:3px}
        </style></head><body><div id="map"></div>
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <script>
          const quakes = $markersJson;
          const map = L.map('map',{zoomControl:false,preferCanvas:true}).setView([-2.5,118],4.4);
          L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{
            maxZoom:18, attribution:'&copy; OpenStreetMap contributors'
          }).addTo(map);
          L.control.zoom({position:'bottomright'}).addTo(map);
          const title=L.control({position:'topleft'});
          title.onAdd=function(){const d=L.DomUtil.create('div','map-title');d.innerHTML='PETA GEMPA INDONESIA<small>'+quakes.length+' kejadian terpetakan</small>';return d;};title.addTo(map);
          quakes.forEach((q,i)=>{
            const mag=Number(q.mag)||0, size=Math.max(24,Math.min(38,22+mag*2));
            const color=mag>=5?'#d92d3a':mag>=3?'#f08a24':'#1976d2';
            const icon=L.divIcon({className:'',html:'<div class="quake-dot" style="width:'+size+'px;height:'+size+'px;background:'+color+'">'+mag.toFixed(1)+'</div>',iconSize:[size,size],iconAnchor:[size/2,size/2]});
            const marker=L.marker([q.lat,q.lon],{icon}).addTo(map);
            const place=String(q.place||'Lokasi tidak tersedia').replace(/[<>&"]/g,' ');
            marker.bindPopup('<b>Gempa M '+mag.toFixed(1)+'</b><br>'+place+'<br>Kedalaman: '+(q.depth||'-')+'<br><small>'+String(q.time||'')+'</small>');
          });
          if(quakes.length===0){L.popup().setLatLng([-2.5,118]).setContent('Belum ada kejadian gempa untuk ditampilkan.').openOn(map);}
        </script></body></html>
        """.trimIndent()
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Header(fg, "Monitoring Gempa Realtime", "Peta geografis • data dari WRS GEMPA")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFDDF7EA), shape = RoundedCornerShape(20.dp)) {
                    Text("LIVE • WRS GEMPA", color = Color(0xFF11774A), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column { LiveWibClock(fg); Text("Data terakhir: $lastUpdated WIB", color = fg.copy(alpha = .68f), fontSize = 10.sp) }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedButton(onClick = openHistory, modifier = Modifier.weight(1f)) { Icon(Icons.Default.History, null); Text("Riwayat", fontSize = 10.sp) }
            OutlinedButton(onClick = openShakemap, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Image, null); Text("ShakeMaps", fontSize = 10.sp) }
            OutlinedButton(onClick = openWeather, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Cloud, null); Text("Cuaca", fontSize = 10.sp) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F0F5)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = true
                        webViewClient = WebViewClient()
                        loadDataWithBaseURL("https://wrsgempa.netlify.app/", mapHtml, "text/html", "UTF-8", null)
                    }
                },
                update = { view ->
                    if (view.tag != markersJson) {
                        view.tag = markersJson
                        view.loadDataWithBaseURL("https://wrsgempa.netlify.app/", mapHtml, "text/html", "UTF-8", null)
                    }
                }
            )
        }
        Text("Peta memakai OpenStreetMap. Data kejadian berasal dari WRS GEMPA; ikuti pengumuman resmi BMKG untuk keputusan keselamatan.", color = fg.copy(alpha = .65f), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
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
                    SettingsRow(Icons.Default.Cloud, "Cuaca BMKG", "Prakiraan resmi", fg) { navigate(10) }
                    SettingsRow(Icons.Default.LocationOn, "Sekitar saya", "Jarak dari perangkat", fg) { navigate(5) }
                    SettingsRow(Icons.Default.Info, "Informasi", "Sumber dan peringatan", fg) { navigate(7) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("WRS GEMPA 1.7.0", color = fg, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Monitoring gempa bumi, potensi tsunami, riwayat, ShakeMap, notifikasi, dan berbagi gambar informasi.", color = fg.copy(alpha = .75f), fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Gempa: layanan WRS GEMPA dan feed terbuka BMKG. Cuaca: API prakiraan BMKG. Peta dasar: OpenStreetMap. Status tsunami harus diverifikasi melalui pengumuman resmi InaTEWS/BMKG.", color = fg.copy(alpha = .6f), fontSize = 11.sp)
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
            Text("Informasi tsunami", color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                "Ringkasan berikut berasal dari catatan peringatan yang tersedia di aplikasi. Status aktif tidak boleh disimpulkan hanya dari arsip; verifikasi pengumuman resmi sebelum mengambil keputusan keselamatan.",
                color = fg.copy(alpha = .72f), fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            if (newestBulletins.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = Muted, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Belum ada buletin tsunami di cache", color = fg, fontWeight = FontWeight.Bold)
                            Text("Aplikasi belum dapat memastikan apakah ada peringatan aktif. Periksa kanal resmi InaTEWS/BMKG.", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                        }
                    }
                }
            } else {
                newestBulletins.forEach { bulletin ->
                    val ended = bulletin.warningEnded == true
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (ended) card else Color(0xFFFFF1F0)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (ended) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (ended) Color(0xFF16804A) else TsunamiRed
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (ended) "Buletin ditandai berakhir" else "Buletin perlu diverifikasi",
                                    color = if (ended) fg else TsunamiRed,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("M ${bulletin.magnitude} • ${bulletin.location}", color = fg, fontWeight = FontWeight.SemiBold)
                            Text("${bulletin.date} • ${bulletin.time} • Kedalaman ${bulletin.depth}", color = fg.copy(alpha = .7f), fontSize = 11.sp)
                            if (bulletin.tsunami.isNotBlank()) Text(bulletin.tsunami, color = fg, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://inatews.bmkg.go.id/web/tsunami"))) } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInBrowser, null)
                Spacer(Modifier.width(8.dp))
                Text("Verifikasi status di InaTEWS resmi")
            }
            Text(
                "Sumber informasi: BMKG/InaTEWS dan data yang tersimpan di perangkat. Ringkasan aplikasi bukan pengganti instruksi evakuasi resmi.",
                color = fg.copy(alpha = .62f), fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun WeatherPage(fg: Color, card: Color, padding: PaddingValues, prefs: android.content.SharedPreferences) {
    var place by remember { mutableStateOf(prefs.getString("weather_place", "") ?: "") }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var updated by remember { mutableStateOf("") }
    var locationText by remember { mutableStateOf("Lokasi perangkat belum dibaca.") }
    var location by remember { mutableStateOf<Location?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun readDeviceLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val loc = readDeviceLocation()
        if (loc == null) {
            locationText = "Lokasi belum tersedia. Aktifkan GPS/lokasi perangkat lalu tekan Coba lagi."
        } else {
            location = loc
            locationText = "GPS perangkat terdeteksi"
        }
    }

    fun loadForecast(loc: Location) {
        busy = true
        error = ""
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // Reverse geocode GPS to human-readable village/city; user never has to enter an administrative code.
                    val address = runCatching {
                        val geocoder = android.location.Geocoder(ctx, java.util.Locale("id", "ID"))
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                    }.getOrNull()
                    val names = listOfNotNull(
                        address?.subLocality, address?.locality, address?.subAdminArea,
                        address?.adminArea
                    ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    val query = (names.firstOrNull() ?: address?.featureName ?: "").trim()
                    if (query.isBlank()) throw IllegalStateException("Nama lokasi dari GPS belum terbaca. Pastikan lokasi aktif dan coba lagi.")

                    // Public region lookup is only used to resolve BMKG's administrative village code.
                    val searchUrl = URL("https://bmkg-restapi.vercel.app/v1/wilayah/search?q=" +
                        java.net.URLEncoder.encode(query, "UTF-8"))
                    val searchConn = searchUrl.openConnection() as HttpURLConnection
                    searchConn.connectTimeout = 10000
                    searchConn.readTimeout = 12000
                    val searchBody = searchConn.inputStream.bufferedReader().use { it.readText() }
                    searchConn.disconnect()
                    val searchRoot = JSONObject(searchBody)
                    val matches = searchRoot.optJSONArray("data")
                        ?: searchRoot.optJSONArray("results")
                        ?: searchRoot.optJSONArray("wilayah")
                        ?: JSONArray()
                    var bestCode = ""
                    var bestName = query
                    var bestDistance = Float.MAX_VALUE
                    for (i in 0 until matches.length()) {
                        val item = matches.optJSONObject(i) ?: continue
                        val code = item.optString("code", item.optString("adm4", item.optString("kode", "")))
                        if (code.isBlank()) continue
                        val lat = item.optDouble("lat", Double.NaN)
                        val lon = item.optDouble("lon", item.optDouble("lng", Double.NaN))
                        val distance = if (lat.isFinite() && lon.isFinite()) {
                            val out = FloatArray(1)
                            Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, out)
                            out[0]
                        } else Float.MAX_VALUE
                        if (bestCode.isBlank() || distance < bestDistance) {
                            bestCode = code
                            bestDistance = distance
                            bestName = listOf("desa", "village", "kelurahan", "kecamatan", "kotkab", "kabupaten", "provinsi")
                                .map { item.optString(it).takeIf(String::isNotBlank) }
                                .filterNotNull().distinct().joinToString(", ").ifBlank { query }
                        }
                    }
                    if (bestCode.isBlank()) throw IllegalStateException("Wilayah dari GPS belum cocok dengan data prakiraan BMKG. Coba perbarui lokasi beberapa saat lagi.")
                    val forecastConn = URL("https://api.bmkg.go.id/publik/prakiraan-cuaca?adm4=" + bestCode).openConnection() as HttpURLConnection
                    forecastConn.connectTimeout = 10000
                    forecastConn.readTimeout = 15000
                    val root = JSONObject(forecastConn.inputStream.bufferedReader().use { it.readText() })
                    forecastConn.disconnect()
                    val locObj = root.optJSONObject("lokasi")
                    val officialName = listOf("desa", "kecamatan", "kotkab", "provinsi")
                        .map { locObj?.optString(it).orEmpty() }
                        .filter { it.isNotBlank() && it != "null" }.distinct().joinToString(", ")
                    val out = mutableListOf<JSONObject>()
                    val data = root.optJSONArray("data")
                    for (i in 0 until (data?.length() ?: 0)) {
                        val groups = data?.optJSONObject(i)?.optJSONArray("cuaca") ?: continue
                        for (j in 0 until groups.length()) {
                            val group = groups.optJSONArray(j) ?: continue
                            for (k in 0 until group.length()) group.optJSONObject(k)?.let { out.add(it) }
                        }
                    }
                    val forecasts = out.distinctBy { it.optString("local_datetime") }.take(24)
                    if (forecasts.isEmpty()) throw IllegalStateException("BMKG belum mengirim prakiraan untuk lokasi ini.")
                    Triple(if (officialName.isBlank()) bestName else officialName, forecasts, bestCode)
                }
                place = result.first
                entries = result.second
                prefs.edit().putString("weather_adm4", result.third)
                    .putString("weather_place", place)
                    .putString("weather_cache", JSONArray().apply { entries.forEach { put(it) } }.toString())
                    .putLong("weather_cache_at", System.currentTimeMillis()).apply()
                updated = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("id", "ID")).format(java.util.Date())
                locationText = "Lokasi perangkat digunakan untuk memilih prakiraan terdekat."
            } catch (e: Exception) {
                error = e.message ?: "Gagal mengambil prakiraan. Periksa koneksi dan izin lokasi."
            } finally { busy = false }
        }
    }

    LaunchedEffect(Unit) {
        runCatching {
            val arr = JSONArray(prefs.getString("weather_cache", "[]"))
            entries = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            if (entries.isNotEmpty()) updated = "Data tersimpan • perbarui untuk data terbaru"
        }
        val loc = readDeviceLocation()
        if (loc != null) {
            location = loc
            locationText = "GPS perangkat terdeteksi"
            loadForecast(loc)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Cuaca di Lokasi Saya", "Prakiraan BMKG otomatis berdasarkan GPS perangkat") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MyLocation, null, tint = Blue, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Lokasi perangkat", color = fg, fontWeight = FontWeight.Bold)
                            Text(locationText, color = fg.copy(alpha = .7f), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(enabled = !busy, onClick = {
                        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (!fine && !coarse) locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else {
                            val loc = readDeviceLocation()
                            if (loc == null) error = "Lokasi GPS belum tersedia. Aktifkan Lokasi pada perangkat lalu coba lagi."
                            else { location = loc; loadForecast(loc) }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.MyLocation, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Mencari lokasi & prakiraan…" else "Gunakan GPS / Perbarui")
                    }
                    if (error.isNotBlank()) Text(error, color = Color(0xFFD32F2F), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (place.isNotBlank()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(place, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Diperbarui: $updated", color = Color(0xFFB9D4FF), fontSize = 11.sp)
                    Text("Sumber prakiraan: BMKG • Lokasi dipilih dari GPS perangkat", color = Color(0xFFD8E6FF), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Peringatan dini cuaca", color = fg, fontWeight = FontWeight.Bold)
                    Text("Untuk peringatan aktif dan instruksi keselamatan, cek pengumuman resmi BMKG. Prakiraan biasa bukan pengganti peringatan darurat.", color = fg.copy(alpha = .8f), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                    TextButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.bmkg.go.id/alerts/nowcast/id"))) } }) { Text("Lihat peringatan resmi BMKG") }
                }
            }
        }
        item { Text("Prakiraan 3 hari • per 3 jam", color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        items(entries) { p ->
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Cloud, tint = Blue, contentDescription = null, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.optString("local_datetime", "—"), color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(p.optString("weather_desc", "Kondisi cuaca tidak tersedia"), color = fg, fontSize = 14.sp)
                        Text("Angin ${p.optString("ws", "—")} km/j • ${p.optString("wd", "—")} • Kelembapan ${p.optString("hu", "—")}%", color = fg.copy(alpha = .65f), fontSize = 10.sp)
                    }
                    Text("${p.optString("t", "—")}°C", color = fg, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        if (entries.isEmpty()) item { Text("Prakiraan akan tampil otomatis setelah izin lokasi diberikan.", color = fg.copy(alpha = .7f)) }
        item { Text("Sumber prakiraan: BMKG. Kode wilayah dicari otomatis di latar belakang; pengguna tidak perlu memasukkan kode.", color = fg.copy(alpha = .65f), fontSize = 10.sp) }
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
