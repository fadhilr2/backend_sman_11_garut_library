# Phase 8: Hardening and Handover Prep

> **Status:** Done · **Commit:** `c885e44` · **Roadmap:** [Phase 8](backend-phases.md#phase-8-hardening-and-handover-prep) · **Spec:** [Endpoints](backend-spec.md#3-endpoints)

## 1. Summary

Makes the backend safe to hand over: tested end to end, documented for the front-end team, and fixed where the code review found problems.
- **Integration tests**: the whole app against a real MySQL 8, started in Docker by Testcontainers. 24 tests across auth, books, archive, import, health and OpenAPI.
- **OpenAPI**: tags, summaries, JWT "Authorize", shared error responses, binary file responses, multipart uploads that work in Swagger UI. A test fails the build if the documented endpoints differ from the spec.
- **Security review**: RFC 6266 download names, login timing, throttling window, frame options, API docs off in prod.
- **Temp-file sweep**: hourly removal of leftovers from interrupted uploads.
- **Fixes found in review**: DB connections held during streaming; `replaceBookFile` could lose or orphan files; covers and thumbnails of STAFF items were visible to anonymous users; PNG covers sent as JPEG; ffmpeg without a timeout; huge images could exhaust memory; request errors said only "Request failed".

**Done when:** tests pass on a clean checkout and the OpenAPI spec matches the real endpoints.

---

## 2. Decisions

- **Docker is a dev-laptop prerequisite only.** Testcontainers needs Docker to start the test database. Without Docker the integration tests are *skipped* (`@Testcontainers(disabledWithoutDocker = true)`), so the build still passes. The server never needs Docker.
- **One shared test context and database.** All `*IntegrationTest` classes extend `support/IntegrationTestBase` with the same configuration, so MySQL starts once per run. Tests therefore use unique names and file contents instead of assuming an empty database.
- **Integration tests are plain surefire tests** (`*IntegrationTest`, run by `mvnw test`). No separate failsafe phase to learn.
- **Stream outside the transaction.** Services now only *look up* a file (`openFile`, `openDownload`, `openCover`, `openThumbnail` → `ServedFile`) inside a short read-only transaction; the controller streams afterwards. Before, a 1 GB video held a pooled DB connection for the whole download. This also removes HTTP types from the services, as the architecture's layering rule asks.
- **File clean-up follows the transaction outcome.** `replaceBookFile` registers an `afterCompletion` hook: committed → delete the old file; rolled back → delete the new one. Before, the old file was deleted before the commit, and a failed save left the new file orphaned.
- **Hidden means hidden.** Covers and thumbnails follow the item's visibility; a STAFF photo's thumbnail is the photo, so it must not be public.
- **`SAMEORIGIN`, not `DENY`.** The front-end may embed the PDF reader in an `<iframe>`; other sites still can't frame the app.
- **API docs are dev-only.** Nginx doesn't expose them anyway, and the prod profile now turns them off.
- **Spec follows the code for `fieldErrors`.** The spec said validation errors carry `fields`; the API has always sent `fieldErrors`. The spec was corrected, since the front-end sees the real body.
- **Not in this phase** (agreed): untracking the Eclipse `.metadata/` folder, the duplicate `spring-boot-starter-web`, `TestLibraryApplication`.

---

## 3. Design

```mermaid
sequenceDiagram
    autonumber
    actor User as Browser
    participant Ctrl as BookController
    participant Svc as BookService (@Transactional readOnly)
    participant DB as MySQL
    participant Resp as RangeFileResponder

    User->>Ctrl: GET /api/books/{id}/download
    Ctrl->>Svc: openDownload(id, isStaff)
    Svc->>DB: find book, visibility check
    Svc->>Svc: publish DownloadEvent (async counter)
    Svc-->>Ctrl: ServedFile(resource, type, name)
    Note over Svc,DB: transaction ends, connection returned
    Ctrl->>Resp: serveAttachment(ServedFile)
    Resp-->>User: 200/206 + Content-Disposition (RFC 6266)
```

---

## 4. Changes

### Streaming and files
| File | Change | Notes |
|---|---|---|
| `storage/ServedFile.java` | New | `(resource, contentType, filename)`; `svg(...)` placeholder; image type from extension. |
| `storage/RangeFileResponder.java` | Modified | `serve`, `serveAttachment` (RFC 6266 name), in-memory bodies, range end clamped to the file end; client disconnects logged at debug. |
| `book/BookService.java`, `book/BookController.java` | Modified | `openFile`/`openDownload`/`openCover`; covers respect STAFF and their real type; `replaceBookFile` clean-up via `afterCompletion`. |
| `archive/ArchiveService.java`, `archive/ArchiveController.java` | Modified | `openFile`/`openDownload`/`openThumbnail`; thumbnails respect STAFF. |

### Security
| File | Change | Notes |
|---|---|---|
| `auth/AuthService.java` | Modified | Dummy BCrypt check for unknown usernames. |
| `auth/LoginThrottlingService.java` | Modified | 15-minute failure window; stale entries removed. |
| `config/SecurityConfig.java` | Modified | `frameOptions(sameOrigin())`. |
| `application-prod.yml` | Modified | springdoc api-docs and swagger-ui disabled. |

### Sweep and robustness
| File | Change | Notes |
|---|---|---|
| `storage/TempFileSweeper.java` | New | Hourly; deletes `temp/` files older than `temp-max-age`; skipped when unmounted. |
| `config/StorageProperties.java` | Modified | `tempMaxAge` (default `24h`). |
| `config/AsyncConfig.java` | Modified | `@EnableScheduling`. |
| `media/MediaProcessor.java` | Modified | 60 s process timeout (output to file/discard), ffmpeg check cached, header-only size read, no thumbnail over 50 MP. |
| `common/GlobalExceptionHandler.java` | Modified | Keeps Spring's reason for request errors. |

### OpenAPI
| File | Change | Notes |
|---|---|---|
| `config/OpenApiConfig.java` | New | Info, `bearerAuth`, server `/`, `ApiError` schema + default error responses. |
| All `*Controller.java` | Modified | `@Tag`, `@Operation`, `@ParameterObject`, binary responses, multipart `metadata` as JSON. |

### Tests
| File | Change | Notes |
|---|---|---|
| `TestcontainersConfiguration.java` | Modified | `mysql:8.0` instead of `latest`; public. |
| `support/IntegrationTestBase.java` | New | Shared setup and helpers. |
| `LibraryApplicationTests.java` | Modified | Extends the base (skipped without Docker instead of failing). |
| `*IntegrationTest.java` (6 classes) | New | See §6.1. |
| `storage/TempFileSweeperTest`, `auth/LoginThrottlingServiceTest` | New | Unit tests. |
| `storage/RangeFileResponderTest` | Modified | Clamped range, `Content-Disposition` encoding, placeholder body. |

---

## 5. API

No endpoints added or removed. Behaviour changes:

| Endpoint | Change |
|---|---|
| `GET /api/books/{id}/cover` | `404` for a STAFF book requested anonymously; `Content-Type` is the real image type. |
| `GET /api/archive/{id}/thumbnail` | `404` for a STAFF item requested anonymously. |
| `GET .../download` | `Content-Disposition` with `filename*=UTF-8''...`. |
| `GET .../file` | A `Range` end past the file end now returns `206` up to the last byte instead of `416`. |
| All | `X-Frame-Options: SAMEORIGIN`; request errors carry Spring's reason in `message`. |

---

## 6. Testing

### 6.0 Prerequisite: Docker Desktop (once, dev laptop only)

Testcontainers asks Docker for a fresh MySQL 8 at the start of the run and removes it afterwards, so the tests never touch the dev database `library`.

1. PowerShell **as Administrator**: `wsl --install`, then reboot (Docker on Windows Home runs on WSL 2).
2. Install Docker Desktop from docker.com with the default "Use WSL 2" option.
3. Start Docker Desktop and wait for "Engine running" (no account needed).
4. Check: `docker run hello-world` prints "Hello from Docker!".

The first test run downloads the `mysql:8.0` image (~600 MB) once; afterwards MySQL starts in ~10–20 s per run. Quit Docker Desktop when not testing; the integration tests are then skipped.

### 6.1 Automated
```powershell
.\mvnw.cmd clean test
```
| Test | Covers |
|---|---|
| `LibraryApplicationTests` | App starts on MySQL 8 with the Flyway schema. |
| `AuthIntegrationTest` | Login, same answer for wrong password / unknown user, lock after 5 failures, deactivated token rejected, 401 vs 403, frame and nosniff headers, request error reason. |
| `BookIntegrationTest` | Upload → FULLTEXT and `LIKE` search → Range `206` → async download count → encoded `Content-Disposition` → duplicate `409` incl. trash → ownership → STAFF book, file and cover `404` anonymously, PNG cover type → replace file (new served, old deleted) → soft delete/restore → grade filter. |
| `ArchiveIntegrationTest` | Default STAFF, thumbnail + dimensions after commit, STAFF thumbnail hidden, media-type and event-date filters. |
| `ImportIntegrationTest` | CSV import: succeeded / skipped (duplicate) / failed (missing file), auto-created category. |
| `HealthIntegrationTest` | UP → DEGRADED without the marker → `503` file request, placeholder cover → recovery; storage report roles. |
| `OpenApiIntegrationTest` | Documented endpoints equal spec §3 exactly; `bearerAuth`; `ApiError` responses; `metadata` part as JSON. |
| Unit tests | Storage, range responder, temp sweep, throttling window, health mapping, CSV reader and row mapper. |

> **Result:** without Docker: `BUILD SUCCESS`, 43 unit tests passed, 24 integration tests skipped. With Docker: not run yet.

### 6.2 Manual (Postman / browser)

#### Setup
| Variable | Initial Value | Notes |
|---|---|---|
| `baseUrl` | `http://localhost:8081` | Dev backend server port |
| `adminToken` | *(empty)* | From `POST /api/auth/login` (admin / admin123) |
| `staffBookId` | *(empty)* | A book uploaded with `"visibility":"STAFF"` and a cover |

---

#### Request 1: Swagger UI
Open `{{baseUrl}}/swagger-ui.html` in a browser.
* **Expected**: endpoints grouped by tag (Books, Archive, Auth, Admin: ...), each with a summary. "Authorize" accepts the admin token. "Try it out" on `POST /api/books` with a PDF and `{"title":"Swagger Test"}` returns `201`.

---

#### Request 2: Download Name with Non-ASCII Characters
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{id}/download` for a book uploaded as `Matematika – Kelas X.pdf`
* **Expected Response** (`200 OK`): header `Content-Disposition: attachment; filename*=UTF-8''Matematika%20%E2%80%93%20Kelas%20X.pdf` (Spring may also add a plain `filename=` fallback). A browser saves it as `Matematika – Kelas X.pdf`.

---

#### Request 3: STAFF Cover Is Hidden
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{{staffBookId}}/cover`
* **Headers**: *(None)*
* **Expected Response** (`404 Not Found`). With `Authorization: Bearer {{adminToken}}`: `200` with the cover's real `Content-Type`.

---

#### Request 4: Clear Error for a Missing Part
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/books`
* **Headers**: `Authorization: Bearer {{adminToken}}`
* **Body** (`form-data`): only `metadata` = `{"title":"No file"}` (`application/json`), no `file`.
* **Expected Response** (`400 Bad Request`):
  ```json
  {
    "status": 400,
    "code": "BAD_REQUEST",
    "message": "Required part 'file' is not present."
  }
  ```

---

#### Request 5: Temp Sweep
Put a file in `./data/storage/temp`, set its modified date more than 24 hours back (PowerShell: `(Get-Item .\data\storage\temp\old.tmp).LastWriteTime = (Get-Date).AddDays(-2)`), and wait for the next full hour.
* **Expected**: the file is gone and the log shows `Temp sweep removed 1 stale file(s)`.

---

## 7. Issues and fixes

| Issue | Cause | Fix |
|---|---|---|
| `LibraryApplicationTests` had failed since Phase 0. | No Docker on the dev laptop; the test required it. | Skipped without Docker; runs once Docker Desktop is installed (§6.0). |
| Spec said `fields` for validation errors. | The code has always sent `fieldErrors`. | Spec corrected. |
