# Stage 5A: Librarian admin, part 1 (implementation plan and manual testing guide)

> **Status:** Done · **Roadmap:** [Stage 5](frontend-plan.md#stage-5-librarian-admin-admin-sidebar-in-stafflayout) · **Previous:** [Stage 4](stage-4-staff-uploads.md) · **Backend refs:** [spec §2, §3.6, §5.6](../.md/backend-spec.md), [phase 9: trash](../.md/phase-9-trash.md), [frontend-guide §8 Admin screens](../frontend-guide.md)

## 1. Summary
Stage 5 is split in two, as you chose:
- **5A (this doc):** moves librarians into the staff layout and adds the screens that manage content.
- **5B (next):** Pengguna, Kategori, Impor and Penyimpanan.

5A delivers:
- **One staff shell for librarians.** `/admin/*` uses `StaffLayout` (Stage 4). Its sidebar shows Buku Saya and Arsip Saya plus a **Pengelolaan** group. The old mock `AdminDashboardView` is deleted, which fixes the "different dashboard, no way to Unggahan" issue.
- **Statistik** (`/admin/statistik`, the librarian's home):
  - Stat cards.
  - A 7-day download line chart.
  - Bar charts of books by type and archive items by media type.
  - The top books table, all from `GET /api/admin/stats`.
- **Semua Buku** (`/admin/buku`) and **Semua Arsip** (`/admin/arsip`):
  - Every item, including other teachers' uploads, bulk imports and `STAFF` items.
  - Search and filters in the URL.
  - Upload, edit and delete any item, reusing the Stage 4 forms.
  - **Undo:** an "Urungkan" button on the delete toast.
- **Tempat Sampah** (`/admin/sampah`), added after backend phase 9:
  - The trash for books and archive items, with the delete date and the automatic purge date.
  - **Pulihkan**, **Hapus Permanen** and **Kosongkan**.

**Done when:**
- `npm run build` passes, and no file imports `AdminDashboardView`, `EditBukuModal` or `DeleteBukuModal` (all three are deleted).
- Logging in as `admin` lands on `/admin/statistik` inside the staff shell, with real numbers and charts. Buku Saya, Arsip Saya and the admin screens are one click apart.
- A librarian can find, edit and delete any book or archive item, then restore it with Urungkan or from Tempat Sampah, or purge it there.
- Teachers still can't reach `/admin/*`, and their sidebar is unchanged.

---

## 2. Decisions
- **Routes.** `/admin` becomes a parent route with `component: StaffLayout` and `meta: { requiresAuth: true, roles: ['LIBRARIAN'], hideNavbar: true }`. It has these children:
  - `''` redirects to `statistik`.
  - `statistik` → `AdminStatistik`, `buku` → `AdminBuku`, `arsip` → `AdminArsip`, `sampah` → `AdminSampah` (`?tab=arsip` for the archive trash).
  - The old `/admin/dashboard` redirects to `/admin/statistik`, for bookmarks.

  `auth.homePath()` already returns `/admin` for librarians, so login lands on Statistik. The header menu's "Panel Admin" keeps working.
- **Sidebar for librarians** (`StaffLayout`). "Unggahan Saya" (Buku Saya, Arsip Saya) stays first. Below it, a **Pengelolaan** group: Statistik, Semua Buku, Semua Arsip and Tempat Sampah. 5B adds Pengguna, Kategori, Impor and Penyimpanan to the same group. The temporary "Panel Admin" link is removed. Teachers' sidebar doesn't change.
- **Statistik**
  - **Five `StatCard`s** (`components/ui/StatCard.vue`): Total Buku, Total Arsip, Total Unduhan, Unduhan Hari Ini, Unduhan Minggu Ini.
  - **7-day line chart:** `dailyDownloads` only contains days that had downloads (the dev backend returned a single day). The view builds the last 7 calendar days, ending today in local time, and fills missing days with 0. X labels are short Indonesian dates ("Sen 17/11").
  - **Two bar charts:** books by type (`BOOK_TYPE_LABELS`) and archive by media type (`MEDIA_TYPE_LABELS`). The categories come from `getFilters()`, so types with 0 items still show.
  - **Top books table:** rank, title (link to `/buku/{id}`), author, downloads.
  - **Charts:** `vue-chartjs` + `chart.js`, already in `package.json`. The components are registered once in `src/utils/charts.js`, since the old `statistik/*` files they came from were deleted in Stage 1. Teal palette, no legend for single-series charts, and `maintainAspectRatio: false` inside fixed-height boxes. Each chart has a short text summary for screen readers.
  - **Controls and states:** a **Muat Ulang** button, loading skeletons, and an error state with retry.
- **Semua Buku / Semua Arsip** reuse the Stage 4 list pattern (table on `md+`, cards on phones, `Pagination`, `StateBlock`). Differences from the Stage 4 lists:
  - **Data source:** the public list endpoints (`GET /books`, `GET /archive`). The librarian's token makes `STAFF` items appear.
  - **Filters (synced to the URL, debounced search):**
    - Books: `q`, `categoryId`, `grade`, `type`, `sort`.
    - Archive: `q`, `categoryId`, `mediaType`, `sort`.
    - Page size 20.
  - **Columns:** an extra **ID** column (muted, `#123`), matching the IDs in Tempat Sampah. Books also show a Kategori column.
  - **Uploader:** the summaries have no uploader. The edit modal shows "Diunggah oleh …" from the detail (`uploadedByName`, or "Impor massal").
  - **Upload:** an **Unggah** button uses the same `BookFormModal` / `ArchiveFormModal`. A librarian's upload also appears in their Buku Saya / Arsip Saya.
- **Restore and trash.** Backend phase 9 added a trash list, which replaced the earlier "Pulihkan berdasarkan ID" panel.
  - **Urungkan:** after a delete, the toast reads "'{title}' dipindahkan ke tempat sampah" with an **Urungkan** button, shown for 8 s. It calls `POST /api/admin/{books|archive}/{id}/restore`. On success the list reloads and the toast says "'{title}' dipulihkan".
  - **Tempat Sampah** (`SampahView`):
    - Buku / Arsip tabs on `GET /api/admin/{books|archive}/trash`, 20 per page, most recently deleted first.
    - **Columns:** ID, title (with file name and size), type (`kind` → book type or media type label), uploader (or "Impor massal"), deleted at (date and time), and purge date (`purgeAt`, or "Tidak otomatis" when the nightly job is off).
    - **Pulihkan:** the restore call, then a toast with a Lihat link to the public page.
    - **Hapus Permanen:** a confirmation ("tidak dapat dibatalkan"), then `DELETE …/trash/{id}`.
    - **Kosongkan Buku/Arsip:** a confirmation with the item count, then `DELETE …/trash`, and a toast "{n} item dihapus permanen".
    - **Errors:** `404` (already restored or purged elsewhere) shows a toast and reloads. `503 STORAGE_UNAVAILABLE` (drive unplugged) shows its message.
    - A note explains automatic purging, and that a file can only be uploaded again after it's purged (the duplicate check still matches trashed files).
  - `DeleteItemModal`'s librarian note becomes "Dapat dipulihkan dengan tombol Urungkan atau dari Tempat Sampah sebelum dihapus permanen."
- **Toasts with an action.** `useToast().show(message, { tone, duration, action: { label, onClick } })`. `ToastHost` renders the action as a button. Clicking it runs `onClick` and hides the toast. Existing calls are unchanged.
- **Shared list logic.** `composables/staffList.js` (Stage 4) gains an optional `filterKeys` list and a `setFilter(patch)` that resets the page. `fetchPage` then receives `{ page, size, ...filters }`. The Stage 4 views call it without `filterKeys`, so they behave as before.
- **Thumbnail polling** moves from `ArsipSayaView` into `composables/thumbnailPolling.js`, so Semua Arsip shares it. `ArsipSayaView` switches to the composable with no change in behaviour.
- **Deleted files:**
  - `src/views/admin/AdminDashboardView.vue`
  - `src/components/admin/EditBukuModal.vue`, `src/components/admin/DeleteBukuModal.vue` (and the then-empty `components/admin/` folder)
  - `components/ui/StatCard.vue` is kept and finally used.

---

## 3. Design

### 3.1 API and utilities
| File | Change |
|---|---|
| `src/api/admin.js` (new) | `getStats()` → `AdminStats`; `restoreBook(id)`, `restoreArchive(id)`, `restoreItem(kind, id)` (return the restored detail); `listTrash(kind, { page, size })`, `purgeTrashItem(kind, id)`, `emptyTrash(kind)` with `kind` = `'book' \| 'archive'`. 5B adds users, categories, import and storage here. |
| `src/utils/charts.js` (new) | Registers `CategoryScale`, `LinearScale`, `PointElement`, `LineElement`, `BarElement`, `Filler` and `Tooltip` once, and exports `CHART_COLORS` and `baseOptions()` (font, grid, tooltip in `id-ID` number format). |
| `src/utils/labels.js` | `lastNDays(n)` → `['YYYY-MM-DD', …]` in local time; `formatShortDay('YYYY-MM-DD')` → "Sen 17/11" (no UTC shift, same rule as `formatEventDate`); `formatDateTime(iso)` for the trash's deleted-at column |
| `src/composables/toast.js`, `src/components/ToastHost.vue` | Optional `action` button (§2) |
| `src/composables/staffList.js` | Optional `filterKeys`, `setFilter` (§2) |
| `src/composables/thumbnailPolling.js` (new) | `useThumbnailPolling(replaceItem)` → `{ processing, thumbKeys, watchProcessing, stopPolling }`, moved from `ArsipSayaView` |

### 3.2 Layout and routes
| File | Change |
|---|---|
| `src/router/index.js` | `/admin` parent with `StaffLayout` and its children (§2); `/admin/dashboard` redirect; remove the `AdminDashboardView` import and route |
| `src/layouts/StaffLayout.vue` | Librarian-only "Pengelolaan" group with Statistik, Semua Buku, Semua Arsip and Tempat Sampah, replacing the single Panel Admin link. The links are defined in one array with a `librarianOnly` flag, so 5B only adds entries. |

### 3.3 Views (`src/views/admin/`)
| File | Content |
|---|---|
| `StatistikView.vue` | Header "Statistik Perpustakaan" with the date and Muat Ulang. Then a grid of 5 `StatCard`s, a line chart (full width, `h-72`), the two bar charts side by side on `lg` (stacked on phones), and the top-books table. |
| `BukuAdminView.vue` | Header "Semua Buku" with the total and an Unggah Buku button, the filter bar, then the table and cards. Actions: Lihat (new tab), Edit (`BookFormModal`), Hapus (`DeleteItemModal` → toast with Urungkan). |
| `ArsipAdminView.vue` | The same for the archive. Thumbnails with `ArchiveThumb` and polling after an upload or edit. Lihat opens `/arsip?item={id}`. |
| `SampahView.vue` | Header "Tempat Sampah" with a Kosongkan button, the Buku / Arsip tabs, the purge note, then the table and cards with Pulihkan and Hapus Permanen (§2). |
| `components/staff/ConfirmModal.vue` (new) | A generic confirmation on `BaseModal`. **Props:** `title`, `message`, `confirmLabel`, `danger`, `loading` (blocks closing). **Emits:** `confirm`. Used for Hapus Permanen and Kosongkan. |

---

## 4. Changes

### 4.1 New files
`src/api/admin.js`, `src/utils/charts.js`, `src/composables/thumbnailPolling.js`, `src/components/staff/ConfirmModal.vue`, `src/views/admin/StatistikView.vue`, `src/views/admin/BukuAdminView.vue`, `src/views/admin/ArsipAdminView.vue`, `src/views/admin/SampahView.vue`

### 4.2 Modified files
| File | Change |
|---|---|
| `src/router/index.js` | Nested `/admin` routes |
| `src/layouts/StaffLayout.vue` | Pengelolaan group |
| `src/composables/staffList.js` | Filters support |
| `src/composables/toast.js`, `src/components/ToastHost.vue` | Toast action |
| `src/components/staff/DeleteItemModal.vue` | Librarian restore note |
| `src/components/staff/BookFormModal.vue`, `ArchiveFormModal.vue` | Edit mode shows "Diunggah oleh {uploadedByName}" (or "Impor massal") next to the current file |
| `src/views/staff/ArsipSayaView.vue` | Uses `useThumbnailPolling` |
| `src/utils/labels.js` | Day helpers for the chart |
| `frontend .md/frontend-plan.md` | Status rows 5A / 5B. 5A is "In progress" when work starts and "Done" at sign-off. |

### 4.3 Deleted files
`src/views/admin/AdminDashboardView.vue`, `src/components/admin/EditBukuModal.vue`, `src/components/admin/DeleteBukuModal.vue`

> **As built:**
> - **Trash replaced restore-by-ID.** 5A was first built with a "Pulihkan berdasarkan ID" panel, because the backend had no trash list. Backend phase 9 added one, so the panel was removed before testing and Tempat Sampah took its place. Restore is now only offered for items in the trash list, which also avoids a backend quirk: `POST …/restore` on an active item returns `200` and does nothing.
> - **Chart colour.** The marks use `#0A7EA0`, a slightly more saturated teal than the brand `#0B6B82`, which failed the dataviz validator's chroma floor (it reads gray). The line is 2 px with 8 px points ringed in the surface colour, and the bars have 4 px rounded tops. There's no legend (one series per chart), and a screen-reader text summary sits under each chart.
> - **Controllers.** `utils/charts.js` also registers `LineController` and `BarController`.
> - **Toast actions** accept either `onClick` (Urungkan) or `to` (a router link, used for Lihat after restoring from the trash).

### 4.4 Stage 5B (next, outline only)
- **Pengguna** (`/admin/pengguna`): list, create a teacher or librarian, edit the name or role, reset the password, deactivate or reactivate. Errors: `DUPLICATE_USERNAME`, `CANNOT_DEACTIVATE_SELF`, `CANNOT_DEACTIVATE_LAST_LIBRARIAN`.
- **Kategori** (`/admin/kategori`): a BOOK/ARCHIVE toggle and the tree, with create, rename, move and delete (`?type=ARCHIVE`). Errors: `CATEGORY_IN_USE`, `DUPLICATE_CATEGORY`, `INVALID_PARENT`.
- **Impor** (`/admin/impor`): start a BOOK or ARCHIVE job, poll it every 2 s, show counts and errors, list recent jobs, and a CSV guide. `IMPORT_ALREADY_RUNNING` opens the running job.
- **Penyimpanan** (`/admin/penyimpanan`): the disk bar against `minFreeBytes`, "Drive tidak terhubung" when unavailable, and a bucket table.

5B gets its own plan and testing guide when you ask for it.

---

## 5. Manual testing guide

### 5.1 Setup
| Item | Value |
|---|---|
| Backend | Dev profile on `http://localhost:8081`; `GET /api/health` → `UP` |
| Frontend | `npm run dev` → `http://localhost:5173` |
| Accounts | Librarian `admin` / `admin123`; teacher `guru_uji` |
| Tools | DevTools Network tab (filter `api`), Console |

**Test data checklist** (your dev backend already covers all of it):

| Need | For tests |
|---|---|
| Books from `guru_uji` and from `admin`, including at least one `STAFF` book | 3a, 3e |
| A few books and archive items you don't mind purging (upload throwaway files, or use the 3d/4d uploads) | 5a–5f |
| Bulk-imported archive items (no uploader) | 4a, 4c |
| At least one download today (the stats already show 4) | 2b, 2c |
| More than 20 books or archive items, for paging (the archive has 21) | 4b |

### 5.2 Build, routes and layout
| # | Step | Expected |
|---|---|---|
| 1a | `npm run build` | No errors |
| 1b | Search `src` for `AdminDashboardView\|EditBukuModal\|DeleteBukuModal` | No matches |
| 1c | Log in as `admin` | You land on `/admin/statistik` inside the staff shell (dark sidebar, no public header). The sidebar has Buku Saya, Arsip Saya, then **Pengelolaan**: Statistik (active), Semua Buku, Semua Arsip, Tempat Sampah. |
| 1d | Open `/admin`, `/admin/dashboard`, and the header menu's "Panel Admin" from `/` | All three end on `/admin/statistik` |
| 1e | Click Buku Saya, then Semua Buku, then Statistik | Each opens without leaving the shell, and the active link follows |
| 1f | As `guru_uji`, open `/admin/statistik` | Redirect to `/unggahan/buku`. The teacher's sidebar has no Pengelolaan group. |
| 1g | Phone width | The drawer shows both groups, and the charts and table fit without horizontal page scroll |

### 5.3 Statistik
| # | Step | Expected |
|---|---|---|
| 2a | Open Statistik | `GET /api/admin/stats` → 200. The five cards match the response: totalBooks, totalArchiveItems, totalDownloads, downloadsToday, downloadsThisWeek. |
| 2b | Line chart | Exactly 7 points, ending today. Days without downloads are 0. Today matches `downloadsToday`. |
| 2c | Download a book from `/buku/{id}` in another tab, then click Muat Ulang | Unduhan Hari Ini, the total and today's point go up by 1 (the count is async, so wait a second) |
| 2d | Bar charts | Books by type shows all 5 types in Indonesian (E-Book, Modul Ajar…), including zeros. Archive shows Foto / Video / Dokumen. The values match `booksByType` and `archiveByMediaType`. |
| 2e | Top books table | Ranked by downloads. The titles link to `/buku/{id}`. |
| 2f | Stop the backend and click Muat Ulang | An error state with Coba Lagi. No console errors. |

### 5.4 Semua Buku
| # | Step | Expected |
|---|---|---|
| 3a | Open Semua Buku | `GET /api/books?…size=20`. Books from all uploaders are listed, including `STAFF` ones (Khusus Staf badge), each with its ID |
| 3b | Search, category, grade, type, sort | Each updates the URL and the request, and resets to page 1. Reload keeps the filters. |
| 3c | Edit a book uploaded by `guru_uji` | The modal shows "Diunggah oleh Budi Santoso, S.Pd.". Saving works (librarians can edit any item). The row updates. |
| 3d | Unggah Buku | Same as Stage 4. The new book appears here and in admin's Buku Saya. |
| 3e | Hapus a book | The modal note mentions Urungkan and Tempat Sampah. After confirming, the toast shows "Urungkan" for about 8 s, and the row disappears. |
| 3f | Click Urungkan | `POST /api/admin/books/{id}/restore` → 200. Toast "'{title}' dipulihkan". The book is back in the list and on `/buku/{id}`. |
| 3g | As `guru_uji`, after 3f | The restored book is back in Buku Saya, if it was theirs |

### 5.5 Semua Arsip
| # | Step | Expected |
|---|---|---|
| 4a | Open Semua Arsip | All 21+ items, including the bulk-imported ones and `STAFF` items, with thumbnails |
| 4b | Paging | Page 2 loads (`?page=2`). Filters (search, category, media type, sort) work as in 3b. |
| 4c | Edit a bulk-imported item | The modal shows "Diunggah oleh Impor massal". Saving works. |
| 4d | Upload a photo | "Memproses thumbnail…", then the real thumbnail, as in Stage 4 |
| 4e | Hapus → Urungkan | Same as 3e–3f, using `/api/admin/archive/{id}/restore` |
| 4f | Arsip Saya (Stage 4) after the refactor | Upload, polling, edit and delete still work as before |

### 5.6 Tempat Sampah
| # | Step | Expected |
|---|---|---|
| 5a | Delete a book in Semua Buku and let the toast expire. Open Tempat Sampah. | `GET /api/admin/books/trash`. The book is first, with its ID, file name and size, type, uploader, deleted time, and a purge date 30 days later. |
| 5b | Pulihkan | `POST /api/admin/books/{id}/restore` → 200. The row leaves the trash, and the toast's Lihat opens `/buku/{id}`. |
| 5c | Delete the book again, then Hapus Permanen in the trash and confirm | The confirmation says it can't be undone. `DELETE /api/admin/books/trash/{id}` → 204, and the row disappears. `/buku/{id}` shows "Buku tidak ditemukan". Uploading the same file again now succeeds (no `DUPLICATE_FILE`). |
| 5d | Arsip tab | The URL gets `?tab=arsip`, and the list uses `/api/admin/archive/trash` with media type labels (Foto / Video / Dokumen). Pulihkan and Hapus Permanen work as in 5b–5c. |
| 5e | Delete 2 archive items, then Kosongkan Arsip and confirm | The confirmation shows the count. `DELETE /api/admin/archive/trash` → `{"purged": 2}`, the toast says "2 item dihapus permanen", and the tab shows the empty state. The Buku tab is untouched. |
| 5f | Open the trash in two tabs. Restore an item in one, then click Hapus Permanen on it in the other. | `404` → toast "Item sudah tidak ada di tempat sampah." and the list reloads. |
| 5g | As `guru_uji`, open `/admin/sampah` | Redirect to `/unggahan/buku` |

### 5.7 Sign-off
All of tests 1–5 pass → commit Stage 5A and set its status to **Done** in `frontend-plan.md`. Then ask for the Stage 5B plan.
