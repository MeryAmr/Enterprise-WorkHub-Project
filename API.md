# WorkHub API Endpoints

Base URL: `http://localhost:8080`

All endpoints except `POST /auth/login` require:
```
Authorization: Bearer <token>
```

---

## Auth

### POST /auth/login
Login and receive a JWT token.

- **Access:** Public
- **Request Body:**
```json
{
  "email": "admin@acme.com",
  "password": "password123"
}
```
- **Response `200`:**
```json
{
  "token": "eyJ..."
}
```

---

### GET /auth/me
Returns the currently authenticated user.

- **Access:** Any authenticated user
- **Response `200`:**
```json
{
  "id": "uuid",
  "email": "admin@acme.com",
  "role": "TENANT_ADMIN",
  "tenantId": "uuid",
  "tenantName": "Acme Corp"
}
```

---

## Projects

### POST /projects
Create a new project.

- **Access:** `TENANT_ADMIN` only
- **Request Body:**
```json
{
  "name": "My Project"
}
```
- **Response `201`:**
```json
{
  "id": "uuid",
  "name": "My Project",
  "tenantId": "uuid",
  "createdBy": "uuid",
  "createdAt": "2024-01-01T00:00:00Z"
}
```
- **Errors:** `400` (blank name), `403` (not admin)

---

### GET /projects
List all projects for the caller's tenant.

- **Access:** Any authenticated user
- **Response `200`:**
```json
[
  {
    "id": "uuid",
    "name": "My Project",
    "tenantId": "uuid",
    "createdBy": "uuid",
    "createdAt": "2024-01-01T00:00:00Z"
  }
]
```

---

### GET /projects/{id}
Get a single project by ID.

- **Access:** Any authenticated user
- **Response `200`:**
```json
{
  "id": "uuid",
  "name": "My Project",
  "tenantId": "uuid",
  "createdBy": "uuid",
  "createdAt": "2024-01-01T00:00:00Z"
}
```
- **Errors:** `404` (not found or belongs to another tenant)

---

### PATCH /projects/{id}
Rename a project.

- **Access:** `TENANT_ADMIN` only
- **Request Body:**
```json
{
  "name": "Updated Project Name"
}
```
- **Response `200`:**
```json
{
  "id": "uuid",
  "name": "Updated Project Name",
  "tenantId": "uuid",
  "createdBy": "uuid",
  "createdAt": "2024-01-01T00:00:00Z"
}
```
- **Errors:** `400` (blank name), `403` (not admin), `404` (not found)

---

### DELETE /projects/{id}
Delete a project.

- **Access:** `TENANT_ADMIN` only
- **Response `204`:** No content
- **Errors:** `403` (not admin), `404` (not found)

---

## Tasks

### POST /projects/{projectId}/tasks
Create a task under a project. If the project does not exist for this tenant, it is
auto-created within the same transaction. If task creation fails, the auto-created
project is rolled back too.

- **Access:** `TENANT_ADMIN` only
- **Request Body:**
```json
{
  "title": "Implement login page"
}
```
- **Response `201`:**
```json
{
  "id": "uuid",
  "title": "Implement login page",
  "status": "TODO",
  "projectId": "uuid",
  "tenantId": "uuid",
  "createdAt": "2024-01-01T00:00:00Z"
}
```
- **Errors:** `400` (blank title — triggers rollback of auto-created project), `403` (not admin)

---

### GET /projects/{projectId}/tasks
List all tasks for a project.

- **Access:** Any authenticated user
- **Response `200`:**
```json
[
  {
    "id": "uuid",
    "title": "Implement login page",
    "status": "TODO",
    "projectId": "uuid",
    "tenantId": "uuid",
    "createdAt": "2024-01-01T00:00:00Z"
  }
]
```
- **Errors:** `404` (project not found or belongs to another tenant)

---

### GET /tasks/{id}
Get a single task by ID.

- **Access:** Any authenticated user
- **Response `200`:**
```json
{
  "id": "uuid",
  "title": "Implement login page",
  "status": "TODO",
  "projectId": "uuid",
  "tenantId": "uuid",
  "createdAt": "2024-01-01T00:00:00Z"
}
```
- **Errors:** `404` (not found or belongs to another tenant)

---

### PATCH /tasks/{id}
Update the status of a task.

- **Access:** Any authenticated user (`TENANT_ADMIN` + `TENANT_USER`)
- **Request Body:**
```json
{
  "status": "IN_PROGRESS"
}
```
Valid values: `TODO`, `IN_PROGRESS`, `DONE`

- **Response `200`:**
```json
{
  "id": "uuid",
  "title": "Implement login page",
  "status": "IN_PROGRESS",
  "projectId": "uuid",
  "tenantId": "uuid",
  "createdAt": "2024-01-01T00:00:00Z"
}
```
- **Errors:** `400` (invalid status value), `404` (task not found or belongs to another tenant)

---

### DELETE /tasks/{id}
Delete a task.

- **Access:** `TENANT_ADMIN` only
- **Response `204`:** No content
- **Errors:** `403` (not admin), `404` (not found)

---

## Error Response Format

All errors follow this structure:
```json
{
  "status": 403,
  "message": "Access denied",
  "timestamp": "2024-01-01T00:00:00Z"
}
```

| Code | Meaning |
|------|---------|
| `400` | Validation failed or bad request |
| `401` | Missing or invalid JWT |
| `403` | Insufficient role |
| `404` | Resource not found (or tenant isolation block) |
| `500` | Internal server error |

---

## Organization

### GET /organization/members
List all users in the caller's organization.

- **Access:** Any authenticated user
- **Response `200`:**
```json
[
  {
    "id": "uuid",
    "email": "admin@acme.com",
    "role": "TENANT_ADMIN",
    "tenantId": "uuid",
    "tenantName": "Acme Corp"
  }
]
```

---

### POST /organization/invite
Invite an existing registered user into the organization by their email.
The user must already have an account and must not belong to any organization.
They are assigned as `TENANT_USER`.

- **Access:** `TENANT_ADMIN` only
- **Request Body:**
```json
{
  "email": "existinguser@example.com"
}
```
- **Response `201`:**
```json
{
  "id": "uuid",
  "email": "existinguser@example.com",
  "role": "TENANT_USER",
  "tenantId": "uuid",
  "tenantName": "Acme Corp"
}
```
- **Errors:**
  - `400` — user is already a member of this organization
  - `400` — user already belongs to another organization
  - `403` — not admin
  - `404` — no registered user found with that email

---

### DELETE /organization/members/{userId}
Remove a user from the organization. The user's account is kept but they are unassigned from the tenant (no org, no role). They can be re-invited later.

- **Access:** `TENANT_ADMIN` only
- **Response `204`:** No content
- **Errors:**
  - `400` — trying to remove yourself
  - `400` — trying to remove another admin
  - `403` — not admin
  - `404` — user not found in this organization

---

## Seeded Test Accounts

| Email | Password | Role |
|-------|----------|------|
| `admin@acme.com` | `password123` | `TENANT_ADMIN` |
| `alice@acme.com` | `password123` | `TENANT_USER` |
| `bob@acme.com` | `password123` | `TENANT_USER` |
