package id.wrsgempa.app

import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShareUtils {
    private fun textPaint(size: Float, bold: Boolean = false, color: Int = 0xFF10203A.toInt()): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = mutableListOf<String>()
        var line = ""
        for (word in text.replace("\n", " ").split(Regex("\\s+"))) {
            val candidate = if (line.isBlank()) word else line + " " + word
            if (paint.measureText(candidate) <= maxWidth) line = candidate
            else {
                if (line.isNotBlank()) out.add(line)
                line = word
            }
        }
        if (line.isNotBlank()) out.add(line)
        return out
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, yStart: Float, paint: Paint, maxWidth: Float, lineGap: Float = 10f): Float {
        var y = yStart
        wrap(text, paint, maxWidth).forEach { line ->
            canvas.drawText(line, x, y, paint)
            y += paint.textSize + lineGap
        }
        return y
    }

    fun quakeBitmap(quake: Quake): Bitmap {
        val width = 1080
        val height = 1420
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(0xFFF5F7FA.toInt())

        val mag = quake.magnitudeValue
        val headerColor = if (mag >= 5) 0xFFE4A900.toInt() else 0xFF1769E0.toInt()
        val sideColor = if (mag >= 5) 0xFFB91C1C.toInt() else 0xFF0A1730.toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = headerColor
        c.drawRect(0f, 0f, width.toFloat(), 250f, paint)
        paint.color = sideColor
        c.drawRect(0f, 0f, 300f, 250f, paint)

        paint.color = 0x33FFFFFF
        c.drawRect(34f, 34f, 266f, 216f, paint)
        c.drawText(String.format(Locale.US, "%.1f", mag), 82f, 145f, textPaint(64f, true, 0xFFFFFFFF.toInt()))

        c.drawText("INFO GEMPA", 335f, 75f, textPaint(28f, true, 0xFF5E2020.toInt()))
        c.drawText("WAKTU: " + quake.date + " " + quake.time, 335f, 118f, textPaint(24f, true))
        drawWrapped(c, quake.location, 335f, 158f, textPaint(20f, true, 0xFF3B2B2B.toInt()), 650f, 6f)

        var y = 305f
        val label = textPaint(24f, true)
        val value = textPaint(24f)
        fun field(name: String, text: String) {
            c.drawText(name + ":", 34f, y, label)
            y = drawWrapped(c, text, 180f, y, value, 850f, 4f) + 18f
        }
        field("Lokasi", quake.location)
        field("Koordinat", quake.coordinates)
        field("Kedalaman", quake.depth)
        field("Sumber", quake.source)
        if (quake.felt.isNotBlank()) field("Dirasakan", quake.felt)
        val tsunamiText = quake.tsunami.trim().ifBlank { "Status tidak tersedia; periksa BMKG/InaTEWS." }
        field("Potensi", tsunamiText)

        val tsunamiLower = tsunamiText.lowercase(Locale.ROOT)
        val negativeStatus = tsunamiLower.contains("tidak berpotensi tsunami") ||
            tsunamiLower.contains("tidak ada peringatan") || tsunamiLower.contains("no tsunami")
        val positiveStatus = !negativeStatus && (tsunamiLower.contains("berpotensi tsunami") ||
            tsunamiLower.contains("warning tsunami") || tsunamiLower.contains("peringatan dini tsunami"))
        paint.color = when {
            positiveStatus -> 0xFFFFE4E6.toInt()
            negativeStatus -> 0xFFEAF8F1.toInt()
            else -> 0xFFFFF4CC.toInt()
        }
        c.drawRoundRect(34f, y + 5f, 1046f, y + 170f, 22f, 22f, paint)
        y += 45f
        c.drawText("STATUS TSUNAMI", 60f, y, textPaint(22f, true))
        y = drawWrapped(c, tsunamiText, 60f, y + 38f, textPaint(24f, true), 940f, 6f) + 22f

        if (quake.shakemap.isNotBlank()) {
            paint.color = 0xFFE7EDF5.toInt()
            c.drawRoundRect(34f, y, 1046f, y + 280f, 18f, 18f, paint)
            c.drawText("SHAKEMAP BMKG", 60f, y + 42f, textPaint(22f, true))
            drawWrapped(c, "Peta guncangan resmi tersedia pada detail kejadian di aplikasi.", 60f, y + 82f, textPaint(20f), 940f, 5f)
        }

        paint.color = 0xFF0A1730.toInt()
        c.drawRect(0f, height - 126f, width.toFloat(), height.toFloat(), paint)
        c.drawText("WRS GEMPA  •  © Powered by Ilham", 34f, height - 88f, textPaint(22f, true, 0xFFFFFFFF.toInt()))
        c.drawText("Sumber resmi: BMKG data.bmkg.go.id/gempabumi", 34f, height - 57f, textPaint(17f, true, 0xFFD8E6FF.toInt()))
        c.drawText("Status tsunami: InaTEWS BMKG • inatews.bmkg.go.id", 34f, height - 28f, textPaint(17f, true, 0xFFD8E6FF.toInt()))
        return bitmap
    }

    fun tsunamiBitmap(title: String, status: String, detail: String): Bitmap {
        val width = 1080
        val height = 1320
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(0xFFF5F7FA.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = 0xFFB91C1C.toInt()
        c.drawRect(0f, 0f, width.toFloat(), 250f, paint)
        c.drawText("!", 70f, 145f, textPaint(95f, true, 0xFFFFFFFF.toInt()))
        c.drawText("PERINGATAN TSUNAMI", 180f, 95f, textPaint(30f, true, 0xFFFFFFFF.toInt()))
        c.drawText(status, 180f, 148f, textPaint(42f, true, 0xFFFFFFFF.toInt()))

        var y = 320f
        c.drawText(title, 48f, y, textPaint(30f, true))
        y += 62f
        y = drawWrapped(c, detail, 48f, y, textPaint(23f), 980f, 8f) + 20f

        paint.color = 0xFFFFF4CC.toInt()
        c.drawRoundRect(48f, y, 1032f, y + 210f, 20f, 20f, paint)
        c.drawText("PETA & INFORMASI DAMPAK", 78f, y + 55f, textPaint(23f, true))
        drawWrapped(c, "Peta perkiraan tinggi muka laut, wilayah berpotensi terdampak, dan status peringatan ditampilkan dari InaTEWS BMKG pada halaman Tsunami di aplikasi.", 78f, y + 98f, textPaint(20f), 930f, 6f)

        paint.color = 0xFF0A1730.toInt()
        c.drawRect(0f, height - 126f, width.toFloat(), height.toFloat(), paint)
        c.drawText("WRS GEMPA  •  © Powered by Ilham", 48f, height - 88f, textPaint(22f, true, 0xFFFFFFFF.toInt()))
        c.drawText("Sumber resmi: BMKG • data.bmkg.go.id/gempabumi", 48f, height - 57f, textPaint(17f, true, 0xFFD8E6FF.toInt()))
        c.drawText("InaTEWS BMKG • inatews.bmkg.go.id", 48f, height - 28f, textPaint(17f, true, 0xFFD8E6FF.toInt()))
        return bitmap
    }

    fun shareBitmap(context: Context, bitmap: Bitmap, fileName: String, chooserTitle: String, shareText: String = ""): Boolean {
        return try {
            val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
            val file = File(dir, fileName + ".png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                if (shareText.isNotBlank()) putExtra(Intent.EXTRA_TEXT, shareText)
                clipData = ClipData.newUri(context.contentResolver, "WRS GEMPA", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String): Boolean {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val finalName = fileName + "_" + stamp + ".png"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WRS GEMPA")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WRS GEMPA").apply { mkdirs() }
                val file = File(dir, finalName)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun captureView(view: android.view.View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                view.draw(Canvas(bitmap))
            }
        } catch (_: Exception) {
            null
        }
    }
}