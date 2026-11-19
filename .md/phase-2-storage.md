# Phase 2: Storage Foundation

> **Status:** Done · **Commit:** `f6b0197` · **Roadmap:** [Phase 2](backend-phases.md#phase-2-storage-foundation) · **Spec:** [Storage and duplicates](backend-spec.md#52-storage-and-duplicates)

## 1. Summary

The file layer shared by Books and Archive. It has no endpoints of its own.
- **Streaming uploads**: files up to 2 GB go straight from the request to a temp file, never fully into memory.
- **Checksums**: SHA-256 computed while streaming, used for duplicate detection.
- **Type checks**: content sniffed with Apache Tika against per-category whitelists.
- **Safe paths**: stored paths can never escape the storage root.
- **Range streaming**: one shared responder for `206 Partial Content`, used for PDF reading and video seeking.
- **Free-space guard**: uploads are refused when the drive is nearly full.

**Done when:** tests show a file saves, duplicates are detected, path traversal is blocked, and a Range request returns 206 with the right bytes.

---

## 2. Decisions

- **Generated file names only.** Files are stored as `<category>/yyyy/MM/<uuid>.<ext>`; the original name stays in the DB. User input never becomes a path.
- **Temp file on the same volume, then atomic move.** A crash mid-upload leaves only a temp file, never a half-written final file.
- **File first, DB second, compensate on failure.** Services store the file before the DB commit and delete it if the save fails, so DB rows never point to missing files.
- **Sniff, don't trust.** The detected type must be on the whitelist *and* match the extension; executables are always rejected.
- **Threshold**: `app.storage.min-free-space` (default 5 GB) → `503 STORAGE_FULL`.

---

## 3. Design

```
  Upload stream
        |
        v
  [DiskSpaceGuard]  ---> free space < threshold? 503 STORAGE_FULL
        |
        v
  [FileStorageService.storeFile]
        |-- stream to <root>/temp/<uuid>.tmp, computing SHA-256
        |-- [FileTypeDetector] Tika sniff + whitelist + extension match
        '-- atomic move to <root>/<category>/yyyy/MM/<uuid>.<ext>
        |
        v
  StoredFileInfo(relativePath, originalFilename, mimeType, sizeBytes, checksumSha256)
```

On startup `StorageDirectories` creates `temp`, `books`, `archive` and `thumbnails` under the root.

---

## 4. Changes

| File | Change | Notes |
|---|---|---|
| `config/StorageProperties.java` | New | `root`, `importDir`, `minFreeSpace` (5 GB). Dev root `./data/storage`, prod `/mnt/library/files`. |
| `storage/StorageDirectories.java` | New | Creates the storage sub-folders. |
| `storage/StorageCategory.java` | New | `BOOK`, `COVER` (both under `books/`), `ARCHIVE`, `THUMBNAIL`. |
| `storage/FileTypeDetector.java` | New | Tika sniffing. Books: pdf, epub, docx, pptx, xlsx. Covers: jpeg, png. Archive: images (jpeg, png, webp, gif), videos (mp4, webm, mkv), documents. |
| `storage/DiskSpaceGuard.java` | New | `ensureSpaceAvailable()`, `isStorageMountedAndWritable()` (for the Phase 7 health check). |
| `storage/FileStorageService.java` | New | `storeFile`, `resolveSafePath`, `deleteStoredFile`, `loadAsResource`. |
| `storage/StoredFileInfo.java` | New | Result record of `storeFile`. |
| `storage/RangeFileResponder.java` | New | `200` full file, `206` single range, `416` invalid range; sets `Accept-Ranges`, `ETag`, `Last-Modified`, `Content-Type`. |

---

## 5. API

None. These classes are used by the Books (Phase 3) and Archive (Phase 4) endpoints.

Errors: `INVALID_FILE_TYPE`, `INVALID_PATH`, `STORAGE_FULL`, `STORAGE_WRITE_FAILED`. See the [error catalog](backend-spec.md#4-errors).

---

## 6. Testing

### 6.1 Automated
```powershell
./mvnw test -Dtest="FileStorageServiceTest,DiskSpaceGuardTest,RangeFileResponderTest,StorageDirectoriesTest"
```
| Test | Covers |
|---|---|
| `FileStorageServiceTest` | Store + stable checksum, blocked extensions and mismatched content (type detection), path traversal (`../`, absolute) → `INVALID_PATH`, delete and load. Temp folder is empty afterwards. |
| `DiskSpaceGuardTest` | Below threshold → `STORAGE_FULL`; mounted/writable check. |
| `RangeFileResponderTest` | `206` single range, `200` full file, `416` past the end. |
| `StorageDirectoriesTest` | Sub-folders created. |

### 6.2 Manual (Postman)

These requests exercise storage through the book endpoints (built in Phase 3). Path traversal and disk-full can't be triggered through the API, so they are covered only by the automated tests above.

#### Setup
| Variable | Initial Value | Notes |
|---|---|---|
| `baseUrl` | `http://localhost:8081` | Dev backend server port |
| `adminToken` | *(empty)* | Set by Request 1 |
| `bookId` | *(empty)* | Set by Request 2 |

After the app starts, `./data/storage` should contain `temp`, `books`, `archive` and `thumbnails`.

---

#### Request 1: Admin Login
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/auth/login`
* **Headers**:
  * `Content-Type: application/json`
* **Body** (`raw` - JSON):
  ```json
  {
    "username": "admin",
    "password": "admin123"
  }
  ```
* **Postman Tests Script**:
  ```javascript
  pm.test("Status code is 200", function () {
      pm.response.to.have.status(200);
      pm.environment.set("adminToken", pm.response.json().token);
  });
  ```

---

#### Request 2: Store a File (Streaming + Checksum + Atomic Move)
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/books`
* **Headers**:
  * `Authorization: Bearer {{adminToken}}`
  * *(Don't set `Content-Type` manually; Postman adds the multipart boundary.)*
* **Body** (`form-data`):
  | Key | Type | Value / File | Content-Type |
  |---|---|---|---|
  | `file` | `File` | *(a sample `.pdf`)* | `application/pdf` |
  | `metadata` | `Text` | `{"title":"Storage Test PDF"}` | `application/json` |
* **Postman Tests Script**:
  ```javascript
  pm.test("Stored as PDF", function () {
      pm.response.to.have.status(201);
      var jsonData = pm.response.json();
      pm.environment.set("bookId", jsonData.id);
      pm.expect(jsonData.mimeType).to.eql("application/pdf");
  });
  ```
* **Expected Response** (`201 Created`): book JSON with `"mimeType": "application/pdf"` and the real `fileSize`.
  * On disk: a new `./data/storage/books/<yyyy>/<MM>/<uuid>.pdf`, and `./data/storage/temp` is empty again.

---

#### Request 3: Duplicate Detection (Same SHA-256)
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/books`
* **Headers & Body**: identical to Request 2 (same PDF).
* **Expected Response** (`409 Conflict`):
  ```json
  {
    "status": 409,
    "code": "DUPLICATE_FILE",
    "message": "A book with the same file content already exists (it may be in the trash)"
  }
  ```
  * On disk: no second copy left in `books/` or `temp/`.

---

#### Request 4: MIME Sniffing Rejects a Disguised File
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/books`
* **Headers**:
  * `Authorization: Bearer {{adminToken}}`
* **Body** (`form-data`):
  | Key | Type | Value / File | Content-Type |
  |---|---|---|---|
  | `file` | `File` | *(a `.txt` or `.exe` renamed to `fake.pdf`)* | `application/pdf` |
  | `metadata` | `Text` | `{"title":"Fake PDF"}` | `application/json` |
* **Expected Response** (`400 Bad Request`):
  ```json
  {
    "status": 400,
    "code": "INVALID_FILE_TYPE",
    "message": "File type is not allowed"
  }
  ```
  *(Depending on what Tika detects, the message may be "File extension does not match its content" or "This file type is not allowed".)*

---

#### Request 5: Full File (No Range)
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{{bookId}}/file`
* **Headers**: *(None)*
* **Expected Response** (`200 OK`): headers `Accept-Ranges: bytes`, `Content-Type: application/pdf`, `Content-Length`, `ETag`, `Last-Modified`; body is the whole PDF.

---

#### Request 6: Range Request (Partial Content)
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{{bookId}}/file`
* **Headers**:
  * `Range: bytes=0-1023`
* **Postman Tests Script**:
  ```javascript
  pm.test("Returns the first 1024 bytes", function () {
      pm.response.to.have.status(206);
      pm.expect(pm.response.headers.get("Content-Range")).to.match(/^bytes 0-1023\/\d+$/);
      pm.expect(pm.response.headers.get("Content-Length")).to.eql("1024");
  });
  ```
* **Expected Response** (`206 Partial Content`): `Content-Range: bytes 0-1023/<total>`, `Accept-Ranges: bytes`; body starts with `%PDF`.

---

#### Request 7: Unsatisfiable Range
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{{bookId}}/file`
* **Headers**:
  * `Range: bytes=999999999-`
* **Expected Response** (`416 Range Not Satisfiable`): `Content-Range: bytes */<total>`.

---

## 7. Issues and fixes

None in this phase. (The WebM/MKV detection fix is recorded in [Phase 4](phase-4-archive.md#7-issues-and-fixes).)
