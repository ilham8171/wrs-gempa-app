# WRS GEMPA Android

Aplikasi Android native untuk menampilkan informasi gempa terbaru dari BMKG.

## Fitur versi awal
- Dashboard Kotlin + Jetpack Compose
- Mengambil data gempa terbaru dari endpoint publik BMKG saat aplikasi dibuka
- Informasi magnitudo, lokasi, waktu, kedalaman, dan potensi tsunami
- Tombol untuk memperbarui data
- Pilihan tampilan terang/gelap
- GitHub Actions untuk membangun APK debug

## Build APK
Buka repository ini dengan Android Studio, lakukan Gradle Sync, lalu pilih **Build > Build APK(s)**. Workflow GitHub Actions juga disiapkan untuk membangun APK debug; setelah workflow selesai, unduh artifact `wrs-gempa-debug-apk`.

## Belum tersedia
Peta interaktif, riwayat gempa, perhitungan gempa terdekat, dan notifikasi otomatis di latar belakang belum diterapkan. Notifikasi otomatis membutuhkan implementasi layanan latar belakang/backend yang andal. Aplikasi ini bukan pengganti peringatan resmi BMKG atau arahan petugas.

Sumber data: BMKG, endpoint publik informasi gempa terbaru.
