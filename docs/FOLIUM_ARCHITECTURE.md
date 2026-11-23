# Arsitektur Folium (target) di atas basis yang ada

Prinsip: perluas lapisan yang sudah ada, jangan buat lapisan kedua. Tidak ada DI framework, tidak ada persistensi kedua, tidak ada rewrite ke Compose.

## 1. Lapisan saat ini dan perannya di Folium

```
App (service locator)
 ├─ parser/      KotatsuContext + KotatsuSource ── kotatsu-parsers (GPL-3.0)   → sumber online (tetap)
 ├─ data/        Room (kotatsu-lite.db) + LocalStorage + Backup                 → satu-satunya persistensi
 ├─ work/        DownloadWorker, TrackerWorker                                  → tetap
 ├─ scrobbling/  AniList/MAL/Kitsu/Shikimori                                    → tetap
 └─ ui/          Activity/Fragment + adapter bersama + tema XML                 → diganti tampilan (Folium)
```

## 2. Sistem desain (Fase 1)

Semua token tetap berupa resource Android, satu sumber kebenaran:

| Token Folium | Implementasi |
|---|---|
| Warna semantik | Atribut M3 di `values/colors.xml` + `values-night/colors.xml`, dirujuk `?attr/...` (sudah begini; palet diganti) |
| Tipografi | `TextAppearance.App.*` di `themes.xml`: Display/H1–H3 → **Playfair Display**, UI → **Inter** (ganti Plus Jakarta Sans) |
| Teks baca | **Noto Serif**, baru di Fase 5 (hanya untuk EPUB/novel) |
| Jepang | Font sistem Noto CJK, tanpa bundel |
| Spasi 4px | `values/dimens.xml` `space_1…space_12` |
| Radius | `ShapeAppearance.App.*` 8/12/16/24/999 |
| Ikon | Vektor Material **outline** (Material Symbols Outlined), satu keluarga, 24dp nav / 18dp inline |

Penyesuaian kontras (wajib, hasil hitung WCAG terhadap palet spesifikasi):

| Masalah | Rasio | Penyesuaian |
|---|---|---|
| Teks sekunder `#756B61` di background `#F5EBDD` | 4,42 | Pakai `#6B6158` (≥4,9 di background) **atau** teks sekunder hanya di surface `#FBF8F2` (4,91) |
| Sakura `#DDA5A0` sebagai teks/ikon di terang | 1,79 | Hanya untuk isian (badge, indikator) dengan teks Ink (7,21) |
| Sage `#A8B5A0` sebagai teks di terang | 1,82 | Hanya untuk container (secondaryContainer), teks pakai Forest Green |
| Border `#E2D7C8` / `#3B433B` | 1,2 / 1,7 | Hanya untuk divider dekoratif. Batas kontrol (field) memakai outline ≥3:1 |

Pengaturan "Warna dari wallpaper" yang sudah ada tetap opsional. Pengaturan reader (mode baca) tetap terpisah dari tema aplikasi.

## 3. Shell & navigasi (Fase 2)

Bottom nav 4 tab, memakai ulang `MainActivity` + fragment yang ada:

| Tab Folium | Disusun dari (sudah ada) | Baru |
|---|---|---|
| **Home** | Kartu hero "Lanjut membaca" (sekarang di Jelajah), riwayat terbaru, feed chapter baru (`track_logs`) sebagai "Baru diperbarui" | Sapaan + logo, "Rekomendasi" dari `parser.getRelatedManga(seed)` (data nyata dari parser, bukan dikarang) |
| **Library** | Favorit + kategori, Riwayat, Unduhan, Bookmark | Status baca, filter tipe, urut, cari lokal, grid/list |
| **Explore** | Tab Jelajah sekarang: cari global, sumber aktif, katalog | Kategori jenis (Manga/Manhwa/Manhua/Komik) → katalog yang terfilter |
| **Pengaturan** | `SettingsActivity` dikelompokkan ulang (Tampilan, Membaca, Library, Penyimpanan, Sinkronisasi = backup + pelacakan) | — |

## 4. Library & status baca (Fase 3)

- **Tabel baru `library_status`** (`mangaId` PK, FK → manga CASCADE; `status` INT; `updatedAt`). Migrasi Room **2→3 aditif**: `CREATE TABLE` saja, tanpa mengubah tabel lama. Backup naik ke versi baru dengan field opsional, dan backup lama tetap bisa dibaca.
- **Status:** `WANT_TO_READ, READING, COMPLETED, ON_HOLD, DROPPED`. Default turunan (tidak ditulis): ada riwayat → Reading; selain itu tidak ada status.
- **Koleksi kustom** = `favourite_categories` yang sudah ada (diberi label "Koleksi"); tidak membuat tabel kedua.
- **Filter tipe:** dari `MangaParser.contentType` milik sumber manga (perkiraan per sumber).

## 5. Progres baca (bersama, Fase 3–5)

Model konseptual spesifikasi dipetakan ke tabel `history` yang ada:

| Field spesifikasi | Komik (sekarang) | EPUB/PDF (Fase 5) |
|---|---|---|
| contentId | `manga.id` | id dokumen lokal (`local:<sha1 uri>`) |
| currentPosition | `chapterUrl` + `page` | EPUB: spine index + offset; PDF: page index (+ scroll) |
| totalPosition / percent | `percent` | sama |
| lastReadAt | `updatedAt` | sama |
| readingStatus | `library_status` | sama |
| bookmarkPosition | tabel `bookmarks` | sama |

Data progres Kotatsu-style yang ada tidak diubah. Kolom tambahan untuk EPUB/PDF dibuat aditif saat Fase 5.

## 6. Reader multi-format (Fase 5)

Satu interface kecil `ReaderEngine` untuk progres + pengaturan bersama. Engine per format:

| Format | Engine | Dependensi |
|---|---|---|
| Komik | `ReaderActivity` yang ada (ditambah zoom, kecerahan, latar, auto-next) | — |
| PDF | `android.graphics.pdf.PdfRenderer` (bawaan Android) di RecyclerView | Tidak ada |
| EPUB | Unzip + render XHTML di WebView dengan CSS pengguna (font, ukuran, spasi, margin), dan TOC dari `nav.xhtml`/`toc.ncx` | Tidak ada (atau Readium, kalau perlu fitur lanjut; evaluasi dulu) |
| Novel online | kotatsu-parsers **tidak punya** sumber novel (0/1256). Butuh parser teks baru per situs pilihan pemilik produk; renderer teks EPUB bisa dipakai ulang | Tidak ada |

Impor file lokal lewat SAF (`OpenDocument`) dengan izin URI persisten; metadata bisa diedit (judul, author) di tabel `manga` dengan source `LOCAL`.
