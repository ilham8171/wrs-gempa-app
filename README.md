# WRS GEMPA Android

Aplikasi Android native untuk menampilkan informasi gempa terbaru dari BMKG.

## Fitur
- Dashboard Kotlin + Jetpack Compose dengan mode terang/gelap
- Data gempa terbaru, gempa M5+, gempa dirasakan, riwayat lokal, dan detail kejadian
- Perhitungan jarak gempa berdasarkan koordinat GPS pada fitur Sekitar Saya
- Notifikasi gempa melalui WorkManager dan Firebase Cloud Messaging
- Halaman prakiraan cuaca 3 hari berbasis GPS atau koordinat manual
- Tombol menuju kanal resmi peringatan dini cuaca BMKG
- Penyimpanan dan berbagi gambar informasi/ShakeMap jika data gambar tersedia

## Fitur versi awal
- Dashboard Kotlin + Jetpack Compose
- Mengambil data gempa terbaru dari endpoint publik BMKG saat aplikasi dibuka
- Informasi magnitudo, lokasi, waktu, kedalaman, dan potensi tsunami
- Tombol untuk memperbarui data
- Pilihan tampilan terang/gelap
- GitHub Actions untuk membangun APK debug

## Prakiraan cuaca
Prakiraan saat ini menggunakan Open-Meteo untuk koordinat GPS/manual. Untuk keselamatan, peringatan dini resmi harus dikonfirmasi melalui BMKG; prakiraan cuaca bukan pengganti peringatan resmi.

## Build APK
Buka repository ini dengan Android Studio, lakukan Gradle Sync, lalu pilih **Build > Build APK(s)**. Workflow GitHub Actions juga disiapkan untuk membangun APK debug; setelah workflow selesai, unduh artifact `wrs-gempa-debug-apk`.

## Belum tersedia
Peringatan dini cuaca otomatis yang terpersonalisasi per wilayah dan notifikasi cuaca latar belakang belum diaktifkan; halaman cuaca saat ini menampilkan prakiraan dan menyediakan tautan ke peringatan resmi BMKG. Notifikasi gempa berkala menggunakan batasan Android WorkManager (interval minimum 15 menit) dan tidak menjamin peringatan seketika. Aplikasi ini bukan pengganti peringatan resmi BMKG atau arahan petugas.

Sumber data: BMKG, endpoint publik informasi gempa terbaru.
