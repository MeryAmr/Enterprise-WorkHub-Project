# WorkHub SaaS — Design Note

---

## 1. Architecture

### Overview

WorkHub follows a classic **layered architecture** built on Spring Boot. Each layer has a single responsibility and communicates only with the layer directly below it.

```
┌─────────────────────────────────────┐
│            Client (HTTP)            │
└────────────────┬────────────────────┘
                 │
┌────────────────▼────────────────────┐
│         Security Layer              │
│  JwtAuthenticationFilter            │
│  TenantContext (ThreadLocal)        │
│  SecurityConfig + RBAC              │
└────────────────┬────────────────────┘
                 │
┌────────────────▼────────────────────┐
│         Controller Layer            │
│  AuthController                     │
│  ProjectController                  │
│  TaskController                     │
│  UserController                     │
└────────────────┬────────────────────┘
                 │
┌────────────────▼────────────────────┐
│          Service Layer              │
│  AuthService                        │
│  ProjectService                     │
│  TaskService                        │
│  UserService                        │
└────────────────┬────────────────────┘
                 │
┌────────────────▼────────────────────┐
│        Repository Layer             │
│  UserRepository                     │
│  TenantRepository                   │
│  ProjectRepository                  │
│  TaskRepository                     │
└────────────────┬────────────────────┘
                 │
┌────────────────▼────────────────────┐
│         PostgreSQL Database         │
└─────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Responsibility |
|---|---|
| **Security** | JWT validation, tenant context extraction, role-based access control |
| **Controller** | HTTP request/response mapping, input validation via `@Valid`, delegates to service |
| **Service** | Business logic, transaction boundaries, tenant isolation enforcement |
| **Repository** | Data access via Spring Data JPA, tenant-scoped queries |

### Key Design Decisions

- **Constructor injection** used throughout — no `@Autowired` on fields. Dependencies are explicit, classes are testable in isolation.
- **DTOs** separate the API contract from the domain model. Entities are never exposed directly in responses.
- **`@Transactional(readOnly = true)`** on all read operations — allows Hibernate to skip dirty checking and enables DB-level read optimizations.
- **`getReferenceById()`** used for FK references in write operations — returns a Hibernate proxy without issuing a SELECT, avoiding unnecessary DB round-trips.

---

## 2. Tenant Approach

### Model

WorkHub uses a **Shared Database, Shared Schema** multi-tenancy strategy. All tenants share the same tables. Every row that belongs to a tenant carries a `tenant_id` foreign key column.

```
tenants
  └── id (PK)
  └── name
  └── plan

users
  └── id (PK)
  └── tenant_id (FK → tenants)   ← isolation column
  └── email
  └── password_hash
  └── role

projects
  └── id (PK)
  └── tenant_id (FK → tenants)   ← isolation column
  └── name
  └── created_by (FK → users)

tasks
  └── id (PK)
  └── tenant_id (FK → tenants)   ← isolation column
  └── project_id (FK → projects)
  └── title
  └── status
  └── version
```

`tenant_id` is present directly on `tasks` (not just reachable via `projects`) so that every table can be filtered independently without joins.

### Tenant Context Extraction

```
Request arrives with JWT
        │
        ▼
JwtAuthenticationFilter
  ├── validates JWT signature
  ├── extracts tenantId claim
  ├── calls TenantContext.setTenantId(tenantId)   ← ThreadLocal
  └── proceeds down filter chain
        │
        ▼
Service method
  └── calls TenantContext.getTenantId()
        │
        ▼
Repository query
  └── WHERE tenant_id = :tenantId
        │
        ▼
finally block in filter
  └── TenantContext.clear()   ← prevents thread pool leakage
```

### Isolation Enforcement

Every repository method that reads or writes tenant-owned data includes the `tenant_id` in its query:

```java
// Never fetches without tenant scope
projectRepository.findByIdAndTenant_Id(projectId, TenantContext.getTenantId())
taskRepository.findAllByProject_IdAndTenant_Id(projectId, TenantContext.getTenantId())
```

A request from Tenant A carrying Tenant A's JWT will always produce Tenant A's `tenantId` from `TenantContext`. The JWT is cryptographically signed — Tenant A cannot forge Tenant B's `tenantId`. Any attempt to access Tenant B's resource returns `404 Not Found`, leaking no information about whether the resource exists.

### Role-Based Access Control (RBAC)

Two roles exist within every tenant:

| Role | Capabilities |
|---|---|
| `TENANT_ADMIN` | Full access — create/update/delete projects and tasks, invite/kick users |
| `TENANT_USER` | Read projects and tasks, update task status only |

The role is embedded in the JWT as a claim and mapped to a Spring Security `GrantedAuthority` (`ROLE_TENANT_ADMIN` / `ROLE_TENANT_USER`) in the filter. Admin-only endpoints are enforced with `@PreAuthorize("hasRole('TENANT_ADMIN')")`.

---

## 3. Transaction Boundary

### Strategy

Spring's `@Transactional` annotation is used at the **service layer**. Controllers never manage transactions — they delegate entirely to services. Repositories are not transactional on their own.

```
Controller.createTask()          ← no transaction
    │
    └── @Transactional
        TaskService.createTask() ← transaction begins here
            │
            ├── projectRepository.findByIdAndTenant_Id()
            │       └── (Step 1) project not found → auto-create
            │           projectRepository.save(autoCreated)   ← write within tx
            │
            ├── validate task title                            ← business rule check
            │       └── blank → throw IllegalArgumentException ← triggers rollback
            │
            └── taskRepository.save(task)                     ← write within tx
        Transaction commits or rolls back here
```

### Rollback Demonstration

The system includes a deliberately demonstrable rollback scenario in `POST /projects/{projectId}/tasks`:

**Step 1 — Auto-create project (write 1):**
If the given `projectId` does not exist for this tenant, a project named `Auto-created project N` is created and saved within the active transaction. The row exists in the DB session but is not yet committed.

**Step 2 — Validate task title (within same transaction):**
If the title is blank, `IllegalArgumentException` is thrown. Spring intercepts it, marks the transaction for rollback, and neither the project nor the task is committed. The DB returns to its state before the request.

**Step 3 — Create task (write 2):**
If the title is valid, the task is saved. Both writes commit atomically.

```
Scenario A — blank title (rollback):
  save(project) ──► save(task) ──► EXCEPTION ──► ROLLBACK
  DB state: unchanged. Project and task both absent.

Scenario B — valid title (commit):
  save(project) ──► save(task) ──► COMMIT
  DB state: project + task both present.
```

### Propagation

All service methods use the default propagation (`REQUIRED`): if a transaction already exists, join it; otherwise start a new one. This means nested service calls participate in the same transaction boundary automatically.

Read-only operations use `@Transactional(readOnly = true)` — this tells Hibernate to skip dirty checking on loaded entities and signals the DB driver that the connection can be optimized for reads.

### Optimistic Locking

The `Task` entity carries a `@Version` field:

```java
@Version
private Long version;
```

Hibernate uses this to detect concurrent updates. If two requests attempt to update the same task simultaneously, the second write will throw `OptimisticLockException` — preventing lost updates without holding a DB lock for the duration of the request.
