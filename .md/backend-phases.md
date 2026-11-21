# Backend Roadmap: Perpustakaan Digital Lokal SMAN 11 Garut

> **When and in what order** the backend is built. Each finished phase has its own doc with the exact changes and a Postman test guide. See [README.md](README.md) for how the docs fit together.

## 1. Status

| Phase | Scope | Status | Doc |
|---|---|---|---|
| 0 | Project setup | Done | — |
| 1 | Auth and users | Done | [phase-1-auth.md](phase-1-auth.md) |
| 2 | Storage foundation | Done | [phase-2-storage.md](phase-2-storage.md) |
| 3 | Categories and books | Done | [phase-3-categories-books.md](phase-3-categories-books.md) |
| 4 | Archive and media processing | Done | [phase-4-archive.md](phase-4-archive.md) |
| 5 | Download counters and admin stats | Done | [phase-5-downloads-stats.md](phase-5-downloads-stats.md) |
| 6 | Bulk import | Done | [phase-6-bulk-import.md](phase-6-bulk-import.md) |
| 7 | Storage usage and health | Done | [phase-7-storage-health.md](phase-7-storage-health.md) |
| 8 | Hardening and handover prep | Done | [phase-8-hardening.md](phase-8-hardening.md) |

---

## 2. Phases

### Phase 0: Project setup
- **Scope**: Initializr project; springdoc, JJWT, Tika and Thumbnailator in `pom.xml`; `application.yml` with `dev` and `prod` profiles; Flyway `V1__create_initial_schema.sql`; feature packages; `ApiError` and `GlobalExceptionHandler`.
- **Done when**: the app starts against a local MySQL, Flyway creates the tables, and `/actuator/health` returns UP.

### Phase 1: Auth and users
- **Scope**: User entity, BCrypt, JWT login, `JwtAuthFilter` (lets anonymous requests through), `SecurityConfig`. First librarian seeded from environment variables, change password, user admin (create teacher, reset password, deactivate).
- **Done when**: a librarian can log in and create a teacher, the teacher can log in, and role-protected endpoints reject anonymous callers.

### Phase 2: Storage foundation
- **Scope**: `FileStorageService` (temp file, checksum, atomic move, safe paths), `FileTypeDetector`, `DiskSpaceGuard`, shared `RangeFileResponder`.
- **Done when**: tests show a file saves, duplicates are detected, path traversal is blocked, and a Range request returns 206 with the right bytes.

### Phase 3: Categories and books
- **Scope**: Category CRUD, then books: upload, edit, replace file, soft delete and restore, ownership rules, covers, search with filters, inline read and download.
- **Done when**: a teacher uploads a PDF, an anonymous user finds it by search and opens it in the browser, and a teacher can't edit another teacher's book. First usable slice for the front-end team.

### Phase 4: Archive and media processing
- **Scope**: Archive upload, list and search, streaming with video seek, `MediaProcessor` for thumbnails, dimensions and duration (`ffmpeg` optional).
- **Done when**: an image and an MP4 can be uploaded, thumbnails appear, the video seeks, and STAFF items are hidden from anonymous callers.

### Phase 5: Download counters and admin stats
- **Scope**: `DownloadEvent` and `DownloadLog`, async counters for books and archive. `GET /api/admin/stats` was pulled forward from Phase 7 to verify the counters.
- **Done when**: downloads increment counts without slowing streaming.

### Phase 6: Bulk import
- **Scope**: CSV and folder import for books and archive on its own single-thread executor, with a per-row error report and a job status endpoint.
- **Done when**: a folder of files plus a CSV imports correctly, duplicates are skipped, and bad rows are reported without stopping the job.

### Phase 7: Storage usage and health
- **Scope**: `GET /api/admin/storage` usage breakdown; `GET /api/health` with degraded mode when the drive is unplugged. *(Admin stats were done in Phase 5; restore in Phases 3 and 4.)*
- **Done when**: unplugging the drive in testing makes file requests return 503 while browsing keeps working.

### Phase 8: Hardening and handover prep
- **Scope**: OpenAPI docs cleaned up for the front-end team, integration tests with Testcontainers, a security review (upload rules, throttling, JWT config, `Content-Disposition` encoding), and a scheduled sweep that deletes stale temp files.
- **Done when**: tests pass on a clean checkout and the OpenAPI spec matches the real endpoints.
- **Note**: the integration tests need Docker Desktop on the developer's machine (Testcontainers starts a throwaway MySQL 8); without it they are skipped. Not needed on the server.

---

## 3. Server configuration (no app code)

| Item | Work |
|---|---|
| Mini PC OS | Linux install, static IP or DHCP reservation; install `ffmpeg`, Java 21 and MySQL 8. |
| Router | DHCP reservation for the mini PC, client isolation **off** (otherwise phones can't reach the server), fixed address for the poster/QR code. |
| Nginx | Proxy `/api` to Spring, serve the front-end, 2 GB upload limit, longer timeouts. |
| External drive | Auto-mount by UUID, correct permissions, mount path matching `app.storage.root`. After the first mount, create the marker once: `touch /mnt/library/files/.library-storage` (owned by the app user). Without it the app treats the drive as unplugged. |
| systemd | Spring Boot, MySQL and Nginx start on boot and restart on failure. |
| Backups | Nightly `mysqldump` keeping 14 copies, plus a second copy of the archive on another disk. |
| Restore test | Actually restore a backup once to prove it works. |
