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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Navy = Color(0xFF0A1730)
private val Blue = Color(0xFF1769E0)
private val Muted = Color(0xFF66758A)
private val Pale = Color(0xFFF3F6FB)
private val TsunamiRed = Color(0xFFB91C1C)
private val TsunamiYellow = Color(0xFFFFF4CC)

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
    val id: String = ""
) {
    val magnitudeValue: Double get() = magnitude.replace(",", ".").toDoubleOrNull() ?: 0.0
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5107)
        }
        val request = PeriodicWorkRequestBuilder<EarthquakeWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "wrs_gempa_alerts", ExistingPeriodicWorkPolicy.KEEP, request
        )
        setContent { WrsGempaApp() }
    }
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

private fun parseWrsQuake(obj: JSONObject): Quake? {
    val lat = obj.optDouble("lat", Double.NaN)
    val lon = obj.optDouble("lon", Double.NaN)
    val magnitude = obj.optDouble("magnitude", Double.NaN)
    if (!magnitude.isFinite()) return null
    val rawTime = obj.optString("time", "")
    val dateObj = try { java.util.Date.from(java.time.Instant.parse(rawTime)) } catch (_: Exception) {
        try { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(rawTime.replace("T", " ").take(19)) } catch (_: Exception) { java.util.Date() }
    }
    val dateFmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale("id", "ID")).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta") }
    val timeFmt = java.text.SimpleDateFormat("HH:mm:ss 'WIB'", java.util.Locale("id", "ID")).apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Jakarta") }
    val latText = if (lat.isFinite()) String.format(java.util.Locale.US, "%.3f", lat) else "—"
    val lonText = if (lon.isFinite()) String.format(java.util.Locale.US, "%.3f", lon) else "—"
    val rawDepth = obj.optString("depth", "—")
    return Quake(dateFmt.format(dateObj), timeFmt.format(dateObj), String.format(java.util.Locale.US, "%.1f", magnitude), rawDepth + if (rawDepth.isNotBlank() && rawDepth != "—" && !rawDepth.contains("km", true)) " km" else "", obj.optString("place", "Indonesia"), if (lat.isFinite() && lon.isFinite()) "$latText, $lonText" else "Koordinat tidak tersedia", if (lat.isFinite()) lat else -2.5, if (lon.isFinite()) lon else 118.0, obj.optString("potential", "—"), obj.optString("felt", ""), obj.optString("shakemap", ""), obj.optString("key", rawTime + "|" + magnitude + "|" + lat + "|" + lon))
}
private fun parseCoordinate(raw: String, isLatitude: Boolean): Double? {
    val cleaned = raw.replace(" LS", "").replace(" LU", "").replace(" BT", "").replace(" BB", "").replace(",", ".")
        .filter { it.isDigit() || it == '.' || it == '-' }
        .toDoubleOrNull() ?: return null
    return when {
        raw.contains("LS") || raw.contains("BB") -> -kotlin.math.abs(cleaned)
        isLatitude -> kotlin.math.abs(cleaned)
        else -> kotlin.math.abs(cleaned)
    }
}

private fun parseQuake(obj: JSONObject): Quake {
    val coord = obj.optString("Coordinates", "")
    val coordParts = coord.split(",")
    val lat = parseCoordinate(obj.optString("Lintang"), true) ?: coordParts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: -2.5
    val lon = parseCoordinate(obj.optString("Bujur"), false) ?: coordParts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 118.0
    return Quake(
        date = obj.optString("Tanggal", "Tanggal tidak tersedia"),
        time = obj.optString("Jam", ""),
        magnitude = obj.optString("Magnitude", "—"),
        depth = obj.optString("Kedalaman", "—"),
        location = obj.optString("Wilayah", "Lokasi tidak tersedia"),
        coordinates = coord.ifBlank { lat.toString() + "," + lon },
        latitude = lat,
        longitude = lon,
        tsunami = obj.optString("Potensi", ""),
        felt = obj.optString("Dirasakan", ""),
        shakemap = obj.optString("Shakemap", ""),
        id = obj.optString("ID", obj.optString("DateTime", ""))
    )
}

private fun quakeArray(root: JSONObject): List<Quake> {
    val value = root.optJSONObject("Infogempa")?.opt("gempa") ?: return emptyList()
    return when (value) {
        is JSONObject -> listOf(parseQuake(value))
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it)?.let(::parseQuake) }
        else -> emptyList()
    }
}

private fun quakeKey(q: Quake): String = listOf(q.date, q.time, q.magnitude, q.location, q.coordinates).joinToString("|")

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
}

private fun quakeFromJson(obj: JSONObject): Quake = Quake(
    date = obj.optString("date"),
    time = obj.optString("time"),
    magnitude = obj.optString("magnitude"),
    depth = obj.optString("depth"),
    location = obj.optString("location"),
    coordinates = obj.optString("coordinates"),
    latitude = obj.optDouble("latitude", -2.5),
    longitude = obj.optDouble("longitude", 118.0),
    tsunami = obj.optString("tsunami"),
    felt = obj.optString("felt"),
    shakemap = obj.optString("shakemap"),
    id = obj.optString("id")
)

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
    val s = q.tsunami.lowercase()
    return (s.contains("berpotensi tsunami") && !s.contains("tidak berpotensi tsunami")) || s.contains("warning tsunami") || s.contains("peringatan dini tsunami")
}

private fun tsunamiStatus(q: Quake?): Pair<String, Color> {
    if (q == null) return "BELUM ADA DATA" to Muted
    return if (isTsunamiPotential(q)) "POTENSI TSUNAMI" to TsunamiRed else "TIDAK ADA POTENSI TSUNAMI" to Color(0xFF147A51)
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun WrsGempaApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var subPage by remember { mutableIntStateOf(-1) }
    var latest by remember { mutableStateOf<Quake?>(null) }
    var quakes by remember { mutableStateOf<List<Quake>>(emptyList()) }
    var feltQuakes by remember { mutableStateOf<List<Quake>>(emptyList()) }
    var history by remember { mutableStateOf(loadHistory(prefs)) }
    var selected by remember { mutableStateOf<Quake?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var lastUpdated by remember { mutableStateOf("Belum diperbarui") }
    var filter by remember { mutableStateOf("Terbaru") }
    var bigAlerts by remember { mutableStateOf(prefs.getBoolean("big_alerts", true)) }
    var feltAlerts by remember { mutableStateOf(prefs.getBoolean("felt_alerts", true)) }
    var tsunamiAlerts by remember { mutableStateOf(prefs.getBoolean("tsunami_alerts", true)) }
    var nearbyAlerts by remember { mutableStateOf(prefs.getBoolean("nearby_alerts", false)) }
    var minMagnitude by remember { mutableStateOf(prefs.getString("min_magnitude", "4.0") ?: "4.0") }
    var radius by remember { mutableStateOf(prefs.getString("radius", "200 km") ?: "200 km") }

    suspend fun refresh() {
        loading = true
        error = ""
        try {
            val wrs = fetchWrsData()
            val recentJson = wrs.optJSONArray("recent") ?: JSONArray()
            val parsedRecent = (0 until recentJson.length()).mapNotNull { i -> recentJson.optJSONObject(i)?.let(::parseWrsQuake) }
            val preferredLatest = (wrs.optJSONObject("official") ?: wrs.optJSONObject("latest"))?.let(::parseWrsQuake)
            latest = preferredLatest ?: parsedRecent.firstOrNull()
            quakes = parsedRecent.distinctBy(::quakeKey)
            feltQuakes = parsedRecent.filter { it.felt.isNotBlank() }
            val tsunamiJson = wrs.optJSONArray("tsunamiHistory") ?: JSONArray()
            val tsunamiItems = (0 until tsunamiJson.length()).mapNotNull { i ->
                val item = tsunamiJson.optJSONObject(i) ?: return@mapNotNull null
                val normalized = JSONObject().apply {
                    put("key", item.optString("key", item.optString("eventid", "tsunami-" + i)))
                    put("magnitude", item.optDouble("magnitude", 0.0))
                    put("lat", item.optDouble("lat", Double.NaN))
                    put("lon", item.optDouble("lon", Double.NaN))
                    put("place", item.optString("place", "Wilayah peringatan tsunami"))
                    put("depth", item.optString("depth", "—"))
                    put("time", item.optString("time", item.optString("timesent", "")))
                    put("potential", item.optString("headline", item.optString("subject", item.optString("potential", "Peringatan tsunami InaTEWS"))))
                    put("felt", item.optString("description", item.optString("instruction", "")))
                    put("shakemap", item.optString("shakemap", ""))
                }
                parseWrsQuake(normalized)
            }
            history = (listOfNotNull(latest) + quakes + feltQuakes + tsunamiItems + history).distinctBy(::quakeKey).take(250)
            saveHistory(prefs, history)
            lastUpdated = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        } catch (_: Exception) {
            error = "Data WRS GEMPA belum dapat dimuat. Periksa koneksi dan endpoint Netlify."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(60_000L)
            refresh()
        }
    }

    val bg = if (dark) Color(0xFF07111F) else Pale
    val fg = if (dark) Color.White else Navy
    val card = if (dark) Color(0xFF13233A) else Color.White

    val scope = rememberCoroutineScope()
    val refreshAction: () -> Unit = { scope.launch { refresh() } }
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
                    subPage == 6 -> TsunamiPage(latest, history.filter(::isTsunamiPotential), fg, card, padding)
                    subPage == 7 -> InfoPage(fg, card, padding)
                    subPage == 8 -> HistoryPage(history, fg, card, padding, { selected = it })
                    subPage == 9 -> ShakeMapsPage(history.filter { it.shakemap.isNotBlank() }, fg, card, padding, { selected = it })
                    else -> when (tab) {
                        0 -> HomePage(latest, quakes, feltQuakes, loading, error, lastUpdated, fg, card, dark, { dark = !dark }, { refreshAction() }, { selected = it }, { tab = 1 }, { subPage = 6 }, padding)
                        1 -> MapPage(lastUpdated, fg, padding, { subPage = 8 }, { subPage = 9 })
                        2 -> QuakeListPage(quakes, feltQuakes, filter, { filter = it }, fg, card, padding, { selected = it })
                        3 -> NotificationPage(latest, quakes, bigAlerts, { bigAlerts = it; prefs.edit().putBoolean("big_alerts", it).apply() }, feltAlerts, { feltAlerts = it; prefs.edit().putBoolean("felt_alerts", it).apply() }, tsunamiAlerts, { tsunamiAlerts = it; prefs.edit().putBoolean("tsunami_alerts", it).apply() }, nearbyAlerts, { nearbyAlerts = it }, minMagnitude, { minMagnitude = it; prefs.edit().putString("min_magnitude", it).apply() }, radius, { radius = it; prefs.edit().putString("radius", it).apply() }, fg, card, padding, { selected = it })
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
            Surface(color = Color(0xFFDDF7EA), shape = RoundedCornerShape(18.dp)) {
                Text("● LIVE BMKG  •  Auto update 60 detik  •  Swipe-down untuk refresh", color = Color(0xFF11774A), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
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
                        Text(latest?.tsunami ?: "Menunggu data BMKG", color = Color(0xFF5E5131), fontSize = 13.sp)
                        Text("Buka dashboard InaTEWS →", color = Color(0xFF7A5A00), fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("M 5+", quakes.size.toString(), "Katalog BMKG", card, fg, Modifier.weight(1f))
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
                            Text("Peta realtime 200 kejadian InaTEWS", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Episenter • magnitudo • kedalaman • aliran kejadian", color = Color(0xFFB9D4FF), fontSize = 10.sp)
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
private fun QuakeListPage(quakes: List<Quake>, felt: List<Quake>, filter: String, setFilter: (String) -> Unit, fg: Color, card: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
        Header(fg, "Daftar Gempa", "M 5+, dirasakan, dan detail kejadian")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Terbaru", "M ≥ 5.0", "Dirasakan").forEach { item ->
                FilterChip(selected = filter == item, onClick = { setFilter(item) }, label = { Text(item, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))
        val source = if (filter == "Dirasakan") felt else quakes
        val shown = if (filter == "M ≥ 5.0") source.filter { it.magnitudeValue >= 5.0 } else source
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (shown.isEmpty()) item { Text("Belum ada data. Tarik layar dari atas atau tunggu update otomatis.", color = Muted, modifier = Modifier.padding(20.dp)) }
            items(shown) { QuakeRow(it, card, fg) { open(it) } }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapPage(lastUpdated: String, fg: Color, padding: PaddingValues, openHistory: () -> Unit, openShakemap: () -> Unit) {
    var monitorWebView by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(lastUpdated) { monitorWebView?.reload() }
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Header(fg, "Monitoring Gempa Realtime", "Peta langsung dari WRS GEMPA milikmu")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFDDF7EA), shape = RoundedCornerShape(20.dp)) {
                    Text("LIVE • WRS GEMPA", color = Color(0xFF11774A), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("Auto refresh 60 detik • Update " + lastUpdated + " WIB", color = fg.copy(alpha = .68f), fontSize = 10.sp)
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
                    monitorWebView = this
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
    val status = if (isTsunamiPotential(quake)) "POTENSI TSUNAMI" else "TIDAK ADA POTENSI TSUNAMI"
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
                    DetailLine("Sumber", "WRS GEMPA • BMKG / InaTEWS / USGS sesuai data kejadian", fg)
                    Text(status, color = if (isTsunamiPotential(quake)) TsunamiRed else Color(0xFF147A51), fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
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
                    Text("WRS GEMPA 1.4.0", color = fg, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
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
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Riwayat tsunami") })
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
    val statusPair = tsunamiStatus(latest)
    val statusText = statusPair.first
    val detail = if (latest != null) {
        "Gempa: M " + latest.magnitude + " • " + latest.location + " • " + latest.depth + ". Status BMKG: " + latest.tsunami
    } else {
        "Belum ada parameter gempa terbaru dari BMKG."
    }
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(18.dp)) {
            Header(fg, "Tsunami Dashboard", "Fokus peringatan dan dampak dari InaTEWS BMKG")
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = if (isTsunamiPotential(latest ?: Quake("", "", "0", "", "", "", 0.0, 0.0, ""))) Color(0xFFFFE8E8) else card), shape = RoundedCornerShape(22.dp)) {
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
                    loadUrl("https://inatews.bmkg.go.id/")
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
            Text("Riwayat tsunami tersimpan: " + history.size + " kejadian gempa terarsip lokal; yang berstatus potensi tsunami dapat dilihat di menu Riwayat.", color = fg.copy(alpha = .65f), fontSize = 10.sp)
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
                    DetailLine("Versi", "1.3.0", fg)
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
