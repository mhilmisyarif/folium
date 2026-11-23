# FOLIUM — Product Specification

> Spesifikasi dari pemilik produk (v1.0), disimpan sebagai sumber kebenaran.
> Ditambah **catatan penerapan** dari audit (`PROJECT_AUDIT.md`) di bawah. Teks spesifikasi tidak diubah isinya.

## Catatan penerapan (hasil audit, 2026-11-23)

- **Kontras:** palet terang perlu penyesuaian kecil supaya lolos WCAG.
  - Teks sekunder `#756B61` di background `#F5EBDD` = 4,42:1; usulan `#6B6158`.
  - Sakura/Sage hanya untuk isian, tidak untuk teks.
  - Rinciannya di `FOLIUM_ARCHITECTURE.md` §2.
- **Font:**
  - Playfair Display, Inter, Noto Serif tersedia di bawah OFL.
  - Noto Sans JP tidak dibundel karena Android sudah punya Noto CJK.
  - Noto Serif ditunda sampai ada reader teks (Fase 5).
- **Tipe konten** hanya tersedia per *sumber* (dari `kotatsu-parsers`), bukan per judul.
- **Novel/EPUB/PDF** belum punya engine. Menu dan kategorinya tidak ditampilkan sebelum engine-nya ada (§12 aturan 8).
- **"Recommended"** hanya dari `getRelatedManga` milik parser; kalau kosong, seksinya disembunyikan (§4.1).
- **Lisensi:** `kotatsu-parsers` GPL-3.0, jadi distribusi Folium perlu lisensi yang kompatibel (keputusan pemilik).

---

**Product Name:** Folium · **Tagline:** Your Cozy Reading Space. · **Category:** Multi-format Digital Reading Platform ·
**Design Direction:** Japanese Cozy Editorial · **Objective:** transform the existing Kotatsu-based comic reader into Folium, a unified digital reading application.

## 1. Project context
Folium evolves an existing Kotatsu-based comic reader; it is **not** a greenfield app. Transform product identity, UX, and
eventually reading capabilities while preserving stable functionality.

Content types (supported and planned): manga, manhwa, manhua, webtoons, novels, EPUB, PDF, and other compatible formats where feasible.

Principles:
1. Preserve Kotatsu-based functionality.
2. Prioritise reading over decoration.
3. One unified personal library.
4. Offline/local reading where applicable.
5. Consistent light and dark themes.
6. Minimal, warm, comfortable.
7. Extensible without unnecessary complexity.

Critical rule: inspect the repository first. It is the source of truth. Do not replace working Kotatsu functionality without a clear reason and an approved migration plan.

## 2. Brand identity
- **Brand:** Folium, a digital reading platform.
- **Character:**
  - Personality: cozy, thoughtful, peaceful, curious.
  - Visual direction: Japanese Cozy Editorial, with a Japanese-minimalist logo.
- **Type & theme:** editorial serif + modern sans; light and dark themes.
- **Philosophy:** a personal library. Every book, manga, novel, or document is a world to explore. The app should feel like a peaceful Japanese reading space, not a content-discovery platform.
- **Tone:** cozy, minimalist, intellectual, elegant, playful. Avoid overly colourful UIs, excessive gradients, aggressive animation, and clutter.

## 3. Design system
**Brand colours**

| Name | Hex |
|---|---|
| Forest Green | `#263D32` |
| Warm Cream | `#F5EBDD` |
| Sakura Pink | `#DDA5A0` |
| Wood Brown | `#5A4636` |
| Ink Black | `#292522` |
| Sage | `#A8B5A0` |

| Token | Light | Dark |
|---|---|---|
| Background | `#F5EBDD` | `#191C19` |
| Surface | `#FBF8F2` | `#232923` |
| Elevated | `#FFFFFF` | `#2D342D` |
| Primary | `#263D32` | `#A8B5A0` |
| Secondary | `#A8B5A0` | `#82947E` |
| Accent | `#DDA5A0` | `#DDA5A0` |
| Text primary | `#292522` | `#F5EBDD` |
| Text secondary | `#756B61` | `#B9B4A9` |
| Border | `#E2D7C8` | `#3B433B` |

Colour rules:
- Use semantic tokens only; never hardcode colours in components.
- Follow the system theme, with a manual override.
- Keep reader appearance settings independent of app appearance.

**Typography**
- **Fonts:** Playfair Display (editorial headings), Inter (UI), Noto Serif (book reading), Noto Sans JP (Japanese). Compatible alternatives are allowed if a font is unavailable.
- **Scale:** Display 40/600 · H1 32/600 · H2 24/600 · H3 20/600 · Body 16/400 · Body small 14/400 · Caption 12/400.
- Reader typography is configured independently.

**Spacing, shape, icons**
- **Spacing:** 4px base: 4, 8, 12, 16, 20, 24, 32, 40, 48.
- **Radius:** 8 / 12 / 16 / 24 / 999. Subtle elevation.
- **Icons:**
  - Reuse the existing icon library; use one family only.
  - Outline style with consistent stroke.
  - 22–24px for navigation, 16–18px inline.

## 4. Information architecture
Primary navigation: Home, Library, Explore, Profile/Settings. The existing navigation may be kept if replacing it adds complexity.

### 4.1 Home
- **Header:** identity, greeting, search, profile/settings.
- **Continue Reading:** cover, title, type, progress, last activity.
- **Recently Added, Recommended:** from real data only.
- **Browse Categories:** Manga, Manhwa, Manhua, Novel, Books, PDF.
- Never fabricate recommendations.

### 4.2 Library
- **Views:** All, Manga, Manhwa, Manhua, Novels, Books, PDFs, Favorites, History, custom collections.
- **Statuses:** Want to Read, Reading, Completed, On Hold, Dropped.
- **Controls:** grid/list, sorting, filtering, search, collections, progress indicators.
- Keep existing library data intact; no data migration or deletion in the initial redesign.

### 4.3 Explore
- **Sections:** search, popular, trending (if supported), genres, categories, recently updated, curated (if supported).
- Preserve Kotatsu source discovery.
- Never invent catalog APIs; follow source rules.

### 4.4 Content details
- **Shows:** cover, title, alternative titles, author/artist, description, genres, tags, type, source, status, progress.
- **Actions:** add to library, start or continue reading.
- Use source metadata. Local metadata of imported documents is editable.

### 4.5 Settings
- **Appearance:** light, dark, system, reader appearance.
- **Reading:** direction, scroll, transitions, controls.
- **Library:** default view, sorting, collections.
- **Storage:** downloads, cache, local files.
- **Sync:** account, backup, preferences.
- Show only working settings.

## 5. Universal reader
One unified experience with format-specific engines.

- **Comic** (preserve Kotatsu): vertical scroll, horizontal pages, RTL/LTR, scaling, page and chapter navigation, brightness, background, progress, auto next chapter.
- **EPUB:**
  - Reflowable text.
  - Font, size, line height, paragraph spacing, margins.
  - Paging or scroll.
  - Progress, bookmarks, chapters, table of contents.
- **PDF:**
  - Continuous or single-page view.
  - Fit width or fit page, zoom.
  - Page navigation, bookmarks, text search when supported.

Principles: one engine per format; shared settings and progress interfaces; separate format-specific controls; no comic-reader performance trade-offs; rendering separate from UI. Add EPUB and PDF only after the comic reader is stable.

## 6. Progress & library data
- **Conceptual fields:** contentId, contentType, currentPosition, totalPosition, lastReadAt, readingStatus, bookmarkPosition, updatedAt.
- **Storage:** follow the existing persistence; no second persistence layer.
- **Behaviour:** autosave and restore position, avoid writes while scrolling, handle interruptions, keep Kotatsu data.
- **Position formats:**
  - Comics: chapter + page.
  - EPUB: a location.
  - PDF: page + scroll.
- Design so sync can be added later.

## 7. UI components
- **Conceptual components:** AppShell, MainNavigation, SearchBar, ContentCard/MangaCard/BookCard, ContinueReadingCard, CollectionCard, ContentDetailHeader, ReadingProgressIndicator, ReaderToolbar, ReaderSettingsPanel, Empty/Loading/ErrorState, ThemeProvider.
- These are concepts, not file names; follow repo conventions.
- Each supports light/dark themes, loading/empty/error states, and accessibility.
- Do not over-abstract.

## 8. UX requirements
- **General:** reading first, predictable navigation, few modals, consistent spacing, readable contrast, subtle motion, accessibility.
- **Loading:** skeletons; never block the whole app.
- **Empty states:** "Your library is waiting for its first story." / "Pick up where you left off." / "Find something worth reading."
- **Errors:** explain what happened and offer recovery; no stack traces.

## 9. Kotatsu preservation (mandatory)
**Audit before substantial changes:**
- Entry points, navigation, reader, sources, persistence.
- Downloads, theme/UI, build/test, license.
- Produce an architecture report.

**Preserve:** sources, reader behaviour, progress, downloads, bookmarks, library data, and settings where possible.

**Never:**
- Remove functionality just because it does not match the visual design.
- Assume Kotatsu names, trademarks, assets, or licences are reusable. Review LICENSE/NOTICE.

Folium has its own branding.

## 10. Phases
- **0 — Audit:** `PROJECT_AUDIT.md`.
- **1 — Brand foundation:** tokens, typography, theme, primitives, logo.
- **2 — Shell & navigation:** Home, Library, Explore, Settings.
- **3 — Library:** statuses, collections, filter/sort, search, progress.
- **4 — Reader refinement.**
- **5 — Multi-format:** EPUB/PDF, introduced incrementally.
- **6 — QA:** tests, build, navigation, reader, themes, data preservation, performance.

## 11. Acceptance criteria
- **Branding:** consistent, centralised tokens; working light/dark themes; consistent typography.
- **Existing functions:** sources, reader controls, progress, downloads, bookmarks, and library data all keep working.
- **UX:** intuitive navigation; library filter/sort; visible progress; empty/loading/error states; focused reader.
- **Engineering:**
  - Respects the existing architecture, with no duplicate implementations.
  - Tests pass and repo conventions are followed.
  - No unexplained breaking changes.

## 12. Working instructions
1. Existing repo; inspect first.
2. Follow conventions.
3. No rewrite.
4. Do not remove Kotatsu functions.
5. Do not swap dependencies without reason.
6. Do not invent APIs or providers.
7. No nonfunctional controls.
8. No fake content.
9. No unnecessary dependencies.
10. Keep changes incremental.
11. Run tests.
12. Explain architectural decisions.
13. Update docs.

Phase 0 deliverables: `docs/PROJECT_AUDIT.md`, `docs/FOLIUM_PRODUCT_SPEC.md`, `docs/FOLIUM_IMPLEMENTATION_PLAN.md`, `docs/FOLIUM_ARCHITECTURE.md`. Stop for approval before Phase 1.

## 13. Final direction
Folium should not feel like a reskinned comic reader. It should be a carefully designed digital library that happens to have an excellent comic-reading experience.

*Your Cozy Reading Space. A place for every story.*
