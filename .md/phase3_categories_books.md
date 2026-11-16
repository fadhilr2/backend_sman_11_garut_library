# Implementation Plan - Phase 3: Categories and Books

## 1. Goal Description
Phase 3 delivers the first complete vertical content slice of the library system. It allows teachers and librarians to create categories, upload and manage books (PDFs, EPUBs, Office documents, covers) with deduplication, and allows anonymous students over Wi-Fi to browse, filter, search (via MySQL FULLTEXT / LIKE), stream inline via HTTP Range requests, and download materials.

---

## 2. User Review Required

> [!IMPORTANT]
> **1. SecurityConfig Matching Order**:
> `/api/books/mine` is a `GET` request requiring authentication. We must configure `.requestMatchers("/api/books/mine").authenticated()` **before** `.requestMatchers(HttpMethod.GET, "/api/books/**").permitAll()` in `SecurityConfig`, otherwise the wildcard match would permit unauthenticated calls to `/api/books/mine` and trigger a `NullPointerException` on the user principal.
>
> **2. Duplicate Detection Strategy**:
> Checksum duplicate checks (`checksum_sha256`) occur **after** streaming the upload to disk and computing the hash, but **before** committing the database transaction. If a book with the same checksum exists (`deleted_at IS NULL`), the newly uploaded file is immediately purged from disk and an HTTP `409 Conflict` (`DUPLICATE_FILE`) is returned.
>
> **3. Multipart JSON Upload Format**:
> The `POST /api/books` endpoint will accept `multipart/form-data` with:
> - `file` (`MultipartFile`, required)
> - `cover` (`MultipartFile`, optional)
> - `metadata` (`@RequestPart("metadata") BookMetadataRequest` or JSON string, required)

---

## 3. Open Questions
* None. All requirements strictly align with [backend-spec.md](file:///c:/Users/lenovo/Documents/GitHub/TPLM/backend_sman_11_garut_library/backend-spec.md) and [backend-architecture.md](file:///c:/Users/lenovo/Documents/GitHub/TPLM/backend_sman_11_garut_library/backend-architecture.md).

---

## 4. Proposed Architecture & Component Flow

```mermaid
flowchart TD
    subgraph Client
        Browser[Browser / Mobile]
    end

    subgraph Security Layer
        JwtFilter[JwtAuthFilter - sets auth if present]
        SecConfig[SecurityConfig - allows anonymous GET, guards write/admin]
    end

    subgraph Controllers
        BookCtrl[BookController /api/books]
        CatCtrl[CategoryController /api/categories]
        MetaCtrl[MetaController /api/meta]
        AdminCatCtrl[CategoryAdminController /api/admin/categories]
    end

    subgraph Service Layer
        BookSvc[BookService - ownership, visibility, duplicate check]
        CatSvc[CategoryService - tree hierarchy, delete guards]
    end

    subgraph Infrastructure
        Storage[FileStorageService & RangeFileResponder]
        DB[(MySQL 8 - Flyway Schema)]
    end

    Browser --> JwtFilter --> SecConfig
    SecConfig --> BookCtrl & CatCtrl & MetaCtrl & AdminCatCtrl
    BookCtrl --> BookSvc
    CatCtrl & AdminCatCtrl --> CatSvc
    BookSvc --> Storage
    BookSvc & CatSvc --> DB
```

---

## 5. Proposed Changes

### Component 1: Common Pagination

#### [NEW] `library/src/main/java/id/sch/sman11garut/library/common/PageResponse.java`
Standard paginated API response wrapper matching frontend expectations:
```java
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages,
    boolean last
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
            page.getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements(),
            page.getTotalPages(),
            page.isLast()
        );
    }
}
```

---

### Component 2: Categories Feature (`id.sch.sman11garut.library.category`)

#### [NEW] `CategoryType.java`
Enum: `BOOK`, `ARCHIVE`.

#### [NEW] `BookCategory.java`
Entity mapped to table `book_categories`:
- `id` (`INT UNSIGNED AUTO_INCREMENT`)
- `name` (`VARCHAR(100) UNIQUE NOT NULL`)
- `parentId` (`INT UNSIGNED NULL`) / self-referencing relationship

#### [NEW] `BookCategoryRepository.java`
- `Optional<BookCategory> findByName(String name)`
- `boolean existsByName(String name)`
- `boolean existsByNameAndIdNot(String name, Integer id)`
- `List<BookCategory> findAllByOrderByOrderByNameAsc()`
- `List<BookCategory> findByParentIsNullOrderByNameAsc()`
- `@Query(value = "SELECT COUNT(*) FROM books WHERE category_id = :categoryId AND deleted_at IS NULL", nativeQuery = true)`: Direct count check preventing circular dependency with `BookService`.

#### [NEW] `CategoryDto.java`, `CreateCategoryRequest.java`, `UpdateCategoryRequest.java`
- `CategoryDto(Integer id, String name, Integer parentId, List<CategoryDto> children)`
- Request DTOs with `@NotBlank` and `@Size(max = 100)`.

#### [NEW] `CategoryService.java`
- `listCategories(CategoryType type)`: Returns categories as a flat list or hierarchical tree.
- `createCategory(CreateCategoryRequest request)`: Validates unique name.
- `updateCategory(Integer id, UpdateCategoryRequest request)`: Updates name and parent (guards against cyclic nesting).
- `deleteCategory(Integer id)`: Verifies that no active books (`deleted_at IS NULL`) are assigned using `bookCategoryRepository.countActiveBooks(id)` before deleting (eliminates circular bean references).

#### [NEW] `CategoryController.java` (`GET /api/categories`)
- Public endpoint supporting `?type=BOOK|ARCHIVE` (defaults to `BOOK`).

#### [NEW] `CategoryAdminController.java` (`/api/admin/categories`)
- Protected by `@PreAuthorize("hasRole('LIBRARIAN')")`:
  - `POST /api/admin/categories`
  - `PUT /api/admin/categories/{id}`
  - `DELETE /api/admin/categories/{id}`

---

### Component 3: Metadata Filters (`id.sch.sman11garut.library.meta`)

#### [NEW] `MetaFiltersDto.java` & `MetaController.java` (`GET /api/meta/filters`)
Returns allowed dropdown options for search filters:
- `gradeLevels`: `["X", "XI", "XII", "ALL"]`
- `bookTypes`: `["EBOOK", "TEACHING_MODULE", "QUESTION_BANK", "SLIDES", "OTHER"]`
- `curricula`: `["K13", "Kurikulum Merdeka", "Umum"]`

---

### Component 4: Books Feature (`id.sch.sman11garut.library.book`)

#### [NEW] Enums
- `BookType`: `EBOOK`, `TEACHING_MODULE`, `QUESTION_BANK`, `SLIDES`, `OTHER`
- `GradeLevel`: `X`, `XI`, `XII`, `ALL`
- `Visibility`: `PUBLIC`, `STAFF`

#### [NEW] `Book.java`
Entity mapped to table `books`:
- `id` (`Long`)
- `title`, `author`, `publisher`
- `publicationYear`: `Short` matching MySQL `SMALLINT` for strict `ddl-auto: validate` compatibility, with overloaded `setPublicationYear(Integer)` setter.
- `description`, `bookType`, `gradeLevel`, `curriculum`, `visibility`
- `originalName`, `storedPath`, `mimeType`, `fileSize`
- `checksumSha256`: `columnDefinition = "CHAR(64)"`
- `coverPath`, `downloadCount` (default `0`)
- `category` (`@ManyToOne BookCategory`)
- `uploadedBy` (`@ManyToOne User`)
- `createdAt`, `updatedAt`, `deletedAt`

#### [NEW] `BookRepository.java` & `BookSpecifications.java`
- Spring Data JPA Repository extending `JpaSpecificationExecutor<Book>`.
- `boolean existsByChecksumSha256AndDeletedAtIsNull(String checksum)`: For duplicate detection.
- `boolean existsByCategoryIdAndDeletedAtIsNull(Integer categoryId)`: For category deletion safety check.
- `Page<Book> findByUploadedByIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId, Pageable pageable)`: For `/api/books/mine`.
- **Search Specification (`BookSpecifications.java`)**:
  - `deletedAt IS NULL`
  - Visibility rule: If anonymous/student, `visibility = Visibility.PUBLIC`. If staff (`TEACHER` or `LIBRARIAN`), shows both `PUBLIC` and `STAFF`.
  - Filter criteria: `categoryId`, `gradeLevel`, `bookType`.
  - Keyword search (`q`):
    - When `q.length() >= 3`: MySQL FULLTEXT search `MATCH(title, author, description) AGAINST (:q IN NATURAL LANGUAGE MODE)` using JPA native query.
    - When `q.length() < 3`: Fallback to `LIKE %:q%` across `title`, `author`, `description`.

#### [NEW] DTOs
- `BookSummaryDto`: Compact representation for search/list grids (id, title, author, bookType, gradeLevel, coverPath, downloadCount, visibility, categoryName).
- `BookDetailDto`: Full metadata representation including file size, mime type, publisher, publication year, uploadedBy info.
- `BookMetadataRequest`: Incoming JSON payload for book creation.
- `UpdateBookRequest`: Incoming payload for metadata updates.

#### [NEW] `BookService.java`
- `listBooks(BookSearchCriteria criteria, boolean isStaff, Pageable pageable)`: Returns `PageResponse<BookSummaryDto>`.
  - Uses `resolveNativePageable` with database column names (`created_at`, `download_count`, `title`) when executing native FULLTEXT queries to prevent SQL `Unknown column` syntax errors.
  - Uses `resolveJpaPageable` with entity property names (`createdAt`, `downloadCount`, `title`) when executing JPA criteria queries.
- `getBook(Long id, boolean isStaff)`: Returns `BookDetailDto`. Throws `404 Not Found` if book is `STAFF` and caller is anonymous, or if soft-deleted.
- `streamBookFile(Long id, boolean isStaff, HttpServletRequest request, HttpServletResponse response)`: Resolves file path and delegates to `RangeFileResponder.write(...)`.
- `downloadBookFile(Long id, boolean isStaff, HttpServletRequest request, HttpServletResponse response)`: Adds `Content-Disposition: attachment; filename="..."`, increments `downloadCount`, and streams file.
- `getBookCover(Long id, HttpServletRequest request, HttpServletResponse response)`: Streams cover image or serves fallback SVG placeholder.
- `createBook(MultipartFile file, MultipartFile cover, BookMetadataRequest metadata, UserPrincipal principal)`:
  - Validates and stores `file` via `FileStorageService.storeFile(..., StorageCategory.BOOK)`.
  - Checks duplicate checksum: if already exists, deletes the uploaded file and throws `AppException.conflict("DUPLICATE_FILE", "...")`.
  - If `cover` provided, stores via `FileStorageService.storeFile(..., StorageCategory.COVER)`.
  - Saves entity with compensation: if DB save throws an error, stored files are deleted immediately.
- `updateBook(Long id, UpdateBookRequest request, UserPrincipal principal)`: Enforces ownership (Teacher can only update own; Librarian can update any).
- `replaceBookFile(Long id, MultipartFile file, UserPrincipal principal)`: Stores new file, updates DB, deletes old file from disk. Enforces ownership.
- `deleteBook(Long id, UserPrincipal principal)`: Soft delete (`deletedAt = Instant.now()`). Enforces ownership.
- `restoreBook(Long id)`: Undoes soft delete (Librarian only).
- `listMyBooks(UserPrincipal principal, Pageable pageable)`: Lists uploads by current authenticated user.

#### [NEW] `BookController.java` (`/api/books/**`)
- Public / Teacher / Librarian routes:
  - `GET /api/books`
  - `GET /api/books/{id}`
  - `GET /api/books/{id}/file`
  - `GET /api/books/{id}/download`
  - `GET /api/books/{id}/cover`
  - `GET /api/books/mine` (`@AuthenticationPrincipal`)
  - `POST /api/books` (multipart)
  - `PUT /api/books/{id}`
  - `PUT /api/books/{id}/file`
  - `DELETE /api/books/{id}`

#### [NEW] `BookAdminController.java` (`/api/admin/books`)
- `POST /api/admin/books/{id}/restore` (`@PreAuthorize("hasRole('LIBRARIAN')")`)

---

### Component 5: Security Configuration Adjustment

#### [MODIFY] `library/src/main/java/id/sch/sman11garut/library/config/SecurityConfig.java`
Ensure `/api/books/mine` and `/api/archive/mine` are explicitly authenticated before general GET matches:
```diff
--- a/SecurityConfig.java
+++ b/SecurityConfig.java
@@ -67,6 +67,9 @@
+                // Authenticated user operations (Teacher + Librarian)
+                .requestMatchers("/api/auth/me", "/api/auth/change-password").authenticated()
+                .requestMatchers("/api/books/mine", "/api/archive/mine").authenticated()
+
                 // Public catalog browsing (anonymous students)
                 .requestMatchers(HttpMethod.GET, "/api/books/**").permitAll()
                 .requestMatchers(HttpMethod.GET, "/api/archive/**").permitAll()
```

---

## 6. Verification Plan

### Automated Verification
```powershell
mvn test-compile -f library/pom.xml
```

### Manual Testing Guide (Postman Format)

#### Environment Variables
Configure the following variables in your Postman Environment:
| Variable | Initial Value | Notes |
|---|---|---|
| `baseUrl` | `http://localhost:8081` | Dev backend server port |
| `adminToken` | *(empty)* | Populated by Request 3 Tests script |
| `bookId` | `1` | Populated by Request 5 Tests script |

---

#### Request 1: List Categories (Public)
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/categories?type=BOOK`
* **Headers**: *(None)*
* **Expected Response** (`200 OK`):
  ```json
  [
    {
      "id": 1,
      "name": "Matematika",
      "type": "BOOK",
      "parentId": null,
      "children": []
    }
  ]
  ```

---

#### Request 2: Get Meta Filter Options (Public)
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/meta/filters`
* **Headers**: *(None)*
* **Expected Response** (`200 OK`):
  ```json
  {
    "gradeLevels": ["X", "XI", "XII", "ALL"],
    "bookTypes": ["EBOOK", "TEACHING_MODULE", "QUESTION_BANK", "SLIDES", "OTHER"],
    "mediaTypes": ["IMAGE", "VIDEO", "DOCUMENT"],
    "curricula": ["K13", "Kurikulum Merdeka", "Umum"]
  }
  ```

---

#### Request 3: Admin Login (Obtain Token)
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
      var jsonData = pm.response.json();
      pm.environment.set("adminToken", jsonData.token);
  });
  ```

---

#### Request 4: Create Book Category (Librarian Only)
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/admin/categories`
* **Headers**:
  * `Content-Type: application/json`
  * `Authorization: Bearer {{adminToken}}`
* **Body** (`raw` - JSON):
  ```json
  {
    "name": "Koleksi Khusus",
    "type": "BOOK"
  }
  ```
* **Expected Response** (`201 Created`):
  ```json
  {
    "id": 13,
    "name": "Koleksi Khusus",
    "type": "BOOK",
    "parentId": null,
    "children": []
  }
  ```

---

#### Request 5: Upload Book (Multipart Form-Data)
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/books`
* **Headers**:
  * `Authorization: Bearer {{adminToken}}`
  *(Note: Do not manually set `Content-Type: multipart/form-data`; let Postman auto-generate the boundary).*
* **Body** (`form-data`):
  | Key | Type | Value / File | Content-Type |
  |---|---|---|---|
  | `file` | `File` | *(Select a sample `.pdf` file)* | `application/pdf` |
  | `cover` | `File` | *(Optional, select `.jpg` image)* | `image/jpeg` |
  | `metadata` | `Text` | `{"title":"Buku Matematika SMA","author":"Drs. Suryono","publisher":"Erlangga","publicationYear":2024,"bookType":"EBOOK","gradeLevel":"X","curriculum":"Kurikulum Merdeka","visibility":"PUBLIC"}` | `application/json` |
* **Postman Tests Script**:
  ```javascript
  pm.test("Status code is 201", function () {
      pm.response.to.have.status(201);
      var jsonData = pm.response.json();
      pm.environment.set("bookId", jsonData.id);
  });
  ```
* **Expected Response** (`201 Created`):
  ```json
  {
    "id": 1,
    "title": "Buku Matematika SMA",
    "author": "Drs. Suryono",
    "publisher": "Erlangga",
    "publicationYear": 2024,
    "bookType": "EBOOK",
    "gradeLevel": "X",
    "curriculum": "Kurikulum Merdeka",
    "visibility": "PUBLIC",
    "originalName": "sample.pdf",
    "mimeType": "application/pdf",
    "fileSize": 1048576,
    "hasCover": true,
    "downloadCount": 0
  }
  ```

---

#### Request 6: Test Duplicate Rejection Check
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/books`
* **Headers & Body**: Identical to Request 5 (uploading the exact same PDF file).
* **Expected Response** (`409 Conflict`):
  ```json
  {
    "status": 409,
    "code": "DUPLICATE_FILE",
    "message": "A book with the same file content already exists"
  }
  ```

---

#### Request 7: Search Books (Full-Text Search & Filters)
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books?q=matematika&grade=X&sort=newest`
* **Headers**: *(None - Public)*
* **Expected Response** (`200 OK`):
  ```json
  {
    "content": [
      {
        "id": 1,
        "title": "Buku Matematika SMA",
        "author": "Drs. Suryono",
        "bookType": "EBOOK",
        "gradeLevel": "X",
        "visibility": "PUBLIC",
        "hasCover": true,
        "downloadCount": 0
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  }
  ```

---

#### Request 8: Get Book Detail
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{{bookId}}`
* **Headers**: *(None for PUBLIC books; `Authorization: Bearer {{adminToken}}` required for STAFF books)*
* **Expected Response** (`200 OK`):
  ```json
  {
    "id": 1,
    "title": "Buku Matematika SMA",
    "author": "Drs. Suryono",
    "publisher": "Erlangga",
    "publicationYear": 2024,
    "bookType": "EBOOK",
    "gradeLevel": "X",
    "curriculum": "Kurikulum Merdeka",
    "visibility": "PUBLIC",
    "originalName": "sample.pdf",
    "mimeType": "application/pdf"
  }
  ```

---

#### Request 9: HTTP Range File Streaming (Inline Preview)
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{{bookId}}/file`
* **Headers**:
  * `Range: bytes=0-511`
* **Expected Response** (`206 Partial Content`):
  * Headers: `Content-Range: bytes 0-511/<total_size>`, `Accept-Ranges: bytes`
  * Body: First 512 bytes of binary content.

---

#### Request 10: Download Book File
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{{bookId}}/download`
* **Headers**: *(None)*
* **Expected Response** (`200 OK`):
  * Headers: `Content-Disposition: attachment; filename="sample.pdf"`
  * Note: Increments `downloadCount` by 1.

---

#### Request 11: Get Book Cover
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/{{bookId}}/cover`
* **Headers**: *(None)*
* **Expected Response** (`200 OK`):
  * Returns uploaded JPEG image or fallback SVG placeholder (`Content-Type: image/svg+xml`).

---

#### Request 12: List My Uploaded Books (Staff Only)
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/books/mine`
* **Headers**:
  * `Authorization: Bearer {{adminToken}}`
* **Expected Response** (`200 OK`): Paginated list of books uploaded by current user.

---

#### Request 13: Soft Delete Book
* **Method**: `DELETE`
* **URL**: `{{baseUrl}}/api/books/{{bookId}}`
* **Headers**:
  * `Authorization: Bearer {{adminToken}}`
* **Expected Response** (`204 No Content`):
  * Calling Request 8 (`GET {{baseUrl}}/api/books/{{bookId}}`) will now return `404 Not Found`.

---

#### Request 14: Restore Soft-Deleted Book (Librarian Only)
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/admin/books/{{bookId}}/restore`
* **Headers**:
  * `Authorization: Bearer {{adminToken}}`
* **Expected Response** (`200 OK`):
  * Book is un-deleted; Request 8 will now succeed again with `200 OK`.
