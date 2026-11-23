# Stage 3: Archive page (implementation plan and manual testing guide)

> **Status:** Done · **Roadmap:** [Stage 3](frontend-plan.md#stage-3-archive-page-viewsarsipviewvue) · **Previous:** [Stage 2](stage-2-public-books.md) · **Backend refs:** [spec §3.2](../.md/backend-spec.md), [frontend-guide §3 Lists, §4 Shapes, §5 Files](../frontend-guide.md)

## 1. Summary
Add a public **school archive** page at `/arsip` for photos, videos and documents from `GET /api/archive`. It has:
- Search and filters (media type, category, event date range), plus sort and paging, all synced to the URL.
- A uniform thumbnail grid.
- A **lightbox** that shows the full image, plays the video or previews the document, with item details, prev/next and download.

The archive is empty on the dev backend (it has 4 categories, 0 items). The user adds test data on the backend side; §5.1 lists what the tests need.

Deliverables:
- `src/api/archive.js`
- Archive labels and helpers in `utils/labels.js`
- `ArchiveThumb.vue` and `ArchiveLightbox.vue`
- `views/ArsipView.vue` and the `/arsip` route
- An "Arsip" link in the header and footer

**Done when:**
- `npm run build` passes.
- An anonymous visitor can browse, filter and open `PUBLIC` photos, videos and documents, and download them.
- `STAFF` items appear only for logged-in staff. Their thumbnails, images and PDFs load, and their videos are download-only.
- A video the browser can't decode shows "Tidak bisa diputar di browser" with a download button.
- `/arsip?item={id}` opens that item's lightbox directly.

---

## 2. Decisions
- **Uniform grid** (your choice): every tile is 4:3, and the thumbnail fills it with `object-cover`. Rows keep the sort order left to right. The real `width`/`height` are used only in the lightbox, to reserve space and avoid layout shift.
- **Top filter bar, not a sidebar.** The archive has fewer filters than books. Layout:
  - Media type as segmented tabs: Semua / Foto / Video / Dokumen, from `meta.mediaTypes`.
  - A search box, a category `<select>` (indented tree from `/categories?type=ARCHIVE`), Dari/Sampai date inputs and a sort `<select>`.
  - Active-filter chips with "Reset Semua", like `/kategori`.
- **URL is the state**, the same pattern as `CariKategoriView`: `?q=&categoryId=&mediaType=&from=&to=&sort=&page=&item=`.
  - `page` starts at 1 in the URL.
  - Search is debounced by 300 ms and uses `router.replace`. Filter changes use push and reset the page.
  - Stale responses are ignored with a request counter.
- **Date range check:** if `from > to`, show an inline error under the inputs and don't send the request, so the backend never gets a bad range.
- **Sort:** `newest` (default, "Terbaru diunggah"), `date` ("Tanggal kegiatan"), `title` ("Judul A–Z"), `popular` ("Paling banyak diunduh").
- **Page size 24** (4/3/2 columns on desktop/tablet/phone).
- **The lightbox is a modal on the same page, not a separate route.** Opening an item sets `?item={id}` with `router.replace`, so the link can be shared and a reload reopens it. Prev/next and closing also use `replace`.
  - Trade-off: browser Back leaves `/arsip` instead of closing the lightbox. Esc, ✕ and a click on the backdrop close it.
- **The lightbox loads `GET /archive/{id}`** when it opens. The summary has no `description`, `mimeType`, `originalName` or `fileSize`. The grid summary fills the header right away while the detail loads.
- **Media in the lightbox**, reusing `useMediaUrl` (`src/api/media.js`):
  - **IMAGE:** `<img>` on `/file` (a blob for `STAFF`). The thumbnail shows behind it until the full image loads.
  - **VIDEO, `PUBLIC`:** `<video controls preload="metadata" :poster="thumbnail">` on `/file`, so streaming and seeking use range requests. On the `error` event: "Tidak bisa diputar di browser" plus Unduh.
  - **VIDEO, `STAFF`:** download only (frontend-guide §5.3, since a blob would load the whole video into memory). Shows the poster (blob) with "Video khusus staf hanya dapat diunduh" and Unduh.
  - **DOCUMENT:** `canPreview(mimeType)` decides. It shows an `<iframe>` (blob for `STAFF`) or the no-preview panel, the same as the book reader.
- **Download** follows the Stage 2 rule: a plain `<a href="/api/archive/{id}/download">` for `PUBLIC`, `downloadAuthed()` for `STAFF`. Only this URL increases `viewCount`. Opening the lightbox doesn't count, and the UI labels the count "diunduh".
- **Prev/next** moves through the current page's results only: ← → keys and arrow buttons, disabled at the ends. When the item came from a deep link and isn't on the current page, both are hidden.
- **`?item` that is missing or `STAFF` while logged out (404):** close the lightbox, remove the `item` parameter, and show a toast "Item arsip tidak ditemukan".
- **Pending media fields:** `width`, `height` and `durationSeconds` are `null` until processing finishes. Then the duration badge is hidden, the lightbox uses a 16:9 box, and the thumbnail URL returns the server's placeholder SVG. No cache buster is needed in this stage, since there are no uploads in the UI.
- **Event dates** (`YYYY-MM-DD`) are formatted by splitting the string, not with `new Date('YYYY-MM-DD')`. That would parse as UTC, and the date could shift by a day in some time zones. A null date shows "Tanpa tanggal".
- Accessibility: the lightbox has `role="dialog"` and `aria-modal`, and the body doesn't scroll while it's open. Focus moves to ✕ on open and returns to the tile on close.

---

## 3. Design

### 3.1 API and utilities
| File | Exports |
|---|---|
| `src/api/archive.js` (new) | `listArchive({ q, categoryId, mediaType, from, to, sort, page = 0, size = 24 })` → `Page<ArchiveSummary>`; `getArchive(id)` → `ArchiveDetail`; `archiveThumbnailUrl(id)`, `archiveFileUrl(id)`, `archiveDownloadUrl(id)`. Same shape as `src/api/books.js`. |
| `src/api/categories.js` | Unchanged. `listCategories('ARCHIVE')` and `flattenCategories` are reused. |
| `src/api/meta.js` | Unchanged. `getFilters().mediaTypes` is reused. |
| `src/utils/labels.js` (extended) | `MEDIA_TYPE_LABELS` (IMAGE → "Foto", VIDEO → "Video", DOCUMENT → "Dokumen"); `ARCHIVE_SORT_OPTIONS`; `formatDuration(sec)` → "0:05" / "3:42" / "1:02:10"; `formatEventDate('YYYY-MM-DD')` → "17 Agustus 2025" (`id-ID` month names, no `Date` parsing); `MIME_EXTENSIONS` gains `image/jpeg` JPG, `image/png` PNG, `image/webp` WEBP, `image/gif` GIF, `video/mp4` MP4, `video/webm` WEBM, `video/x-matroska` MKV. |

### 3.2 Components
| File | Purpose |
|---|---|
| `src/components/ArchiveThumb.vue` (new) | Fills its `relative` parent, like `BookCover`. Props: `item` and `cacheKey` (optional, for Stage 4). Uses `useMediaUrl(() => ({ url: archiveThumbnailUrl(id), visibility }))`. On load error, or while a blob is loading, it shows a neutral tile with the media-type icon. Badges: the media-type pill (top left), "Khusus Staf" when `STAFF` (top right), and for `VIDEO` a ▶ icon centered plus `formatDuration` (bottom right, only when `durationSeconds` is set). |
| `src/components/ArchiveLightbox.vue` (new) | Props: `item` (summary), `hasPrev`, `hasNext`. Emits `close`, `prev`, `next`. Loads `getArchive(item.id)` (ignores stale results). Layout: a dark full-screen overlay with the media area on the left or top. The info panel (title, `formatEventDate`, category, description, file name and size, "{viewCount}× diunduh", uploader or "Impor massal") is on the right on desktop and below on a phone. The media rules are in §2. The detail states are loading (skeleton), 404 (emits `close` with a not-found flag) and other errors (inline message with Coba Lagi). |
| `src/components/StateBlock.vue`, `Pagination.vue` | Reused as they are |

### 3.3 `views/ArsipView.vue` (new)
- **Hero band** in the catalog's teal style: "Arsip Sekolah", a subtitle ("Dokumentasi kegiatan, prestasi dan dokumen resmi SMAN 11 Garut") and the total item count.
- **Filter bar** (§2) and active chips. The results line reads "Menampilkan a–b dari N item".
- **Grid** of `<button>` tiles: an `aspect-[4/3]` `ArchiveThumb`, then the title (2 lines), then event date · category. A click opens the lightbox.
- **States** from `StateBlock`: loading skeleton (24 tiles), empty with reset, and error with retry.
- **Pagination** at the bottom. Changing the page scrolls to the top of the grid.
- **Lightbox** is shown while `route.query.item` is set.
  - The item comes from the current results when it's there; otherwise the lightbox gets a stub `{ id }` and loads everything from the detail.
  - Prev/next replace `item` with the neighbour's id.
- Watches `route.fullPath` (guarded by `route.name === 'Arsip'`). A change to `item` alone doesn't send a list request again: compare the query without `item`.

### 3.4 Router and navigation
| File | Change |
|---|---|
| `src/router/index.js` | Add `{ path: '/arsip', name: 'Arsip', component: ArsipView, meta: { title: 'Arsip Sekolah - E-Library SMAN 11 Garut' } }`, lazy-loaded. `scrollBehavior`: no scroll to top when only `query.item` changes. |
| `src/components/HeaderNavbar.vue` | A third nav link "Arsip<br />Sekolah" → `/arsip`, active when `$route.path === '/arsip'`, same classes as the other two |
| `src/components/FooterSection.vue` | `<a href="#arsip">` becomes `<router-link to="/arsip">` |

---

## 4. Changes

### 4.1 New files
`src/api/archive.js`, `src/components/ArchiveThumb.vue`, `src/components/ArchiveLightbox.vue`, `src/components/MediaTypeIcon.vue` (shared photo/video/document icon, added during implementation), `src/views/ArsipView.vue`

### 4.2 Modified files
| File | Change |
|---|---|
| `src/utils/labels.js` | §3.1 additions |
| `src/router/index.js` | `/arsip` route, scroll behaviour for `?item` |
| `src/components/HeaderNavbar.vue` | Arsip link |
| `src/components/FooterSection.vue` | Arsip link |
| `src/components/StateBlock.vue` | New `aspectClass` prop (default `aspect-[3/4]`), so the archive skeleton uses 4:3 tiles. Added during implementation. |
| `frontend .md/frontend-plan.md` | Stage 3 row: "In progress" when work starts, "Done" at sign-off, with a link to this doc |

No changes to the Stage 1/2 views, `media.js`, `client.js` or the auth store.

> **As built:** page changes scroll to the top of the page (the router's existing behaviour), not to the top of the grid. A video whose container loads but whose video track can't be decoded (the browser plays audio only, `videoWidth === 0`) is also treated as unplayable.

---

## 5. Manual testing guide

### 5.1 Setup
| Item | Value |
|---|---|
| Backend | Dev profile on `http://localhost:8081`; `GET /api/health` → `UP`. ffmpeg on the server machine (it's installed at `C:\ffmpeg\bin`), so videos get poster frames and durations. |
| Frontend | `npm run dev` → `http://localhost:5173` |
| Test data | Added on the backend side (see the checklist below). Wait a few seconds after adding, so thumbnails, sizes and durations are processed. |
| Accounts | Anonymous (a private window, or after Keluar) and `admin` / `admin123` |
| Tools | DevTools Network tab (filter `api`), Console |

**Test data checklist** (archive items default to `STAFF`, so set `PUBLIC` explicitly on the rest):

| Need | For tests |
|---|---|
| Photos in different shapes: landscape, portrait, square, panorama | 3b, 4a |
| One mp4 (H.264/AAC) and one webm video, `PUBLIC` | 3b, 4d, 4e |
| A video the browser can't decode, for example an `.mkv` with MPEG-4 Part 2 video | 4f |
| One `PUBLIC` PDF and one `.docx` document | 4g, 4h |
| One `STAFF` photo, one `STAFF` PDF and one `STAFF` mp4 | 5a–5f |
| Items in at least 2 categories, and event dates in 2025 and other years | 3d, 3e, 3h |
| One item with no event date, and one with a very long title | 3e, layout |
| Titles with "pentas" and "RI" in them | 3g |

`STAFF` items follow the same rule as books in Stage 2: hidden from anonymous visitors (404), visible to logged-in staff.

### 5.2 Build and navigation
| # | Step | Expected |
|---|---|---|
| 1 | `npm run build` | No errors |
| 2a | Header | A third link "Arsip Sekolah". It's highlighted on `/arsip` and not on the other pages. |
| 2b | Footer "Arsip" link | Opens `/arsip` |

### 5.3 Grid and filters (anonymous)
| # | Step | Expected |
|---|---|---|
| 3a | Open `/arsip` | `GET /api/archive?…size=24`, `/api/categories?type=ARCHIVE` and `/api/meta/filters` → 200. The hero shows the `PUBLIC` count. Tiles are all the same 4:3 size, newest first. There are no `STAFF` items. |
| 3b | Thumbnails | Photos show their real image (cropped to fill). Videos show a poster frame with ▶ and a duration like "0:05". Documents show the server placeholder. |
| 3c | Tab "Video", then "Foto", then "Dokumen", then "Semua" | Each sets `mediaType` in the URL and the request, and only that type is shown |
| 3d | Category select "Prestasi" | `categoryId=…` and only those items. A chip appears, and its ✕ removes the filter. |
| 3e | Dari `2025-01-01`, Sampai `2025-12-31` | `from`/`to` are sent. Only items with an event date in 2025 are shown, and items with no date are excluded. |
| 3f | Dari later than Sampai | An inline error appears and no request is sent. Fixing the dates sends it. |
| 3g | Type `pentas` slowly; then `RI` | One request about 300 ms after the last keystroke each time. Both find their items. No history entry per keystroke. |
| 3h | Sort "Tanggal kegiatan" | `sort=date`, newest event date first. "Judul A–Z" → `sort=title`. |
| 3i | "Reset Semua" | Clears every filter, including `q` and the dates |
| 3j | Copy a filtered URL into a new tab; then use Back a few times | The same state is restored in the new tab, and Back steps through earlier filter states |
| 3k | Search `zzzzqq` | Empty state, and its reset button works |
| 3l | Paging | With 24 or fewer items there's one page and no pager. To test paging, open `/arsip?page=2`: it's empty or shows the second page without errors. (In Stage 5 an import can add more items.) |

### 5.4 Lightbox (anonymous)
| # | Step | Expected |
|---|---|---|
| 4a | Click a photo | The lightbox opens and the URL gets `?item={id}`. Network: `GET /api/archive/{id}` and `/file`. The full image shows at the right aspect ratio, with no jump. The panel shows title, event date, category, description, file name, size and "N× diunduh". |
| 4b | ← / → keys and the arrow buttons | Moves to the neighbouring items on the page, and the URL `item` updates. The arrows are disabled at the first and last item. The grid behind doesn't reload (no new `/api/archive?` request). |
| 4c | Esc, ✕, backdrop click | Each closes the lightbox, removes `item` from the URL and returns focus to the tile. The page doesn't scroll while it's open. |
| 4d | mp4 video | Plays with sound. Dragging the seek bar works (Network shows `206 Partial Content` on `/file`). |
| 4e | webm video | Plays |
| 4f | mkv (MPEG-4 Part 2) video | "Tidak bisa diputar di browser" with Unduh, and no console errors besides the media error |
| 4g | PDF document | Inline in an `<iframe>`, like the book reader |
| 4h | docx document | "Pratinjau tidak tersedia untuk format DOCX" with Unduh |
| 4i | Unduh on any item | Saves the original file name. After closing and reopening, the "diunduh" count is up by 1 (async, so wait a second). |
| 4j | Reload while `?item={id}` is open | The lightbox reopens on the same item |
| 4k | Open `/arsip?item=999999` | The lightbox closes, `item` is removed and a toast says "Item arsip tidak ditemukan" |
| 4l | Phone width | The info panel moves below the media, controls stay reachable, nothing scrolls horizontally. The grid has 2 columns. |

### 5.5 `STAFF` visibility
| # | Step | Expected |
|---|---|---|
| 5a | Anonymous: open `/arsip?item={staffId}` (a `STAFF` item's id, from the backend) | Not-found toast (the API returns 404) |
| 5b | Log in as `admin` and open `/arsip` | The `STAFF` items appear with a "Khusus Staf" badge, and the hero count includes them. Their thumbnail `<img>` src is `blob:`, and the requests carry `Authorization`. |
| 5c | `STAFF` photo | The full image loads as a `blob:` URL |
| 5d | `STAFF` PDF | The iframe src is `blob:` and the PDF renders |
| 5e | `STAFF` video | No `<video>` player. The poster shows "Video khusus staf hanya dapat diunduh", and Unduh saves the mp4 with its original name. |
| 5f | Open and close several `STAFF` items, then prev/next through them | No console errors. Old `blob:` URLs no longer open in a new tab, because they've been revoked. |
| 5g | `PUBLIC` items while logged in | Plain `/api/archive/{id}/thumbnail` and `/file` URLs, not `blob:` |

### 5.6 Errors and resilience
| # | Step | Expected |
|---|---|---|
| 6a | Stop the backend and reload `/arsip` | An error state "Tidak dapat terhubung ke server perpustakaan…" with Coba Lagi. No uncaught console errors. |
| 6b | Start the backend and click Coba Lagi | The data loads without a page reload |
| 6c | With the lightbox open, stop the backend and press → | The lightbox shows an inline error with Coba Lagi, and the grid stays as it was |

### 5.7 Sign-off
All of tests 1–6 pass → commit Stage 3 and set its status to **Done** in `frontend-plan.md`.
