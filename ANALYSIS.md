# Analisis Kotatsu 9.4.1 (build 1033) — via REA

Target: `org.koitharu.kotatsu_9.4.1-1033_...apk`, SHA-256 `79b5698c8c35fcbb41a59126e10a7dd3ce7cf2cbdddb64d53dc799a0376be184`.

## Bukti (REA)
| Evidence | Isi |
|---|---|
| `ev_754eb123…` (inspect_artifact) | Inventaris ZIP lengkap: 1428 entri, 0 kontradiksi integritas |
| `ev_5ab02312…` (project_android_application_graph) | `classes.dex`, `classes2.dex`, `resources.arsc`, native `libavif_android.so` + `libconscrypt_jni.so` untuk 4 ABI |

JADX dan apktool tidak terpasang, jadi manifest/bytecode **tidak** didekompilasi. Fakta di bawah berasal dari
pemindaian string descriptor di DEX (statis, terobservasi) dan file `META-INF/*.version`.

## Arsitektur (terobservasi dari nama class di DEX)
- **Kotlin + AndroidX Views + Material**, DI dengan **Hilt/Dagger**, async dengan **kotlinx-coroutines**.
- **Network**: OkHttp3 + Conscrypt (TLS) + Jsoup (scraping HTML); `assets/isrgrootx1.pem` (root Let's Encrypt untuk Android lama).
- **Gambar**: Coil 3, `subsampling-scale-image-view` (davemorrissey) untuk zoom, libavif untuk AVIF.
- **DB**: Room. Tabel: `manga, tags, manga_tags, chapters, history, favourites, favourite_categories, bookmarks,
  preferences, sources, tracks, track_logs, suggestions, scrobblings, stats, local_index`.
- **Background**: WorkManager (+hilt-work) untuk tracker update chapter baru & download.
- **Crash report**: ACRA.
- **Parser** (`org.koitharu.kotatsu.parsers`, library terpisah): `MangaParser` → `AbstractMangaParser` →
  `PagedMangaParser`/`SinglePageMangaParser`; model `Manga, MangaChapter, MangaPage, MangaTag, MangaListFilter, SortOrder,
  MangaParserSource`. Ratusan implementasi situs di `parsers/site/**` (48 grup/bahasa terdeteksi).
- **Modul fitur** (`org.koitharu.kotatsu.*`): explore, search, filter, details, reader, history, favourites, bookmarks,
  download, local (CBZ), tracker, suggestions, scrobbling (AniList, MAL, Kitsu, Shikimori, Discord), sync, backups, stats, widget.

## Alur inti
`Sumber (parser) → getList/search → details (getDetails: chapter) → reader (getPages → getPageUrl → Coil)`,
dengan posisi baca disimpan ke tabel `history` (`chapter_id, page, scroll, percent`).

## Clone v0.3 — semua sumber Kotatsu
- Parser buatan sendiri (MangaReader/Madara/MangaDex) diganti dengan library resmi `com.github.KotatsuApp:kotatsu-parsers:4d1e521aef`
  (JitPack, GPL-3.0). Ini commit yang sama dengan `gradle/libs.versions.toml` Kotatsu v9.4.1, dan repo upstream sudah diarsipkan sejak Nov 2025.
- 1256 sumber; 374 ditandai *broken* oleh upstream dan disembunyikan, begitu juga sumber novel (teks). Sisanya 882 sumber yang tampil di **Katalog sumber**.
- `parser/KotatsuContext.kt` = `MangaLoaderContextImpl`: OkHttp 5, cookie WebView, UA WebView, `evaluateJs` lewat WebView,
  descrambling gambar (Bitmap), domain override per sumber (`ConfigKey.Domain` ← SharedPreferences `domains`).
- `SourceHeadersInterceptor` = `CommonHeadersInterceptor`: header sumber + interceptor parser untuk request parser (tag) dan gambar/unduhan
  (header `X-Manga-Source`). `CloudflareInterceptor`: request GET parser yang kena challenge diulang di WebView tersembunyi.
- `parser/KotatsuSource.kt`: adapter ke interface app. Daftar memakai paging offset, chapter dibalik ke urutan terbaru dulu,
  dan untuk sumber multi-terjemahan dipilih satu *branch* (bahasa perangkat, kalau tidak ada yang terbesar).
- DB v2: kolom `manga.publicUrl` (migrasi 1→2, ikut di backup). Sumber aktif default: KomikStation, MangaRead, MangaDex.

### v0.4 — Desain ulang minimalis (UI/UX Pro Max + Dribbble "manga reader app")
- **Gaya:** Minimalism & Swiss. Permukaan datar, garis tipis (hairline) sebagai pengganti bayangan, satu aksen.
- **Palet "kertas & tinta":**
  - Netral stone + aksen amber.
  - Terang: primary amber-700 `#B45309` (amber-500 ditolak karena kontrasnya hanya 3,2:1).
  - Gelap: permukaan stone-950 `#0C0A09` + amber-400.
  - Warna wallpaper hanya opsional.
- **Font:** Plus Jakarta Sans (OFL, `res/font`, variable font; lisensi di `assets/`). Judul ExtraBold/Bold dengan tracking rapat, isi teks Regular/Medium. Font dipasang lewat textAppearance karena `fontFamily` di tema akan menimpa bobot.
- **Dari Dribbble** (polanya saja): kartu hero "Lanjut membaca", latar cover yang di-blur pada detail, badge pill amber, judul seksi tebal.
- **Interaksi** (semua mati kalau skala animasi sistem 0):
  - Item list muncul bertahap (stagger).
  - Kartu mengecil 0,96 saat ditekan.
  - Fade-through antar tab.
  - Transisi geser + fade antar layar.
- **Komponen:** judul layar besar, nav bawah dengan indikator pill, kolom cari berbentuk pill, tile aksi cepat, chip pill, dialog bersudut 28dp.

### v0.3.1 — Katalog ala Kotatsu + polish UI (UI/UX Pro Max)
- **Katalog sumber** (`ui/SourcesUi.kt`, mengikuti `SourcesCatalogActivity` Kotatsu):
  - Hanya menampilkan sumber yang belum aktif.
  - Chip bahasa (default bahasa perangkat) dan filter chip tipe konten, diurutkan menurut jumlah sumber.
  - Pencarian di toolbar.
  - Favicon diambil lewat `getFavicons()` lalu disimpan di disk; kalau gagal, ikon huruf.
  - Sumber rusak ditandai ikon ⊘, tombol + memunculkan snackbar "Urungkan".
- **Jelajah:** baris sumber memakai favicon; tombol ⋮ atau tekan lama membuka menu "Ganti domain" / "Nonaktifkan".
- **Tema:**
  - Warna brand rose `#E11D48` (rekomendasi UI/UX Pro Max untuk aplikasi hiburan); pada Android 12+ semua peran M3 diturunkan dari seed itu.
  - Opsi "Warna dari wallpaper" ada di Pengaturan.
  - Palet statis terang/gelap tersedia untuk Android < 12.
- **Cover:** rasio 2:3 tetap (`CoverImageView`), jadi tidak ada lompatan layout.
- **Empty state:** pakai ikon.
- **Aksesibilitas:** ikon dekoratif disembunyikan dari pembaca layar, aksi ikon diberi label, target sentuh ≥48dp.

## Clone v0.2 — UI & fitur ala Kotatsu
| Kotatsu (terobservasi di DEX) | Clone |
|---|---|
| Tab Riwayat · Favorit · Jelajah · Feed | `MainActivity` + `MainFragments.kt` (badge jumlah update baru) |
| Room: manga, chapters, history, favourites, favourite_categories, bookmarks, track_logs, stats, scrobblings | `data/Entities.kt`, `AppDatabase.kt` (Room + KSP) |
| Kategori favorit + "track" per kategori | dialog kategori di tab Favorit; multi-pilih di Detail |
| Reader webtoon/halaman, slider, bookmark | `ReaderActivity`: webtoon, halaman LTR/RTL, zona tap, chapter sebelum/berikut, bookmark, statistik |
| DownloadWorker / local | `work/Workers.kt` + `LocalStorage` (folder gambar per chapter, baca offline) |
| TrackWorker → Feed + notifikasi | `TrackerWorker` periodik (3/6/12/24 jam), "Cek sekarang" |
| Global search, detail, buka di browser, share | `SearchActivity`, `DetailsActivity` |
| Scrobbling AniList/MAL/Kitsu/Shikimori | `scrobbling/` — Client ID dari pengguna, redirect `mangareader://oauth/<id>` |
| backups/ | Pengaturan → Buat/Pulihkan backup (JSON via SAF; token tidak ikut) |
| CloudFlareActivity + WebViewExecutor | `BrowserActivity` + `WebViewFetcher` (fallback otomatis untuk halaman GET) |
| Statistik baca | `StatsActivity` |

Belum ada: widget, saran (suggestions), Discord RPC, kunci biometrik, sync server, import CBZ, zoom gambar,
filter berdasarkan tag, refresh-token otomatis scrobbler (login ulang saat token habis).

## Clone v0.1 (sumber & domain)
Mengikuti model sumber Kotatsu: parser per *engine* dipakai ulang untuk banyak situs, domain tiap situs bisa diganti.
- `parser/MangaParser.kt` — kontrak `getList(page, query)` / `getDetails` / `getPages`; URL disimpan **relatif** ke domain.
- `parser/site/MangaReaderParser.kt` (Themesia/MangaStream, umum di situs Indonesia) dan `MadaraParser.kt` (Madara) — Jsoup.
- `parser/Sources.kt` — daftar sumber. Situs baru dengan engine yang sama = 1 baris.
- Menu **Sumber & domain** (`SourcesActivity`) — override domain per sumber (SharedPreferences `domains`); favorit/riwayat tetap jalan
  karena id = `sumber:url-relatif`.
- Cloudflare: cookie OkHttp = `CookieManager` WebView, UA sama dengan WebView, `BrowserActivity` untuk menyelesaikan challenge.
- MangaDex tetap ada (API resmi, domain tetap).
- Test: `EngineParsersTest` memakai HTML asli (komikstation.org, mangaread.org, 2026-11-22) di `app/src/test/resources/fixtures`.

Tidak dibuat: plugin parser eksternal, download/CBZ, tracker/WorkManager, sync, scrobbling, backup, kategori favorit,
mode baca paged/zoom, filter tag, Hilt, varian AJAX lama Madara (`admin-ajax.php`).
