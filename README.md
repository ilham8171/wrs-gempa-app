# WRS GEMPA Android

Aplikasi pemantauan gempa bumi Indonesia berbasis Android native (Kotlin + Jetpack Compose). Proyek ini mempertahankan integrasi data yang ada sambil merapikan antarmuka, atribusi sumber, dan pengalaman berbagi informasi.

## Fitur saat ini

- Dashboard ringkasan gempa terbaru dan daftar kejadian.
- Detail gempa termasuk magnitudo, kedalaman, lokasi, waktu, dan informasi potensi tsunami yang tersedia dari sumber.
- Peta ringkasan pada dashboard serta halaman peta.
- Halaman prakiraan cuaca BMKG berbasis lokasi perangkat.
- Dashboard status tsunami yang menekankan verifikasi ke kanal resmi InaTEWS.
- Notifikasi Android/FCM dan pemantauan latar belakang berkala sesuai konfigurasi proyek.
- Simpan dan bagikan kartu informasi gempa/tsunami sebagai gambar.
- Gambar hasil ekspor mencantumkan identitas `© Powered by Ilham` dan atribusi sumber.
- Halaman Informasi menjelaskan sumber data dan batasan keselamatan.

## Struktur kode penting

- `MainActivity.kt` — navigasi dan layar utama Compose yang masih memuat sebagian besar alur UI.
- `WrsDesignSystem.kt` — token warna bersama agar gaya antarlayar konsisten.
- `WrsDataSources.kt` — alamat sumber resmi dan catatan keselamatan terpusat.
- `ShareUtils.kt` — pembuatan, penyimpanan, dan berbagi gambar.
- `EarthquakeWorker.kt` — pekerjaan latar belakang pemantauan.
- `WrsFirebaseMessagingService.kt` — penanganan pesan FCM.

## Sumber informasi

- BMKG — data gempa: https://data.bmkg.go.id/gempabumi/
- BMKG — prakiraan cuaca: https://api.bmkg.go.id/publik/prakiraan-cuaca
- InaTEWS BMKG — informasi tsunami: https://inatews.bmkg.go.id/
- OpenStreetMap — atribusi peta: https://www.openstreetmap.org/copyright

Data dan tampilan aplikasi merupakan sarana pemantauan, bukan pengganti peringatan resmi. Untuk tindakan keselamatan, ikuti instruksi BMKG/InaTEWS dan petugas berwenang.

## Build APK

1. Buka repository ini di Android Studio.
2. Jalankan Gradle Sync.
3. Pilih **Build > Build APK(s)**.

Workflow GitHub Actions juga membangun APK debug. Setelah workflow berhasil, buka tab **Actions**, pilih run terbaru, lalu unduh artifact `wrs-gempa-debug-apk`.

## Catatan pengembangan

Aplikasi ini adalah proyek Android native Kotlin/Compose, bukan proyek Flutter. Perubahan UI sebaiknya tetap menggunakan struktur native yang ada dan tidak mengganti endpoint, worker, atau alur notifikasi tanpa alasan teknis yang terverifikasi.
