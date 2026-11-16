# Implementation Plan - Phase 1: Authentication & User Management

## 1. Overview
In accordance with `backend-architecture.md` and `backend-spec.md`, Phase 1 implements user management and stateless JWT authentication.
* **Anonymous Access**: Students browse/read public content without accounts.
* **Staff Access**: `TEACHER` and `LIBRARIAN` authenticate via `POST /api/auth/login` and receive a JWT (8-hour expiry).
* **Role Enforcement**: Method-level security (`@PreAuthorize`) protects write and admin endpoints. Public and read endpoints remain accessible to all callers.

---

## 2. Proposed Changes & Components

### Step 1: Update Maven Dependencies (`pom.xml`)
Add missing JJWT runtime implementations required by `jjwt-api 0.13.0`:
* `io.jsonwebtoken:jjwt-impl` (runtime)
* `io.jsonwebtoken:jjwt-jackson` (runtime)

---

### Step 2: Configuration & Properties
* **`JwtProperties.java`** in `config/`:
  * Bound to `app.jwt` (`secret`, `expiryHours = 8`).
* Update `LibraryApplication.java` to enable configuration property binding (`StorageProperties`, `JwtProperties`).

---

### Step 3: Security & JWT Infrastructure
* **`SecurityConfig.java`**:
  * Expose `PasswordEncoder` bean (`BCryptPasswordEncoder`).
  * Enable `@EnableMethodSecurity`.
  * Configure stateless `SecurityFilterChain`:
    * Public: `/api/auth/login`, `/error`, `/actuator/health`, `/api/health`, and public GET routes (`/api/books/**`, `/api/archive/**`, `/api/categories/**`).
    * Authenticated: `/api/auth/me`, `/api/auth/change-password`.
    * Admin-only (`ROLE_LIBRARIAN`): `/api/admin/**`.
    * Install `JwtAuthFilter` before `UsernamePasswordAuthenticationFilter`.
  * **Critical Fix**: Explicitly allow `/error` in `permitAll()` so Spring Boot's error dispatcher does not convert 400/404/405 errors into 401 Unauthorized.
* **`JwtService.java`** in `auth/`:
  * Generate signed HMAC-SHA256 tokens with claims: `sub` (username), `userId`, `role`, `fullName`.
  * Validate token signature and expiration, extracting username.
* **`JwtAuthFilter.java`** in `config/`:
  * `OncePerRequestFilter` inspecting `Authorization: Bearer <token>`.
  * Validates token and populates `SecurityContextHolder` with `UsernamePasswordAuthenticationToken`.
  * **Critical Design Rule**: Never blocks anonymous requests. If token is absent or invalid, simply lets the chain continue so anonymous traffic passes through.
* **`CustomUserDetailsService.java`** in `auth/`:
  * Loads active users via `UserRepository.findByUsernameAndActiveTrue(username)` and maps to Spring Security `UserDetails`.

---

### Step 4: First Admin Bootstrapping
* **`AdminUserSeeder.java`** in `user/`:
  * `CommandLineRunner` checking `userRepository.existsByRole(Role.LIBRARIAN)`.
  * If no librarian exists, reads `ADMIN_USERNAME` and `ADMIN_PASSWORD` (with safe dev fallbacks `admin` / `admin123`), hashes the password with BCrypt, and seeds the initial `LIBRARIAN` user.

---

### Step 5: Auth Feature (`id.sch.sman11garut.library.auth`)
* **DTOs**:
  * `LoginRequest`: `username`, `password` (Bean validation `@NotBlank`).
  * `LoginResponse`: `token`, `user` (`UserDto`).
  * `ChangePasswordRequest`: `oldPassword`, `newPassword` (`@NotBlank`, `@Size(min = 6)`).
* **`LoginThrottlingService.java`**:
  * Simple in-memory counter with short lockout window to slow password brute-force guessing.
* **`AuthService.java`**:
  * Validates credentials against `UserRepository`, checks active status, generates JWT.
  * `changePassword(...)`: Verifies old password with `PasswordEncoder.matches`, encodes new password, updates DB.
* **`AuthController.java`**:
  * `POST /api/auth/login` &rarr; `{ token, user }`
  * `GET /api/auth/me` &rarr; returns logged-in user profile
  * `POST /api/auth/change-password` &rarr; updates password for caller

---

### Step 6: User Admin Feature (`id.sch.sman11garut.library.user`)
* **DTOs**:
  * `UserDto`: `id`, `username`, `fullName`, `role`, `active`, `createdAt`.
  * `CreateUserRequest`: `username`, `password`, `fullName` (creates `TEACHER`).
  * `UpdateUserRequest`: optional `fullName`, optional `password`, optional `active`.
* **`UserService.java`**:
  * `listUsers()`: returns list of `UserDto`.
  * `createUser(CreateUserRequest)`: validates unique username, hashes password, saves new teacher.
  * `updateUser(Long id, UpdateUserRequest)`: updates details; prevents librarians from deactivating their own account or the sole active librarian.
* **`UserAdminController.java`**:
  * `@PreAuthorize("hasRole('LIBRARIAN')")`
  * `GET /api/admin/users`
  * `POST /api/admin/users`
  * `PUT /api/admin/users/{id}`

---

## 3. Manual Testing Guide (Postman Format)

### Environment Variables
Configure the following variables in your Postman Environment:
| Variable | Initial Value | Notes |
|---|---|---|
| `baseUrl` | `http://localhost:8081` | Dev backend server port |
| `adminToken` | *(empty)* | Populated by Request 1 Tests script |
| `teacherToken` | *(empty)* | Populated by Request 4 Tests script |

---

### Request 1: Admin Login
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
* **Expected Response** (`200 OK`):
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 1,
      "username": "admin",
      "fullName": "Administrator Perpustakaan",
      "role": "LIBRARIAN",
      "active": true
    }
  }
  ```

---

### Request 2: Get Current User Profile
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/auth/me`
* **Headers**:
  * `Authorization: Bearer {{adminToken}}`
* **Expected Response** (`200 OK`):
  ```json
  {
    "id": 1,
    "username": "admin",
    "fullName": "Administrator Perpustakaan",
    "role": "LIBRARIAN",
    "active": true
  }
  ```

---

### Request 3: Create Teacher Account (Librarian Only)
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/admin/users`
* **Headers**:
  * `Content-Type: application/json`
  * `Authorization: Bearer {{adminToken}}`
* **Body** (`raw` - JSON):
  ```json
  {
    "username": "guru_matematika",
    "password": "password123",
    "fullName": "Budi Santoso, S.Pd.",
    "role": "TEACHER"
  }
  ```
* **Expected Response** (`201 Created`):
  ```json
  {
    "id": 2,
    "username": "guru_matematika",
    "fullName": "Budi Santoso, S.Pd.",
    "role": "TEACHER",
    "active": true
  }
  ```

---

### Request 4: Teacher Login
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/auth/login`
* **Headers**:
  * `Content-Type: application/json`
* **Body** (`raw` - JSON):
  ```json
  {
    "username": "guru_matematika",
    "password": "password123"
  }
  ```
* **Postman Tests Script**:
  ```javascript
  pm.test("Status code is 200", function () {
      pm.response.to.have.status(200);
      var jsonData = pm.response.json();
      pm.environment.set("teacherToken", jsonData.token);
  });
  ```
* **Expected Response** (`200 OK`):
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "user": {
      "id": 2,
      "username": "guru_matematika",
      "fullName": "Budi Santoso, S.Pd.",
      "role": "TEACHER",
      "active": true
    }
  }
  ```

---

### Request 5: Role Authorization Check (Teacher Forbidden on Admin Endpoint)
* **Method**: `GET`
* **URL**: `{{baseUrl}}/api/admin/users`
* **Headers**:
  * `Authorization: Bearer {{teacherToken}}`
* **Expected Response** (`403 Forbidden`):
  ```json
  {
    "status": 403,
    "code": "FORBIDDEN",
    "message": "Access Denied"
  }
  ```

---

### Request 6: Change Password
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/auth/change-password`
* **Headers**:
  * `Content-Type: application/json`
  * `Authorization: Bearer {{teacherToken}}`
* **Body** (`raw` - JSON):
  ```json
  {
    "oldPassword": "password123",
    "newPassword": "newPassword456"
  }
  ```
* **Expected Response** (`200 OK`):
  ```json
  {
    "message": "Password changed successfully"
  }
  ```

---

### Request 7: Anonymous Health Check
* **Method**: `GET`
* **URL**: `{{baseUrl}}/actuator/health`
* **Headers**: *(None)*
* **Expected Response** (`200 OK`):
  ```json
  {
    "status": "UP"
  }
  ```
