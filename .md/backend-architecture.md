# Backend Architecture: Perpustakaan Digital Lokal SMAN 11 Garut

> **How** the backend is built. The API contract is in [backend-spec.md](backend-spec.md); the schedule is in [backend-phases.md](backend-phases.md).

Style: **modular monolith**. One Spring Boot application, one deployable JAR, one MySQL database, files on the local disk. Organized by feature (package-by-feature) with a strict layering inside each feature.

## 1. Runtime view

```
 Student / teacher devices (phone, laptop)
            |  Wi-Fi, HTTP
            v
      +-----------+
      |  Router   |  Wi-Fi + DHCP (reserved IP for the mini PC)
      +-----------+
            |  LAN
            v
 +---------------------------------------------------------------+
 |  Mini PC (Linux)                                              |
 |                                                               |
 |   Nginx :80                                                   |
 |     |-- /        -> static front-end files                    |
 |     '-- /api/*    -> proxy                                    |
 |                       v                                       |
 |   Spring Boot app :8080 (single JAR, systemd service)         |
 |     |                    |                  |                 |
 |     v                    v                  v                 |
 |   MySQL :3306        File storage        ffmpeg / ffprobe     |
 |   (localhost only)   /mnt/library        (child process,      |
 |                      (1 TB HDD)           thumbnails only)    |
 +---------------------------------------------------------------+
```

Nothing leaves the LAN. MySQL listens on localhost only. Spring Boot listens on localhost only (Nginx is the single entry point).

## 2. Internal structure

Package root: `id.sch.sman11garut.library`

```
config/      SecurityConfig, JwtAuthFilter, JwtProperties, StorageProperties, TrashProperties, AsyncConfig, OpenApiConfig
common/      ApiError, AppException, GlobalExceptionHandler, PageResponse, Visibility, TrashItemDto, PurgeResultDto
auth/        AuthController, AuthService, JwtService, LoginThrottlingService, CustomUserDetailsService
user/        User, UserRepository, UserService, UserAdminController, AdminUserSeeder
category/    BookCategory, ArchiveCategory, repositories, CategoryService, controllers
meta/        MetaController (filter dropdown values)
storage/     FileStorageService, RangeFileResponder, DiskSpaceGuard, FileTypeDetector, TempFileSweeper
book/        Book, BookRepository, BookSpecifications, BookService, BookController, BookAdminController
archive/     ArchiveItem, ArchiveRepository, ArchiveSpecifications, ArchiveService, controllers
media/       MediaProcessor (thumbnails, ffprobe), listens for ArchiveItemCreatedEvent
stats/       DownloadLog, DownloadEventListener, ItemsPurgedListener, StatsService, AdminStatsController
importer/    ImportJob, ImportService, ImportRunner, ImportCsvReader, ImportRowMapper, ImportController
health/      HealthService, HealthController, StorageHealthIndicator, StorageUsageService, AdminStorageController
trash/       TrashPurgeJob (nightly purge; the trash endpoints live in BookAdminController / ArchiveAdminController)
```

### Layering inside each feature

| Layer | Responsibility | Must not |
|---|---|---|
| Controller | HTTP mapping, request validation, DTO in/out | contain business rules or touch repositories |
| Service | Business rules, ownership/visibility checks, transactions | know about HTTP types |
| Repository | JPA queries and Specifications | contain logic |
| Entity | Table mapping | be returned from controllers (use DTOs) |

### Dependency rules (keeps the monolith from turning into spaghetti)

```
controller -> service -> repository
book, archive -> storage, category, user (via services)
importer       -> book service, archive service, category service (never their repositories)
media, stats   -> react to events only; nothing calls them directly
health         -> storage, book service, archive service (read-only reporting)
trash          -> book service, archive service (scheduled purge only)
storage        -> depends on nothing in the app (pure infrastructure)
```

Cross-feature calls go through **services**, never another feature's repository. Book and archive do not depend on each other.

## 3. Key flows

### 3.1 Upload (book or archive item)

1. Controller validates the multipart request and metadata (Bean Validation).
2. Service checks the role, and for edits checks ownership.
3. `DiskSpaceGuard` rejects the upload if free space is under the threshold.
4. `FileStorageService` streams the upload to a temp file on the same volume, computes SHA-256, detects the real type (Tika), and rejects disallowed types.
5. Atomic move into the final path (`books/yyyy/mm/uuid.ext` or `archive/...`).
6. Duplicate check by checksum, including soft-deleted rows (the DB unique key covers them too): if it exists, delete the stored file and return 409.
7. Service saves the entity in a DB transaction. If this fails, the stored file is deleted (compensation).
8. Archive only: the service publishes an `ArchiveItemCreatedEvent`. `MediaProcessor` receives it **after commit** (`@TransactionalEventListener(AFTER_COMMIT)`) on the `media` executor, generates the thumbnail, dimensions and duration, and updates the row. The upload response does not wait for it.

Files are written **before** the DB commit and cleaned up on failure, so there are never DB rows pointing to missing files.

### 3.2 View / stream / download

1. Controller receives the request (optionally with a JWT).
2. Service (`openFile` / `openDownload` / `openCover` / `openThumbnail`, a short read-only transaction) loads metadata and applies the visibility rule: `STAFF` items, including their covers and thumbnails, return 404 to anonymous callers. It returns a `ServedFile` (resource, content type, original name).
3. `FileStorageService` resolves the stored path safely (no path traversal; must stay under the storage root).
4. For `/download` only: publish a `DownloadEvent`. `stats` handles it asynchronously (insert log row, increment the counter), so streaming never blocks on stats.
5. Back in the controller, **after the transaction has ended**, `RangeFileResponder` answers `200` or `206 Partial Content` with `Accept-Ranges`, `Content-Type`, and `ETag`/`Last-Modified`; downloads get an RFC 6266 `Content-Disposition`. A long video stream therefore never holds a DB connection.

### 3.3 Search

Query parameters become a JPA `Specification` (category, grade, type, visibility, not deleted). The text query adds a MySQL `MATCH ... AGAINST` condition, with a `LIKE` fallback for terms under 3 characters. Results are paged.

### 3.4 Bulk import

Librarian triggers `/api/admin/import`. `ImportService` checks the CSV exists and no job is running, commits an `import_jobs` row, and hands it to `ImportRunner` on the **single-thread `import` executor**. The runner reads `{import-dir}/{books|archive}/*.csv` and, per row, calls `BookService.createBookFromStream` / `ArchiveService.createArchiveItemFromStream`: the same create path as a normal upload, so validation, checksums, compensation and events behave identically. There is no transaction around the loop; each row commits on its own. Duplicates count as skipped, other errors are recorded per row, and neither stops the job. Any job-level failure marks the job `FAILED`; jobs left `RUNNING` by a restart are marked `FAILED` at startup. Details: [phase-6-bulk-import.md](phase-6-bulk-import.md).

### 3.5 Trash and permanent delete

A soft delete only sets `deleted_at`. Purging (one item, a whole trash, or the nightly `TrashPurgeJob` for items older than the retention) runs in `BookService` / `ArchiveService`, inside one transaction:

1. `FileStorageService.ensureMounted()`: an unplugged drive fails with 503 **before** anything changes, so rows are never deleted while their files can't be.
2. Publish `ItemsPurgedEvent`. `stats` handles it **synchronously** in the same transaction and deletes the items' `download_logs` (that table has no foreign key to the item tables).
3. Delete the rows.
4. An `afterCommit` hook deletes the files (book file and cover, or archive file and thumbnail). A rollback keeps rows, logs and files.

This is the reverse of upload ordering on purpose: on upload the file is written first, so a failure can't leave a row pointing at a missing file. On purge the row goes first, so the worst case (a crash between commit and file delete) is an orphan file, never a row pointing at a missing file. The nightly job purges books and archive in separate transactions. Details: [phase-9-trash.md](phase-9-trash.md).

## 4. Cross-cutting concerns

**Security**
- Stateless JWT. `JwtAuthFilter` reads a token if present and sets the authentication, but never rejects anonymous requests, so public endpoints stay open while staff see `STAFF` items.
- Roles enforced with method security (`@PreAuthorize`) on write and admin endpoints. Ownership checks live in services.
- BCrypt for passwords; unknown usernames still run one BCrypt check, so timing doesn't reveal which accounts exist. Login throttling: 5 failures within 15 minutes lock a username for 5 minutes (in memory, stale entries removed).
- Upload hardening: generated file names, sniffed content types, no executables, paths constrained to the storage root.
- Headers: Spring Security defaults (`nosniff` etc.), with `X-Frame-Options: SAMEORIGIN` so the front-end may show PDFs in an `<iframe>`.
- Media processing: `ffmpeg`/`ffprobe` run with a 60 s timeout; images over 50 megapixels get no thumbnail (memory).

**Error handling**: one `GlobalExceptionHandler` mapping domain exceptions to the standard `{status, code, message}` body.

**Transactions**: DB transactions only wrap DB work. File operations stay outside them, with explicit compensation. Where a file must change together with a row (replacing a book file), the file clean-up runs in an `afterCompletion` hook: the old file is deleted only after commit, the new one on rollback.

**Async work**: three small executors defined in `AsyncConfig`: `mediaExecutor` (2–4 threads: thumbnails), `statsExecutor` (1–2 threads: download logs and counters) and `importExecutor` (1 thread: bulk import). Events use Spring's in-process `ApplicationEventPublisher`. There is no message broker. Scheduling (`@EnableScheduling`) runs two jobs on Spring's scheduler thread: `TempFileSweeper` (hourly) and `TrashPurgeJob` (nightly, `app.trash.purge-cron`).

**Configuration**: `application.yml` plus `dev` and `prod` profiles. Secrets (`JWT_SECRET`, `ADMIN_PASSWORD`, DB password) come from environment variables via the systemd unit.

**Observability**: logs to a rotating file, Actuator exposing only `/health`, and a custom storage health indicator (drive mounted, writable, free space). The same data feeds `/api/health`. "Mounted" means the storage root contains a `.library-storage` marker file, so an empty mount point on the OS disk is never mistaken for the drive; in prod the app never creates the root or the marker itself. Details: [phase-7-storage-health.md](phase-7-storage-health.md).

**API documentation**: springdoc-openapi generates the OpenAPI spec for the front-end team (`/swagger-ui.html`, `/v3/api-docs`; dev only, disabled in prod). `OpenApiConfig` adds the JWT scheme and the shared `ApiError` responses; `OpenApiIntegrationTest` fails the build if the documented endpoints drift from the spec.

**Testing**: unit tests for pure logic (storage, CSV, throttling, health mapping) and `*IntegrationTest` classes that run the whole app against MySQL 8 in Docker via Testcontainers. Without Docker the integration tests are skipped, not failed. Details: [phase-8-hardening.md](phase-8-hardening.md).

**Database migrations**: Flyway runs on startup; Hibernate only validates (`ddl-auto: validate`).

## 5. Technology choices

| Concern | Choice |
|---|---|
| Language/runtime | Java 21 (LTS) |
| Framework | Spring Boot 4.1 (Web MVC, Data JPA, Security, Validation, Actuator) |
| Database | MySQL 8 + Flyway |
| Auth | Spring Security + JJWT 0.13 |
| File type detection | Apache Tika |
| Image thumbnails | Thumbnailator |
| Video thumbnails/duration | ffmpeg / ffprobe via `ProcessBuilder` |
| CSV import | Apache Commons CSV |
| API docs | springdoc-openapi |
| Build | Maven (wrapper: `mvnw`) |
| Tests | JUnit 5, Spring Boot Test, Testcontainers (MySQL) |

## 6. Deployment shape

- Build one executable JAR. Run it as a `systemd` service (`Restart=on-failure`), bound to `127.0.0.1:8080`.
- Nginx in front handles the front-end files, proxies `/api`, and sets the upload limit and timeouts.
- Flyway migrates the schema at startup. The first LIBRARIAN is seeded if none exists.
- Update procedure: stop the service, back up the DB, replace the JAR, start the service.

## 7. Decisions and trade-offs

| Decision | Why | Cost |
|---|---|---|
| Modular monolith | 3-10 users/day, small team, one server. Simplest to build, run and hand over. | Single process: if it dies, everything is down (mitigated by systemd restart). |
| Package-by-feature + dependency rules | Keeps modules separable if it ever needs splitting. | Needs discipline in code review. |
| Files on disk, metadata in MySQL | Large PDFs/videos don't belong in the DB, and streaming from disk is simple. | DB and disk can drift, so we write the file first and compensate on failure. |
| In-process events | Decouples thumbnails and stats without infrastructure. | Pending async work is lost on a crash (thumbnails can be regenerated by a retry job). |
| Stateless JWT | Front-end is a separate client, no session storage. | Tokens can't be revoked before expiry (8 h, acceptable here). |
| Plain MySQL search | Enough for this data size. | Weaker relevance ranking than a search engine. |
| No transcoding | Avoids heavy CPU work on a mini PC. | Unsupported video formats are download-only. |
| Trash with a 30-day automatic purge | Deleted items stay restorable for a month, and disk space comes back without a librarian having to remember. | A file can't be re-uploaded until its trashed copy is purged (the checksum still matches). |

## 8. Out of scope (deliberately)

Microservices, message queues, Redis/caching, Elasticsearch, object storage, video transcoding, student accounts, and multi-server or HA setups. HTTPS is optional: it's an offline LAN, but it can be added at Nginx later if the school wants it.

## 9. Main risks to design around

- **Disk full or drive unplugged**: handled by the free-space guard, health checks, and degraded mode (browsing works, files return 503).
- **Power loss**: UPS recommended, and Flyway plus InnoDB handle crash recovery, but the drive should be shut down cleanly where possible.
- **Large uploads over Wi-Fi**: long timeouts, streaming to disk, and temp-file cleanup for aborted uploads (`TempFileSweeper`: hourly, files older than 24 h).
- **Data loss**: nightly DB dump plus a second copy of the archive.
