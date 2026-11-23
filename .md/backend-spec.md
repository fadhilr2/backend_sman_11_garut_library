# Backend Spec: Perpustakaan Digital Lokal SMAN 11 Garut

> The API contract for the front-end. **What** the backend does, not how. Design lives in [backend-architecture.md](backend-architecture.md); schedule in [backend-phases.md](backend-phases.md).

## 1. Overview

- **Users**: students are anonymous (no accounts). Only `TEACHER` and `LIBRARIAN` log in.
- **Publishing**: uploads are published immediately (no approval step).
- **Content areas** (share the same storage, auth and category mechanism):
  - **Books**: ebooks, teaching modules, question banks, slides. The core of the proposal.
  - **Archive**: school images, videos and documents. Extra scope built on the same pieces.
- **Network**: offline school LAN. Nginx serves the front-end and proxies `/api` to Spring Boot.

---

## 2. Roles & access

| Action | Anonymous (student) | TEACHER | LIBRARIAN |
|---|---|---|---|
| Browse/read/download `PUBLIC` books and archive items | yes | yes | yes |
| View `STAFF` items | no (404) | yes | yes |
| Upload book / archive item | no | yes | yes |
| Edit/delete book / archive item | no | own only | any |
| Manage categories, users, import, stats, restore | no | no | yes |

- **Login**: `POST /api/auth/login` returns a JWT valid for 8 hours. Send it as `Authorization: Bearer <token>`.
- **Passwords**: BCrypt. Repeated failed logins are temporarily locked (`ACCOUNT_TEMPORARILY_LOCKED`).
- **Hidden, not forbidden**: a `STAFF` item requested anonymously returns `404`, so its existence is not revealed.
- **No uploader**: bulk-imported items have no uploader; only librarians can edit them.

---

## 3. Endpoints

`Phase` = the roadmap phase that delivers it. All paths are under `/api`.

### 3.1 Public: Books
| Method | Path | Notes | Phase |
|---|---|---|---|
| GET | `/books` | Query: `q`, `categoryId`, `grade`, `type`, `page`, `size`, `sort` (`newest`, `title`, `popular`). Only `PUBLIC` unless the caller is staff. | 3 |
| GET | `/books/{id}` | Metadata. | 3 |
| GET | `/books/{id}/file` | Inline stream (in-browser reader). Supports HTTP `Range`. | 3 |
| GET | `/books/{id}/download` | Same file as attachment. Logs the download and increments `downloadCount` (async). | 3, 5 |
| GET | `/books/{id}/cover` | Cover image (`image/jpeg` or `image/png`), or an SVG placeholder. A `STAFF` book's cover is `404` for anonymous callers. | 3, 8 |

### 3.2 Public: Archive
| Method | Path | Notes | Phase |
|---|---|---|---|
| GET | `/archive` | Query: `q`, `categoryId`, `mediaType` (`IMAGE`/`VIDEO`/`DOCUMENT`), `from`, `to` (event date), `page`, `size`, `sort` (`newest`, `title`, `popular`, `date` = event date). | 4 |
| GET | `/archive/{id}` | Metadata incl. width, height, duration, event date. | 4 |
| GET | `/archive/{id}/file` | Inline stream. **Range support is required** so videos can play and seek. | 4 |
| GET | `/archive/{id}/download` | Attachment. Logged and increments `viewCount` (async). | 4, 5 |
| GET | `/archive/{id}/thumbnail` | Generated thumbnail (image or video poster frame), or an SVG placeholder per media type. A `STAFF` item's thumbnail is `404` for anonymous callers. | 4, 8 |

### 3.3 Public: Shared
| Method | Path | Notes | Phase |
|---|---|---|---|
| GET | `/categories?type=BOOK\|ARCHIVE` | Category list (tree via `parentId` / `children`). Default `BOOK`. | 3 |
| GET | `/meta/filters` | Grade levels, book types, media types, curricula for filter dropdowns. | 3 |
| GET | `/health` | `{status, database, storage}`. `status`: `UP`, `DEGRADED` (DB up, storage `UNAVAILABLE` or `LOW_SPACE`) → `200`; `DOWN` (DB unreachable) → `503`. No byte counts. | 7 |

### 3.4 Auth
| Method | Path | Notes | Phase |
|---|---|---|---|
| POST | `/auth/login` | `{username, password}` → `{token, user}` | 1 |
| GET | `/auth/me` | Current user. | 1 |
| POST | `/auth/change-password` | `{oldPassword, newPassword}` (min 6 chars). | 1 |

### 3.5 Teacher + Librarian
| Method | Path | Notes | Phase |
|---|---|---|---|
| POST | `/books` | `multipart/form-data`: `file`, optional `cover`, `metadata` (JSON). 409 on duplicate content. | 3 |
| PUT | `/books/{id}` | Update metadata (own, or any for librarian). | 3 |
| PUT | `/books/{id}/file` | Replace the file. | 3 |
| DELETE | `/books/{id}` | Soft delete. | 3 |
| GET | `/books/mine` | Uploads by the current user. | 3 |
| POST | `/archive` | `multipart/form-data`: `file`, `metadata` (JSON: `title`, `description`, `categoryId`, `eventDate`, `visibility`). `mediaType` is detected from the file. | 4 |
| PUT | `/archive/{id}` | Update metadata. | 4 |
| DELETE | `/archive/{id}` | Soft delete. | 4 |
| GET | `/archive/mine` | Uploads by the current user. | 4 |

### 3.6 Librarian only
| Method | Path | Notes | Phase |
|---|---|---|---|
| GET/POST/PUT | `/admin/users[/{id}]` | Create teachers, reset password, deactivate. | 1 |
| POST/PUT/DELETE | `/admin/categories[/{id}]` | Category CRUD (`type`, `name`, `parentId`). Delete is blocked while the category is in use. | 3 |
| POST | `/admin/books/{id}/restore`, `/admin/archive/{id}/restore` | Undo soft delete. | 3, 4 |
| GET | `/admin/books/trash`, `/admin/archive/trash` | Trash list, most recently deleted first, paged (`page`, `size`, default 20). Items: `{id, title, kind, originalName, mimeType, fileSize, uploadedByName, deletedAt, purgeAt}`. `kind` is the book type or archive media type; `uploadedByName` is `null` for bulk imports; `purgeAt` is `null` when automatic purging is off. See §5.6. | 9 |
| DELETE | `/admin/books/trash/{id}`, `/admin/archive/trash/{id}` | Permanently delete one item in the trash (row, file, cover or thumbnail, download logs). `204`; `404` if the item isn't in the trash. | 9 |
| DELETE | `/admin/books/trash`, `/admin/archive/trash` | Empty that trash. `200 {"purged": n}`. | 9 |
| GET | `/admin/stats` | Totals, downloads today/this week, 7-day trend, top books, counts by type/media type. | 5 |
| POST | `/admin/import` | Body `{"target": "BOOK"\|"ARCHIVE"}`. Starts a bulk import from the server folder (see §5.5). `202 Accepted` + job. | 6 |
| GET | `/admin/import/{jobId}` | Job progress and per-row error report. | 6 |
| GET | `/admin/import` | 20 most recent import jobs. | 6 |
| GET | `/admin/storage` | Disk total/used/free, plus count and bytes for books and archive `IMAGE`/`VIDEO`/`DOCUMENT` (active and trash), thumbnails and temp files. Disk fields are `null` while the drive is unavailable. | 7 |

---

## 4. Errors

Every error uses the same body:
```json
{ "status": 409, "code": "DUPLICATE_FILE", "message": "A book with the same file content already exists (it may be in the trash)" }
```
Validation errors add a `fieldErrors` object (`{"title": "Title is required"}`). Errors raised by Spring while reading the request (missing multipart part, unreadable JSON, wrong method) use the HTTP status name as `code` (e.g. `BAD_REQUEST`) and Spring's reason as `message` (e.g. `Required part 'file' is not present.`).

The generated OpenAPI document (dev: `/swagger-ui.html`, `/v3/api-docs`) lists every endpoint below with these error responses; a test keeps it equal to this section.

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Request body fails Bean Validation. |
| 400 | `INVALID_CREDENTIALS` | Wrong username/password, or inactive account. |
| 400 | `ACCOUNT_TEMPORARILY_LOCKED` | Too many failed logins. |
| 400 | `INVALID_OLD_PASSWORD`, `SAME_PASSWORD` | Change password. |
| 400 | `CANNOT_DEACTIVATE_SELF`, `CANNOT_DEACTIVATE_LAST_LIBRARIAN` | User admin. |
| 400 | `FILE_REQUIRED` | Upload without a file. |
| 400 | `INVALID_FILE_TYPE` | Type not allowed, or extension does not match content. |
| 400 | `INVALID_PATH` | Stored path escapes the storage root. |
| 400 | `STORAGE_FAILED` | Upload stream could not be read. |
| 400 | `INVALID_PARENT` | Category set as its own parent. |
| 400 | `IMPORT_CSV_NOT_FOUND`, `IMPORT_DIR_NOT_CONFIGURED` | Bulk import. |
| 401 | `UNAUTHORIZED` | Protected endpoint called without a valid token. |
| 403 | `FORBIDDEN` | Wrong role, or editing someone else's item. |
| 404 | `NOT_FOUND` | Missing, soft-deleted, or `STAFF` item requested anonymously. |
| 409 | `DUPLICATE_FILE` | Same file content already stored (including items in the trash). |
| 409 | `DUPLICATE_USERNAME`, `DUPLICATE_CATEGORY` | Unique name taken. |
| 409 | `CATEGORY_IN_USE` | Deleting a category that still has books. |
| 409 | `IMPORT_ALREADY_RUNNING` | Another import job is RUNNING. |
| 500 | `STORAGE_WRITE_FAILED`, `INTERNAL_ERROR` | Server-side failure. |
| 503 | `STORAGE_FULL` | Free disk space under `app.storage.min-free-space`. |
| 503 | `STORAGE_UNAVAILABLE` | Storage drive not mounted (no `.library-storage` marker) or not writable. File, upload, import and permanent-delete requests only; browsing keeps working. |
| 503 | `IMPORT_QUEUE_FULL` | Import executor queue full. |

---

## 5. Business rules

### 5.1 Visibility and defaults
- `visibility`: `PUBLIC` (anyone on the Wi-Fi) or `STAFF` (logged-in staff only).
- Books default to `PUBLIC`, `EBOOK`, grade `ALL`. Archive items default to `STAFF`.
- Enums: book `type` = `EBOOK`, `TEACHING_MODULE`, `QUESTION_BANK`, `SLIDES`, `OTHER`; `grade` = `X`, `XI`, `XII`, `ALL`; `mediaType` = `IMAGE`, `VIDEO`, `DOCUMENT`.

### 5.2 Storage and duplicates
- Files are stored as `<root>/{books|archive|thumbnails}/<yyyy>/<MM>/<uuid>.<ext>` (book covers go under `books/`). User-supplied names are never used on disk; the original name is kept in the DB for downloads.
- Uploads are streamed to disk (never fully in memory), hashed with SHA-256, and atomically moved into place.
- Same SHA-256 as an existing item (active **or** soft-deleted) → `409 DUPLICATE_FILE`.
- Uploads are rejected with `503 STORAGE_FULL` when free space is under the threshold (default 5 GB).

### 5.3 Allowed file types
- **Books**: pdf, epub, docx, pptx, xlsx. **Covers**: jpg, png.
- **Archive**: images jpg, png, webp, gif; videos mp4 (H.264/AAC), webm, mkv; documents as books.
- Type is sniffed from content (Apache Tika). Executables and extension/content mismatches are rejected.

### 5.4 Streaming, thumbnails, search
- **Range**: single range, `206 Partial Content`, `Accept-Ranges`, `ETag`, `Last-Modified`; a range end past the file end is clamped to the last byte; a start past the end → `416`. No transcoding: videos the browser can't decode are still downloadable.
- **Downloads**: `Content-Disposition: attachment` with the original name encoded per RFC 6266 / RFC 5987 (`filename*=UTF-8''...`), so non-ASCII names such as `Matematika – Kelas X.pdf` survive.
- **Framing**: responses carry `X-Frame-Options: SAMEORIGIN`; the front-end (same origin) may show `/file` in an `<iframe>`, other sites may not.
- **Thumbnails** (archive, async): images → ~400 px JPEG + width/height (images over 50 megapixels: width/height only, placeholder thumbnail); videos → frame at 1 s + duration/width/height via `ffmpeg`/`ffprobe` if installed (60 s limit per run), otherwise null fields and a placeholder.
- **Search**: MySQL FULLTEXT on `title, author, description` (books) and `title, description` (archive) for queries of 3+ characters; `LIKE` for shorter ones. Results are paged.

### 5.5 Bulk import
- The librarian copies files plus a CSV into the import folder, then calls `POST /admin/import`:
  - `{import-dir}/books/books.csv` with `filename,title,author,publisher,year,type,category,grade,curriculum,visibility`
  - `{import-dir}/archive/archive.csv` with `filename,title,description,category,eventDate,visibility`
- Only `filename` is required; blank cells use the defaults in §5.1; a blank title becomes the file name.
- UTF-8 (BOM allowed), `,` or `;` delimiter, case-insensitive headers and enum values. `year` = 4 digits, `eventDate` = `YYYY-MM-DD`.
- Rows go through the normal upload rules. Duplicates are counted as `skipped`; bad rows as `failed` with a message. One job runs at a time.
- Unknown category names are **created automatically**. Source files are left in place, so re-running is safe.

### 5.6 Soft delete
- Deleting sets `deleted_at`; deleted items return 404 and are excluded from lists. They stay in the **trash**, where librarians can restore them. `POST …/restore` on an item that isn't deleted returns `200` and changes nothing.
- **Permanent delete** (purge) removes the row, its files (book file and cover, or archive file and thumbnail) and its download logs. It can't be undone. Until an item is purged, its checksum still blocks re-uploading the same file (§5.2).
- Librarians purge one item or empty a whole trash (books and archive separately). While the drive is unplugged, purging returns `503 STORAGE_UNAVAILABLE` and deletes nothing.
- **Automatic purge**: a nightly job (02:30) purges items deleted more than `app.trash.retention` ago (default 30 days), so `purgeAt = deletedAt + retention`. With `app.trash.auto-purge: false` nothing is purged automatically and `purgeAt` is `null`. The job skips a run while the drive is unplugged.

---

## 6. Configuration

```yaml
app:
  storage:
    root: /mnt/library/files        # 1 TB HDD mount (dev: ./data/storage)
    import-dir: /mnt/library/import # (dev: ./data/import)
    min-free-space: 5GB
    auto-create-root: false         # dev: true (creates root + marker)
    temp-max-age: 24h               # hourly sweep deletes older files in <root>/temp
  trash:
    auto-purge: true                # nightly purge of old trash (§5.6)
    retention: 30d
    purge-cron: "0 30 2 * * *"
  jwt:
    secret: ${JWT_SECRET}
    expiry-hours: 8
spring:
  servlet.multipart.max-file-size: 2GB      # videos
  servlet.multipart.max-request-size: 2GB
  jpa.hibernate.ddl-auto: validate          # Flyway owns the schema
```
- **Storage marker**: the storage root must contain a `.library-storage` file, or the drive counts as unplugged. With `auto-create-root: false` (prod) the app never creates the root or marker; create the marker once after mounting the drive.
- **First admin**: if no LIBRARIAN exists at startup, one is created from `ADMIN_USERNAME` / `ADMIN_PASSWORD` (dev fallback `admin` / `admin123`).
- **Nginx** must match the upload limit: `client_max_body_size 2g;`, `proxy_request_buffering off;` (stream uploads instead of buffering them on the SSD) and raised `proxy_read_timeout` / `proxy_send_timeout` (e.g. 600 s).
- **CORS**: not needed in production; the front-end is served by the same Nginx.
- **API docs**: `/swagger-ui.html` and `/v3/api-docs` in dev only; the prod profile disables them (`springdoc.api-docs.enabled: false`).

---

## 7. Operations

- **Health / degraded mode**: `/api/health` checks the DB and that the storage root is mounted (marker present) and writable; `/actuator/health` has a matching `storage` component. If the HDD is unplugged, the app keeps running (also when started without it): browsing works, file and upload endpoints return `503 STORAGE_UNAVAILABLE`, covers and thumbnails show placeholders. Plugging the drive back in recovers without a restart.
- **Temp files**: uploads interrupted by a crash or power loss leave files in `<root>/temp`; an hourly job deletes those older than `temp-max-age` (skipped while the drive is unplugged).
- **Backups**: nightly `mysqldump` (keep 14). Archive photos/videos can't be re-downloaded, so keep a second copy on another disk.
- **Capacity**: 1080p video is roughly 0.5–1 GB per 10 minutes. `/api/admin/storage` shows usage before the disk fills.
