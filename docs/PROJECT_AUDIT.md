# Audit Proyek — Fase 0 Folium

Tanggal audit: 2026-11-23. Sumber: repositori `D:\src\manga-reader` apa adanya (bukan asumsi).
Riwayat pembuatan clone ada di `ANALYSIS.md`.

## 1. Ringkasan

| Aspek | Temuan |
|---|---|
| Jenis | Aplikasi Android native, satu modul `:app`, ~3.800 baris Kotlin (24 file + 2 file test) |
| Asal | Clone Kotatsu yang ditulis ulang (bukan fork kode Kotatsu); sumber konten memakai library resmi `kotatsu-parsers` |
| Identitas saat ini | `applicationId` `dev.mangareader`, nama "Manga Reader", versionCode 2 / versionName 0.2 |
| Git | **Bukan repositori git**: tidak ada riwayat dan tidak ada titik rollback |
| Lisensi | **Tidak ada `LICENSE`/`NOTICE`**; lihat §9 |

## 2. Tech stack (terverifikasi dari `app/build.gradle.kts`, `settings.gradle.kts`)

- **Bahasa:** Kotlin 2.2.0 (JVM 17), AGP 8.11.1, Gradle 8.14.3 (wrapper), KSP 2.2.0-2.0.2.
- **SDK:** compile/target 36, min 23, core library desugaring (`desugar_jdk_libs` 2.1.5).
- **UI:**
  - Android Views + ViewBinding (tanpa Compose).
  - Material Components 1.12, AppCompat, RecyclerView, ViewPager2, SwipeRefresh, Preference.
- **Data:** Room 2.7.2 (KSP), SharedPreferences.
- **Jaringan:**
  - OkHttp 5.2.1 + Jsoup 1.21.2.
  - Cookie berbagi dengan WebView `CookieManager`, dengan fallback WebView untuk Cloudflare.
- **Gambar:** Coil 3.2.0 (`coil-network-okhttp`), ditambah fetcher favicon kustom.
- **Background:** WorkManager 2.10.2 (unduhan, pelacak chapter baru).
- **Sumber konten:**
  - `com.github.KotatsuApp:kotatsu-parsers:4d1e521aef` (JitPack, GPL-3.0); commit sama dengan Kotatsu 9.4.1.
  - Repo upstream diarsipkan sejak Nov 2025.
- **Tanpa DI** (Hilt tidak dipakai): service locator di `App.kt` (`Context.app`).
- **Font:** Plus Jakarta Sans (variable, OFL) di `res/font`, lisensi di `assets/OFL-PlusJakartaSans.txt`.

## 3. Titik masuk & navigasi

- **`App`:**
  - Membuat DB, OkHttp + interceptor sumber/Cloudflare, dan daftar sumber.
  - Juga ImageLoader (Coil), scrobbler, penjadwalan tracker, dan opsi warna wallpaper.
- **`MainActivity`** (launcher): toolbar besar + bottom nav 4 tab, yaitu **Riwayat · Favorit · Jelajah · Pembaruan**. Pengaturan dan cari ada di menu toolbar.
- **Activity lain:**
  - `DetailsActivity`, `ReaderActivity`.
  - `SourceActivity` (katalog satu sumber), `SearchActivity` (global).
  - `SourcesActivity` (katalog sumber ala Kotatsu).
  - `BookmarksActivity`, `DownloadsActivity`, `StatsActivity`.
  - `SettingsActivity`, `ScrobblersActivity`, `OAuthActivity` (deep link `mangareader://oauth/<id>`).
  - `BrowserActivity` (WebView dalam aplikasi).

## 4. Persistensi (Room `kotatsu-lite.db`, versi 2, migrasi 1→2 menambah `manga.publicUrl`)

| Tabel | Isi |
|---|---|
| `manga` | Cache metadata: id = `"<SOURCE>:<url relatif>"`, title, cover, deskripsi, author, tags, source, publicUrl |
| `chapters` | url, nama, posisi (urutan terbaru dulu) |
| `favourite_categories` / `favourites` | Kategori + flag `track` |
| `history` | chapterUrl, chapterName, page, percent, updatedAt (1 baris per manga) |
| `bookmarks` | Per halaman, dengan imageUrl |
| `track_logs` | Feed chapter baru |
| `stats` | Waktu baca + halaman |
| `scrobblings` | Tautan ke AniList/MAL/Kitsu/Shikimori |
| `downloads` | Antrean/status unduhan |

SharedPreferences: `default` (pengaturan, `sources_enabled`), `domains` (override domain per sumber), `scrobblers` (token).
File: `files/downloads/<sha1 manga>/<sha1 chapter>/NNNN.ext` + penanda `.complete`. Backup/restore berupa JSON via SAF.

## 5. Reader komik (`ReaderActivity`, 319 baris)

- **Sudah ada:**
  - Mode webtoon (scroll vertikal), halaman LTR, dan halaman RTL.
  - Zona ketuk, slider halaman, chapter sebelum/berikut, bookmark, imersif, statistik.
  - Baca lokal lebih dulu kalau sudah diunduh.
- **Progres** disimpan saat chapter dimuat/berganti dan saat `onPause`, tidak di setiap scroll (sudah memenuhi "hindari write berlebih").
- **Belum ada:**
  - Zoom (pinch), kecerahan, warna latar reader.
  - Pindah otomatis ke chapter berikut di ujung webtoon.
  - Pengaturan tampilan reader yang terpisah dari tema aplikasi (baru ada mode baca).

## 6. Sumber konten

- **Adapter:**
  - `parser/KotatsuSource.kt` memetakan parser Kotatsu ke interface `MangaParser` milik app.
  - Daftar memakai paging offset, chapter dibalik ke terbaru dulu, dan dipilih satu *branch* terjemahan.
- **Context:** `parser/KotatsuContext.kt` = `MangaLoaderContextImpl`. Isinya config domain, `evaluateJs` WebView, descrambling bitmap, interceptor header/Cloudflare.
- **Jumlah sumber:**
  - 1256 sumber Kotatsu, **semuanya komik**. Dari anotasi: 948 MANGA, 290 HENTAI, 11 COMICS, 6 OTHER, 1 MANHWA, dan **0 NOVEL**. Kata "novel" di parser hanya opsi filter, dan `getPages` selalu mengembalikan gambar. Sumber rusak tetap tampil dengan tanda.
  - Default aktif: KomikStation, MangaRead, MangaDex.
- **Status jaringan saat diuji:**
  - MangaDex diblokir dari jaringan pengguna.
  - `komikcast.li` sekarang mengarah ke situs judi (domain berpindah tangan).

## 7. Unduhan & offline

`DownloadWorker` (foreground, dataSync) + `LocalStorage` (tulis atomik). Reader otomatis memakai file lokal; sudah terverifikasi offline.

## 8. Tema & UI saat ini

- **Token terpusat:** `values/colors.xml` + `values-night/colors.xml` (peran M3), `values/themes.xml`.
- **Isi `themes.xml`:** tipografi, bentuk, gaya komponen, animasi layar.
- **Palet saat ini:** stone + amber.
- **Bentuk & komponen:**
  - Radius 10/16/24/28dp.
  - Komponen bersama: `SourceAdapter`, `MangaAdapter`, `AnimatedListAdapter`, `CoverImageView` (2:3), `LetterDrawable`, empty/error state, kartu hero "Lanjut membaca".
- **Interaksi:** stagger, press-scale, fade-through tab; semua menghormati skala animasi 0.
- **Ikon:** vektor Material **filled** (bukan outline; spesifikasi Folium meminta outline).

## 9. Lisensi & atribusi

- **`kotatsu-parsers` berlisensi GPL-3.0** dan di-link ke dalam APK, jadi distribusi APK Folium harus berlisensi GPL-3.0 (atau kompatibel) dengan kode sumber tersedia. Repo belum punya `LICENSE`. **Perlu keputusan pemilik.**
- **Kode aplikasi ditulis ulang**, tidak menyalin kode Kotatsu, tapi masih banyak komentar "Kotatsu: …" sebagai rujukan desain. Merek "Kotatsu" tidak dipakai di UI, kecuali satu nama file DB internal `kotatsu-lite.db`; nama file ini tidak terlihat pengguna dan **tidak boleh diganti** tanpa migrasi data.
- **Font:** Plus Jakarta Sans (OFL), sudah ada lisensinya. Font usulan Folium (Playfair Display, Inter, Noto Serif) juga OFL. Noto Sans JP tidak perlu dibundel karena Android sudah punya Noto CJK sistem.
- **File APK Kotatsu asli** (`org.koitharu.kotatsu_9.4.1-…apk`) ada di root repo. Ini binari pihak ketiga yang tidak boleh ikut didistribusikan; **sebaiknya dipindah keluar repo.**

## 10. Test & build

- `./gradlew assembleDebug testDebugUnitTest` (JDK 24 di `C:\Program Files\Java\jdk-24`, SDK `D:\dev\sdk`).
- **12 unit test, semua lulus:**
  - Backup round-trip, tolak versi asing, diff tracker, nomor chapter, nama file halaman, tulis atomik, parsing AniList.
  - Semua sumber bisa diinstansiasi dengan domain valid, id sumber stabil, pemilihan branch, normalisasi domain, deteksi Cloudflare.
- **Belum ada:** instrumented test dan CI. UI diuji manual di emulator AVD `mr_test` (Android 16).

## 11. Risiko regresi & migrasi

1. **Ganti `applicationId` ke Folium** membuat Android menganggapnya aplikasi baru; data di perangkat yang sudah terpasang tidak ikut. Saat ini hanya ada data uji, tapi tetap keputusan pemilik.
2. **Status baca** (Want to Read, …) butuh skema baru (DB v3). Harus migrasi aditif (`ADD`/tabel baru), tanpa menghapus data.
3. **Tipe konten per manga tidak tersedia:** parser hanya memberi `contentType` per *sumber*. Filter Manga/Manhwa/Manhua di Library harus memakai tipe sumber (perkiraan; sumber campuran memberi hasil kurang tepat).
4. **Novel/EPUB/PDF belum ada sama sekali:** menu atau kategori untuknya tidak boleh tampil sebelum readernya ada (aturan "tanpa kontrol nonfungsional").
5. **Library parser sudah diarsipkan:** domain kedaluwarsa bisa mengarah ke situs berbahaya (contoh KomikCast).
6. **Tidak ada git:** perubahan besar tanpa titik rollback. Sangat disarankan `git init` + commit sebelum Fase 1.
