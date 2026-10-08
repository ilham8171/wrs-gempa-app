package id.wrsgempa.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Navy = Color(0xFF0A1730)
private val Blue = Color(0xFF1769E0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Dashboard() }
    }
}

@Composable
private fun Dashboard() {
    var magnitude by remember { mutableStateOf("—") }
    var location by remember { mutableStateOf("Memuat data BMKG…") }
    var time by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf("—") }
    var potential by remember { mutableStateOf("Memuat informasi…") }
    var loading by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        error = ""
        try {
            val result = withContext(Dispatchers.IO) {
                val c = URL("https://data.bmkg.go.id/DataMKG/TEWS/autogempa.json").openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                try {
                    val json = c.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(json).getJSONObject("Infogempa").getJSONObject("gempa")
                } finally { c.disconnect() }
            }
            magnitude = result.optString("Magnitude", "—")
            location = result.optString("Wilayah", "Lokasi tidak tersedia")
            time = result.optString("Tanggal", "—") + " • " + result.optString("Jam", "—")
            depth = result.optString("Kedalaman", "—")
            potential = result.optString("Potensi", "Informasi tidak tersedia")
        } catch (_: Exception) {
            error = "Gagal memuat data. Periksa internet dan coba lagi."
        }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    val bg = if (dark) Color(0xFF07111F) else Color(0xFFF4F7FC)
    val fg = if (dark) Color.White else Navy
    val card = if (dark) Color(0xFF13233A) else Color.White
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF76A9FF)) else lightColorScheme(primary = Blue)) {
        Scaffold(containerColor = bg) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Blue, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Waves, contentDescription = "WRS GEMPA", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("WRS GEMPA", color = fg, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Pantauan gempa Indonesia", color = fg.copy(alpha = 0.65f), fontSize = 12.sp)
                        }
                        TextButton(onClick = { dark = !dark }) { Text(if (dark) "Terang" else "Gelap") }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("GEMPA TERKINI • BMKG", color = Color(0xFFB9D4FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            Text(magnitude, color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black)
                            Text("MAGNITUDO", color = Color(0xFFB9D4FF), fontSize = 11.sp)
                            Spacer(Modifier.height(12.dp))
                            Text(location, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(time, color = Color(0xFFB9D4FF), fontSize = 12.sp)
                            Spacer(Modifier.height(16.dp))
                            Text("KEDALAMAN", color = Color(0xFFB9D4FF), fontSize = 10.sp)
                            Text(depth, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                item {
                    Button(onClick = { scope.launch { refresh() } }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (loading) "Memuat data…" else "Perbarui data gempa")
                    }
                    if (error.isNotEmpty()) Text(error, color = Color(0xFFD32F2F), modifier = Modifier.padding(top = 8.dp))
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = card), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("POTENSI TSUNAMI", color = fg, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(potential, color = fg.copy(alpha = 0.75f), fontSize = 14.sp)
                        }
                    }
                }
                item {
                    Text("Sumber data: BMKG. Data dapat berubah sewaktu-waktu.", color = fg.copy(alpha = 0.65f), fontSize = 11.sp)
                    Text("Bukan pengganti peringatan resmi dan arahan petugas.", color = fg.copy(alpha = 0.65f), fontSize = 11.sp)
                }
            }
        }
    }
}
