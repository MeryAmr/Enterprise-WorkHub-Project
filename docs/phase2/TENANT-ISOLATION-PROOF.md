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

**Evidence (paste actual response):**

```
HTTP/1.1 404
...
{"message":"Project not found"}
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

**Evidence (paste actual response):**

```
HTTP/1.1 404
...
{"message":"Task not found"}
```

## Test 3 — Cross-tenant list

Acme lists projects. Globex's project must NOT appear.

```bash
curl -s -H "Authorization: Bearer $JWT_A" localhost:8080/projects | jq
```

**Expected:** Globex Secret is absent from the list. Acme sees only its own
projects (initially `[]` if no Acme projects yet).

**Evidence (paste actual response):**

```
[]
```

Same for tasks and reports — every list endpoint goes through
`findAllBy...Tenant_Id(TenantContext.getTenantId())`.

## Why this works

1. JWT `tenantId` claim is signed by the server — clients cannot forge it.
2. `JwtAuthenticationFilter` extracts the claim and sets `TenantContext`.
3. Every service method reads `TenantContext.getTenantId()` and passes it into
   a `findBy...AndTenant_Id` query. There is **no repository method that
   returns rows without a tenant filter**.
4. `TenantContext.clear()` runs in `finally` blocks on both the HTTP path and
   the Kafka consumer path so the value cannot leak across pooled threads.
