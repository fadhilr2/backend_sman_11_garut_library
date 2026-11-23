# Stage 2: Public book pages (implementation plan and manual testing guide)

> **Status:** Done · **Roadmap:** [Stage 2](frontend-plan.md#stage-2-public-book-pages) · **Previous:** [Stage 1](stage-1-foundation.md) · **Backend refs:** [spec §3.1, §3.3](../.md/backend-spec.md), [frontend-guide §3 Lists, §4 Shapes, §5 Files](../frontend-guide.md)

## 1. Summary
Replace the mock data on the three public book pages with the real API, and keep each page's current design:
- `KatalogView` (`/`)
- `CariKategoriView` (`/kategori`, `/search`)
- `DetailBukuView` (`/buku/:id`)

Parts of the UI that the backend has no data for are removed: DDC, mapel, availability, stock, ISBN, page count, table of contents, the barcode scan, the fake reader controls and pinjam cetak.

Deliverables:
- Endpoint modules for books, categories and meta, with labels and format helpers.
- `useMediaUrl`, which loads `STAFF` covers and files through blob URLs.
- Shared `BookCover` and `Pagination` components.
- Catalog: real totals, a popular section with grade pills, and the full collection with category pills and paging.
- Search: debounced `q`, category/grade/type filters from the API, sort, paging, all synced to the URL.
- Detail: real metadata, an inline reader for formats that can be previewed, download, citation and related books.
- Loading, empty and error states on every page.
- `scripts/seed-books.py`, which fills the dev backend with test books.

**Done when:**
- `npm run build` passes, and no mock book arrays are left in the three views.
- An anonymous visitor can find a `PUBLIC` PDF by search or filter, open it, read it inline and download it.
- `STAFF` books appear only for logged-in staff, with their covers and files loading.
- A non-PDF book shows a download panel instead of the reader.

---

## 2. Decisions
- **Each page keeps its own card markup.** The three card layouts differ (horizontal popular cards and gradient grid on the catalog, neutral grey grid on search, related grid on detail), so only the **cover** is shared, as `components/BookCover.vue`.
- **Cover placeholders:** search keeps the neutral grey placeholder from commit `76d8646`. The catalog and related books keep the gradient look. The gradient is picked from a fixed palette by `id % n`, so each book always gets the same one.
- **Cover URL:** `hasCover: true` → `/api/books/{id}/cover`. `hasCover: false` → the local placeholder, with no request sent. An image `error` also falls back to the placeholder.
- **`STAFF` media:** `useMediaUrl()` fetches with the `Authorization` header and returns a blob URL, which is revoked when the source changes or the component unmounts. `PUBLIC` media uses plain URLs, so the browser cache and range requests still work (frontend-guide §5.3).
- **Downloads:**
  - `PUBLIC`: a plain `<a href="/api/books/{id}/download">`. The server sets the file name, and only this URL counts a download.
  - `STAFF`: `downloadAuthed()`, which fetches as a blob, then clicks a temporary `<a download="originalName">`.
- **Inline preview:** `canPreview(mimeType)` checks `INLINE_MIME_TYPES` in `utils/labels.js`, which is `['application/pdf']` for now. When the backend supports more types, that one list is extended.
- **The reader uses the browser's own PDF viewer** in an `<iframe>` (the server allows same-origin framing). The fake search, page and zoom controls are removed, since the built-in viewer already has them. The "Layar Penuh" toggle and an "Buka di Tab Baru" link stay.
- **Search state lives in the URL:** `?q=&categoryId=&grade=&type=&sort=&page=`, with `page` starting at 1 for readability. The view watches `route.query`, so links, reload and back/forward all work. The search box is debounced by 300 ms and calls `router.replace` (no history entry per keystroke). Changing a filter resets `page` to 1.
- **Stale responses are ignored.** Each list fetch takes a counter value and drops its result if a newer request has started.
- **Filters are single-select** (radio buttons), because `/api/books` takes one `categoryId`, `grade` and `type`. There's no curriculum filter: the API has no such parameter.
- **Enum labels fall back to the raw code**, so a new value from `/meta/filters` still shows up.
- **`/buku/:id` reuses its component** when you open a related book, so the view watches `route.params.id` and reloads everything, including the reader and blob URLs.
- **Test data comes from a script, not the UI.** Uploads arrive in Stage 4, so `scripts/seed-books.py` (Python standard library only) creates and uploads the test books with the admin token. A `409 DUPLICATE_FILE` is skipped, so running it again is safe.

---

## 3. Design

### 3.1 New API and utility modules
| File | Exports |
|---|---|
| `src/api/books.js` | `listBooks({ q, categoryId, grade, type, sort, page, size })` → `Page<BookSummary>`; `getBook(id)` → `BookDetail`; `bookCoverUrl(id)`, `bookFileUrl(id)`, `bookDownloadUrl(id)` (plain `/api/...` strings) |
| `src/api/categories.js` | `listCategories(type = 'BOOK')` → tree; `flattenCategories(tree)` → `[{ id, name, parentId, depth }]` (handles a missing `children`) |
| `src/api/meta.js` | `getFilters()`: `/meta/filters`, kept in memory after the first call |
| `src/api/media.js` | `useMediaUrl(sourceGetter)`, where `sourceGetter` returns `{ url, visibility } \| null`. It returns `{ src, error }` refs, cleans up, and doesn't call `fetch` for `PUBLIC`. Also `downloadAuthed(url, fileName)`. |
| `src/utils/labels.js` (extended) | `BOOK_TYPE_LABELS` (EBOOK → "E-Book", TEACHING_MODULE → "Modul Ajar", QUESTION_BANK → "Bank Soal", SLIDES → "Bahan Presentasi", OTHER → "Lainnya"), `GRADE_LABELS` (X/XI/XII → "Kelas X/XI/XII", ALL → "Semua Kelas"), `VISIBILITY_LABELS`, `labelFor(map, code)`, `formatBytes`, `formatDate` (`id-ID`), `fileExtension(name, mime)`, `INLINE_MIME_TYPES`, `canPreview(mime)` |

### 3.2 New components
| File | Purpose |
|---|---|
| `src/components/BookCover.vue` | Props: `book` (`id`, `title`, `hasCover`, `visibility`, plus optional badges), `variant` (`'gradient'` \| `'neutral'`) and `rounded`. Shows the image through `useMediaUrl` when `hasCover` is true, otherwise the variant's placeholder with the title. A `default` slot holds the floating badges. |
| `src/components/Pagination.vue` | Props: `page` (starts at 0), `totalPages`. Emits `update:page`. Shows ‹ 1 … 4 5 6 … 20 ›, styled like the current search pager. Hidden when `totalPages <= 1`. |
| `src/components/StateBlock.vue` | Shared loading skeleton, empty state and error states. Props: `state` (`'loading'` \| `'empty'` \| `'error'`), `title`, `message`. Emits `retry` and `reset`. Styled like the current empty state in `CariKategoriView`. |

### 3.3 Field mapping per page

**`KatalogView` (`/`)**
| Current UI | Becomes |
|---|---|
| Hero "Presensi" button | Already "Jelajahi Koleksi" (Stage 1) |
| Hero stat "4.800+ Buku" | `totalElements` from `listBooks({ size: 1 })` |
| Hero stat "28 Kategori" | `flattenCategories(...).length` |
| Right widget "Petugas Bertugas: Siti Rohmah", "Ruang Baca Buka" | Neutral text: "Akses melalui Wi-Fi sekolah" and "Koleksi digital aktif". There's no fake staff name. |
| "Sinkronisasi Dapodik" card | "Kurikulum Merdeka & K13" / "Koleksi diunggah oleh guru & pustakawan" |
| "Buku Pilihan … (Fase E & F)" + fase pills | **"Buku Terpopuler"**. The pills are "Semua" plus `meta.gradeLevels`, each calling `listBooks({ sort: 'popular', size: 3, grade })`. Badge: type • category. The stock line becomes "{downloadCount}× diunduh". |
| "Katalog Lengkap Koleksi" + category pills | "Semua Kategori" plus the root categories from `/categories`, calling `listBooks({ sort: 'newest', size: 8, categoryId, page })` |
| Cover badges Dewey / Fase / stock pill | Type label / grade label / a "Khusus Staf" pill only for `STAFF` |
| `subCategory`, `author (year)`, `pages` | `categoryName`, `author (publicationYear)` (each part skipped when null), `curriculum` (or the type label) |
| "Menampilkan X dari 4.812", fake pager | "Menampilkan {content.length} dari {totalElements} judul" + `<Pagination>` |
| Detail / Baca buttons | `/buku/{id}` and `/buku/{id}#baca` |
| "Panduan Baca" alert | Text updated to the real flow (open a book → read in the browser or download). No mention of bookmarks or text search. |

**`CariKategoriView` (`/kategori`)**
| Current UI | Becomes |
|---|---|
| Search input (prefilled "Fisika Modern") | Empty by default, debounced into `?q=`. The "Cari Buku" button and Enter apply it immediately. |
| "Pindai Barcode" button + scan modal | Removed |
| "Paling Dicari" quick tags | The first 6 root categories as chips (they set `categoryId`) |
| Active-filter chips (fase / mapel / DDC / format / availability) | Chips for `q`, category, grade and type. Each ✕ clears it, and "Reset Semua" clears everything. |
| Sidebar accordions (Kurikulum / DDC / Format / Ketersediaan) + "Terapkan" button | Three accordions: **Kategori** (the tree from `flattenCategories`, indented by `depth`), **Kelas** (`meta.gradeLevels`) and **Jenis Bahan** (`meta.bookTypes`), each a radio group with "Semua". Changes apply at once, so the Terapkan button is removed. |
| Sort `populer/terbaru/judul` | `popular` / `newest` (default) / `title` |
| "Menampilkan X dari Y" | `totalElements`, with "baris a–b" computed from `page * size` |
| Results header "Hasil Pencarian … • fase" + fake description | `"{q}"` or "Semua Koleksi", plus the active filter labels. The description becomes "{totalElements} judul ditemukan". |
| Card badges `faseBadge` / `ddcBadge` / format | Grade label / type label / a "Staf" badge only for `STAFF` |
| "Baca Daring" button | "Lihat & Baca" → `/buku/{id}` |
| Client-side filtering and paging | Server paging, `size: 12`, through `<Pagination>` |

**`DetailBukuView` (`/buku/:id`)**
| Current UI | Becomes |
|---|---|
| Breadcrumb "Buku Teks Fase F" | `categoryName` → `/kategori?categoryId={id}`. Hidden when null. |
| Large cover + badges + "18.4 MB (PDF 2024)" | `BookCover` (gradient) with type and grade badges. The bottom tag is `{formatBytes(fileSize)} • {EXT}`. |
| "Stok Rak" card + Pinjam Cetak | **"Informasi Berkas"** card: original file name, size, "{downloadCount}× diunduh" |
| "Kutipan Ilmiah" card | Kept. Builds an APA-style string from author, year (or "t.t."), title and publisher, then copies it to the clipboard. |
| Top badges | Type, category and curriculum (each only when present), plus a "Khusus Staf" badge for `STAFF` |
| Penulis / Penerbit / Terbit | Real fields, each hidden when null |
| Metrics ISBN / Halaman / Format / Bahasa | **Kelas** / **Format** (EXT) / **Ukuran** / **Ditambahkan** (`formatDate(createdAt)`) |
| "Baca Daring Sekarang" | Scrolls to `#baca`. Disabled with a tooltip when `!canPreview`. |
| "Unduh E-Book (Offline)" | The plain download link for `PUBLIC`, `downloadAuthed` for `STAFF`. Shows a toast with the file name. |
| Share | Kept (copies the URL) |
| Tabs Sinopsis / Daftar Isi / Bibliografi | **Deskripsi** (`description`, or "Belum ada deskripsi.") / **Data Bibliografi** (author, publisher, year, curriculum, category, type, access, file name, uploader `uploadedByName` (or "Impor massal"), updated date). The Daftar Isi tab is removed. |
| Fake reader canvas, search, page, zoom, prev/next | The header keeps the title and file name, with "Buka di Tab Baru" and Layar Penuh. The body is an `<iframe :src>` (about 80vh, full screen with the existing `isFullscreen` overlay) when `canPreview`. Otherwise a panel says "Pratinjau tidak tersedia untuk format {EXT}" with an Unduh button. |
| Related books (4 hard-coded) | `listBooks({ categoryId, size: 5 })` without the current book, up to 4. With no category, `sort: 'popular'` instead. Hidden when empty. Cards use `BookCover` (gradient). The badge is the type label and the footer shows the download count. |
| Not found / error | 404 → a "Buku tidak ditemukan" card with a link back to the catalog. Other errors → an error state with a retry button. |
| Opening `/buku/{id}#baca` | After load, scrolls to the reader |

### 3.4 Seed script: `scripts/seed-books.py`
- Usage: `python scripts/seed-books.py [--base http://localhost:8081] [--user admin --password admin123] [--extra PATH ...]`
- Logs in, reads `/categories` and matches categories by name. It then creates and uploads **16 books**:
  - Different types, grades, years and curricula, spread over at least 6 categories.
  - **2 `STAFF`** books, one of them with a cover.
  - **5 with a generated PNG cover**.
  - 1 with no author, publisher or year.
  - 1 with a very long title.
  - Titles chosen for the search tests ("Matematika Peminatan", "Fisika Gelombang", "Sejarah Garut", plus a 2-letter word like "IPA").
- Each PDF is a minimal valid PDF with one page showing its title. The content is unique per title, so the duplicate check never trips on the script's own books.
- `--extra PATH`: uploads your own files as well (for example a `.docx` or `.epub`) to test the no-preview panel.
- Prints one line per book: `201 created id=…`, or `409 skipped (duplicate)`.

---

## 4. Changes

### 4.1 New files
`src/api/books.js`, `src/api/categories.js`, `src/api/meta.js`, `src/api/media.js`, `src/components/BookCover.vue`, `src/components/Pagination.vue`, `src/components/StateBlock.vue`, `scripts/seed-books.py`

### 4.2 Modified files
| File | Change |
|---|---|
| `src/utils/labels.js` | Enum labels and format helpers (§3.1) |
| `src/views/KatalogView.vue` | §3.3. Removes `rekomendasiBuku`, `koleksiBuku`, `rekomendasiPills` and `kategoriPills`. |
| `src/views/CariKategoriView.vue` | §3.3. Removes `allBooks`, `mapelOptions`, `ddcOptions`, `popularTags`, the scan modal state, and client-side `filteredBooks` and paging. |
| `src/views/DetailBukuView.vue` | §3.3. Removes `bukuDetail` mock, `daftarIsiList`, `relatedBooks` mock, the reader page and zoom state, and `handlePinjamCetak`. Removes the `h()` icon tab definitions for the dropped tab. |
| `frontend .md/frontend-plan.md` | Status: Stage 2 "In progress" when work starts, "Done" at sign-off |

No changes to the router, auth or Stage 1 files, apart from the label additions.

---

## 5. Manual testing guide

### 5.1 Setup
| Item | Value |
|---|---|
| Backend | Dev profile on `http://localhost:8081`. `GET /api/health` → `UP` |
| Frontend | `npm run dev` → `http://localhost:5173` |
| Test data | `python scripts/seed-books.py --extra "C:\path\to\any.docx"`. Expect 16 or 17 lines of `201 created`. A re-run gives `409 skipped`. |
| Accounts | Anonymous (a private window, or after Keluar) and `admin` / `admin123` |
| Tools | DevTools Network tab (filter `api`), Console |

Remember: `STAFF` books only exist for logged-in staff. Run each "anonymous" test while logged out.

### 5.2 Build and cleanup
| # | Step | Expected |
|---|---|---|
| 1 | `npm run build` | No errors |
| 2 | Search the three views for `koleksiBuku\|rekomendasiBuku\|allBooks\|ddcOptions\|mapelOptions\|daftarIsiList\|isbn\|Siti Rohmah\|Pinjam` | No matches |

### 5.3 Catalog `/` (anonymous)
| # | Step | Expected |
|---|---|---|
| 3a | Open `/` | Network: `GET /api/books?size=1`, `/api/categories?type=BOOK` and `/api/meta/filters` → 200. The hero stats show the real book count (only `PUBLIC` books, so the seeded `STAFF` ones aren't counted) and the real category count. |
| 3b | "Buku Terpopuler" | Up to 3 cards. Pills: Semua, Kelas X, Kelas XI, Kelas XII, Semua Kelas. Clicking "Kelas X" sends `grade=X&sort=popular&size=3` and shows only grade X books. |
| 3c | "Katalog Lengkap" | 8 cards, newest first. The cover shows the uploaded PNG for books with a cover and a stable gradient with the title for the rest. Reloading gives the same gradients. |
| 3d | Click a category pill (for example "Matematika") | The request includes `categoryId=…&page=0`, and only that category's books are shown. A category with no books shows the empty state with a reset button. |
| 3e | Pagination | With 14+ `PUBLIC` books: page 2 sends `page=1`, and the "Menampilkan" text updates. ‹ is disabled on page 1 and › on the last page. |
| 3f | Card "Baca" | Opens `/buku/{id}#baca` and scrolls to the reader. "Detail" opens `/buku/{id}` at the top. |
| 3g | No fake content | No Dewey, stock, page count or "Siti Rohmah" text anywhere on the page. |

### 5.4 Search `/kategori` (anonymous)
| # | Step | Expected |
|---|---|---|
| 4a | Open `/kategori` | The search box is empty. Results are newest first, 12 per page. The sidebar has Kategori, Kelas and Jenis Bahan, with labels in Indonesian ("Modul Ajar", "Bank Soal"…). There's no barcode button or DDC. |
| 4b | Type `gelombang` slowly | **One** request about 300 ms after the last keystroke, with `q=gelombang`. The URL becomes `/kategori?q=gelombang`. "Fisika Gelombang" is found. |
| 4c | Type `IP` (2 letters) | Still returns matches (the server uses a substring search for 1–2 characters). |
| 4d | Pick Kategori "Fisika", then Kelas "XI", then Jenis "Modul Ajar" | Each change updates the URL and makes a request with all the active parameters. Chips appear for each filter. ✕ on a chip removes only that filter, and "Reset Semua" clears all of them, including `q`. |
| 4e | Sort "Judul (A–Z)" | `sort=title`, and the results are in alphabetical order |
| 4f | With 13+ results, go to page 2 | `page=1` in the request and `page=2` in the URL. Changing any filter returns to page 1. |
| 4g | Copy the URL with filters, open it in a new tab | The same filters, chips and results are restored. |
| 4h | Browser **Back** after a few filter changes | Each step restores the earlier filter state. Typing in the search box doesn't add history entries. |
| 4i | Type quickly `fis` → `fisika` → `sej` | The final results match `sej`. An earlier, slower response never replaces them. |
| 4j | Search `zzzzqq` | The empty state shows, and its reset button clears the filters. |
| 4k | Category quick chips under the search bar | Clicking one sets that category. |

### 5.5 Book detail `/buku/:id`
| # | Step | Expected |
|---|---|---|
| 5a | Open a seeded PDF book with a cover | The breadcrumb shows the category (clicking it opens `/kategori?categoryId=…`). Title, author, publisher and year are correct. Metrics: Kelas, Format "PDF", Ukuran, Ditambahkan. The Deskripsi tab shows the text, and Data Bibliografi shows real fields. |
| 5b | The book with no author, publisher or year | Those lines are hidden. There are no "null" or "undefined" strings, and the citation uses "t.t.". |
| 5c | Reader | The `<iframe>` loads `/api/books/{id}/file` and the PDF shows in the browser viewer. "Layar Penuh" fills the screen, and the close button exits. "Buka di Tab Baru" opens the PDF in a new tab. |
| 5d | Unduh | Saves the original file name (`….pdf`). Network: `/api/books/{id}/download` → 200. After a reload the download count goes up by 1 (the update is async, so wait a second). |
| 5e | The `--extra` `.docx` book | The reader area shows "Pratinjau tidak tersedia untuk format DOCX" with Unduh. "Baca Daring Sekarang" is disabled. Downloading works. |
| 5f | Salin Sitasi / Share | A toast appears, and the clipboard has the APA string / the page URL. |
| 5g | Related books | Up to 4 books from the same category, never the current one. Clicking one updates the URL, title, reader and related list without a full reload. |
| 5h | `/buku/999999` | Shows "Buku tidak ditemukan" with a link back to the catalog. |

### 5.6 `STAFF` visibility
| # | Step | Expected |
|---|---|---|
| 6a | Anonymous: search for a seeded `STAFF` title | Not found |
| 6b | Anonymous: open `/buku/{staffId}` directly (ids are in the seed output) | "Buku tidak ditemukan", because the API returns 404 |
| 6c | Log in as `admin`, then search again | The `STAFF` books appear with a "Staf" badge. The catalog totals now include them. |
| 6d | Admin: the `STAFF` book with a cover | The cover shows. Network: `/api/books/{id}/cover` fetched with an `Authorization` header, and the `<img>` src is `blob:`. |
| 6e | Admin: open that `STAFF` book | The reader iframe src is `blob:`, and the PDF renders. Unduh saves the original file name. |
| 6f | Admin: go from the `STAFF` book to another page and back several times | No growing memory or console errors. Blob URLs are revoked, which you can check by opening an old `blob:` URL in a new tab: it fails. |
| 6g | `PUBLIC` covers while logged in | Still plain `/api/books/{id}/cover` URLs, not `blob:` |

### 5.7 Errors and resilience
| # | Step | Expected |
|---|---|---|
| 7a | Stop the backend and reload `/`, `/kategori` and a detail page | Each shows the error state "Tidak dapat terhubung ke server perpustakaan…" with **Coba Lagi**. There are no uncaught errors in the console. |
| 7b | Start the backend and click **Coba Lagi** | The data loads without a page reload. |
| 7c | While on `/kategori`, set the viewport to phone width (DevTools device toolbar) | The sidebar stacks above the results, nothing scrolls horizontally, and the chips wrap. |

### 5.8 Sign-off
All of tests 1–7 pass → commit Stage 2 and set its status to **Done** in `frontend-plan.md`.
