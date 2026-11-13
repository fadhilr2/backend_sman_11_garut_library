# Backend Spec: Perpustakaan Digital Lokal SMAN 11 Garut

Stack: Spring Boot 3 (Java 17+), Spring Data JPA, Spring Security, Flyway, MySQL 8, Nginx in front.
Users: students are anonymous (no accounts). Only TEACHER and LIBRARIAN log in. Uploads publish immediately (no approval).

The system has two content areas that share the same storage, auth and category mechanism:

- **Books**: ebooks, teaching modules, question banks, slides (the core of the proposal).
- **Archive**: school archive of images, videos and documents (extra scope, built on the same pieces).

## 1. Access rules

| Action | Anonymous (student) | TEACHER | LIBRARIAN |
|---|---|---|---|
| Browse/read/download `PUBLIC` books and archive items | yes | yes | yes |
| View `STAFF` items (archive defaults to STAFF) | no | yes | yes |
| Upload book / archive item | no | yes | yes |
| Edit/delete book / archive item | no | own only | any |
| Manage categories, users, import, stats | no | no | yes |

Auth: `POST /api/auth/login` returns a JWT (8 h expiry). Send as `Authorization: Bearer <token>`. Passwords hashed with BCrypt.

## 2. Endpoints

### Public: Books
| Method | Path | Notes |
|---|---|---|
| GET | `/api/books` | Query: `q`, `categoryId`, `grade`, `type`, `page`, `size`, `sort` (`newest`, `title`, `popular`). `q` uses FULLTEXT (fallback to LIKE for short terms). Only PUBLIC unless caller is staff. |
| GET | `/api/books/{id}` | Metadata. 404 if STAFF and caller anonymous. |
| GET | `/api/books/{id}/file` | Stream inline (in-browser reader). Supports HTTP `Range`. |
| GET | `/api/books/{id}/download` | Same file as attachment; logs the download and increments `download_count`. |
| GET | `/api/books/{id}/cover` | Cover image, or default placeholder. |

### Public: Archive
| Method | Path | Notes |
|---|---|---|
| GET | `/api/archive` | Query: `q`, `categoryId`, `mediaType` (IMAGE/VIDEO/DOCUMENT), `from`, `to` (event date), `page`, `size`, `sort` (`newest`, `eventDate`, `title`). |
| GET | `/api/archive/{id}` | Metadata (dimensions, duration, event date). |
| GET | `/api/archive/{id}/file` | Inline stream. **Range support is required** so videos can play and seek. |
| GET | `/api/archive/{id}/download` | Attachment; logged. |
| GET | `/api/archive/{id}/thumbnail` | Generated thumbnail (image or video poster frame). |

### Public: Shared
| Method | Path | Notes |
|---|---|---|
| GET | `/api/categories?type=BOOK\|ARCHIVE` | Category list (tree via `parentId`). |
| GET | `/api/meta/filters` | Grade levels, book types, media types, curricula for filter dropdowns. |
| GET | `/api/health` | DB reachable + storage mounted. |

### Auth
| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | `{username, password}` -> `{token, user}` |
| GET | `/api/auth/me` | Current user |
| POST | `/api/auth/change-password` | `{oldPassword, newPassword}` |

### Teacher + Librarian
| Method | Path | Notes |
|---|---|---|
| POST | `/api/books` | `multipart/form-data`: `file`, optional `cover`, `metadata` (JSON). Validates type, size, checksum (409 on duplicate). |
| PUT | `/api/books/{id}` | Update metadata (own, or any for librarian). |
| PUT | `/api/books/{id}/file` | Replace file. |
| DELETE | `/api/books/{id}` | Soft delete (own, or any for librarian). |
| GET | `/api/books/mine` | Uploads by current user. |
| POST | `/api/archive` | `multipart/form-data`: `file`, `metadata` (JSON: title, description, categoryId, eventDate, visibility). `mediaType` is detected from the file. |
| PUT | `/api/archive/{id}` | Update metadata. |
| DELETE | `/api/archive/{id}` | Soft delete. |
| GET | `/api/archive/mine` | Uploads by current user. |

### Librarian only
| Method | Path | Notes |
|---|---|---|
| POST/PUT/DELETE | `/api/admin/categories[/{id}]` | Category CRUD (`type`, `name`, `parentId`). Delete blocked if in use, or reassign. |
| GET/POST/PUT | `/api/admin/users[/{id}]` | Create teachers, reset password, deactivate. |
| POST | `/api/admin/import` | Body: `{target: "BOOK"\|"ARCHIVE"}`. Starts bulk import from the server folder (see section 4). Returns `jobId`. |
| GET | `/api/admin/import/{jobId}` | Progress and per-row error report. |
| GET | `/api/admin/stats` | Totals, downloads per day/week, top books, counts by type/media type. |
| GET | `/api/admin/storage` | Disk used/free on the 1 TB drive, plus breakdown: books vs archive images vs videos. |
| POST | `/api/admin/books/{id}/restore`, `/api/admin/archive/{id}/restore` | Undo soft delete. |

Error format (all endpoints): `{ "status": 409, "code": "DUPLICATE_FILE", "message": "..." }`.

## 3. Suggested package layout

```
id.sch.sman11garut.library
  config/        SecurityConfig, JwtFilter, StorageProperties
  auth/          AuthController, JwtService, UserDetailsServiceImpl
  user/          User, UserRepository, UserService, UserAdminController
  category/      Category, CategoryRepository, CategoryController, CategoryAdminController
  book/          Book, BookRepository (+Specification), BookService, BookController
  archive/       ArchiveItem, ArchiveRepository (+Specification), ArchiveService,
                 ArchiveController, MediaProcessor (thumbnails, ffprobe)
  storage/       FileStorageService (save/load/delete, checksum, path safety),
                 RangeFileResponder (shared Range streaming for books + archive)
  stats/         DownloadLog, StatsService, AdminStatsController
  importer/      ImportJob, BulkImportService (BOOK and ARCHIVE strategies), ImportController
  common/        GlobalExceptionHandler, ApiError
```

Key `application.yml` settings:
```yaml
app:
  storage:
    root: /mnt/library/files        # mount point of the 1 TB HDD
    import-dir: /mnt/library/import
  jwt:
    secret: ${JWT_SECRET}
    expiry-hours: 8
spring:
  servlet.multipart.max-file-size: 2GB     # videos
  servlet.multipart.max-request-size: 2GB
  jpa.hibernate.ddl-auto: validate         # Flyway owns the schema
```
Nginx must match: `client_max_body_size 2g;` and raised `proxy_read_timeout` / `proxy_send_timeout` (e.g. 600s) for large uploads.

## 4. Implementation notes

**Storage (shared)**
- Layout: `<root>/books/<yyyy>/<mm>/<uuid>.<ext>`, `<root>/archive/<yyyy>/<mm>/<uuid>.<ext>`, `<root>/thumbnails/<uuid>.jpg`. Never use user-supplied names on disk (prevents path traversal). Keep the original name in the DB for downloads.
- Compute SHA-256 while saving. Duplicate checksum returns 409.
- Stream uploads straight to disk (Spring multipart streaming); never load a whole video into memory. Write to a temp file in the same volume, then atomic rename.
- Check free disk space before accepting an upload and return 507/409 with a clear message if the 1 TB drive is nearly full (e.g. under 5 GB free).

**Allowed types**
- Books: pdf, epub, docx, pptx, xlsx; covers jpg/png.
- Archive images: jpg, png, webp, gif. Videos: mp4 (H.264/AAC) and webm. Documents: pdf, docx, pptx, xlsx.
- Check the extension and sniff the MIME type (e.g. Apache Tika); reject executables and mismatches.

**Range streaming (books and archive)**
- One shared component handling `Range` (single range is enough) using `ResourceRegion`, with `Accept-Ranges: bytes`, `206 Partial Content`, correct `Content-Type`, and caching headers (`ETag` or `Last-Modified`).
- Videos play only if the browser can decode them. Recommend MP4 H.264/AAC. Transcoding is out of scope: unsupported files are still downloadable, and the UI should offer "Download" when the browser can't play them.

**Thumbnails and media info (archive)**
- Images: generate a ~400px JPEG thumbnail (Thumbnailator or ImageIO) and store width/height.
- Videos: use `ffmpeg`/`ffprobe` if installed on the mini PC: poster frame at ~1 s, plus duration/width/height. If ffmpeg is missing, leave these null and fall back to a default icon. Run thumbnail generation asynchronously so the upload response is fast.
- Install `ffmpeg` in the setup script and document it in the handbook.

**Search**
- `MATCH(title, author, description) AGAINST (:q IN NATURAL LANGUAGE MODE)` for books, `MATCH(title, description)` for archive. MySQL ignores words under 3 characters, so fall back to `LIKE %q%` for short queries.

**Bulk import**
- Librarian copies files plus a CSV into `import-dir` (USB/SFTP), then calls `/api/admin/import`.
- Books CSV: `filename,title,author,publisher,year,type,category,grade,curriculum,visibility`.
- Archive CSV: `filename,title,description,category,eventDate,visibility`.
- Runs async (`@Async`), skips duplicates by checksum, records failures per row in `import_jobs.error_report`. Unknown category names are created automatically (or reported; pick one behavior).

**Other**
- **First admin**: on startup, if no LIBRARIAN exists, create one from `ADMIN_USERNAME` / `ADMIN_PASSWORD` env vars.
- **Soft delete**: queries filter `deleted_at IS NULL`. A nightly cleanup (optional) removes files and `download_logs` rows for items deleted over 30 days ago.
- **Health / degraded mode**: `/api/health` checks the storage root is mounted and writable. If the HDD is unplugged, catalog browsing still works and file endpoints return 503 with a clear message.
- **Backups**: nightly `mysqldump` (keep the last 14). The 1 TB drive holds the files themselves, so decide whether the archive (photos/videos can't be re-downloaded) needs a second copy on another disk or USB drive. Document restore in the handbook.
- **Capacity planning**: videos eat space quickly (roughly 0.5-1 GB per 10 min of 1080p). The `/api/admin/storage` breakdown lets the librarian see this before the disk fills.
- **CORS**: the front-end is served from the same Nginx, so proxy `/api` to Spring on `localhost:8080` and no CORS is needed in production.

## 5. Build order (months 3-5)

1. Project setup, Flyway V1, entities/repositories, global error handling
2. Auth (login, JWT, roles, first-admin seeding)
3. Storage service + shared Range streaming
4. Books: list/detail/search, upload, stream/download
5. Categories, user admin, download logging
6. Archive: upload, thumbnails/media info, stream (videos), list/search
7. Bulk import (books, then archive), stats, storage/health endpoints
8. Nginx config, systemd units, backup timer, handbook draft
