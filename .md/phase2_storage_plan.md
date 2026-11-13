# Implementation Plan - Phase 2: Storage Foundation

## 1. Overview
In Phase 1, you successfully verified authentication, user administration, and password hashing. 

According to `backend-phases.md` and `backend-spec.md`, the next phase is **Phase 2: Storage Foundation**. This phase builds the core physical file management and streaming layer that will be shared by both the **Books** and **Archive** modules.

---

## 2. Architecture & Design Principles

```
  Multipart Upload
         |
         v
  [DiskSpaceGuard]  ---> (Checks >= 5GB free; rejects with 507/503 if full)
         |
         v
  [FileStorageService]
         |-- Stream straight to disk (<root>/temp/<uuid>.tmp)
         |-- Incrementally compute SHA-256 checksum
         |-- [FileTypeDetector] (Apache Tika MIME sniffing + whitelist check)
         '-- Atomic move to final path: <root>/<subfolder>/yyyy/mm/<uuid>.<ext>
```

* **Zero Memory Buffering**: Large videos (up to 2GB) and PDFs are streamed directly from the HTTP request to a temporary file on the storage disk.
* **Path Traversal Protection**: Relative paths stored in DB (e.g. `books/2026/11/abc.pdf`) are resolved strictly under `app.storage.root`. Any `..` traversal attempt is rejected.
* **Range Streaming**: The shared `RangeFileResponder` answers HTTP `Range` requests with `206 Partial Content`, enabling video scrubbing/seeking and in-browser PDF streaming.
* **Compensation Cleanup**: File operations occur before database commits; if a database transaction fails, `FileStorageService.deleteStoredFile()` cleans up the orphaned file.

---

## 3. Proposed Components & Implementation Steps

### Step 1: Storage Properties & Directory Bootstrap
* Use [StorageProperties.java](file:///c:/Users/lenovo/Documents/GitHub/TPLM/backend_sman_11_garut_library/library/src\main\java\id\sch\sman11garut\library\config\StorageProperties.java):
  * `root` (e.g., `./data/storage` in dev, `/mnt/library/files` in prod).
  * `importDir` (`./data/import` or `/mnt/library/import`).
  * `minFreeSpace` (default `5GB`).
* Automatically create directories on startup:
  * `<root>/temp`
  * `<root>/books`
  * `<root>/archive`
  * `<root>/thumbnails`

---

### Step 2: `FileTypeDetector` (`storage/FileTypeDetector.java`)
* Uses Apache Tika (`Tika` / `Detector`) to inspect file magic bytes directly from disk:
  * **Books Whitelist**: `application/pdf`, `application/epub+zip`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document` (docx), `application/vnd.openxmlformats-officedocument.presentationml.presentation` (pptx), `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` (xlsx).
  * **Covers Whitelist**: `image/jpeg`, `image/png`.
  * **Archive Whitelist**:
    * Images: `image/jpeg`, `image/png`, `image/webp`, `image/gif`.
    * Videos: `video/mp4`, `video/webm`.
    * Documents: same document types as books.
  * Rejects executables (`.exe`, `.sh`, `.bat`, `.jar`, `.js`, etc.) and mismatches between file extension and detected content type.

---

### Step 3: `DiskSpaceGuard` (`storage/DiskSpaceGuard.java`)
* Checks free space using `Files.getFileStore(root).getUsableSpace()`.
* Throws `AppException.unavailable("STORAGE_FULL", "Insufficient storage space...")` if free space < `minFreeSpace`.
* Provides `isStorageMountedAndWritable()` for `/actuator/health` and `/api/health`.

---

### Step 4: `FileStorageService` (`storage/FileStorageService.java`)
* Methods:
  1. `storeFile(InputStream inputStream, String originalFilename, StorageCategory category)`:
     * Streams input stream to `<root>/temp/<uuid>.tmp`.
     * Calculates SHA-256 hash using `DigestInputStream` during stream copy.
     * Sniffs MIME type via `FileTypeDetector`.
     * Generates target relative path: `${category}/yyyy/MM/${uuid}.${extension}`.
     * Atomically moves temp file to target directory (`Files.move(..., ATOMIC_MOVE)`).
     * Returns `StoredFileInfo(relativePath, originalFilename, mimeType, sizeBytes, checksumSha256)`.
  2. `resolveSafePath(String relativePath)`:
     * Normalizes and verifies that `resolvedPath.startsWith(storageRoot)`.
     * Throws `AppException.badRequest("INVALID_PATH", "Path traversal attempt detected")` if outside root.
  3. `deleteStoredFile(String relativePath)`:
     * Deletes physical file if present (used for compensation on DB rollback).
  4. `loadAsResource(String relativePath)`:
     * Returns `org.springframework.core.io.Resource` (`UrlResource` / `FileSystemResource`).

---

### Step 5: `RangeFileResponder` (`storage/RangeFileResponder.java`)
* Shared utility for streaming media and documents:
  * Accepts `HttpServletRequest`, `HttpServletResponse`, `Resource`, and `contentType`.
  * Checks HTTP `Range` header (`bytes=start-end`):
    * If absent: returns `200 OK` with `Accept-Ranges: bytes`, `Content-Length`, `ETag`, and `Last-Modified`.
    * If present: computes partial byte slice, sets headers `Content-Range: bytes start-end/total`, returns `206 Partial Content`.
    * If invalid / out-of-bounds: returns `416 Range Not Satisfiable`.

---

## 4. Manual Testing Guide (For You to Run)
Once implemented, you can verify Phase 2:
1. **Startup Directory Creation**: Check that the storage root directory and subfolders (`temp`, `books`, `archive`, `thumbnails`) are created.
2. **File Storage & Deduplication**: Run unit/slice test or controller test to upload a test file, verify it moves to `<root>/books/yyyy/MM/<uuid>.ext` and its SHA-256 is correctly calculated.
3. **MIME Sniffing & Type Rejection**: Test uploading an executable or mismatched file (e.g. `.exe` disguised as `.pdf`) and verify it is rejected.
4. **Range Streaming Test**: Request a stored file using `curl -H "Range: bytes=0-1023"` and verify response is `206 Partial Content` with `Content-Range` header.
