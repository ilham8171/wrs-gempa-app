package id.wrsgempa.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

private val Navy = Color(0xFF0A1730)
private val Blue = Color(0xFF1769E0)
private val Muted = Color(0xFF6E7B8E)
private val Pale = Color(0xFFF3F6FB)

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
    val felt: String = ""
) {
    val magnitudeValue: Double get() = magnitude.replace(",", ".").toDoubleOrNull() ?: 0.0
    val depthValue: Int get() = depth.filter { it.isDigit() }.toIntOrNull() ?: 0
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

private suspend fun fetchJson(path: String): JSONObject = withContext(Dispatchers.IO) {
    val connection = URL("https://data.bmkg.go.id/DataMKG/TEWS/$path").openConnection() as HttpURLConnection
    connection.connectTimeout = 12000
    connection.readTimeout = 12000
    connection.setRequestProperty("User-Agent", "WRS-GEMPA-Android")
    try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    } finally {
        connection.disconnect()
    }
}

private fun parseQuake(obj: JSONObject): Quake {
    val coord = obj.optString("Coordinates", "")
    val parts = coord.split(",")
    val latRaw = obj.optString("Lintang")
    val latValue = latRaw.replace(" LS", "").replace(" LU", "").replace(",", ".")
        .filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
    val lat = latValue?.let { if (latRaw.contains("LS")) -kotlin.math.abs(it) else kotlin.math.abs(it) }
        ?: parts.getOrNull(0)?.toDoubleOrNull() ?: -2.5
    val lonRaw = obj.optString("Bujur")
    val lonValue = lonRaw.replace(" BT", "").replace(" BB", "").replace(",", ".")
        .filter { it.isDigit() || it == '.' || it == '-' }.toDoubleOrNull()
    val lon = lonValue?.let { if (lonRaw.contains("BB")) -kotlin.math.abs(it) else kotlin.math.abs(it) }
        ?: parts.getOrNull(1)?.toDoubleOrNull() ?: 118.0
    return Quake(
        date = obj.optString("Tanggal", "Tanggal tidak tersedia"),
        time = obj.optString("Jam", ""),
        magnitude = obj.optString("Magnitude", "—"),
        depth = obj.optString("Kedalaman", "—"),
        location = obj.optString("Wilayah", "Lokasi tidak tersedia"),
        coordinates = coord.ifBlank { "${lat}, ${lon}" },
        latitude = lat,
        longitude = lon,
        tsunami = obj.optString("Potensi", "Informasi potensi tsunami tidak tersedia"),
        felt = obj.optString("Dirasakan", "")
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

@Composable
private fun WrsGempaApp() {
    val appContext = LocalContext.current.applicationContext
    val prefs = remember { appContext.getSharedPreferences("wrs_alerts", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var latest by remember { mutableStateOf<Quake?>(null) }
    var quakes by remember { mutableStateOf<List<Quake>>(emptyList()) }
    var feltQuakes by remember { mutableStateOf<List<Quake>>(emptyList()) }
    var selected by remember { mutableStateOf<Quake?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Terbaru") }
    var bigAlerts by remember { mutableStateOf(prefs.getBoolean("big_alerts", true)) }
    var feltAlerts by remember { mutableStateOf(prefs.getBoolean("felt_alerts", true)) }
    var tsunamiAlerts by remember { mutableStateOf(prefs.getBoolean("tsunami_alerts", true)) }
    var nearbyAlerts by remember { mutableStateOf(prefs.getBoolean("nearby_alerts", false)) }
    var minMagnitude by remember { mutableStateOf(prefs.getString("min_magnitude", "4.0") ?: "4.0") }
    var radius by remember { mutableStateOf(prefs.getString("radius", "200 km") ?: "200 km") }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        error = ""
        try {
            val a = fetchJson("autogempa.json")
            latest = a.optJSONObject("Infogempa")?.optJSONObject("gempa")?.let(::parseQuake)
            quakes = quakeArray(fetchJson("gempaterkini.json"))
            feltQuakes = quakeArray(fetchJson("gempadirasakan.json"))
            if (quakes.isEmpty() && latest != null) quakes = listOfNotNull(latest)
        } catch (_: Exception) {
            error = "Data BMKG belum dapat dimuat. Periksa koneksi internet lalu coba perbarui."
            if (quakes.isEmpty()) quakes = listOfNotNull(latest)
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val bg = if (dark) Color(0xFF07111F) else Pale
    val fg = if (dark) Color.White else Navy
    val card = if (dark) Color(0xFF13233A) else Color.White
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF76A9FF), background = bg, surface = card) else lightColorScheme(primary = Blue, background = bg, surface = card)) {
        Scaffold(
            containerColor = bg,
            bottomBar = {
                NavigationBar(containerColor = card) {
                    val labels = listOf("Beranda", "Peta", "Gempa", "Notifikasi", "Lainnya")
                    val icons = listOf(Icons.Default.Home, Icons.Default.Map, Icons.Default.ShowChart, Icons.Default.Notifications, Icons.Default.MoreHoriz)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index; selected = null },
                            icon = { Icon(icons[index], contentDescription = label) },
                            label = { Text(label, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            if (selected != null) {
                DetailPage(selected!!, fg, card, { selected = null }, padding)
            } else {
                when (tab) {
                    0 -> HomePage(latest, quakes, feltQuakes, loading, error, fg, card, dark, { dark = !dark }, { scope.launch { refresh() } }, { selected = it }, padding)
                    1 -> MapPage(quakes.ifEmpty { listOfNotNull(latest) }, fg, padding, { selected = it })
                    2 -> QuakeListPage(quakes, feltQuakes, filter, { filter = it }, fg, card, padding, { selected = it })
                    3 -> NotificationPage(latest, quakes, bigAlerts, { bigAlerts = it; prefs.edit().putBoolean("big_alerts", it).apply() }, feltAlerts, { feltAlerts = it; prefs.edit().putBoolean("felt_alerts", it).apply() }, tsunamiAlerts, { tsunamiAlerts = it; prefs.edit().putBoolean("tsunami_alerts", it).apply() }, nearbyAlerts, { nearbyAlerts = it; prefs.edit().putBoolean("nearby_alerts", it).apply() }, minMagnitude, { minMagnitude = it; prefs.edit().putString("min_magnitude", it).apply() }, radius, { radius = it; prefs.edit().putString("radius", it).apply() }, fg, card, padding, { selected = it })
                    4 -> MorePage(fg, card, dark, { dark = !dark }, padding, { tab = it })
                    5 -> NearbyPage(quakes, fg, card, padding, { selected = it })
                    6 -> TsunamiPage(latest, fg, card, padding)
                    else -> InfoPage(fg, card, padding)
                }
            }
        }
    }
}

@Composable
private fun Header(fg: Color, title: String, subtitle: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(46.dp).background(Blue, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Waves, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = fg, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            if (subtitle.isNotBlank()) Text(subtitle, color = fg.copy(alpha = .65f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun HomePage(latest: Quake?, quakes: List<Quake>, feltQuakes: List<Quake>, loading: Boolean, error: String, fg: Color, card: Color, dark: Boolean, toggleDark: () -> Unit, refresh: () -> Unit, open: (Quake) -> Unit, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Header(fg, "WRS GEMPA", "Monitoring Gempa Bumi Indonesia") }
                TextButton(onClick = toggleDark) { Text(if (dark) "Terang" else "Gelap") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = Color(0xFFDDF7EA), shape = RoundedCornerShape(20.dp)) { Text("  ● Live BMKG  ", color = Color(0xFF11774A), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(5.dp)) }
                Spacer(Modifier.width(8.dp))
                Text("Data gempa", color = fg.copy(alpha = .7f), fontSize = 12.sp)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().clickable { latest?.let(open) }) {
                Column(Modifier.padding(20.dp)) {
                    Text("GEMPA TERBARU", color = Color(0xFFB9D4FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(58.dp).background(Color(0x22FF5252), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.CrisisAlert, contentDescription = null, tint = Color(0xFFFF6262), modifier = Modifier.size(34.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("M ${latest?.magnitude ?: "—"}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                            Text(latest?.location ?: if (loading) "Memuat data BMKG…" else "Data belum tersedia", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("${latest?.depth ?: "—"} • ${latest?.date ?: ""} ${latest?.time ?: ""}", color = Color(0xFFB9D4FF), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(latest?.tsunami ?: "Menunggu informasi dari BMKG", color = Color(0xFFB9D4FF), fontSize = 12.sp)
                    Text("Ketuk untuk melihat detail →", color = Color.White.copy(alpha = .8f), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatCard("Hari ini", quakes.size.toString(), "Data terkini", card, fg, Modifier.weight(1f))
                StatCard("M ≥ 5.0", quakes.count { it.magnitudeValue >= 5.0 }.toString(), "Gempa besar", card, fg, Modifier.weight(1f))
                StatCard("Dirasakan", feltQuakes.size.toString(), "Daftar BMKG", card, fg, Modifier.weight(1f))
            }
        }
        item {
            Button(onClick = refresh, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(15.dp)) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (loading) "Memuat data BMKG…" else "Perbarui data gempa")
            }
            if (error.isNotBlank()) Text(error, color = Color(0xFFD32F2F), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { latest?.let(open) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = Blue)
                        Spacer(Modifier.width(8.dp))
                        Text("Peta aktivitas gempa", color = fg, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(110.dp).background(Navy, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Map, contentDescription = null, tint = Color(0xFF72C6B2), modifier = Modifier.size(36.dp))
                            Text("Lihat peta gempa Indonesia", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("Titik gempa terbaru dari BMKG", color = Color(0xFFB9D4FF), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item { Text("Gempa terbaru", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        items(quakes.take(5)) { quake -> QuakeRow(quake, card, fg) { open(quake) } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("POTENSI TSUNAMI", color = fg, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(latest?.tsunami ?: "Informasi potensi tsunami belum tersedia.", color = fg.copy(alpha = .75f), fontSize = 13.sp)
                }
            }
        }
        item { Text("Sumber data: BMKG. Data dapat berubah sewaktu-waktu. Bukan pengganti peringatan resmi dan arahan petugas.", color = fg.copy(alpha = .65f), fontSize = 11.sp) }
    }
}

@Composable
private fun StatCard(title: String, value: String, subtitle: String, card: Color, fg: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(title, color = fg.copy(alpha = .7f), fontSize = 10.sp)
            Text(value, color = fg, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = fg.copy(alpha = .6f), fontSize = 9.sp)
        }
    }
}

@Composable
private fun QuakeRow(quake: Quake, card: Color, fg: Color, open: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = open)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (quake.magnitudeValue >= 5) Color(0xFFFF5555) else if (quake.magnitudeValue >= 4) Color(0xFFFFB52E) else Color(0xFF49B78A), CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("M ${quake.magnitude}   •   ${quake.location}", color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("${quake.depth} • ${quake.date} ${quake.time}", color = fg.copy(alpha = .65f), fontSize = 11.sp)
                if (quake.felt.isNotBlank()) Text("Dirasakan: ${quake.felt}", color = fg.copy(alpha = .65f), fontSize = 10.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Detail", tint = fg.copy(alpha = .5f))
        }
    }
}

@Composable
private fun QuakeListPage(quakes: List<Quake>, felt: List<Quake>, filter: String, setFilter: (String) -> Unit, fg: Color, card: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    val source = if (filter == "Dirasakan") felt else quakes
    val shown = when (filter) {
        "M ≥ 5.0" -> source.filter { it.magnitudeValue >= 5.0 }
        "Dirasakan" -> source
        else -> source
    }
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
        Header(fg, "Daftar Gempa", "Informasi dari BMKG")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Terbaru", "M ≥ 5.0", "Dirasakan").forEach { item ->
                FilterChip(selected = filter == item, onClick = { setFilter(item) }, label = { Text(item, fontSize = 11.sp) })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Belum ada data untuk filter ini. Tarik kembali setelah memuat data BMKG.", color = fg.copy(alpha = .7f))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 18.dp)) {
                items(shown) { QuakeRow(it, card, fg) { open(it) } }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MapPage(quakes: List<Quake>, fg: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Header(fg, "Peta Gempa", "Lokasi gempa terbaru")
            Spacer(Modifier.height(10.dp))
            Text("Peta menggunakan OpenStreetMap. Koneksi internet diperlukan.", color = fg.copy(alpha = .65f), fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
            },
            update = { web ->
                val markers = quakes.take(40).joinToString("\n") {
                    "L.marker([${it.latitude}, ${it.longitude}]).addTo(map).bindPopup('<b>M ${it.magnitude}</b><br>${it.location.replace("'", "")}<br>${it.depth}');"
                }
                val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"><style>html,body,#map{height:100%;margin:0;background:#0a1730}</style><script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script></head><body><div id="map"></div><script>var map=L.map('map').setView([-2.5,118],4);L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:18,attribution:'© OpenStreetMap'}).addTo(map);$markers</script></body></html>"""
                web.loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
            }
        )
    }
}

@Composable
private fun DetailPage(quake: Quake, fg: Color, card: Color, back: () -> Unit, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, contentDescription = "Kembali", tint = fg) }
                Column {
                    Text("Detail Gempa", color = fg, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Sumber informasi: BMKG", color = fg.copy(alpha = .65f), fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CrisisAlert, contentDescription = null, tint = Color(0xFFE64B4B), modifier = Modifier.size(42.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("M ${quake.magnitude}", color = fg, fontSize = 30.sp, fontWeight = FontWeight.Black)
                            Text(quake.location, color = fg, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    DetailLine("Waktu", "${quake.date} • ${quake.time}", fg)
                    DetailLine("Kedalaman", quake.depth, fg)
                    DetailLine("Koordinat", quake.coordinates, fg)
                    DetailLine("Lintang", "${quake.latitude}", fg)
                    DetailLine("Bujur", "${quake.longitude}", fg)
                    DetailLine("Dirasakan", quake.felt.ifBlank { "Tidak ada informasi dirasakan" }, fg)
                    DetailLine("Potensi tsunami", quake.tsunami, fg)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF2FF)), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Blue)
                    Spacer(Modifier.width(10.dp))
                    Text("Ikuti informasi resmi BMKG dan arahan petugas setempat. Data aplikasi bukan pengganti peringatan darurat.", color = Navy, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, fg: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = fg.copy(alpha = .6f), fontSize = 11.sp)
        Text(value.ifBlank { "—" }, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NotificationPage(latest: Quake?, quakes: List<Quake>, big: Boolean, setBig: (Boolean) -> Unit, felt: Boolean, setFelt: (Boolean) -> Unit, tsunami: Boolean, setTsunami: (Boolean) -> Unit, nearby: Boolean, setNearby: (Boolean) -> Unit, minMagnitude: String, setMinMagnitude: (String) -> Unit, radius: String, setRadius: (String) -> Unit, fg: Color, card: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Notifikasi & Alert", "Atur jenis informasi yang ingin kamu pantau") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Jenis notifikasi", color = fg, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    ToggleRow("Gempa besar (M ≥ 5.0)", big, setBig, fg)
                    ToggleRow("Gempa dirasakan", felt, setFelt, fg)
                    ToggleRow("Potensi tsunami", tsunami, setTsunami, fg)
                    ToggleRow("Gempa dekat saya", nearby, setNearby, fg)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pengaturan tambahan", color = fg, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Batas magnitudo untuk tampilan", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("3.0", "4.0", "5.0", "6.0").forEach { v ->
                            FilterChip(selected = minMagnitude == v, onClick = { setMinMagnitude(v) }, label = { Text("M $v") })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Radius pemantauan", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("50 km", "100 km", "200 km", "500 km").forEach { v ->
                            FilterChip(selected = radius == v, onClick = { setRadius(v) }, label = { Text(v) })
                        }
                    }
                    Text("Pengecekan otomatis oleh Android berjalan berkala (minimal interval sistem 15 menit); waktu pengiriman dapat dipengaruhi penghemat baterai.", color = fg.copy(alpha = .65f), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        item { Text("Gempa terbaru", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
        items(quakes.take(8)) { QuakeRow(it, card, fg) { open(it) } }
        if (latest != null) item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable { open(latest) }) {
                Column(Modifier.padding(14.dp)) {
                    Text("Peringatan potensi tsunami", color = fg, fontWeight = FontWeight.Bold)
                    Text(latest.tsunami, color = fg.copy(alpha = .7f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, fg: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = Blue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = fg, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun MorePage(fg: Color, card: Color, dark: Boolean, toggleDark: () -> Unit, padding: PaddingValues, navigate: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Lainnya", "Pengaturan dan informasi aplikasi") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Tampilan", color = fg, fontWeight = FontWeight.Bold)
                    SettingsRow(Icons.Default.DarkMode, "Mode tampilan", if (dark) "Gelap" else "Terang", fg) { toggleDark() }
                    SettingsRow(Icons.Default.Notifications, "Notifikasi & alert", "Kelola jenis peringatan", fg) { navigate(3) }
                    SettingsRow(Icons.Default.Map, "Peta gempa", "Lihat titik gempa terbaru", fg) { navigate(1) }
                    SettingsRow(Icons.Default.History, "Daftar gempa", "Data terbaru BMKG", fg) { navigate(2) }
                    SettingsRow(Icons.Default.LocationOn, "Sekitar saya", "Gempa terdekat dari lokasi", fg) { navigate(5) }
                    SettingsRow(Icons.Default.Warning, "Potensi tsunami", "Informasi dari BMKG", fg) { navigate(6) }
                    SettingsRow(Icons.Default.Info, "Informasi", "Tentang aplikasi dan sumber data", fg) { navigate(7) }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Tentang WRS GEMPA", color = fg, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("WRS GEMPA membantu memantau informasi gempa bumi Indonesia melalui data BMKG.", color = fg.copy(alpha = .75f), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Sumber data: data.bmkg.go.id • Peta: OpenStreetMap", color = fg.copy(alpha = .65f), fontSize = 11.sp)
                    Text("Versi aplikasi 1.1.0", color = fg.copy(alpha = .65f), fontSize = 11.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E6)), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFB85C00))
                    Spacer(Modifier.width(8.dp))
                    Text("Aplikasi ini bukan kanal peringatan darurat resmi. Untuk keputusan keselamatan, ikuti BMKG dan petugas berwenang.", color = Color(0xFF7A3F00), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, fg: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Blue)
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, color = fg.copy(alpha = .6f), fontSize = 11.sp)
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = fg.copy(alpha = .5f))
    }
}


@Composable
private fun NearbyPage(quakes: List<Quake>, fg: Color, card: Color, padding: PaddingValues, open: (Quake) -> Unit) {
    val context = LocalContext.current
    var userLocation by remember { mutableStateOf<Location?>(null) }
    var locationMessage by remember { mutableStateOf("Izinkan akses lokasi untuk menghitung jarak gempa.") }
    fun readLocation() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locationMessage = "Izin lokasi belum diberikan."
            return
        }
        try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            userLocation = providers.mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null }
            }.maxByOrNull { it.time }
            locationMessage = if (userLocation == null) "Lokasi belum tersedia. Aktifkan Lokasi/GPS lalu coba lagi." else
                "Lokasi ditemukan: %.4f, %.4f".format(userLocation!!.latitude, userLocation!!.longitude)
        } catch (_: Exception) {
            locationMessage = "Tidak bisa membaca lokasi. Pastikan layanan lokasi aktif."
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) readLocation() else locationMessage = "Izin lokasi ditolak. Kamu masih bisa melihat daftar gempa di tab Gempa."
    }
    LaunchedEffect(Unit) { readLocation() }
    val sorted = if (userLocation == null) emptyList() else quakes.map { quake ->
        val distance = FloatArray(1)
        Location.distanceBetween(userLocation!!.latitude, userLocation!!.longitude, quake.latitude, quake.longitude, distance)
        quake to distance[0] / 1000.0
    }.sortedBy { it.second }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Sekitar Saya", "Pantau gempa berdasarkan lokasi perangkat") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Blue, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(locationMessage, color = fg, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Izinkan / perbarui lokasi") }
                }
            }
        }
        if (userLocation != null) {
            item { Text("Gempa terdekat", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            items(sorted.take(15)) { (quake, distance) ->
                Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { open(quake) }) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("M ${quake.magnitude} • ${quake.location}", color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("${quake.depth} • ${quake.date} ${quake.time}", color = fg.copy(alpha = .65f), fontSize = 11.sp)
                        }
                        Text(if (distance < 1) "<1 km" else "%.0f km".format(distance), color = Blue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TsunamiPage(latest: Quake?, fg: Color, card: Color, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header(fg, "Potensi Tsunami", "Informasi dari data BMKG") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Icon(Icons.Default.Waves, contentDescription = null, tint = Blue, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Informasi gempa terbaru", color = fg, fontWeight = FontWeight.Bold)
                    Text(latest?.location ?: "Data BMKG belum tersedia", color = fg.copy(alpha = .7f), fontSize = 13.sp)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(latest?.tsunami ?: "Belum ada informasi potensi tsunami.", color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Periksa pengumuman resmi BMKG untuk status terkini. Jangan mengandalkan aplikasi ini sebagai satu-satunya sumber peringatan.", color = fg.copy(alpha = .7f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoPage(fg: Color, card: Color, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(fg, "Informasi", "Tentang WRS GEMPA") }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("WRS GEMPA", color = fg, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Aplikasi pemantauan informasi gempa bumi Indonesia yang mengambil data dari BMKG.", color = fg.copy(alpha = .75f), fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    DetailLine("Sumber gempa", "BMKG — data.bmkg.go.id", fg)
                    DetailLine("Peta", "OpenStreetMap", fg)
                    DetailLine("Versi", "1.1.0", fg)
                    DetailLine("Peringatan", "Bukan pengganti informasi resmi atau arahan petugas.", fg)
                }
            }
        }
    }
}
