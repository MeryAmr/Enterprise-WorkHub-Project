# Tenant Isolation Proof

WorkHub uses a **shared database, shared schema** multi-tenancy model. Every
tenant-scoped entity (`projects`, `tasks`, `reports`) carries a `tenant_id`
foreign key, and **every repository query filters by `tenant_id`**:

- [`ProjectRepository`](backend/src/main/java/com/workhub/backend/repository/ProjectRepository.java) — `findAllByTenant_Id`, `findByIdAndTenant_Id`
- [`TaskRepository`](backend/src/main/java/com/workhub/backend/repository/TaskRepository.java) — `findAllByProject_IdAndTenant_Id`, `findByIdAndTenant_Id`
- [`ReportRepository`](backend/src/main/java/com/workhub/backend/repository/ReportRepository.java) — `findAllByTenant_Id`, `findByIdAndTenant_Id`

The current tenant id is held in a `ThreadLocal`
[`TenantContext`](backend/src/main/java/com/workhub/backend/security/TenantContext.java),
populated from the `tenantId` JWT claim by
[`JwtAuthenticationFilter`](backend/src/main/java/com/workhub/backend/security/JwtAuthenticationFilter.java)
and cleared at the end of the request. Kafka consumers (which run on a separate
thread pool) set `TenantContext` manually from the message body in
[`ReportConsumer`](backend/src/main/java/com/workhub/backend/messaging/ReportConsumer.java)
and clear it in `finally`.

## Expected status: 404 (not 403)

When tenant A asks for a resource that belongs to tenant B, we return **404 Not
Found** instead of 403 Forbidden. Rationale: returning 403 leaks the existence
of a resource owned by another tenant. 404 keeps the foreign tenant's data
invisible — from tenant A's perspective the resource simply does not exist.

## Setup

The seeder creates **two tenants**:

| Tenant      | Admin email           | User email              |
|-------------|-----------------------|-------------------------|
| Acme Corp   | `admin@acme.com`      | `alice@acme.com`        |
| Globex Inc  | `admin@globex.com`    | `charlie@globex.com`    |

All passwords: `password123`. Login at `POST /auth/login`.

Evidence run:

```text
PROJ_B=25312e9e-2174-439f-b003-c9511b06a64d
TASK_B=625a3569-4fd8-4dff-abfa-9fddcbf01e92
GLOBEX_NAME=Globex Isolation Proof 1777928197
```

```bash
# Acme admin token
JWT_A=$(curl -s -X POST localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@acme.com","password":"password123"}' | jq -r .token)

# Globex admin token
JWT_B=$(curl -s -X POST localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@globex.com","password":"password123"}' | jq -r .token)

# Globex creates a project + task
PROJ_B=$(curl -s -X POST localhost:8080/projects \
  -H "Authorization: Bearer $JWT_B" -H "Content-Type: application/json" \
  -d '{"name":"Globex Secret"}' | jq -r .id)
TASK_B=$(curl -s -X POST localhost:8080/projects/$PROJ_B/tasks \
  -H "Authorization: Bearer $JWT_B" -H "Content-Type: application/json" \
  -d '{"title":"Confidential"}' | jq -r .id)
```

## Test 1 — Cross-tenant read

Acme tries to read Globex's project.

```bash
curl -i -H "Authorization: Bearer $JWT_A" \
  localhost:8080/projects/$PROJ_B
```

**Expected:** `HTTP/1.1 404 Not Found`. Body: `{"message":"Project not found"}`.

**Evidence:**

```
HTTP/1.1 404
X-Correlation-Id: bf259d09-f838-404c-a33a-8977cd11088e
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Type: application/json
Transfer-Encoding: chunked
Date: Mon, 04 May 2026 20:56:37 GMT

{"status":404,"message":"Project not found","timestamp":"2026-05-04T20:56:37.974276328Z"}
```

## Test 2 — Cross-tenant update

Acme tries to update Globex's task.

```bash
curl -i -X PATCH -H "Authorization: Bearer $JWT_A" \
  -H "Content-Type: application/json" \
  -d '{"status":"DONE"}' \
  localhost:8080/tasks/$TASK_B
```

**Expected:** `HTTP/1.1 404 Not Found`. Globex DB row is unchanged.

**Evidence:**

```
HTTP/1.1 404
X-Correlation-Id: 0364ce97-fb88-4049-aa74-016206b10558
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
Content-Type: application/json
Transfer-Encoding: chunked
Date: Mon, 04 May 2026 20:56:37 GMT

{"status":404,"message":"Task not found","timestamp":"2026-05-04T20:56:37.993860977Z"}
```

## Test 3 — Cross-tenant list

Acme lists projects. Globex's project must NOT appear.

```bash
curl -s -H "Authorization: Bearer $JWT_A" localhost:8080/projects | jq
```

**Expected:** Globex Secret is absent from the list. Acme sees only its own
projects (initially `[]` if no Acme projects yet).

**Evidence:**

```json
{
  "visibleProjectIds": [
    "fe726a5d-18a8-4825-bf39-a14e6b83a652",
    "3b5aedee-b4e6-40e6-8863-f22591b15cc9",
    "53fd7c6d-80f8-45d2-b2a8-421737542643",
    "dfad3bc9-dadb-4040-85e1-5e0754a7e51b",
    "e439b76b-f710-40b0-bd5d-a6c20e22c0a9"
  ],
  "visibleProjectNames": [
    "Phase 2 Demo Project",
    "Phase 2 Demo Project",
    "Phase 2 Demo Project",
    "Phase 2 Demo Project",
    "Full Check Acme Project"
  ],
  "leakedGlobexProjectId": false,
  "leakedGlobexProjectName": false
}
```

Same for tenant-scoped task/report reads — every lookup goes through a
tenant-filtered repository method such as `findByIdAndTenant_Id(...)`, and
project/task list endpoints use `findAllBy...Tenant_Id(...)`.

## Why this works

1. JWT `tenantId` claim is signed by the server — clients cannot forge it.
2. `JwtAuthenticationFilter` extracts the claim and sets `TenantContext`.
3. Every service method reads `TenantContext.getTenantId()` and passes it into
   a `findBy...AndTenant_Id` query. There is **no repository method that
   returns rows without a tenant filter**.
4. `TenantContext.clear()` runs in `finally` blocks on both the HTTP path and
   the Kafka consumer path so the value cannot leak across pooled threads.
