<h2>Development</h2>

**Backend (code we write)**

- **Auth and roles**: JWT login for teachers and librarians only, first admin seeded from environment variables, and user admin.
- **Categories**: generic, optionally nested, separate for books and archive.
- **Storage service**: safe file naming, checksum duplicate detection, type validation, streaming uploads, and a free-space check.
- **Range streaming**: shared by books and archive, and required for video playback.
- **Books**: CRUD, upload, inline read, download, covers, and ownership rules with soft delete.
- **Archive**: images, videos and documents, with event date and visibility.
- **Media processing**: thumbnails, plus video poster and duration via `ffmpeg`.
- **Search and browse**: filters plus MySQL FULLTEXT.
- **Bulk import**: folder plus CSV, for both books and archive.
- **Stats, storage usage, and health endpoint**.

**Configuring (server and infrastructure, no app code)**

- **Mini PC OS**: Linux install, static IP or DHCP reservation, and `ffmpeg`, Java and MySQL installed.
- **Router**: DHCP reservation for the mini PC, client isolation turned off (otherwise phones can’t reach the server), and a fixed address that goes on the poster or QR code.
- **Nginx**: proxy `/api` to Spring, serve the front-end, 2 GB upload limit, and longer timeouts.
- **External drive**: auto-mount by UUID, correct permissions, and a mount path that matches `app.storage.root`.
- **systemd services**: Spring Boot, MySQL and Nginx start on boot and restart on failure.
- **Backups**: nightly `mysqldump` with 14 copies kept, plus a second copy of the archive on another disk.
- **Restore test**: actually restore a backup once to prove it works.

<h2>Phase 0: Project setup (in progress)</h2>

- **Done:** Initializr project with the core dependencies.
- **Left:** add springdoc, JJWT, Tika and Thumbnailator to `pom.xml`, write `application.yml` with `dev` and `prod` profiles, add the Flyway `V1__init.sql`, create the feature packages, and add `ApiError` and `GlobalExceptionHandler`.
- **Done when:** the app starts against a local MySQL, Flyway creates the tables, and `/actuator/health` returns UP.

<h2>Phase 1: Auth and users</h2>

- User entity and repository, BCrypt, JWT login, `JwtAuthFilter` (lets anonymous requests through), and `SecurityConfig`.
- First librarian seeded from environment variables, change-password, and user admin (create teacher, reset password, deactivate).
- **Done when:** a librarian can log in, create a teacher, and the teacher can log in. Role-protected endpoints reject anonymous callers.

<h2>Phase 2: Storage foundation</h2>

- `FileStorageService` (temp file, checksum, atomic move, safe paths), `FileTypeDetector`, `DiskSpaceGuard`, and the shared `RangeFileResponder`.
- **Done when:** tests show a file saves, duplicates are detected, path traversal is blocked, and a Range request returns 206 with the right bytes.

<h2>Phase 3: Categories and books </h2>

- Category CRUD, then books: upload, edit, replace file, soft delete, ownership rules, covers, search with filters, inline read and download.
- **Done when:** a teacher uploads a PDF, an anonymous user finds it by search and opens it in the browser, and a teacher can’t edit another teacher’s book. This is the first usable slice, so the front-end team can integrate against it.

<h2>Phase 4: Archive</h2>

- Archive upload, list and search, streaming (video seek), and `MediaProcessor` for thumbnails, dimensions and duration (`ffmpeg` optional).
- **Done when:** an image and an MP4 can be uploaded, thumbnails appear, the video seeks, and STAFF items are hidden from anonymous callers.

<h2> Phase 5: Stats and events </h2>

- `DownloadEvent` and `DownloadLog`, async counters for books and archive.
- **Done when:** downloads increment counts without slowing streaming.

<h2> Phase 6: Bulk import </h2>

- CSV and folder import for books and archive, running on its own single-thread executor, with a per-row error report and job status endpoint.
- **Done when:** a folder of files plus a CSV imports correctly, duplicates are skipped, and bad rows are reported without stopping the job.

<h2> Phase 7: Admin, storage and health </h2>

- Admin stats, storage usage breakdown, `/api/health` with degraded mode (drive unplugged), and restore for soft-deleted items.
- **Done when:** unplugging the drive in testing makes file requests return 503 while browsing keeps working.

<h2> Phase 8: Hardening and handover prep </h2>

- OpenAPI docs cleaned up for the front-end team, integration tests with Testcontainers, a security review (upload rules, throttling, JWT config), and a sweep job that deletes stale temp files.
- **Done when:** tests pass on a clean checkout and the OpenAPI spec matches the real endpoints.