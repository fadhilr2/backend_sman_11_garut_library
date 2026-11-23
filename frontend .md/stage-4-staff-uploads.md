# Stage 4: Staff uploads (implementation plan and manual testing guide)

> **Status:** Done · **Roadmap:** [Stage 4](frontend-plan.md#stage-4-staff-uploads-unggahan) · **Previous:** [Stage 3](stage-3-archive.md) · **Backend refs:** [spec §2, §3.5, §4, §5](../.md/backend-spec.md), [frontend-guide §6 Uploads, §7 Errors](../frontend-guide.md)

## 1. Summary
Replace the `/unggahan` placeholder with a **staff area** where teachers and librarians manage their own uploads:
- List my books and my archive items (`/books/mine`, `/archive/mine`).
- Upload new ones with a progress bar.
- Edit their metadata, and replace a book's file.
- Delete them (soft delete).

The area uses its own **staff shell** (`layouts/StaffLayout.vue`): a sidebar instead of the public header and footer. Stage 5's admin screens reuse it.

Deliverables:
- `src/api/upload.js` (XHR multipart with progress and abort)
- Write calls in `books.js` and `archive.js`
- `layouts/StaffLayout.vue`, with nested routes `/unggahan/buku` and `/unggahan/arsip`
- `components/staff/`: `FilePicker.vue`, `BookFormModal.vue`, `ArchiveFormModal.vue`, `DeleteItemModal.vue`
- `views/staff/BukuSayaView.vue` and `views/staff/ArsipSayaView.vue`. These replace `UnggahanSayaView.vue`.

**Done when:**
- `npm run build` passes.
- A teacher can upload a book (with an optional cover) and an archive item, and sees progress, then "Memproses…", then the new row.
- Edits save only the changed fields. A book's file can be replaced.
- Delete removes the row, and the item is gone from the public pages.
- Every error code from frontend-guide §7.1 that these forms can hit shows in the right place: under the input, on the file picker, or as a toast.
- A new archive photo or video gets its real thumbnail without a manual reload.

---

## 2. Decisions
- **Staff shell** (your choice). The public header and footer are hidden (`meta.hideNavbar` on the parent route). Sidebar contents:
  - The school logo and "E-Library Staf".
  - A user card (name, role).
  - Links: **Buku Saya**, **Arsip Saya**, and for librarians **Panel Admin** (`/admin`, the current dashboard until Stage 5).
  - At the bottom: **Lihat Katalog** (`/`), **Ganti Kata Sandi** (the existing `ChangePasswordModal`) and **Keluar**.
  - On phones the sidebar becomes a top bar with a menu button that opens it as a drawer.
- **Routes:** `/unggahan` redirects to `/unggahan/buku`. The Arsip tab is `/unggahan/arsip`. Both are nested under `StaffLayout` with `requiresAuth` and `roles: ['TEACHER', 'LIBRARIAN']`. The page number is in the URL (`?page=`, starting at 1), like the public lists. Links to `/unggahan` (header menu, `homePath()` for teachers) keep working through the redirect.
- **Forms are modals** (your choice), built on `components/ui/BaseModal.vue` (`maxWidth="2xl"`). The modal's `v-model` goes through a guard: while an upload is running, closing (✕, Esc, backdrop) asks "Batalkan unggahan?" and aborts the XHR on yes.
- **Edit loads the detail first** (`getBook` / `getArchive`). The summaries have no `description`, and archive summaries have no `originalName`. The form fills from the detail. While it loads, the modal shows a skeleton.
- **Edits send only changed fields** (frontend-guide §6.4):
  - Book `PUT` always includes `title`.
  - Unchanged fields and empty strings are left out.
  - **Fields can't be cleared on the server.** When a field had a value and is emptied, its hint turns into a warning ("Kolom ini tidak dapat dikosongkan, isi dengan nilai baru atau biarkan seperti semula") and Simpan is disabled. For category, "— Tanpa kategori —" is offered only on upload, not on edit.
  - Simpan is disabled until something changes.
- **Replacing a book's file:** an optional "Ganti Berkas" picker in the book edit modal.
  - On Simpan, the metadata `PUT` runs first (if changed), then `PUT /books/{id}/file` with progress.
  - If the file step fails after the metadata saved, the modal stays open with the error on the picker, and the list is refreshed, so the saved metadata isn't lost.
  - Archive files and book covers can't be replaced (no endpoint). The edit form says so.
- **Uploads use XHR** (`src/api/upload.js`), because `fetch` can't report upload progress.
  - `metadata` is a `Blob` with `type: application/json`, and `Content-Type` is never set by hand.
  - The progress text reads "Mengunggah 45% · 12,3 MB dari 27 MB". At 100% it switches to "Memproses…" until the response arrives, because the server still hashes and moves the file.
  - Errors are normalized into the same `ApiError` as `client.js`, and a `401` triggers the same session-ended handler.
- **Client-side file checks** before uploading: extension against the accept list and size ≤ 2 GB. The server still sniffs the content, and its `INVALID_FILE_TYPE` shows on the picker too.

  | Field | Accept |
  |---|---|
  | Book file | `.pdf,.epub,.docx,.pptx,.xlsx` |
  | Book cover | `.jpg,.jpeg,.png` |
  | Archive file | `.jpg,.jpeg,.png,.webp,.gif,.mp4,.webm,.mkv,.pdf,.epub,.docx,.pptx,.xlsx` |

- **Where errors appear:**

  | Error | Shown |
  |---|---|
  | `VALIDATION_FAILED` | `fieldErrors[field]` under the matching input. Unknown keys go into a form-level alert. |
  | `FILE_REQUIRED`, `INVALID_FILE_TYPE`, `DUPLICATE_FILE`, `FILE_TOO_LARGE` | On the file picker. `DUPLICATE_FILE` adds "mungkin ada di tempat sampah, hubungi pustakawan". |
  | `STORAGE_FULL`, `STORAGE_UNAVAILABLE`, network errors | A form-level alert |
  | `FORBIDDEN`, `NOT_FOUND` on edit or delete | A toast, then the list reloads (the item was deleted or isn't yours) |

- **Defaults on upload:**
  - Book: `PUBLIC`, `EBOOK`, grade `ALL`.
  - Archive: visibility **`STAFF`**, with a note "Arsip baru bersifat Khusus Staf secara bawaan".
  - Visibility is a two-option radio with a one-line explanation each.
- **Choices come from the API:** book types, grades and curricula from `getFilters()`, and categories from `listCategories('BOOK' | 'ARCHIVE')` + `flattenCategories` (indented `<select>`). On edit, a curriculum value that's no longer in `meta.curricula` is kept as an extra option.
- **Client validation** mirrors the server:
  - Title is required, max 255.
  - Author and publisher max 255.
  - Year is 4 digits.
  - Event date is a valid `YYYY-MM-DD` from `<input type="date">`.
- **Archive thumbnails after an upload or edit:**
  - New `IMAGE`/`VIDEO` items get a "Memproses thumbnail…" badge.
  - The view polls `GET /archive/{id}` every 2 s, for up to 30 s, until `width` is set. It then updates the row and sets `ArchiveThumb`'s `cacheKey` (the `?v=` cache buster from Stage 3).
  - `DOCUMENT` items aren't polled.
  - When polling stops without a result (for example, no ffmpeg), the badge is removed and the placeholder stays.
- **Delete:** a new `components/staff/DeleteItemModal.vue` in the style of `components/admin/DeleteBukuModal.vue`, with real data and the mock text removed. It shows the cover or thumbnail, title and file info.
  - A teacher sees "Hanya pustakawan yang dapat memulihkan item yang dihapus".
  - A librarian sees "Dapat dipulihkan melalui Panel Admin" (restore arrives in Stage 5).
  - After `204`, the row is removed and the page reloads. If that empties the last page, the view steps back one page.
- **Leaving during an upload:** `onBeforeRouteLeave` asks to confirm, and a `beforeunload` listener warns while an XHR is running.
- **`components/admin/DeleteBukuModal.vue` and `EditBukuModal.vue` stay**, because the mock `AdminDashboardView` still imports them. Stage 5 deletes all three together.
- **Lists:**
  - A table on `md+` (`components/ui/BaseTable.vue`) and stacked cards on phones.
  - Page size 10, newest first (the backend default for `/mine`).
  - Loading, empty and error states use `StateBlock`. The empty state has an "Unggah …" button.
- **Toasts:** a small shared `components/ToastHost.vue` plus a `useToast()` composable (`src/composables/toast.js`) inside `StaffLayout`. The staff pages and Stage 5 then share one toast instead of each copying the Stage 2/3 markup. The public pages keep their own toasts; they aren't touched in this stage.

---

## 3. Design

### 3.1 API modules
| File | Change |
|---|---|
| `src/api/client.js` | Export two helpers it already has internally: `apiErrorFrom(status, bodyText)` (the `readError` logic, working on text) and `notifyUnauthorized(error)`. `request()` keeps its behaviour. |
| `src/api/upload.js` (new) | `upload(path, { method = 'POST', parts, onProgress })` → `{ promise, abort }`. `parts` is a list of `[name, File \| Blob, fileName?]`. Builds a `FormData`, sends with the `Authorization` header and calls `onProgress({ loaded, total })`. Resolves with the parsed JSON. Rejects with an `ApiError`: `apiErrorFrom` on HTTP errors, `NETWORK_ERROR` on `onerror`, and `ABORTED` on abort, which the UI ignores. `jsonPart(obj)` builds the metadata `Blob`. |
| `src/api/books.js` | Add `listMyBooks({ page, size })`, `createBook({ file, cover, metadata, onProgress })` → `{ promise, abort }`, `updateBook(id, patch)` (`PUT` JSON), `replaceBookFile(id, file, onProgress)` → `{ promise, abort }`, `deleteBook(id)` |
| `src/api/archive.js` | Add `listMyArchive({ page, size })`, `createArchive({ file, metadata, onProgress })` → `{ promise, abort }`, `updateArchive(id, patch)`, `deleteArchive(id)` |
| `src/utils/errors.js` | Add `ABORTED` (silent) and a `FILE_ERROR_CODES` set, so the forms know which codes belong on the picker |
| `src/utils/labels.js` | Add `BOOK_FILE_ACCEPT`, `COVER_ACCEPT`, `ARCHIVE_FILE_ACCEPT`, `MAX_UPLOAD_BYTES = 2 * 1024 ** 3`, and `fileMatchesAccept(file, accept)` |

### 3.2 Layout and routes
| File | Purpose |
|---|---|
| `src/layouts/StaffLayout.vue` (new) | The sidebar shell from §2 around a `<router-view>`. Content area `max-w-6xl`, background `#F8FAFC`, teal accents as on the public pages. Holds `ChangePasswordModal` and `ToastHost`. Keluar calls `auth.logout()` and goes to `/`. |
| `src/router/index.js` | Replace the `UnggahanSaya` route with a parent `{ path: '/unggahan', component: StaffLayout, meta: { requiresAuth, roles, hideNavbar }, children: [ { path: '', redirect: { name: 'BukuSaya' } }, { path: 'buku', name: 'BukuSaya', … }, { path: 'arsip', name: 'ArsipSaya', … } ] }`, each with its own `meta.title`. Vue Router merges parent `meta` into `route.meta`, so the existing guard and `App.vue`'s `hideNavbar` check work unchanged. |
| `src/views/staff/UnggahanSayaView.vue` | **Deleted** (the placeholder) |

### 3.3 Components (`src/components/staff/`)
| File | Purpose |
|---|---|
| `FilePicker.vue` | A drop zone plus a hidden `<input type="file">`.<br>**Props:** `modelValue` (File \| null), `accept`, `label`, `hint`, `error`, `disabled`, `maxBytes`.<br>**Behaviour:** drag and drop, or click to choose; shows the file name, size and a ✕ to clear. It checks the extension and size itself and shows its own error, or the `error` prop from the server.<br>**Optional image preview:** for the book cover, it shows the picked image from `URL.createObjectURL` and revokes it when the file changes or the picker unmounts. |
| `UploadProgress.vue` | **Props:** `loaded`, `total`, `processing`. A bar with "Mengunggah 45% · 12,3 MB dari 27 MB", then "Memproses…" with an indeterminate bar. |
| `BookFormModal.vue` | **Props:** `modelValue` (open), `bookId` (null = create). **Emits:** `saved(detail, { created })`.<br>**Fields:** Judul*, Penulis, Penerbit, Tahun Terbit, Jenis, Kelas, Kategori, Kurikulum, Akses, Deskripsi. On create also Berkas* and Sampul (optional). On edit, "Ganti Berkas" (optional), plus a read-only line with the current file name, size and "Sampul tidak dapat diganti".<br>**Behaviour:** the save logic, error mapping, dirty tracking and upload guard from §2. |
| `ArchiveFormModal.vue` | The same pattern. **Fields:** Judul*, Deskripsi, Kategori, Tanggal Kegiatan, Akses (default `STAFF`). On create, Berkas*. On edit, a read-only file line with "Berkas arsip tidak dapat diganti". The media type is detected by the server and shown read-only on edit. |
| `DeleteItemModal.vue` | **Props:** `modelValue`, `kind` (`'book' \| 'archive'`), `item`. **Emits:** `deleted(id)`. Calls `deleteBook` / `deleteArchive` and shows errors inline. The confirm button shows a spinner while deleting. |
| `src/components/ToastHost.vue` + `src/composables/toast.js` (new) | `useToast().show(message, { tone })` with tones `success`, `error` and `info`. One toast at a time, 3 s, `role="status"`. |

### 3.4 Views
**`views/staff/BukuSayaView.vue` (`/unggahan/buku`)**
- **Header:** "Buku Saya", "{totalElements} judul" and an **Unggah Buku** button.
- **Table columns:**
  - Sampul + Judul (author underneath): `BookCover` (compact) in a 40×56 box.
  - Jenis · Kelas.
  - Akses: a badge for Publik or Khusus Staf.
  - Ditambahkan: `formatDate(createdAt)`.
  - Diunduh.
  - Aksi: **Lihat** (`/buku/{id}`, new tab), **Edit**, **Hapus**.
- **Phone:** the same data as cards.
- `Pagination` and `StateBlock` are reused.
- **Toasts after saving:**
  - Upload: "Buku berhasil diunggah". The list returns to page 1, where the new book appears first.
  - Edit: "Perubahan disimpan". The row updates in place from the `PUT` response.

**`views/staff/ArsipSayaView.vue` (`/unggahan/arsip`)**
- The same structure, with these columns:
  - Thumbnail + Judul: `ArchiveThumb` in a 4:3 box 72 px wide, with the "Memproses thumbnail…" badge while polling.
  - Jenis media.
  - Tanggal Kegiatan (`formatEventDate`, or "Tanpa tanggal").
  - Akses.
  - Diunduh.
  - Aksi: **Lihat** (`/arsip?item={id}`, new tab), **Edit**, **Hapus**.
- Thumbnail polling as in §2. Timers are cleared on unmount.

---

## 4. Changes

### 4.1 New files
`src/api/upload.js`, `src/layouts/StaffLayout.vue`, `src/components/staff/FilePicker.vue`, `src/components/staff/UploadProgress.vue`, `src/components/staff/BookFormModal.vue`, `src/components/staff/ArchiveFormModal.vue`, `src/components/staff/DeleteItemModal.vue`, `src/components/ToastHost.vue`, `src/composables/toast.js`, `src/views/staff/BukuSayaView.vue`, `src/views/staff/ArsipSayaView.vue`

> **As built**, some shared pieces were pulled out of the two forms and the two views:
> - `src/composables/uploadTask.js`: upload progress, abort, a global count of running uploads, the `beforeunload` warning and `confirmLeaveIfUploading()` for `onBeforeRouteLeave`.
> - `src/composables/staffList.js`: the paged `/mine` list with `?page=` in the URL, silent reload after save or delete, and the step back from an empty last page.
> - `src/components/staff/VisibilityField.vue` (Publik / Khusus Staf radio), `VisibilityBadge.vue`, `RowActions.vue` (Lihat / Edit / Hapus) and `formUtils.js` (shared select classes, the "can't be cleared" warning).
> - On a `401` during an upload, the redirect to login runs after the upload has been counted as finished, so no "leave the page?" prompt appears.

### 4.2 Modified files
| File | Change |
|---|---|
| `src/api/client.js` | Export `apiErrorFrom` and `notifyUnauthorized` (§3.1) |
| `src/api/books.js`, `src/api/archive.js` | Mine, create, update, replace file and delete calls |
| `src/utils/errors.js`, `src/utils/labels.js` | §3.1 additions |
| `src/router/index.js` | Nested `/unggahan` routes (§3.2) |
| `frontend .md/frontend-plan.md` | Stage 4 row: "In progress" when work starts, "Done" at sign-off, with a link to this doc |

### 4.3 Deleted files
`src/views/staff/UnggahanSayaView.vue`

There are no changes to the public pages, `media.js` or the auth store. The header's "Unggahan Saya" link still points to `/unggahan`.

---

## 5. Manual testing guide

### 5.1 Setup
| Item | Value |
|---|---|
| Backend | Dev profile on `http://localhost:8081`; `GET /api/health` → `UP` |
| Frontend | `npm run dev` → `http://localhost:5173` |
| Accounts | Librarian `admin` / `admin123`; teacher `guru_uji` (already on the backend, with the password you set) |
| Tools | DevTools Network tab (filter `api`), Console |

**Test data checklist** (local files to upload; nothing to add on the backend):

| Need | For tests |
|---|---|
| A small PDF and a larger PDF or video (≥ 50 MB, so the progress bar is visible) | 3a, 3b, 5a |
| A `.docx` or `.epub` | 3c |
| A JPG or PNG to use as a book cover | 3a |
| A text file renamed to `.pdf` (content doesn't match) | 4c |
| A file with an unsupported extension, such as `.zip` or `.exe` | 4b |
| A file already in the library (for example one you uploaded in 3a) | 4d |
| A photo (jpg/png) and a short mp4 for the archive | 5a, 5b |

### 5.2 Build, routes and layout
| # | Step | Expected |
|---|---|---|
| 1 | `npm run build` | No errors |
| 2a | Logged out, open `/unggahan/buku` | Redirects to `/login?redirect=/unggahan/buku`. After login as `guru_uji` you land back on `/unggahan/buku`. |
| 2b | As `guru_uji`, open `/unggahan` | Redirects to `/unggahan/buku`. The public header and footer are hidden, and the sidebar shows the name, role "Guru", Buku Saya (active), Arsip Saya, Lihat Katalog, Ganti Kata Sandi and Keluar. There's no Panel Admin. |
| 2c | Log in as `admin` and open the header menu → Unggahan Saya | The same shell, plus a **Panel Admin** link to `/admin` |
| 2d | Phone width | The sidebar collapses to a top bar. The menu button opens it as a drawer, and choosing a link closes it. Nothing scrolls horizontally. The list shows cards instead of a table. |
| 2e | Ganti Kata Sandi / Keluar in the sidebar | The modal works as in Stage 1. Keluar goes to `/` logged out. |

### 5.3 Books: upload, edit, delete (as `guru_uji`)
| # | Step | Expected |
|---|---|---|
| 3a | Unggah Buku: pick the small PDF and a cover, fill Judul, set Jenis "Modul Ajar" and Kelas "XI", then Unggah | Network: `POST /api/books` (multipart: `file`, `cover`, `metadata` as `application/json`). The progress bar runs and the modal closes. Toast "Buku berhasil diunggah". The new book is first in the list with its cover. It opens at `/buku/{id}` with the right fields. |
| 3b | Upload the large file | The progress shows a percentage and MB; at 100% "Memproses…" until the response. Closing the modal mid-upload asks "Batalkan unggahan?". Yes → the request is cancelled (status "canceled" in Network), no row is added and no error toast appears. |
| 3c | Upload a `.docx` with visibility **Khusus Staf** | It saves. The row has a "Khusus Staf" badge. Logged out, the book doesn't appear in `/kategori`. |
| 3d | Edit the 3a book: change only Penulis, then Simpan | `PUT /api/books/{id}` body is `{ "title": …, "author": … }` and nothing else. Toast "Perubahan disimpan" and the row updates. |
| 3e | Edit: empty a filled field (for example Penerbit) | A warning under the field. Simpan is disabled until it's filled again or reset. |
| 3f | Edit with no changes | Simpan stays disabled |
| 3g | Edit: Ganti Berkas with another PDF, then Simpan | `PUT /api/books/{id}/file` with progress. The file name and size in the edit form and on `/buku/{id}` change, and the reader shows the new PDF. |
| 3h | Hapus on a book, then confirm | The modal shows the title and the note about restoring via the librarian. `DELETE` → `204`, the row disappears and a toast appears. `/buku/{id}` now shows "Buku tidak ditemukan". |
| 3i | Paging | With 11+ books, page 2 loads (`?page=2` in the URL). Deleting the only item on the last page steps back one page. |

### 5.4 Errors (books)
| # | Step | Expected |
|---|---|---|
| 4a | Submit without a file or without Judul | Inline errors on the picker and on Judul. No request is sent. |
| 4b | Pick a `.zip` | The picker refuses it: "Jenis berkas tidak didukung" |
| 4c | Upload the text file renamed to `.pdf` | The server answers `400 INVALID_FILE_TYPE`, and the message shows **on the file picker** |
| 4d | Upload the same PDF as in 3a again | `409 DUPLICATE_FILE` on the picker, with the note about the trash and the librarian |
| 4e | Tahun Terbit `99` or `20255` | An inline error (4 digits). No request is sent. |
| 4f | Stop the backend and try to upload | A form-level alert "Tidak dapat terhubung ke server perpustakaan…". The modal stays open with its values. |
| 4g | Start an upload, then press browser Back | A "leave the page?" confirmation. Staying keeps the upload going. |

### 5.5 Archive (as `guru_uji`)
| # | Step | Expected |
|---|---|---|
| 5a | Arsip Saya → Unggah Arsip with a photo, Tanggal Kegiatan set, Akses left at the default | Akses defaults to **Khusus Staf**. After upload, the row shows "Memproses thumbnail…". Within a few seconds the real thumbnail appears without a reload (Network: `GET /api/archive/{id}` polling, then `/thumbnail?v=…`). |
| 5b | Upload a short mp4 as Publik | The poster frame and duration appear after processing. Logged out, it plays on `/arsip`. |
| 5c | Edit: change Tanggal Kegiatan and Akses to Publik | `PUT /api/archive/{id}` with only `eventDate` and `visibility`. The row updates. |
| 5d | Lihat | `/arsip?item={id}` opens in a new tab with the lightbox |
| 5e | Hapus | The row is removed. `/arsip?item={id}` shows "Item arsip tidak ditemukan". |
| 5f | Upload an `.exe` renamed to `.jpg` | `INVALID_FILE_TYPE` on the picker |

### 5.6 Ownership and roles
| # | Step | Expected |
|---|---|---|
| 6a | As `guru_uji` | Buku Saya and Arsip Saya list only `guru_uji`'s uploads, none of the admin's |
| 6b | As `admin` | Lists only the admin's own uploads. Bulk-imported items (no uploader) aren't listed; they arrive in the Stage 5 admin screens. |
| 6c | As `guru_uji`, delete a book in one tab, then edit it in an older tab | Edit shows a toast (the detail load or `PUT` returns `404`) and the list reloads |
| 6d | Let the token expire during a session (or remove `elib_token` in DevTools, then Simpan) | Redirect to `/login?redirect=…&expired=1`, as in Stage 1 |

### 5.7 Sign-off
All of tests 1–6 pass → commit Stage 4 and set its status to **Done** in `frontend-plan.md`.
