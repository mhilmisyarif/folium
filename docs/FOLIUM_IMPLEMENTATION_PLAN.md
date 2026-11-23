# Rencana Implementasi Folium

Status per kebutuhan spesifikasi, lalu fase sisa. Yang sudah dikerjakan **dilewati** (sesuai permintaan).

## A. Sudah ada (tidak dikerjakan ulang)

| Kebutuhan | Bukti |
|---|---|
| Fungsi Kotatsu inti: sumber, katalog, cari global, detail, unduhan/offline, bookmark, riwayat, favorit + kategori, feed chapter baru, statistik, scrobbling, backup | `ANALYSIS.md`, diuji di emulator |
| Token warna semantik terpusat terang/gelap + ikut sistem + override manual | `colors.xml`, `values-night`, pref `theme` |
| Sistem tipografi lewat text appearance, font kustom terbundel | `themes.xml`, `res/font` |
| Bentuk/radius sebagai token, elevasi minim | `ShapeAppearance.App.*` |
| Empty / error state dengan tindakan pemulihan, tanpa stack trace mentah | `Widget.App.EmptyState`, `showErrorState`, `errorMessage()` |
| Kartu "Lanjut membaca" (cover, judul, progres, CTA) | `header_explore.xml` |
| Reader komik: webtoon/LTR/RTL, navigasi halaman & chapter, progres tersimpan tanpa write per-scroll | `ReaderActivity` |
| Animasi halus + menghormati "matikan animasi" | `animationsEnabled()` |

## B. Fase sisa (urut prioritas)

### Fase 1 — Fondasi brand Folium — **SELESAI (2026-11-23)**
Hasil:
- **Palet:** `colors.xml`/`values-night` (brand + peran M3 + token `on_image*`/`image_scrim*`); semua pasangan teks ≥4,5:1.
- **Font:** Playfair Display 600 (Display 40 / H1 32 / H2 24 / H3 20) + Inter (Body 16/14, Caption 12); lisensi OFL di `assets/`.
- **Token tata letak:** `dimens.xml` `space_1…space_12`, 79 nilai layout di-snap ke grid 4dp; radius 8/12/16/24/999.
- **Ikon:** 20 ikon diganti Material Symbols Outlined (Apache-2.0).
- **Brand:** logo daun (`ic_folium_logo`), ikon launcher adaptif + monochrome + fallback Android 6–7, nama "Folium", halaman Tentang dengan atribusi.
- **Lain-lain:** ikon huruf favicon memakai warna brand kalem; nama file backup `folium-backup.json`.
- **Perbaikan sampingan:** judul tab hilang setelah layar dibuat ulang (ganti tema/rotasi).

Tidak diubah, menunggu keputusan pemilik: `applicationId`, nama file DB, skema OAuth `mangareader://`, lisensi repo.

Rencana awal Fase 1:
1. **Palet Folium** → `colors.xml` / `values-night/colors.xml`, dengan penyesuaian kontras dari `FOLIUM_ARCHITECTURE.md` §2.
2. **Font:** Playfair Display (judul) + Inter (UI) dari `google/fonts` (OFL), ganti Plus Jakarta Sans di `TextAppearance.App.*`. Lisensi disalin ke `assets/`.
3. **Token spasi** `dimens.xml` (4px), radius 8/12/16/24/999.
4. **Ikon outline:** ganti 20 vektor filled di `res/drawable/ic_*.xml` dengan Material Symbols Outlined.
5. **Brand:**
   - `app_name` = "Folium", tagline di layar Home/Settings.
   - Logo minimalis (vektor, daun/halaman) + ikon launcher adaptif. Saat ini belum ada ikon launcher kustom.
6. **Belum diubah** (butuh keputusan): `applicationId`, `namespace`, nama file DB.

### Fase 2 — Shell & navigasi — **SELESAI (2026-11-23)**
- **Navigasi bawah:** Beranda · Library · Jelajah · Pengaturan (`menu/bottom_nav.xml`, `MainActivity`). Badge chapter baru ada di Beranda.
- **Beranda** (`HomeFragment`, `fragment_home.xml`, `view_rail.xml`):
  - Sapaan sesuai jam, cari, kartu hero "Lanjut membaca".
  - Rail riwayat lain, rail "Baru diperbarui" (feed `track_logs`, dengan "Lihat semua" → `FeedActivity`).
  - **Rekomendasi** dari `MangaParser.getRelated()` → `getRelatedManga` parser untuk manga terakhir dibaca; disembunyikan kalau kosong atau gagal.
  - "Jelajahi menurut jenis" (Manga/Manhwa/Manhua/Komik → katalog terfilter).
  - Empty state kalau belum ada riwayat.
- **Library** (`LibraryFragment`): tile Unduhan/Bookmark/Statistik + tab Favorit (dengan koleksi) / Riwayat, memakai ulang `FavouritesFragment`/`HistoryFragment`.
- **Jelajah:** cari + chip jenis + sumber aktif + katalog. Hero dan tile dipindah ke Beranda/Library.
- **Pengaturan:**
  - Jadi tab, dikelompokkan Tampilan / Membaca / Library / Penyimpanan / Sinkronisasi / Folium.
  - Indentasi ikon kosong dihapus, dan emoji 🔔 di dialog kategori diganti teks "dipantau".
- **Notifikasi chapter baru:** Beranda + `FeedActivity` (pengganti `SettingsActivity` yang kini tak terpakai).
- **Keterbatasan data:** jenis konten adalah label per *sumber* dari kotatsu-parsers. Hanya 1 sumber berlabel MANHWA, karena kebanyakan situs manhwa berlabel MANGA, jadi filter jenis hanya seakurat label upstream.
- **Belum:** dua baris tab bertumpuk di Library (tab bagian + tab koleksi). Ini dirapikan di Fase 3 saat koleksi/filter jadi chip.

Rencana awal Fase 2:
Home · Library · Explore · Pengaturan, memakai ulang fragment yang ada (lihat arsitektur §3). Rekomendasi hanya dari `getRelatedManga`; seksi disembunyikan kalau tidak ada data.

### Fase 3 — Library — **SELESAI (2026-11-23)**
- **Data:**
  - Tabel `library_status` (entity `LibraryStatusEntity`, enum `ReadingStatus` dengan kode stabil 1–5).
  - Room v3 dengan **migrasi 2→3 aditif** (`CREATE TABLE` saja). Terverifikasi di emulator di atas DB v2 berisi data; semua favorit dan riwayat utuh.
- **Status efektif:** status pilihan pengguna, atau "Sedang dibaca" kalau ada riwayat (diturunkan, tidak ditulis).
- **Query `observeLibrary()`:** semua manga yang difavoritkan, ada di riwayat, atau punya status.
- **Fungsi murni `filterLibrary`:**
  - Tampilan: Semua/Favorit/Riwayat/koleksi.
  - Filter status, filter jenis (per sumber), cari judul/author.
  - Urut: terakhir dibaca / judul / ditambahkan.
- **UI Library:**
  - Chip tampilan; chip koleksi muncul hanya kalau ada >1 koleksi, sehingga dua baris tab bertumpuk hilang.
  - Chip dropdown status/jenis/urut (urutan diingat), cari lokal, grid/daftar (diingat). Daftar menampilkan status · chapter + progress bar; grid memberi badge untuk status pilihan.
  - Tekan lama: ubah status / hapus dari riwayat.
  - Menu: kelola koleksi, hapus riwayat.
- **Detail:** tombol "Tambahkan ke Library" / "Status: …" (`chooseStatus`).
- **Backup:** field `statuses` opsional, tanpa naik versi. Backup lama tetap terbaca, kode status asing dilewati.
- **Kode yang dihapus** karena digantikan, tanpa duplikasi: `FavouritesFragment`, `HistoryFragment`, `menu/favourites.xml`, `menu/history.xml`.
- **Koleksi bawaan baru** bernama "Utama". Koleksi lama tidak diganti namanya.
- **Test:** 4 test baru di `LibraryTest` (status efektif, tampilan/koleksi/status/jenis, cari & urut, backup). Total 16 lulus. Belum ada test instrumented untuk migrasi Room; migrasi diverifikasi manual di emulator.

Rencana awal Fase 3:
Tabel `library_status` (migrasi 2→3 aditif), pemilih status di Detail, filter (status, tipe, koleksi), urut (terakhir dibaca, judul, ditambahkan), cari lokal, grid/list, backup ikut status.

### Fase 4 — Penyempurnaan reader komik — **SELESAI (2026-11-23)**
- **Zoom** (mode halaman): `ZoomImageView` berbasis Matrix, tanpa dependensi baru.
  - Pinch 1–4×, ketuk ganda 2,5× di titik ketuk, geser saat ter-zoom.
  - Pager mengambil alih lagi di tepi gambar, jadi pindah halaman tetap bisa.
- **Panel pengaturan baca** (bottom sheet, ikon ⚙ di toolbar reader, `sheet_reader_settings.xml`): mode baca, latar halaman (Hitam/Abu/Kertas/Putih), kecerahan (ikuti sistem / slider), lanjut otomatis, layar tetap menyala.
  - Semua tersimpan di pref yang sama dengan Pengaturan → Membaca, dan terpisah dari tema aplikasi.
- **Footer akhir chapter** (webtoon dan halaman terakhir pager): menampilkan "Akhir Chapter X" + chapter berikutnya + tombol.
  - Dengan lanjut otomatis aktif, chapter berikutnya dibuka setelah 1,5 detik; menggulir kembali membatalkannya.
  - Di chapter terbaru tampil "Kamu sudah sampai di chapter terbaru."
  - Halaman (history/bookmark/slider) tidak pernah menghitung footer.
- **Teruji di emulator:** semua fitur di atas, termasuk lanjut otomatis 226 → 227. Database dan pref di-backup sebelum uji dan dipulihkan sesudahnya.
- **Belum:** zoom di mode webtoon (Kotatsu punya); kecerahan di bawah level sistem (filter gelap) juga belum.

Rencana awal Fase 4:
Zoom (pinch/double-tap), kecerahan, warna latar, auto-next chapter di akhir webtoon, panel pengaturan reader bertema Folium.

### Fase 5 — Multi-format — **TAHAP 1 SELESAI (2026-11-23): PDF + EPUB lokal**
- **Impor** (Library → ikon unggah):
  - Pemilih file sistem (SAF) dengan izin baca persisten; file tetap di tempatnya.
  - Disimpan sebagai baris `manga` dengan sumber `LOCAL`, id `LOCAL:<uri>`, dan format sebagai tag (`PDF`/`EPUB`).
  - Cover: halaman 1 PDF, cover EPUB, atau ikon huruf. Masuk Library sebagai "Ingin dibaca"; impor ulang memperbarui, tidak menggandakan.
- **Routing:** `DetailsActivity.intent` / `ReaderActivity.intent` mengarahkan dokumen lokal ke `DocumentActivity`, jadi kesembilan titik masuk tidak berubah.
- **PDF:**
  - `PdfRenderer` bawaan Android, render di satu thread (aturan PdfRenderer), cache LRU.
  - Mode gulir bersambung (fit width, tinggi halaman dipesan di depan, jarak 8dp) atau per halaman dengan zoom (`ZoomImageView`).
  - Navigasi: ke halaman, slider, prev/next.
- **EPUB** (`document/EpubBook.kt`, JVM murni):
  - Ekstrak dengan perlindungan zip-slip + batas ukuran + tmp→rename.
  - Parsing container/OPF/spine (`linear="no"` dilewati), judul dari nav EPUB3 atau NCX EPUB2, cover, parser XML tanpa DTD/entitas eksternal.
- **Reader EPUB:**
  - WebView yang hanya melayani file buku dari origin `https://book.local/`. Akses file/konten dimatikan, request lain 404, dan link eksternal tidak diikuti.
  - Font Serif (Noto Serif sistem) / Sans, ukuran, spasi baris, margin, latar. Daftar isi, link antarbab.
- **Progres:**
  - Tabel `history` dengan kolom baru `offset` (posisi gulir di dalam bab; migrasi **3→4 aditif**).
  - Status "Ingin dibaca" otomatis jadi "Sedang dibaca" saat mulai membaca; aturan ini juga berlaku untuk komik.
- **Library:**
  - Filter jenis mengenali PDF/"Buku (EPUB)".
  - Tekan lama → "Hapus dari Library" (hapus baris + folder ekstrak + cover, lepas izin file; file asli tidak dihapus).
- **Edit metadata** (judul, penulis) dari menu reader dokumen.
- **Refactor:** latar/kecerahan/layar-menyala dipindah ke `ReaderAppearance` + `part_reader_appearance.xml`, dipakai bersama reader komik dan dokumen.
- **Test:** `EpubBookTest` (EPUB3 nav+cover+decode, EPUB2 NCX+meta cover+fallback, tolak zip slip, normalisasi path). Total 20 lulus.
- **Uji emulator:**
  - Dengan PDF 3 halaman dan EPUB 3 bab buatan sendiri (`scratchpad/make_samples.py`).
  - Diuji: impor, baca, mode, latar Kertas, daftar isi, pemulihan posisi, status, hapus.
  - Data pengguna di-backup dan dipulihkan.
- **Novel online — tahap 2 selesai (2026-11-23):** kotatsu-parsers tidak punya sumber novel (0/1256), jadi Folium punya sumbernya sendiri.
  - **Model:** interface `TextSource` (`getChapterText` → `ChapterText`: paragraf yang sudah dibersihkan, `locked`, `webUrl`).
  - **`WebnovelSource`** (webnovel.com, dipilih pemilik produk):
    - Membaca JSON `__NEXT_DATA__` dari halaman publik mobile: ranking (Jelajah, 20 teratas), pencarian (berhalaman), katalog (detail + semua chapter per volume), chapter.
    - Domain bisa diganti; `robots.txt` mengizinkan halaman-halaman ini.
  - **Konten berbayar:**
    - Chapter berbayar (`isVip>0 && isAuth==0`) diberi label "Berbayar".
    - Reader hanya menampilkan **cuplikan publik** yang dikirim situs ke semua orang, plus kotak penjelasan dan link "Baca di situs sumber".
    - Data terenkripsi (`encryptType`, `encryptKeyPool`) tidak pernah dipakai.
  - **Reader:** jalur teks EPUB di `DocumentActivity` dipakai ulang (font/ukuran/spasi/margin/latar, daftar isi, link "bab berikutnya", posisi gulir).
    - Halaman chapter disajikan lewat `shouldInterceptRequest` di origin privat `https://novel.local/` (`loadDataWithBaseURL` dengan base https dicegat lalu 404).
    - Isi dibersihkan dengan `Jsoup.clean(Safelist.basic())`.
    - Progres lewat `saveHistory()` komik (+`offset`), jadi riwayat, status otomatis, scrobble, dan tracker chapter baru ikut berlaku.
  - **Lain-lain:**
    - Kategori "Novel" di Beranda/Jelajah/katalog.
    - Tombol/aksi unduh disembunyikan untuk sumber teks (`DownloadWorker` hanya gambar).
    - Label lanjut baca tanpa "(hal. n)" untuk teks/dokumen.
  - **Test:** `WebnovelParsingTest` (5): ranking, pencarian, katalog (79 chapter, urutan, label), chapter gratis (sanitasi `<script>`/`&`), chapter berbayar (cuplikan + terkunci).
    - Fixture memakai struktur asli dengan teks penulis diganti teks dummy dan `</` di-escape seperti Next.js.
  - **Uji emulator:** ranking + cover, detail 1553 chapter (berbayar berlabel), baca chapter gratis, chapter berbayar dengan kotak penjelasan, status otomatis. Data pengguna di-backup dan dipulihkan.
  - **Unduhan novel (2026-11-23):**
    - Antrean `DownloadWorker` yang sama. Teks chapter disimpan sebagai `chapter.json` di folder chapter (tulis atomik + `.complete`).
    - **Hanya chapter gratis** (`TextSource.isPaid`): "Unduh semua/belum dibaca" otomatis menyaring, tekan lama pada chapter berbayar menampilkan pesan, dan worker juga menolak chapter `locked`.
    - Reader novel membaca file lokal dulu, jadi bisa dibaca offline.
    - Diuji: 39 dari 1553 chapter (39 gratis) terunduh, dibaca dengan Wi-Fi dan data mati, chapter berbayar ditolak. Test `NovelDownloadTest`; total 27 lulus.
  - **Belum:** halaman ranking berikutnya (butuh API privat situs, tidak dipakai), rekomendasi novel.
  - **Ditolak:** `orv.pages.dev`, situs penggemar yang menayangkan seluruh teks dan EPUB *Omniscient Reader's Viewpoint* tanpa tanda sebagai pemegang hak. Tidak dibuatkan parser.
- **Belum:**
  - Pencarian teks PDF (PdfRenderer tidak mengekstrak teks sebelum Android 15).
  - Bookmark di dalam dokumen, mode EPUB berhalaman (sekarang gulir per bab).
  - Kategori "Buku/PDF" di Beranda.

- **Jelajah terpisah (2026-11-23):** tombol Komik | Novel & Buku di Jelajah. Sumber, katalog (`SourcesActivity` dengan `kind`), pencarian, dan kosong-state per jenis. Impor PDF/EPUB ada di ruang Novel & Buku.
- **Pengaturan pembaca terpisah (2026-11-23):** Pengaturan → Membaca berisi dua layar (`ReaderSettingsActivity`):
  - **Pembaca komik** memakai kunci dan label Kotatsu (`pref_reader.xml`): mode bawaan (+ Vertikal baru), otomatis deteksi webtoon (dari tipe sumber/tag) dan mode diingat per judul, mode skala (`ZoomImageView.ScaleMode`), celah webtoon, kontrol ergonomis, tombol volume (+ balik), animasi halaman, auto-next, popup perubahan bab, latar, layar penuh, orientasi, layar menyala, bilah informasi (+ transparan), muat halaman lebih awal (selalu/Wi-Fi/tidak).
  - **Pembaca novel & buku:** font, ukuran teks, spasi baris, margin, latar (bawaan Kertas), layar menyala, mode PDF. Kecerahan/latar/layar menyala komik dan teks punya kunci sendiri (`ReaderProfile`).
  - Tidak diambil dari Kotatsu: zoom webtoon, crop halaman, warna ditingkatkan, multitasking, tombol zoom. Deteksi webtoon Kotatsu mengukur rasio halaman; di sini pakai tipe sumber/tag.
  - Diuji di emulator: kedua layar, mode Vertikal terbuka dan bergeser per halaman. Data pengguna di-backup dan dipulihkan.

- **Pengaturan per sumber (2026-11-23)**, mengikuti Kotatsu `SourceSettingsFragment` (`ui/SourceSettings.kt`):
  - Dibuka dari: tekan lama / tombol ⋮ sumber di Jelajah, tekan lama di Katalog sumber, ikon gerigi di halaman sumber.
  - Isi: aktifkan sumber, domain (dialog lama, tetap di `domains` agar ikut backup), opsi yang dideklarasikan parser (`onCreateConfig`: User-Agent, tampilkan konten mencurigakan, pisahkan berdasarkan terjemahan, server gambar), perlambat unduhan (jeda 1,5 dtk antar halaman di `DownloadWorker`), masuk (parser `MangaParserAuthProvider`, lewat browser bawaan + "Masuk sebagai …"), hapus cookie, buka di browser.
  - Nilai disimpan di `source_<ID>` (satu file per sumber, seperti Kotatsu) dan dibaca `KotatsuContext.getConfig`.
  - Tidak diambil: "nonaktifkan notifikasi captcha" (Folium tidak punya notifikasi captcha). Belum: opsi sumber ini belum ikut backup (domain sudah).
  - Diuji di emulator: layar KomikStation dan MangaDex, server gambar tersimpan, halaman sumber tetap memuat. MangaDex sendiri diblokir jaringan (bukan karena perubahan ini).

- **Pengaturan → Sumber manga (2026-11-23)**, mengikuti Kotatsu `pref_sources.xml` (kunci sama), layar `SettingsScreenActivity` + `SourcesSettingsFragment`:
  - Urutkan: Manual (diatur di Kelola sumber, `sources_order`) / Alfabetis / Terakhir digunakan (`source_usage`, dicatat saat membuka sumber atau membaca). "Populer" Kotatsu tidak diambil.
  - Tampilan kisi di Jelajah (`sources_grid`, bawaan aktif seperti Kotatsu; `item_source_grid.xml`).
  - Kelola sumber (`ManageSourcesActivity`): tahan-geser untuk urutan manual, nonaktifkan, ketuk untuk pengaturan sumber.
  - Aktifkan semua sumber, Katalog sumber ("Tersedia: N"), Nonaktifkan NSFW (sumber tipe HENTAI disembunyikan dari Jelajah, katalog, pencarian; manga per judul tidak bisa disaring karena tidak ada rating konten).
  - Mode penyamaran NSFW (Aktif / Selalu tanya / Nonaktif): reader tidak menyimpan riwayat dan statistik.
  - Tandai genre berbahaya: chip genre berwarna error di Detail (daftar kata kunci di `SourcesSettings.kt`).
  - Pilih mirror otomatis: `KotatsuSource.withMirrors` mencoba mirror lain saat daftar/detail/halaman gagal (tidak menimpa domain kustom; satu kali per sesi per sumber, seperti Kotatsu `MirrorSwitcher`).
  - Tangani tautan: activity-alias `LinkHandler` dengan 903 domain sumber (dibuat dari kotatsu-parsers; perlu dibuat ulang kalau library diperbarui), saklar mengaktifkan/mematikan komponen. Tautan dibuka lewat `LinkResolver` library.
  - Diuji di emulator: kisi Jelajah, layar Sumber manga, Kelola sumber (geser → urutan tersimpan), tautan KomikStation → Detail judul (±20 dtk). Mirror otomatis dan penyamaran belum dipicu manual.

Rencana awal Fase 5:
Urutan: PDF (PdfRenderer, tanpa dependensi) → EPUB (WebView + CSS) → novel online (sumber `NOVEL`) → impor lokal + edit metadata. Kategori/menu format baru baru tampil di UI setelah engine-nya berfungsi.

### Bahasa (2026-11-23)
- Bahasa Inggris jadi bahasa bawaan (`values/`), Bahasa Indonesia di `values-in/` (308 string + 10 array). Semua teks yang tadinya hardcode di XML pengaturan dan di Kotlin dipindah ke resource. Pesan error internal (lapisan tanpa `Context`) memakai bahasa Inggris.
- Pengaturan → Tampilan → Bahasa: English / Bahasa Indonesia / Ikuti sistem, lewat `AppCompatDelegate.setApplicationLocales` (`autoStoreLocales` untuk Android ≤12, `locales_config.xml` untuk pengaturan bahasa per-aplikasi Android 13+).
- Saat pertama dibuka (termasuk update dari versi lama) aplikasi beralih ke Inggris, kecuali bahasa sudah dipilih. Dipanggil dari `MainActivity`, karena di Android 13+ AppCompat butuh activity.
- Penanda bab berbayar WebNovel jadi 🔒 (netral bahasa); label lama "· Berbayar" yang tersimpan tetap dikenali.
- Diuji di emulator: bawaan `[en]`, ganti ke Indonesia `[id]`, kembali ke Inggris; teks layar utama dan pengaturan dicek.

### Fase 6 — QA — **SELESAI (2026-11-23)**
Rencana: tes migrasi DB, logika status default, pemetaan progres PDF/EPUB; build; uji manual terang/gelap; data lama tetap ada.

Hasil:
- **Tes migrasi** (`app/src/androidTest/.../data/MigrationTest.kt`, instrumented): DB v1 (SQL buatan Room dikurangi tambahan v2–v4) berisi manga, bab, favorit, riwayat, bookmark, unduhan → dimigrasi ke v4 oleh Room (skema divalidasi Room), data utuh, tabel status baru bisa dipakai. Lulus di emulator. `MigrationTestHelper` tidak dipakai karena skema lama tidak pernah diekspor.
  - Jalankan **tanpa** `connectedAndroidTest` (Gradle meng-uninstall aplikasi → data hilang): pasang APK aplikasi + `app-debug-androidTest.apk`, lalu `adb shell am instrument -w -e class dev.mangareader.data.MigrationTest dev.mangareader.test/androidx.test.runner.AndroidJUnitRunner`, lalu `adb uninstall dev.mangareader.test`.
- **Status default:** sudah tercakup `LibraryTest.effectiveStatus_derivesReadingFromHistoryOnly`.
- **Progres PDF/EPUB:** dipindah ke `documentProgress()` (`document/LocalDocuments.kt`) + `DocumentProgressTest`. **Bug diperbaiki:** PDF dihitung `halaman/total` (indeks mulai 0), jadi halaman terakhir tersimpan 90% (PDF 10 halaman) dan tidak pernah 100%; sekarang halaman yang tampil ikut dihitung.
- **Lint:** 2 error diperbaiki (`paddingHorizontal` di style butuh API 26 → `paddingStart/End`; `accessibilityHeading` API 28 → `values-v28`). Juga: ikon dekoratif tile Library `importantForAccessibility=no`, teks "Bagikan" ke resource, filter tautan `autoVerify=false` (Folium tidak bisa memverifikasi situs orang lain), 9 resource sisa dihapus. Sisa 0 error; peringatan tersisa kebanyakan saran gaya (`UseKtx`), versi dependensi, dan false positive font (`app:*` sudah ada).
- **Build + semua unit test** lulus.
- **Uji manual** (emulator, tema terang dan gelap): Beranda, Library, Jelajah (kisi), Pengaturan, Detail, Sumber manga, pembaca komik — tidak ada teks tak terbaca atau warna yang salah.
- **Data lama:** setelah semua update di sesi ini, DB pengguna di emulator tetap v4, `integrity_check` ok, riwayat/favorit/bookmark/unduhan/status/statistik ada. Setiap uji memakai backup+restore data pengguna.
- **Performa:** cold start 1,8–2,7 dtk di emulator x86 (bukan perangkat nyata). Belum diprofilkan di perangkat fisik.

## C. File yang tersentuh Fase 1

- `app/src/main/res/values/colors.xml`, `values-night/colors.xml`.
- `app/src/main/res/values/themes.xml`: tipografi, warna statusbar, `light_bars` tetap.
- `app/src/main/res/font/*`: hapus `plus_jakarta_sans.ttf` + `jakarta*.xml`, tambah `playfair_display.ttf`, `inter.ttf` + family XML.
- `app/src/main/assets/`: lisensi OFL font baru.
- `app/src/main/res/values/dimens.xml`: baru.
- `app/src/main/res/drawable/ic_*.xml`: 20 ikon jadi outline. Tambah `ic_folium_logo.xml`.
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` + foreground/background vektor (baru), dan `AndroidManifest.xml` (`android:icon`, `roundIcon`).
- `app/src/main/res/values/strings.xml`: `app_name`, tagline.
- Layout yang memakai warna hardcode untuk teks di atas cover (`header_explore.xml` `#FFFFFF`/`#E7E5E4`, scrim): pindah ke token `on_image`.
- `ANALYSIS.md` / `docs/*`: pembaruan.

Tidak ada perubahan Kotlin di Fase 1, kecuali kalau nama style berubah. Data pengguna tidak tersentuh.

## D. Keputusan yang dibutuhkan dari pemilik

1. **Lisensi repo:** GPL-3.0 (diwajibkan `kotatsu-parsers` kalau APK didistribusikan).
2. **`applicationId`:** tetap `dev.mangareader` (data aman) atau ganti, misalnya `app.folium` (dianggap aplikasi baru).
3. **Mulai pelacakan git** sebelum Fase 1.
4. **File APK Kotatsu** di root repo dipindahkan ke luar repo.
