# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Backend for the offline LAN digital library of SMAN 11 Garut: Spring Boot 4.1 / Java 21 modular monolith, MySQL 8 + Flyway, files stored on local disk. It runs on a mini PC behind Nginx; nothing leaves the LAN.

The design docs live in `.md/` and are the source of truth. Read `.md/README.md` first: it explains which doc owns which facts and gives the templates every doc follows.
- `backend-spec.md`: the API contract (endpoints, roles, error codes, business rules).
- `backend-architecture.md`: modules, flows, dependency rules, trade-offs.
- `backend-phases.md`: the roadmap. `phase-N-*.md`: what each phase built and how to test it.
- `Router.md` and `Ubuntu Server.md`: step-by-step notes for the deployment hardware (TP-Link router with no WAN, Ubuntu mini PC netplan/DHCP, external drive mount). `Deployment.md`: the go-live runbook (MySQL, secrets env file, systemd unit, Nginx, smoke tests, backups, updates). All three are linked from `.md/README.md`.

When code changes the contract or the design, update the doc that owns that fact (and the phase doc) instead of repeating it in several places.

## Commands (Windows, PowerShell)

```powershell
.\mvnw.cmd clean test                                   # all tests
.\mvnw.cmd test -Dtest=FileStorageServiceTest           # one class
.\mvnw.cmd test -Dtest=BookIntegrationTest#methodName   # one method
.\mvnw.cmd spring-boot:run                              # run app (dev profile, port 8081)
.\mvnw.cmd clean package                                # executable JAR in target/
```

- The `dev` profile is the default. It needs a local MySQL on `localhost:3306` (user `root`, empty password; the `library` DB is created automatically). Storage goes in `./data/storage` and `./data/import` (gitignored).
- The dev admin login is `admin` / `admin123`, seeded by `AdminUserSeeder`. Swagger UI is at `http://localhost:8081/swagger-ui.html` (dev only).
- The `prod` profile binds `127.0.0.1:8080` and needs the env vars `DB_PASSWORD`, `JWT_SECRET` and `ADMIN_PASSWORD`. Storage is `/mnt/library/files`.

## Tests

- Unit tests (`*Test`) cover pure logic: storage, CSV parsing, throttling and health mapping.
- Integration tests (`*IntegrationTest`) extend `support/IntegrationTestBase`. They boot the whole app with MockMvc against a `mysql:8.0` Testcontainers container plus a temp storage dir. They need Docker Desktop running; **without Docker they are skipped, not failed**, so check the `Skipped` count before trusting a green build.
- All integration tests share one Spring context and one DB. Use unique names and file contents (`unique()`, `uniquePdf()`, `uniquePng(w, h)`) and never assume an empty DB. Async effects (thumbnails, download counters, imports) are checked with `await(...)`.
- `OpenApiIntegrationTest` fails if the documented endpoints drift from the spec. Update it when you add or change endpoints.

## Architecture essentials

Package-by-feature under `id.sch.sman11garut.library`: `auth`, `user`, `category`, `book`, `archive`, `storage`, `media`, `stats`, `importer`, `health`, `meta`, `trash`, plus `config` and `common`.

Rules that are easy to break:
- Layering is Controller -> Service -> Repository. Controllers return DTOs, never entities. Business rules and ownership/visibility checks belong in services.
- Cross-feature calls go through **services**, never another feature's repository. `book` and `archive` don't depend on each other. `storage` depends on nothing in the app. `media` and `stats` only react to events (`ArchiveItemCreatedEvent` after commit, `DownloadEvent`); nothing calls them directly.
- **Files are written before the DB commit and compensated on failure.** Uploads stream to a temp file, then get a SHA-256 checksum, a Tika type sniff and an atomic move. A duplicate checksum (soft-deleted rows included) returns 409. If saving the entity fails, the stored file is deleted. When a file is replaced, the old one is deleted in an `afterCompletion` hook. A permanent delete (trash purge) does the reverse: it checks the drive, deletes the row and download logs, then deletes the files `afterCommit`. DB transactions never wrap file I/O.
- File serving (`RangeFileResponder`, which handles Range/206 and ETag) runs **after** the read-only service transaction ends, so streams don't hold DB connections.
- Visibility: `STAFF` items, including their covers and thumbnails, return 404 to anonymous callers. `JwtAuthFilter` never rejects anonymous requests. Write endpoints are protected with `@PreAuthorize`.
- Bulk import (`importer`) reuses `BookService.createBookFromStream` / `ArchiveService.createArchiveItemFromStream` on the single-thread import executor. Each row commits on its own.
- Async executors (`media`, `stats`, `import`) are defined in `config/AsyncConfig`. There is no broker or cache.
- Errors: throw `AppException` with a code from the spec catalog. `GlobalExceptionHandler` renders `{status, code, message}`.
- Schema: add a new Flyway migration in `src/main/resources/db/migration/` (never edit an applied one; only `V1__create_initial_schema.sql` exists so far, so the next is `V2__...`). Hibernate is `ddl-auto: validate`.
- Storage health requires a `.library-storage` marker file in the storage root. Prod never auto-creates the root or the marker.

## Workflow

- Commits go directly on `main` with conventional prefixes (`feat:`, `docs:`, `chore:`). Each phase so far is one `feat:` commit for the code plus a `docs:` commit for its phase doc.
