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

## 3. Manual Testing Guide (Executable PowerShell Commands)

Start the application:
```powershell
cd c:\Users\lenovo\Documents\GitHub\TPLM\backend_sman_11_garut_library\library
mvn spring-boot:run
```

Verify in console:
```text
No LIBRARIAN account found in database. Seeding initial admin: admin
Initial LIBRARIAN account created successfully with username 'admin'.
```

#### 1. Admin Login (`POST /api/auth/login`)
```powershell
$loginBody = @{
    username = "admin"
    password = "admin123"
} | ConvertTo-Json

$loginResponse = Invoke-RestMethod -Uri http://localhost:8081/api/auth/login -Method Post -ContentType "application/json" -Body $loginBody
$loginResponse
$adminToken = $loginResponse.token
```

#### 2. Verify Profile (`GET /api/auth/me`)
```powershell
$adminHeaders = @{ Authorization = "Bearer $adminToken" }
Invoke-RestMethod -Uri http://localhost:8081/api/auth/me -Headers $adminHeaders
```

#### 3. Create a Teacher Account (`POST /api/admin/users`)
```powershell
$teacherBody = @{
    username = "guru_matematika"
    password = "password123"
    fullName = "Budi Santoso, S.Pd."
    role = "TEACHER"
} | ConvertTo-Json

$createdTeacher = Invoke-RestMethod -Uri http://localhost:8081/api/admin/users -Method Post -ContentType "application/json" -Headers $adminHeaders -Body $teacherBody
$createdTeacher
```

#### 4. Teacher Login (`POST /api/auth/login`)
```powershell
$teacherLoginBody = @{
    username = "guru_matematika"
    password = "password123"
} | ConvertTo-Json

$teacherResponse = Invoke-RestMethod -Uri http://localhost:8081/api/auth/login -Method Post -ContentType "application/json" -Body $teacherLoginBody
$teacherToken = $teacherResponse.token
$teacherResponse
```

#### 5. Verify Role Authorization Protection
Verify that the teacher token is blocked from accessing the librarian-only `/api/admin/users` endpoint:
```powershell
$teacherHeaders = @{ Authorization = "Bearer $teacherToken" }

try {
    Invoke-RestMethod -Uri http://localhost:8081/api/admin/users -Headers $teacherHeaders
} catch {
    $_.Exception.Response.StatusCode
    $_.ErrorDetails.Message
}
```
* **Expected result:** Status `403 Forbidden` (`FORBIDDEN`).

#### 6. Password Change (`POST /api/auth/change-password`)
```powershell
$changePassBody = @{
    oldPassword = "password123"
    newPassword = "newPassword456"
} | ConvertTo-Json

Invoke-RestMethod -Uri http://localhost:8081/api/auth/change-password -Method Post -ContentType "application/json" -Headers $teacherHeaders -Body $changePassBody
```

#### 7. Verify Anonymous Access Remains Open
```powershell
Invoke-RestMethod -Uri http://localhost:8081/actuator/health
```
* **Expected result:** Returns `UP` without requiring an `Authorization` header.
