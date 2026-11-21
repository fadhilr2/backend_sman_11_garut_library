# Backend Docs: Perpustakaan Digital Lokal SMAN 11 Garut

Start here. Each doc type answers one question; if something belongs to another type, link to it instead of repeating it.

| Doc | Answers | Changes when |
|---|---|---|
| [backend-spec.md](backend-spec.md) | **What** does the API do? Roles, endpoints, rules, error codes. | The contract with the front-end changes. |
| [backend-architecture.md](backend-architecture.md) | **How** is it built? Modules, flows, cross-cutting design, trade-offs. | A design decision changes. |
| [backend-phases.md](backend-phases.md) | **When / in what order?** Phase list, status, "done when". | A phase starts, finishes or is re-scoped. |
| `phase-N-<topic>.md` | **What exactly was built in phase N**, and how to test it. | While the phase is in progress, and when a bug in it is fixed later. |

Phase docs:

| Phase | Doc |
|---|---|
| 1 | [phase-1-auth.md](phase-1-auth.md) |
| 2 | [phase-2-storage.md](phase-2-storage.md) |
| 3 | [phase-3-categories-books.md](phase-3-categories-books.md) |
| 4 | [phase-4-archive.md](phase-4-archive.md) |
| 5 | [phase-5-downloads-stats.md](phase-5-downloads-stats.md) |
| 6 | [phase-6-bulk-import.md](phase-6-bulk-import.md) |
| 7 | [phase-7-storage-health.md](phase-7-storage-health.md) |
| 8 | [phase-8-hardening.md](phase-8-hardening.md) |

---

## Conventions (all docs)

- One `#` title, numbered `##` sections, plain Markdown (no HTML tags).
- Links between docs are relative (`[spec](backend-spec.md)`), never `file:///` paths.
- Code paths are written from the package root: `book/BookService.java`.
- Facts live in one place. The spec owns endpoints and error codes; the architecture owns module and flow design; phase docs link to them.
- Tables for lists of things (endpoints, files, errors). Prose only for reasoning.

---

## Templates

### Spec
```
# Backend Spec: <system>
> Status line: version / last updated
## 1. Overview          users, content areas, one paragraph
## 2. Roles & access    access matrix + auth rule
## 3. Endpoints         one table per audience: Method | Path | Notes | Phase
## 4. Errors            error body format + error code catalog
## 5. Business rules    storage, file types, search, import, defaults, soft delete
## 6. Configuration     key application.yml settings + Nginx requirements
## 7. Operations        health, backups, capacity
```

### Architecture
```
# Backend Architecture: <system>
## 1. Runtime view            deployment diagram
## 2. Internal structure      packages, layering, dependency rules
## 3. Key flows               one subsection per flow
## 4. Cross-cutting concerns  security, errors, transactions, async, config, observability
## 5. Technology choices
## 6. Deployment
## 7. Decisions and trade-offs
## 8. Out of scope
## 9. Risks
```

### Roadmap (`backend-phases.md`)
```
# Backend Roadmap
## 1. Status            table: Phase | Scope | Status | Doc
## 2. Phases            one "### Phase N: <name>" block each: Scope, Done when
## 3. Server configuration (non-code work)
```
Status values: `Done`, `In progress`, `Planned`.

### Phase doc (`phase-N-<topic>.md`)
```
# Phase N: <Name>
> **Status:** Done · **Commit:** <sha> · **Roadmap:** [Phase N](backend-phases.md#phase-n-...)

## 1. Summary            what and why, bullet list of deliverables, "Done when"
## 2. Decisions          rules and choices a reviewer must know (why, not just what)
## 3. Design             flow diagram (mermaid) + short explanation
## 4. Changes            tables per package: File | Change | Notes   (Change = New / Modified)
## 5. API                endpoints added/changed + error codes (link to the spec for the full contract)
## 6. Testing
### 6.1 Automated        command + what the tests cover
### 6.2 Manual (Postman) Setup (environment variables, test data), then requests
## 7. Issues and fixes   Issue / Cause / Fix, or "None"
```

Postman request block (same in every phase doc):
````
#### Request N: <What it checks>
* **Method**: `POST`
* **URL**: `{{baseUrl}}/api/...`
* **Headers**:
  * `Authorization: Bearer {{adminToken}}`
* **Body** (`raw` - JSON | `form-data`): ...
* **Postman Tests Script**: (only when it stores a variable or asserts something)
  ```javascript
  pm.test("...", function () { ... });
  ```
* **Expected Response** (`200 OK`): JSON or a short description
````
`baseUrl` is always `http://localhost:8081` (dev profile). The dev admin login is `admin` / `admin123`.
