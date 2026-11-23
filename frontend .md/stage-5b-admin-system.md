# Stage 5B: Librarian admin, part 2 (implementation plan and manual testing guide)

> **Status:** Done · **Roadmap:** [Stage 5](frontend-plan.md#stage-5-librarian-admin-admin-sidebar-in-stafflayout) · **Previous:** [Stage 5A](stage-5a-admin-content.md) · **Backend refs:** [spec §3.6](../.md/backend-spec.md), [phase 1 users](../.md/phase-1-auth.md), [phase 3 categories](../.md/phase-3-categories-books.md), [phase 7 storage](../.md/phase-7-storage-health.md), [frontend-guide §8](../frontend-guide.md)

## 1. Summary
This stage adds the last four librarian screens to the **Pengelolaan** sidebar group from 5A:
- **Pengguna** (`/admin/pengguna`): staff accounts. Create, edit the name and role, reset the password, deactivate or reactivate.
- **Kategori** (`/admin/kategori`): the book and archive category trees. Create, rename, move and delete.
- **Unggah Massal** (`/admin/impor`): pick many files in the browser. They become editable rows (title from the file name, shared metadata), then upload one by one through the normal upload endpoints, with a status per row. No CSV needed.
- **Penyimpanan** (`/admin/penyimpanan`): disk space against the upload threshold, and the space used by books, archive media, trash, thumbnails and temp files.

With 5B done, the whole roadmap in `frontend-plan.md` is complete.

**Done when:**
- `npm run build` passes.
- A librarian can create a teacher account that can log in, reset its password, and deactivate it (login then fails).
- The UI blocks self-lockout, and the backend's `CANNOT_DEACTIVATE_*` errors still show if reached.
- Categories can be added, renamed, moved and deleted in both trees. In-use, duplicate and invalid-parent errors show clearly. New categories appear in the upload forms and public filters.
- A librarian can select dozens of files, adjust titles and shared metadata, and upload them in one go. Each row ends as Berhasil, Dilewati (duplicate) or Gagal (with the reason), and one failure doesn't stop the batch.
- Storage shows real numbers, a clear "drive not connected" state, and a warning near the threshold.

---

## 2. Decisions

### 2.1 General
- **Routes:** four children added to the `/admin` parent (LIBRARIAN, `StaffLayout`): `pengguna` → `AdminPengguna`, `kategori` → `AdminKategori`, `impor` → `AdminImpor` (sidebar label **Unggah Massal**), `penyimpanan` → `AdminPenyimpanan`.
- **Sidebar:** the `Pengelolaan` group in `StaffLayout`'s `GROUPS` array gets these four links after Tempat Sampah.
- **API:** all in `src/api/admin.js`, as thin `request()` wrappers. These lists are plain arrays, not pages (frontend-guide §3), so there's no `useStaffList`. Each view keeps a simple `state` / `items` pair and uses `StateBlock`.
- **Reused components:** `BaseModal`, `BaseInput`, `BaseButton`, `BaseTable`, `ConfirmModal` (5A), `useToast` (5A), `selectClass` (`components/staff/formUtils.js`), `errorMessage` / `ERROR_MESSAGES` (`utils/errors.js`) and the `formatBytes` / `formatDate` / `formatDateTime` / `formatNumber` helpers (`utils/labels.js`).
- **Field errors:** `VALIDATION_FAILED` `fieldErrors` go under their inputs. Unknown keys go into a form-level alert, the same pattern as the Stage 4 forms.

### 2.2 Pengguna
- **List** from `GET /admin/users`, sorted active first, then by name.
  - Client-side search over name and username, and a role filter (Semua / Pustakawan / Guru). The list is small and unpaged.
  - **Columns:** Nama (with "(Anda)" on your own row), Nama pengguna (`@username`), Peran (badge), Status (Aktif / Nonaktif), Dibuat, Aksi.
- **Tambah Pengguna** modal (`POST /admin/users`):
  - Fields: Nama Lengkap*, Nama Pengguna* (3–50 characters, `a-z 0-9 . _ -`, lowercased), Kata Sandi* (6+, with a show/hide toggle and a **Buat Acak** button that fills a random 10-character password), and Peran (radio, default Guru).
  - `DUPLICATE_USERNAME` shows on the username field.
  - On success, a toast "Akun {username} dibuat", plus a note to hand the password over in person, since it isn't shown again.
- **Edit** modal (`PUT /admin/users/{id}`, only changed fields):
  - Fields: Nama Lengkap and Peran.
  - **For your own account, Peran is disabled** with the hint "Peran akun sendiri tidak dapat diubah". This prevents locking yourself out of the admin area. Only `CANNOT_DEACTIVATE_SELF` exists on the backend, so this guard is client-side.
- **Reset Kata Sandi** modal (`PUT` with `{ password }`):
  - New password (6+), Buat Acak, show/hide.
  - Success toast "Kata sandi {username} diperbarui".
  - For your own row the action is hidden, and the hint points to the sidebar's Ganti Kata Sandi, which checks the old password.
- **Nonaktifkan / Aktifkan** (`PUT` with `{ active }`):
  - Deactivating goes through `ConfirmModal`: "{name} tidak akan bisa masuk. Unggahan yang sudah ada tetap tersimpan."
  - Disabled on your own row.
  - `CANNOT_DEACTIVATE_SELF` and `CANNOT_DEACTIVATE_LAST_LIBRARIAN` show as error toasts, as frontend-guide §7.1 says.
  - Reactivating needs no confirmation.
- **There is no delete.** The backend has no user delete, so the page says so in a one-line note.

### 2.3 Kategori
- **Tabs** Buku / Arsip (`?type=archive` in the URL; Buku is the default), each loading `GET /categories?type=BOOK|ARCHIVE`.
- **Tree view:** nested rows (indent by depth), each with the name, a child count, and actions: **Tambah Sub**, **Ubah**, **Hapus**.
  - A **Tambah Kategori** button in the header adds a root category.
  - The categories endpoint has no item counts, so in-use is only known when a delete fails.
- **Create / Ubah** modal:
  - Name* (max 100).
  - **Induk** select ("— Kategori utama —" plus the tree). When editing, the category itself and its descendants are left out of the list, so the UI can't build a cycle. The server's `INVALID_PARENT` still shows if hit.
  - Create: `POST /admin/categories` `{ type, name, parentId? }`.
  - Edit: `PUT /admin/categories/{id}` `{ name, parentId }`, with **`?type=ARCHIVE` on the archive tab** (frontend-guide §8).
  - **Checked against the dev backend** (throwaway categories, since removed): `PUT` is a full update. `parentId: null` moves the category to the top level, so the form always sends the current parent. "— Kategori utama —" is always offered.
- **Hapus:**
  - `ConfirmModal`, then `DELETE /admin/categories/{id}` (`?type=ARCHIVE` on the archive tab).
  - **Categories with subcategories can be deleted.** The backend allows it (checked: `204`), and the subcategories become top-level categories. The confirmation says so: "{n} subkategorinya akan dipindahkan menjadi kategori utama".
  - `CATEGORY_IN_USE` → error toast "Pindahkan dulu item dari kategori ini" with a link to Semua Buku / Semua Arsip filtered by that category.
- **Errors:** `DUPLICATE_CATEGORY` shows on the name field; `INVALID_PARENT` on the Induk field.
- **After any change** the tree reloads. Pages that load categories (upload forms, public filters, admin filters) fetch them on mount, so they pick up the change.

### 2.4 Unggah Massal (browser bulk upload)
The backend's server-folder CSV import (`/api/admin/import`, phase 6) is **not used by the frontend** (your decision: too technical for the admin panel). It stays available on the backend for a technician who wants to migrate a large collection directly.

No backend change: every row is a normal `POST /api/books` or `POST /api/archive` (Stage 4's `createBook` / `createArchive` over XHR), so file checks, duplicate detection, the free-space guard and thumbnails behave exactly like a single upload.

**Choosing the target and defaults:**
- **Target toggle:** Buku / Arsip. Changing it clears the queue, after a confirmation if rows exist.
- **Shared defaults** (applied to every row):
  - **Buku:** Jenis, Kelas, Kategori, Kurikulum, Akses (default Publik).
  - **Arsip:** Kategori, Tanggal Kegiatan, Akses (default **Khusus Staf**, as for single uploads).
  - The options come from `getFilters()` and `listCategories()`, the same as the Stage 4 forms.
- **Adding files:** a multi-file drop zone plus "Pilih Berkas", using the target's accept list (`BOOK_FILE_ACCEPT` / `ARCHIVE_FILE_ACCEPT`) and the 2 GB limit per file.
  - Files that fail the check are listed under the drop zone as "ditolak: {name} ({reason})" and never become rows.
  - The queue is capped at **200 rows** per batch.
  - Picking the same file twice in one batch (same name, size and last-modified) is ignored.

**Rows:**
- **Columns:** No, Berkas (name · size), **Judul** (editable; filled from the file name: extension removed, `_` and `-` become spaces, extra spaces collapsed, max 255), and for books an optional **Penulis**. Then Status and a ✕ to remove a waiting row.
- **Other fields:** they come from the shared defaults. Edit them per item afterwards in Semua Buku / Semua Arsip.
- **Validation:** an empty title is marked inline, and Mulai Unggah is disabled until every waiting row has one.

**Uploading:**
- **Mulai Unggah** uploads rows **one at a time** (sequential keeps the Wi-Fi and the server's hashing load predictable).
  - Header: "Mengunggah 12 dari 40" with an overall bar and the current file's `UploadProgress`.
  - Each upload is tracked with `useUploadTask`, so leaving the page or closing the tab asks first (Stage 4 guard).

**Row status and results:**

| Result | Row shows |
|---|---|
| Waiting | Menunggu |
| Uploading | the current percentage, then "Memproses…" |
| `201` | **Berhasil**, with a link (`/buku/{id}` or `/arsip?item={id}`, new tab) |
| `409 DUPLICATE_FILE` | **Dilewati**, "sudah ada di perpustakaan (mungkin di tempat sampah)" |
| `INVALID_FILE_TYPE`, `VALIDATION_FAILED` and other per-file errors | **Gagal** with the Indonesian message. The batch continues. |
| `STORAGE_FULL`, `STORAGE_UNAVAILABLE`, network errors | The **batch stops** (the next files would fail the same way). The current row is Gagal, the rest stay Menunggu, and an alert explains why. |
| `401` | The batch stops, and the global handler sends you to login |

**Controls:**
- **Batalkan:** aborts the current XHR. That row becomes **Dibatalkan**, the rest stay Menunggu, and Mulai can resume.
- **When the batch ends:** a summary "{n} berhasil, {n} dilewati, {n} gagal", a toast, and links to Semua Buku / Semua Arsip.
- **Coba Lagi yang Gagal:** puts Gagal and Dibatalkan rows back to Menunggu.
- **Bersihkan yang Selesai:** removes Berhasil and Dilewati rows.

**Limits:**
- No covers in bulk, and a cover can't be added afterwards (the backend has no cover-replace endpoint). Books that need a cover go through the single Unggah Buku form. The page says this next to the Buku target.
- New archive photos and videos get their thumbnails processed by the server afterwards, as usual. The rows don't poll for them.

### 2.5 Penyimpanan
- `GET /admin/storage`, with a **Muat Ulang** button.
- **Drive connected** (`available: true`):
  - A disk bar of used / total bytes, with a marker at the reserve point (`total − minFreeBytes`).
  - Text: "{free} kosong dari {total}".
  - Status:
    - **OK:** free > 2 × `minFreeBytes`.
    - **Hampir penuh** (amber): free ≤ 2 × `minFreeBytes`.
    - **Penuh** (red): free < `minFreeBytes`. "Unggahan dan impor ditolak sampai ruang dikosongkan", with a link to Tempat Sampah.
- **Drive not connected** (`available: false`): a rose panel "Drive penyimpanan tidak terhubung", explaining that browsing still works but reading, downloading, uploading and importing fail until the drive and its `.library-storage` marker are back. The disk bar is hidden. The table below still shows the database counts.
- **Usage table:**

  | Row | Aktif (count · size) | Tempat sampah (count · size) |
  |---|---|---|
  | Buku | ✓ | ✓ |
  | Arsip: Foto, Video, Dokumen | ✓ | ✓ |
  | Thumbnail, Berkas sementara | size only | — |
  | **Total** | ✓ | ✓ |

  - A **Porsi ruang** column shows each row's share of the space the collection uses (active + trash) as a small teal bar with its percentage. This replaced the planned stacked bar: one bar per row stays a single series (no new hues) and is labelled in place.
  - When the trash takes space, a "Lihat Tempat Sampah" link goes to `/admin/sampah`.
- **Note:** book covers aren't counted separately (a known backend gap, phase 7).
- **Out of scope:** the `/api/health` banner (frontend-guide §9) stays a possible follow-up.

---

## 3. Design

### 3.1 API (`src/api/admin.js`, additions)
| Function | Endpoint |
|---|---|
| `listUsers()`, `createUser(body)`, `updateUser(id, patch)` | `GET/POST /admin/users`, `PUT /admin/users/{id}` |
| `createCategory({ type, name, parentId })`, `updateCategory(type, id, { name, parentId })`, `deleteCategory(type, id)` | `POST /admin/categories`; `PUT/DELETE /admin/categories/{id}`, adding `?type=ARCHIVE` when `type === 'ARCHIVE'` |
| `getStorage()` | `GET /admin/storage` |

### 3.2 Files
| File | Purpose |
|---|---|
| `src/views/admin/PenggunaView.vue` | §2.2. Table on `md+`, cards on phones. |
| `src/components/admin/UserFormModal.vue` | Create and edit (mode by `user` prop) |
| `src/components/admin/ResetPasswordModal.vue` | Password reset with Buat Acak |
| `src/components/admin/PasswordField.vue` | Input with show/hide and Buat Acak (`crypto.getRandomValues`, no ambiguous characters) |
| `src/views/admin/KategoriView.vue` | §2.3. Tabs and tree. |
| `src/components/admin/CategoryTreeRow.vue` | Recursive row: name, child count, actions |
| `src/components/admin/CategoryFormModal.vue` | Create and edit with the parent select (excludes self and descendants) |
| `src/views/admin/ImporView.vue` | §2.4. Target toggle, shared defaults, multi-file drop zone, rows table (cards on phones), sequential upload loop, summary. |
| `src/components/admin/BulkFileRow.vue` | One queued file: editable title (and author for books), status badge, link or error, remove button |
| `src/views/admin/PenyimpananView.vue` | §2.5. Disk bar, status, usage table and breakdown bar. |
| `src/utils/labels.js` | `titleFromFileName(name)` for the bulk rows. The error messages these screens need already exist in `utils/errors.js`. |

`components/admin/` is the folder 5A emptied. It now holds the 5B admin-only parts.

---

## 4. Changes

### 4.1 New files
`src/views/admin/PenggunaView.vue`, `KategoriView.vue`, `ImporView.vue`, `PenyimpananView.vue`; `src/components/admin/UserFormModal.vue`, `ResetPasswordModal.vue`, `PasswordField.vue`, `CategoryTreeRow.vue`, `CategoryFormModal.vue`, `BulkFileRow.vue`

> **As built:**
> - **Category cycles.** The backend rejects only a category as its own parent (`INVALID_PARENT`). It accepted putting a parent under its own child, which creates a cycle (checked on the dev backend, then cleaned up). The frontend prevents this by leaving the category and all its descendants out of the Induk list. It's worth fixing on the backend too.
> - **Unggah Massal** is `views/admin/ImporView.vue` plus `components/admin/BulkFileRow.vue`. Rows can still be edited after Gagal or Dibatalkan. Leaving the page while the queue runs asks first, and confirming cancels the current upload.
> - **Pengguna** uses small render-function components inside the view (role badge, status badge, row actions) instead of separate files.

### 4.2 Modified files
| File | Change |
|---|---|
| `src/api/admin.js` | §3.1 |
| `src/router/index.js` | Four `/admin` children |
| `src/layouts/StaffLayout.vue` | Four Pengelolaan links and icons (Pengguna, Kategori, Unggah Massal, Penyimpanan) |
| `src/utils/labels.js` | `titleFromFileName` |
| `frontend .md/frontend-plan.md` | 5B row: link, "In progress" when work starts, "Done" at sign-off |

---

## 5. Manual testing guide

### 5.1 Setup
| Item | Value |
|---|---|
| Backend | Dev profile on `http://localhost:8081`; `GET /api/health` → `UP` |
| Frontend | `npm run dev` → `http://localhost:5173` |
| Accounts | `admin` / `admin123`; `guru_uji` |
| Tools | DevTools Network tab (filter `api`), Console, a second private window for logging in as other users |

**Test data checklist:**

| Need | For tests |
|---|---|
| About 10 local files for **Unggah Massal**: several PDFs and a `.docx` for books, plus photos and one video for the archive, one file already in the library (duplicate), and one text file renamed to `.pdf` (wrong content). Names with `_` or `-`, such as `Modul_Fisika-Kelas-X.pdf`, show the title cleanup. | 4a–4h |
| A category that has a book in it (e.g. "Matematika") and one that's empty | 3e, 3f |
| Optional: unplug or rename the storage drive (phase 7 §6.2 steps) | 5c |

### 5.2 Navigation
| # | Step | Expected |
|---|---|---|
| 1a | `npm run build` | No errors |
| 1b | As `admin`, look at the sidebar | Pengelolaan lists Statistik, Semua Buku, Semua Arsip, Pengguna, Kategori, Unggah Massal, Penyimpanan, Tempat Sampah. Each opens inside the shell. |
| 1c | As `guru_uji`, open `/admin/pengguna` | Redirect to `/unggahan/buku` |

### 5.3 Pengguna
| # | Step | Expected |
|---|---|---|
| 2a | Open Pengguna | `GET /api/admin/users`. Rows for admin "(Anda)" and guru_uji, with role and status badges. Search and the role filter work without new requests. |
| 2b | Tambah Pengguna: name "Guru Coba", username `guru_coba`, Buat Acak, role Guru | `POST` → 201. A toast, and the row appears. In a private window, `guru_coba` can log in with that password and lands on `/unggahan`. |
| 2c | Tambah again with `guru_coba` | "Nama pengguna sudah dipakai." on the username field |
| 2d | Username `ab`, or password `123` | Inline errors (3–50 characters / min 6). No request is sent. |
| 2e | Edit guru_coba: change the name, then the role to Pustakawan, then back | `PUT` contains only the changed field each time. The badge updates. As Pustakawan, guru_coba's next login lands on `/admin`. |
| 2f | Reset Kata Sandi for guru_coba | `PUT {password}` → 200 and a toast. The old password now fails, and the new one works. |
| 2g | Nonaktifkan guru_coba | A confirmation, then status Nonaktif. Logging in as guru_coba gives "Nama pengguna atau kata sandi salah." Aktifkan → they can log in again. |
| 2h | Your own row (admin) | Nonaktifkan and the Peran field are disabled. Reset Kata Sandi is hidden and points to Ganti Kata Sandi. |
| 2i | Optional: make guru_coba a librarian, log in as guru_coba, and deactivate admin. | Deactivating admin works, because guru_coba is now another active librarian. Reactivate admin afterwards. The last-librarian case can't be reached from the UI, because your own row is locked. |

### 5.4 Kategori
| # | Step | Expected |
|---|---|---|
| 3a | Open Kategori | The Buku tab shows the book tree. Arsip (`?type=archive`) shows Dokumen Resmi, Kegiatan Sekolah, Lainnya, Prestasi. |
| 3b | Tambah Kategori "Kategori Uji" (Buku) | `POST {type: BOOK, name}` → 201, and it appears in the tree. It also shows in the upload form's Kategori select and on `/kategori`. |
| 3c | Tambah Sub under "Kategori Uji": "Sub Uji" | Appears indented, and the parent shows "1 subkategori". |
| 3d | Ubah "Sub Uji": rename it, then move it to another parent | `PUT /api/admin/categories/{id}` with `{name, parentId}`. The tree updates. The Induk list never offers the category itself or its children. |
| 3e | Hapus a category that has books (e.g. Matematika) | Confirm, then `409 CATEGORY_IN_USE` → error toast with a link to Semua Buku filtered by that category |
| 3f | Hapus "Kategori Uji" while "Sub Uji" is still under it | The confirmation says 1 subkategori will become a top-level category. After `204`, "Sub Uji" shows at the top level. Then delete "Sub Uji" too. |
| 3g | Tambah "Prestasi" on the Arsip tab | "Nama kategori sudah ada." on the name field |
| 3h | Arsip tab: create, rename and delete a test category | `PUT` and `DELETE` URLs end with `?type=ARCHIVE`, and all succeed |

### 5.5 Unggah Massal
| # | Step | Expected |
|---|---|---|
| 4a | Open Unggah Massal | `/admin/impor`, and the target is Buku. The defaults panel shows Jenis, Kelas, Kategori, Kurikulum and Akses (Publik). The no-cover note is visible. |
| 4b | Drop 6 book files, including `Modul_Fisika-Kelas-X.pdf`, a `.jpg` and the same PDF twice | 5 rows. The `.jpg` is listed as rejected, and the second copy is ignored. The title reads "Modul Fisika Kelas X". |
| 4c | Empty one title | That row is marked and Mulai Unggah is disabled. Fill it in again. |
| 4d | Set defaults (e.g. Jenis Modul Ajar, Kelas XI, a category), then Mulai Unggah | One `POST /api/books` at a time, each with `metadata` carrying the row's title and the shared defaults. "Mengunggah n dari 5" and the current file's progress. Rows turn Berhasil with links. |
| 4e | The batch includes the duplicate file and the renamed text file | Duplicate → **Dilewati**. Renamed file → **Gagal** "Jenis berkas tidak didukung." The other rows still finish. The summary counts match. |
| 4f | Start another batch and click Batalkan mid-file | The current request is cancelled and that row becomes Dibatalkan; the rest stay Menunggu. Mulai continues from there. Coba Lagi yang Gagal requeues Gagal and Dibatalkan rows. |
| 4g | During an upload, click another sidebar link | The leave-page confirmation. Staying keeps the batch going. |
| 4h | Switch the target to Arsip, then drop photos and a video with Akses left at the default | The confirmation clears the queue. Akses defaults to Khusus Staf, and Tanggal Kegiatan is applied to all rows. After upload, the items are in Semua Arsip with thumbnails (they appear a few seconds later, as usual). |
| 4i | Stop the backend mid-batch | The current row is Gagal, the batch stops with an alert, and the remaining rows stay Menunggu |

### 5.6 Penyimpanan
| # | Step | Expected |
|---|---|---|
| 5a | Open Penyimpanan | `GET /api/admin/storage`. The disk bar shows the used share and the reserve marker, with "{free} kosong dari {total}". The table matches the JSON (Buku and Foto/Video/Dokumen, active and trash, thumbnails, temp, totals). |
| 5b | Delete an item, then Muat Ulang | Its size moves from Aktif to Tempat sampah. The "Lihat Tempat Sampah" link works. |
| 5c | Optional: unplug or rename the storage folder per phase 7 and reload | "Drive penyimpanan tidak terhubung" panel, no disk bar, and the table still shows counts. Restore the drive afterwards. |
| 5d | Phone width | The bar, status and table fit (the table scrolls inside its card only) |

### 5.7 Sign-off
All of tests 1–5 pass → commit Stage 5B and set its status to **Done** in `frontend-plan.md`. The frontend roadmap is then complete.
