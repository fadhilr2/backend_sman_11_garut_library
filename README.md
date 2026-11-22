# Perpustakaan Digital Lokal SMAN 11 Garut: Backend

The backend for SMAN 11 Garut's offline digital library. It runs on a mini PC on the school LAN: students browse and read books and school archive media from their phones over Wi-Fi without an account, while teachers and librarians log in to upload and manage content.

- **Books**: ebooks, teaching modules, question banks and slides, with search, an in-browser reader and downloads.
- **Archive**: school photos, videos (with seeking) and documents, with generated thumbnails.
- **Admin**: users, categories, bulk import from CSV, download stats, storage usage and health.

One Spring Boot application (a modular monolith), one MySQL database, and files on a local disk. Nothing leaves the LAN.

---

## 1. Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 4.1 (Web MVC, Data JPA, Security, Validation, Actuator) |
| Database | MySQL 8, schema managed by Flyway |
| Auth | Stateless JWT (JJWT), BCrypt passwords |
| Files | Apache Tika (type detection), Thumbnailator, optional `ffmpeg` / `ffprobe` |
| API docs | springdoc-openapi (Swagger UI, dev only) |
| Build / tests | Maven wrapper, JUnit 5, Testcontainers |

Full list and reasoning: [backend-architecture.md §5](.md/backend-architecture.md#5-technology-choices).

---

## 2. Getting started (dev)

**Prerequisites**
- Java 21
- MySQL 8 on `localhost:3306` (dev login `root` with an empty password; change it in `src/main/resources/application-dev.yml`)
- Optional: `ffmpeg` / `ffprobe` on the `PATH` for video thumbnails and durations
- Optional: Docker Desktop, needed only for the integration tests (§3)

**Run**
```powershell
.\mvnw.cmd spring-boot:run      # Windows
./mvnw spring-boot:run          # Linux / macOS
```

The `dev` profile is the default. On startup:
- The `library` database is created if it doesn't exist, and **Flyway applies the migrations automatically**. Don't run the SQL in `db/migration` by hand, and point the app at an empty database (with `baseline-on-migrate`, a database that already has tables makes Flyway skip `V1`).
- A first librarian is seeded if none exists: `admin` / `admin123`.
- Files go to `./data/storage` and the bulk import folder is `./data/import`. Both are created automatically in dev.

| URL | What |
|---|---|
| `http://localhost:8081/api/...` | The API |
| `http://localhost:8081/swagger-ui.html` | Swagger UI ("Authorize" takes the token from `POST /api/auth/login`) |
| `http://localhost:8081/api/health` | Health: `UP`, `DEGRADED` or `DOWN` |

---

## 3. Tests

```powershell
.\mvnw.cmd clean test
```

- **Unit tests** need nothing else.
- **Integration tests** (`*IntegrationTest`) run the whole app against a throwaway MySQL 8 that Testcontainers starts in Docker, so they never touch your dev database. Docker Desktop has to be running. Without it they are **skipped, not failed**, so check for `Skipped: 0` in the summary.
- The first run downloads the `mysql:8.0` image (about 600 MB).
- Run one class with `.\mvnw.cmd test -Dtest=BookIntegrationTest`.

Docker setup on Windows and what each test covers: [phase-8-hardening.md §6](.md/phase-8-hardening.md#6-testing).

---

## 4. API at a glance

All paths are under `/api`. Errors always use one body: `{ "status", "code", "message" }`.

| Audience | Endpoints |
|---|---|
| Anyone (students) | `GET /books`, `/books/{id}`, `/file`, `/download`, `/cover`; `GET /archive`, `/archive/{id}`, `/file`, `/download`, `/thumbnail`; `GET /categories`, `/meta/filters`, `/health` |
| Logged in | `POST /auth/login`, `GET /auth/me`, `POST /auth/change-password` |
| Teacher + librarian | Upload, edit, replace and soft-delete books and archive items; `GET /books/mine`, `/archive/mine` |
| Librarian only | `/admin/users`, `/admin/categories`, restore, `/admin/stats`, `/admin/import`, `/admin/storage` |

- Log in with `POST /api/auth/login`, then send `Authorization: Bearer <token>` (valid for 8 hours).
- `STAFF` items return `404` to anonymous callers, so their existence isn't revealed.

The full contract (query parameters, error codes, business rules): [backend-spec.md](.md/backend-spec.md).

---

## 5. Project structure

Package root `id.sch.sman11garut.library`, organized by feature:

```
config/    security, JWT, storage, async and OpenAPI configuration
common/    ApiError, AppException, GlobalExceptionHandler, paging
auth/      login, JWT, login throttling
user/      users and the first-librarian seeder
category/  book and archive categories
meta/      values for the filter dropdowns
storage/   file storage, Range responses, disk space guard, temp-file sweep
book/      books
archive/   archive items
media/     thumbnails and video metadata (async)
stats/     download logs and admin stats (async)
importer/  CSV bulk import
health/    health and storage usage
```

Layering and dependency rules: [backend-architecture.md §2](.md/backend-architecture.md#2-internal-structure).

---

## 6. Production

The production setup is a Linux mini PC: Nginx on port 80 serves the front-end and proxies `/api` to the app on `127.0.0.1:8080`. The app runs as a `systemd` service with the `prod` profile.

```bash
./mvnw clean package
SPRING_PROFILES_ACTIVE=prod java -jar target/*.jar
```

| Environment variable | Purpose |
|---|---|
| `JWT_SECRET` | JWT signing key (256-bit). Required. |
| `DB_USERNAME` / `DB_PASSWORD` | MySQL login (username defaults to `library`). |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | First librarian, created only if none exists. **Set these in prod**: without them the seeder falls back to `admin` / `admin123` in every profile. |

Before the first start:
- Mount the storage drive at `/mnt/library` and create the marker file once: `touch /mnt/library/files/.library-storage`. Without the marker the app treats the drive as unplugged and runs in degraded mode (browsing works, file requests return `503`).
- Set Nginx to match the 2 GB upload limit: `client_max_body_size 2g;` and longer proxy timeouts.
- In prod, Swagger UI is off and logs go to `/var/log/sman11-library/app.log`.

Server checklist (router, drive, backups, restore test): [backend-phases.md §3](.md/backend-phases.md#3-server-configuration-no-app-code). Configuration reference: [backend-spec.md §6](.md/backend-spec.md#6-configuration).

---

## 7. Documentation

The design docs live in [`.md/`](.md/README.md):

| Doc | Answers |
|---|---|
| [backend-spec.md](.md/backend-spec.md) | **What** the API does: roles, endpoints, errors, rules, configuration. |
| [backend-architecture.md](.md/backend-architecture.md) | **How** it's built: modules, flows, security, trade-offs. |
| [backend-phases.md](.md/backend-phases.md) | **In what order** it was built: phases 0 to 8, all done. |
| `phase-N-*.md` | What each phase changed, and how to test it (including Postman guides). |
