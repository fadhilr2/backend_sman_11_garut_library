# Plan: Align the E-Library frontend with the backend API

## Context
The Vue 3 frontend was built on mock data, before the backend existed. The backend is now finished: all 9 phases in `.md/backend-phases.md` are done (phase 9, the trash, was added during Stage 5A). Its contract is in `.md/backend-spec.md` and `frontend-guide.md`, and it differs from the frontend in several ways:
- The frontend has visitor attendance (presensi) and visitor statistics, which the backend doesn't support.
- The frontend has no archive, upload, users, categories, import or storage features.
- Login is simulated, and the roles don't match the backend's `TEACHER` and `LIBRARIAN`.

Decisions the user has already made:
- Remove presensi.
- The stats page becomes librarian-only and shows `GET /api/admin/stats`.
- Login is for staff only, and a wrong password is handled as `400`.
- `LIBRARIAN` = admin.
- Add a teacher page, an archive page and the admin pages.
- Uploads live in the staff/admin pages.
- Filters come from the API.
- Add the Vite dev proxy.
- Remove all mock data and delete the dead files.
- Admin uses a sidebar with one route per screen.
- Stats become a single page.
- Archive is public at `/arsip`.

## Status
Work is done one stage at a time, with a commit after each stage. **All stages are done**: the frontend now covers the whole backend contract, apart from the follow-ups listed under "Out of scope". Each stage doc has an "As built" note where the build differs from its plan.

| Stage | Scope | Status | Doc |
|---|---|---|---|
| 1 | Foundation: proxy, API client, auth store, router guards, header, login, removals | Done | [stage-1-foundation.md](stage-1-foundation.md) |
| 2 | Public book pages: catalog, search, detail and reader | Done | [stage-2-public-books.md](stage-2-public-books.md) |
| 3 | Archive page | Done | [stage-3-archive.md](stage-3-archive.md) |
| 4 | Staff uploads (`/unggahan`) | Done | [stage-4-staff-uploads.md](stage-4-staff-uploads.md) |
| 5A | Librarian admin, part 1: staff shell for librarians, Statistik, Semua Buku, Semua Arsip, Tempat Sampah | Done | [stage-5a-admin-content.md](stage-5a-admin-content.md) |
| 5B | Librarian admin, part 2: Pengguna, Kategori, Unggah Massal, Penyimpanan | Done | [stage-5b-admin-system.md](stage-5b-admin-system.md) |

## Target routes
| Path | Access | View |
|---|---|---|
| `/` | public | `KatalogView`: newest and popular books, category and grade pills |
| `/kategori` (alias `/search`) | public | `CariKategoriView`: search with filters, paged |
| `/buku/:id` | public | `DetailBukuView`: metadata, reader, download, related books |
| `/arsip` | public | `ArsipView` (new): thumbnail grid, filters, lightbox |
| `/login` | guests only | `LoginView`: one staff form, redirects by role |
| `/unggahan` → `/unggahan/buku`, `/unggahan/arsip` | TEACHER, LIBRARIAN | `staff/BukuSayaView`, `staff/ArsipSayaView`: my books and archive items, with upload, edit and delete |
| `/admin` → `/admin/statistik` (also `/admin/dashboard`) | LIBRARIAN | `admin/StatistikView` |
| `/admin/buku`, `/admin/arsip` | LIBRARIAN | `admin/BukuAdminView`, `admin/ArsipAdminView`: all items, upload, edit, delete with Urungkan |
| `/admin/pengguna`, `/admin/kategori` | LIBRARIAN | `admin/PenggunaView`, `admin/KategoriView` |
| `/admin/impor` | LIBRARIAN | `admin/ImporView` (sidebar "Unggah Massal"): browser bulk upload |
| `/admin/penyimpanan`, `/admin/sampah` | LIBRARIAN | `admin/PenyimpananView`, `admin/SampahView` (Tempat Sampah) |

`/unggahan` and `/admin/*` share `layouts/StaffLayout.vue` (`meta.hideNavbar`, no public header or footer). Its sidebar has the "Unggahan Saya" group for all staff and the librarian-only "Pengelolaan" group: Statistik, Semua Buku, Semua Arsip, Pengguna, Kategori, Unggah Massal, Penyimpanan, Tempat Sampah.

## Stage 1: Foundation
> **Scope as built:** see [stage-1-foundation.md](stage-1-foundation.md). Stage 1 only covers auth. `upload.js`, `media.js`, the `books`/`archive`/`categories`/`meta`/`admin` endpoint modules and the enum and format helpers in `labels.js` are added in the stage that first uses them (2–4). Error messages live in `src/utils/errors.js`. `AdminDashboardView.vue` stayed, guarded and with real logout, until Stage 5A replaced it and deleted it.

- **`vite.config.js`**: add `server.proxy: { '/api': { target: 'http://localhost:8081', changeOrigin: true } }`.
- **`src/api/client.js`**: a `request(path, { method, query, body, form })` wrapper around `fetch`.
  - Adds `Authorization: Bearer` whenever a token exists, on public calls too.
  - Serializes `query` and skips empty values.
  - Reads error bodies with `res.json()` inside a try/catch. Non-JSON bodies fall back by status (`413`, `502`, `504`).
  - Throws an `ApiError { status, code, message, fieldErrors }`.
  - On a `401` from any call other than login: log out, then redirect to `/login?redirect=…`.
- **`src/api/upload.js`**: an XHR multipart upload with an `onProgress` callback. The `metadata` part is a `Blob` of type `application/json`, and `Content-Type` is never set by hand.
- **`src/api/media.js`**: a `useMediaUrl(urlRef, visibilityRef)` composable. `PUBLIC` items, or a logged-out viewer, use the plain URL. `STAFF` items get a blob URL fetched with the auth header, which is revoked on unmount. There is also a `downloadAuthed(url, name)` helper.
- **Endpoint modules** (thin wrappers over `client`): `src/api/auth.js`, `books.js`, `archive.js`, `categories.js`, `meta.js`, `admin.js`.
- **`src/stores/auth.js`**: a module-level `reactive` store, since the project doesn't use Pinia.
  - State is `{ token, user, ready }`, saved to `localStorage`.
  - `init()` checks `exp` first, then calls `/auth/me`. `login()` and `logout()` handle the session.
  - A timer logs the user out when the JWT `exp` passes.
  - Getters: `isStaff`, `isLibrarian`, `canEdit(item)` (`LIBRARIAN`, or `uploadedById === user.id`).
- **`src/utils/labels.js`**:
  - Indonesian labels for the enums (`TEACHING_MODULE` → "Modul Ajar", and so on).
  - Error `code` → Indonesian message, following frontend-guide §7.1.
  - `formatBytes`, `formatDate`, `formatDuration`.
  - `INLINE_MIME_TYPES` (only `application/pdf` for now) and `canPreview(mime)`. This one list gets extended when the backend adds inline support for more types.
- **`src/router/index.js`**:
  - New route table with `meta.requiresAuth`, `meta.roles` and `meta.guestOnly`.
  - A `beforeEach` that awaits `auth.ready`, then sends guests to `/login?redirect=` and the wrong role to `/`.
  - Remove the unused imports.
- **`src/main.js`**: call `auth.init()` before `mount`.
- **`HeaderNavbar.vue`**:
  - Links: Katalog, Cari & Filter, Arsip. Remove Presensi, Statistik and the "Presensi Siswa" button.
  - Logged out: a "Masuk Staf" button.
  - Logged in: a menu with the user's name, Unggahan Saya, Panel Admin (librarian only), Ganti Kata Sandi and Keluar.
- **`components/ChangePasswordModal.vue`** (new):
  - Calls `POST /auth/change-password`.
  - Shows `INVALID_OLD_PASSWORD` and `SAME_PASSWORD` on the form.
  - Requires a new password of at least 6 characters.
- **`LoginView.vue`**:
  - Drop the admin/user toggle and the simulated delay.
  - Call `auth.login()`.
  - On `400` with `INVALID_CREDENTIALS`, show "Nama pengguna atau kata sandi salah". On `ACCOUNT_TEMPORARILY_LOCKED`, show the server's message.
  - Redirect to `?redirect`, or by role: `LIBRARIAN` → `/admin`, `TEACHER` → `/unggahan`.
  - Lower the password minimum to 6 characters, matching the backend.
- **Delete**:
  - `views/PresensiPage.vue`, `components/FormPresensi.vue`, `components/PengunjungTerkiniSidebar.vue`
  - all 6 files in `views/statistik/`
  - `views/BerandaPage.vue`, `views/KategoriPage.vue`, `views/buku/DetailBukuPage.vue`, `views/admin/AdminDashboardPage.vue`
  - `views/admin/AdminDashboardView.vue`, once its pieces have moved into the stage 5 admin views
- **Keep and reuse** `components/ui/*`: `BaseModal`, `BaseInput`, `BaseButton`, `BaseTable`, `BaseBadge`, `StatCard`. The admin pages will use them.

## Stage 2: Public book pages
Each page keeps its current visual design. Only the mock data changes, to backend fields from `BookSummary` and `BookDetail` in frontend-guide §4. UI parts the backend has no data for are removed. Each page gets loading, empty and error states.

- **Shared components**:
  - `components/BookCard.vue`: pulled out of the card markup in `KatalogView` and `CariKategoriView`, keeping the hover animation. The cover comes from `useMediaUrl('/api/books/{id}/cover', visibility)`.
  - `components/Pagination.vue`: page numbers start at 0 in the API and are shown starting at 1.
- **`KatalogView`**:
  - Two sections: newest and most popular, each a `GET /books` with `size=8`.
  - Grade pills come from `meta.gradeLevels`. Category pills are the root categories from `GET /categories`.
- **`CariKategoriView`**:
  - `q` is debounced by 300 ms.
  - Filters: a category tree with one selection (radio, since the API takes a single `categoryId`), grade and type from `/meta/filters`.
  - Sort: `newest`, `title`, `popular`. Page size 12.
  - All state syncs to the URL query, so `/kategori?cat=` deep links still work.
  - Remove: DDC, mapel, availability, popular tags, barcode scan.
- **`DetailBukuView`**:
  - Uses `GET /books/{id}`. Shows title, author, publisher, year, type, grade, curriculum, category, description, file name, size and type, uploader and download count.
  - Reader: when `canPreview(mimeType)` is true, an `<iframe>` on `/file`, or a blob URL for `STAFF` books. Otherwise a "can't be previewed" panel.
  - The Unduh button is always shown. It's a plain link for `PUBLIC` books and `downloadAuthed` for `STAFF` books.
  - Related books come from `GET /books?categoryId=…&size=4`, excluding the current book.
  - A `404` shows a not-found state.
  - Remove: ISBN, table of contents, the fake page and zoom controls, pinjam cetak.

## Stage 3: Archive page (`views/ArsipView.vue`)
- Uses `GET /archive` with `q`, `categoryId` (from `/categories?type=ARCHIVE`), `mediaType`, `from`, `to` and `sort` (including `date`), all synced to the URL query.
- The grid uses `/archive/{id}/thumbnail`, with a duration badge from `durationSeconds` and an aspect ratio from `width` and `height`.
- `components/ArchiveLightbox.vue`:
  - Images: `<img>` on `/file`.
  - Videos: `<video controls>` on `/file`. On its `error` event, show "Tidak bisa diputar di browser" plus a download button. `STAFF` videos are download-only.
  - Documents: same rule as books, `canPreview` decides between iframe and download.

## Stage 4: Staff uploads (`/unggahan`)
- **`layouts/StaffLayout.vue`**: sidebar plus content area, in the same style as the current admin view.
- **`components/staff/BookForm.vue`**: upload and edit for books, adapted from `components/admin/EditBukuModal.vue` and the upload form in `AdminDashboardView.vue`.
  - Fields: title, author, publisher, year, type, grade, category, curriculum, visibility, description.
  - `accept=".pdf,.epub,.docx,.pptx,.xlsx"`, a check against the 2 GB size limit, and a progress bar that switches to "Memproses…" at 100%.
  - Edits send `PUT` with only the changed fields, and always include `title`.
  - A replace-file option uses `PUT /books/{id}/file`.
  - Errors: `fieldErrors` appear under their inputs. `DUPLICATE_FILE` and `INVALID_FILE_TYPE` appear on the file picker.
- **`components/staff/ArchiveForm.vue`**: the same pattern for archive items (title, description, category, event date, visibility defaulting to `STAFF`). After an upload it reloads after a few seconds, adding a `?v=` cache buster to the thumbnail URL.
- **`views/staff/UnggahanSayaView.vue`**: Buku and Arsip tabs on `/books/mine` and `/archive/mine` (paged), with upload, edit, and delete through the existing `components/admin/DeleteBukuModal.vue`, generalized for both item types.

## Stage 5: Librarian admin (`/admin/*`, sidebar in `StaffLayout`)
- **`StatistikView`**:
  - `StatCard`s: total books, total archive items, total downloads, downloads today, downloads this week.
  - A 7-day line chart of `dailyDownloads`, and bar charts of `booksByType` and `archiveByMediaType`, using vue-chartjs. Reuse the Chart.js registration from the current `statistik/*View.vue` files before deleting them.
  - A table of `topBooks`.
- **`BukuAdminView` and `ArsipAdminView`**:
  - All items through `GET /books` or `/archive`. The librarian's token makes `STAFF` items appear.
  - Search, filters, edit, delete and upload, reusing the stage 4 forms.
  - Restore: after a delete, show a toast with an "Urungkan" button that calls `/admin/{books|archive}/{id}/restore`.
- **`SampahView`** (`/admin/sampah`, added after backend phase 9): the trash lists (`/admin/{books|archive}/trash`) with Pulihkan, Hapus Permanen and Kosongkan. See [stage-5a-admin-content.md](stage-5a-admin-content.md).
- **`PenggunaView`**:
  - A table from `GET /admin/users`.
  - Create: username 3–50 characters, password 6+, full name, role defaulting to `TEACHER`.
  - Edit sends only the changed fields: full name, password reset, role, active.
  - Deactivate with `{active:false}`. Errors: `CANNOT_DEACTIVATE_SELF`, `CANNOT_DEACTIVATE_LAST_LIBRARIAN`, `DUPLICATE_USERNAME`.
- **`KategoriView`**:
  - A BOOK/ARCHIVE toggle and the tree, with create, rename and delete.
  - Archive categories add `?type=ARCHIVE` to `PUT` and `DELETE`.
  - Handles `CATEGORY_IN_USE`, `DUPLICATE_CATEGORY` and `INVALID_PARENT`.
- **`ImporView`** (sidebar "Unggah Massal"): browser bulk upload. Many files become editable rows with shared metadata and upload one by one through `POST /books` or `/archive`. The backend's server-folder CSV import (`/admin/import`) is intentionally not used in the admin panel. See [stage-5b-admin-system.md](stage-5b-admin-system.md).
- **`PenyimpananView`**:
  - A disk bar of free space against `minFreeBytes`. When `available` is false, it shows "Drive tidak terhubung" instead.
  - A table of book and archive buckets by media type, with active and trash counts and sizes, plus thumbnail and temp sizes.

## Out of scope (possible follow-ups)
- The health polling banner (frontend-guide §9).
- The backend's server-folder CSV import (`/admin/import`, phase 6). Left out of the admin panel on purpose (Stage 5B); Unggah Massal covers the same need from the browser.
- **Backend:** reject category cycles. `PUT /admin/categories/{id}` only refuses a category as its own parent, and accepts a parent placed under its own child. The admin UI already prevents it (Stage 5B).
- **Backend:** there's no endpoint to replace a book cover or an archive file, so the edit forms say these can't be changed.
- ~~An endpoint that lists trashed items.~~ Delivered by backend phase 9 and used by Tempat Sampah (Stage 5A).

## Verification
- `npm run build` passes after each stage, with no leftover imports of deleted files (checked with Grep for `Presensi`, `statistik/`, `BerandaPage` and similar).
- Start the backend on `:8081` (dev profile), then run `npm run dev`:
  1. **Anonymous:** catalog, search, filters and the archive load. No `STAFF` items appear. A `PUBLIC` PDF opens in the reader and downloads. `/admin` and `/unggahan` redirect to `/login`.
  2. **Login as `admin` / `admin123`:**
     - A wrong password shows the error. 5 wrong passwords show the lockout message.
     - A successful login lands on `/admin/statistik` with real numbers.
     - Create a `TEACHER` user, create categories, and upload a book (with a cover) and a video (the thumbnail appears without a reload).
     - Upload the same file again → duplicate error. Delete, then undo → item restored; delete again and purge it in Tempat Sampah. Upload a batch in Unggah Massal. Storage shows data.
  3. **Login as the teacher:**
     - Lands on `/unggahan`, and `/admin` redirects away.
     - Can upload and edit their own items.
     - `STAFF` items now appear in the catalog, with covers loaded through blob URLs.
     - Change their password.
  4. **Session expiry:** set a token in `localStorage` with a past `exp` and reload → logged out.
- Commit after each stage.
