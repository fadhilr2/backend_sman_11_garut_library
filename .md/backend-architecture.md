# Backend Architecture: Perpustakaan Digital Lokal SMAN 11 Garut

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
config/      SecurityConfig, JwtAuthFilter, StorageProperties, AsyncConfig, OpenApiConfig
common/      ApiError, GlobalExceptionHandler, PageResponse, exceptions
auth/        AuthController, AuthService, JwtService
user/        User, UserRepository, UserService, UserAdminController
category/    Category, CategoryRepository, CategoryService, controllers
storage/     FileStorageService, RangeFileResponder, DiskSpaceGuard, FileTypeDetector
book/        Book, BookRepository, BookSpecifications, BookService, BookController
archive/     ArchiveItem, ArchiveRepository, ArchiveSpecifications, ArchiveService, ArchiveController
media/       MediaProcessor (thumbnails, ffprobe), listens for upload events
stats/       DownloadLog, StatsService, AdminStatsController, listens for download events
importer/    ImportJob, BulkImportService, ImportController
health/      StorageHealthIndicator, HealthController
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
importer       -> book service, archive service (never their repositories)
media, stats   -> react to events only; nothing calls them directly
storage        -> depends on nothing in the app (pure infrastructure)
```

Cross-feature calls go through **services**, never another feature's repository. Book and archive do not depend on each other.

## 3. Key flows

### 3.1 Upload (book or archive item)

1. Controller validates the multipart request and metadata (Bean Validation).
2. Service checks the role, and for edits checks ownership.
3. `DiskSpaceGuard` rejects the upload if free space is under the threshold.
4. `FileStorageService` streams the upload to a temp file on the same volume, computes SHA-256, detects the real type (Tika), and rejects disallowed types.
5. Duplicate check by checksum: if it exists, delete the temp file and return 409.
6. Atomic move into the final path (`books/yyyy/mm/uuid.ext` or `archive/...`).
7. Service saves the entity in a DB transaction. If this fails, the stored file is deleted (compensation).
8. After commit, publish an `ItemCreatedEvent`. `MediaProcessor` handles it asynchronously (thumbnail, dimensions, duration) and updates the row. The upload response does not wait for it.

Files are written **before** the DB commit and cleaned up on failure, so there are never DB rows pointing to missing files.

### 3.2 View / stream / download

1. Controller receives the request (optionally with a JWT).
2. Service loads metadata and applies the visibility rule: `STAFF` items return 404 to anonymous callers.
3. `FileStorageService` resolves the stored path safely (no path traversal; must stay under the storage root).
4. `RangeFileResponder` answers `200` or `206 Partial Content` with `Accept-Ranges`, `Content-Type`, and `ETag`/`Last-Modified`.
5. For `/download` only: publish a `DownloadEvent`. `stats` handles it asynchronously (insert log row, increment the counter), so streaming never blocks on stats.

### 3.3 Search

Query parameters become a JPA `Specification` (category, grade, type, visibility, not deleted). The text query adds a MySQL `MATCH ... AGAINST` condition, with a `LIKE` fallback for terms under 3 characters. Results are paged.

### 3.4 Bulk import

Librarian triggers `/api/admin/import`. The service creates an `import_jobs` row and runs on a **single-thread executor**, reading the CSV and files from the import directory. Each row goes through the same `BookService`/`ArchiveService` create path as a normal upload, so validation, checksums and events behave identically. Errors are recorded per row and do not stop the job.

## 4. Cross-cutting concerns

**Security**
- Stateless JWT. `JwtAuthFilter` reads a token if present and sets the authentication, but never rejects anonymous requests, so public endpoints stay open while staff see `STAFF` items.
- Roles enforced with method security (`@PreAuthorize`) on write and admin endpoints. Ownership checks live in services.
- BCrypt for passwords. Simple login throttling (in-memory failed-attempt counter) to slow guessing.
- Upload hardening: generated file names, sniffed content types, no executables, paths constrained to the storage root.

**Error handling**: one `GlobalExceptionHandler` mapping domain exceptions to the standard `{status, code, message}` body.

**Transactions**: DB transactions only wrap DB work. File operations stay outside them, with explicit compensation.

**Async work**: two small executors defined in `AsyncConfig`: `media` (2 threads: thumbnails) and `import` (1 thread). Events use Spring's in-process `ApplicationEventPublisher`. There is no message broker.

**Configuration**: `application.yml` plus `dev` and `prod` profiles. Secrets (`JWT_SECRET`, `ADMIN_PASSWORD`, DB password) come from environment variables via the systemd unit.

**Observability**: logs to a rotating file, Actuator exposing only `/health`, and a custom storage health indicator (drive mounted, writable, free space). The same data feeds `/api/health`.

**API documentation**: springdoc-openapi generates the OpenAPI spec for the front-end team.

**Database migrations**: Flyway runs on startup; Hibernate only validates (`ddl-auto: validate`).

## 5. Technology choices

| Concern | Choice |
|---|---|
| Language/runtime | Java 21 (LTS) |
| Framework | Spring Boot 3.x (Web, Data JPA, Security, Validation, Actuator) |
| Database | MySQL 8 + Flyway |
| Auth | Spring Security + JJWT |
| File type detection | Apache Tika |
| Image thumbnails | Thumbnailator |
| Video thumbnails/duration | ffmpeg / ffprobe via `ProcessBuilder` |
| API docs | springdoc-openapi |
| Build | Maven (or Gradle, pick one) |
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

## 8. Out of scope (deliberately)

Microservices, message queues, Redis/caching, Elasticsearch, object storage, video transcoding, student accounts, and multi-server or HA setups. HTTPS is optional: it's an offline LAN, but it can be added at Nginx later if the school wants it.

## 9. Main risks to design around

- **Disk full or drive unplugged**: handled by the free-space guard, health checks, and degraded mode (browsing works, files return 503).
- **Power loss**: UPS recommended, and Flyway plus InnoDB handle crash recovery, but the drive should be shut down cleanly where possible.
- **Large uploads over Wi-Fi**: long timeouts, streaming to disk, and temp-file cleanup for aborted uploads (a scheduled sweep of stale temp files).
- **Data loss**: nightly DB dump plus a second copy of the archive.
