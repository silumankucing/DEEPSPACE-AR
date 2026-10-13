# DeepSpace Android (Kotlin)

Project Android sederhana berbasis Kotlin + View XML.

## Struktur

- `app/` module aplikasi Android
- `settings.gradle.kts` konfigurasi module root
- `build.gradle.kts` plugin root

## Menjalankan

1. Buka folder ini di Android Studio.
2. Jika belum ada Gradle Wrapper, jalankan di terminal folder project:
   - `gradle wrapper`
3. Sync Gradle.
4. Run `app` ke emulator/perangkat.

## Catatan

- Pastikan Android SDK sudah terpasang.
- Jika diminta versi JDK, gunakan JDK 17.

.\gradlew.bat build installDebug