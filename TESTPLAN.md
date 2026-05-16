# TESTPLAN — WorkHub Enterprise Testing

This document explains every test in the project, what it proves, how it is wired, and how it maps to the SWAPD 452 grading rubric (Section 6 — Enterprise Testing, 15 marks).

The project ships with two test layers:

1. **Unit tests** — fast, in-memory, mocked dependencies. Cover service-level logic in isolation.
2. **Integration tests (`*IT.java`)** — boot the full Spring context against real Postgres (Docker) and real Kafka (either docker-compose or an ephemeral Testcontainers instance). These satisfy the *integration testing* requirement of the PDF and are what the rubric grades.

All tests live under `backend/src/test/java/com/workhub/backend/`.

---

## 1. Tools and Libraries

The tests rely on a small but standard Spring-ecosystem toolbox. Each tool plays a specific role.

### 1.1 JUnit 5 (Jupiter)
The test runner. Provides the `@Test`, `@BeforeEach`, `@AfterEach`, `@ExtendWith` annotations. Every test class in this project is a JUnit 5 class.

### 1.2 Spring Boot Test (`@SpringBootTest`)
Bootstraps the full Spring application context inside the JVM during the test run. We use the `MOCK` web environment which means no real Tomcat is started — Spring still wires every bean (controllers, services, repositories, security filters, JPA, Kafka listeners) but HTTP calls are dispatched through `MockMvc` rather than a real socket. This is the standard way to write integration tests that need the real Spring graph but should still run fast.

### 1.3 MockMvc + `@AutoConfigureMockMvc`
A virtual HTTP client wired to the Spring `DispatcherServlet`. It lets the test perform `mockMvc.perform(get("/projects/{id}", ...))` and get back a `MockHttpServletResponse` exactly as a real client would, including Spring Security filters, controller advice (`GlobalExceptionHandler`), validation, and tenant filters. No port is opened.

In Spring Boot 4, `@AutoConfigureMockMvc` lives in `org.springframework.boot.webmvc.test.autoconfigure` (moved from the old `…test.autoconfigure.web.servlet`).

### 1.4 AssertJ (`assertThat`)
Fluent assertion library. Replaces JUnit's `assertEquals(...)` with chainable, readable matchers (`assertThat(list).hasSize(2).extracting(...).containsExactlyInAnyOrder(...)`).

### 1.5 Mockito + `MockitoExtension`
The unit-test mocking framework. `@Mock` produces stub dependencies, `@InjectMocks` wires them into the system-under-test, `when(...).thenReturn(...)` defines behaviour, `verify(...)` asserts interactions.

### 1.6 `@MockitoBean` (Spring Boot 4)
Replaces the deprecated `@MockBean`. Inside `@SpringBootTest`, marking a field `@MockitoBean ReportProducer reportProducer` swaps the real Kafka producer bean in the context with a Mockito stub. We use this in integration tests that must not actually publish to Kafka (e.g. `TenantIsolationIT`, `RbacIT`, `TransactionRollbackIT`, `ConcurrencyIT`, `ObservabilityIT`).

Lives in `org.springframework.test.context.bean.override.mockito.MockitoBean`.

### 1.7 Testcontainers (`kafka` + `junit-jupiter`)
A Java library that programmatically starts and stops real Docker containers for a single test class. The test annotates a static field with `@Container` and Testcontainers handles `docker run …` and `docker rm` automatically. The container is ephemeral — fresh image, random host port, full teardown after the test JVM exits.

We use `ConfluentKafkaContainer` (Confluent CP-Kafka in KRaft mode, no ZooKeeper) for `MessagingReliabilityIT`. The image is pinned to `confluentinc/cp-kafka:7.5.0`. Only requirement on the host is that Docker is running.

### 1.8 Awaitility (`await()...untilAsserted(...)`)
A polling library for asynchronous assertions. After we publish a Kafka message, the consumer runs on a separate thread; we cannot assert immediately. Awaitility re-evaluates the supplied lambda until it passes or the timeout elapses (here, 15 seconds). This is the PDF-mandated way to express "wait for the consumer to finish".

### 1.9 Spring Data JPA repositories
All test setup uses the same `Tenant/User/Project/Task/Report` repositories that production code uses. Each `repo.save(...)` triggers a real `INSERT` against Postgres, and is committed (Spring Data wraps each `save()` in its own short transaction unless an outer one is active). This is critical for the rollback and concurrency tests.

### 1.10 JWT (`JwtService`)
Test setup calls `jwtService.generateToken(user)` to mint a real token for the seeded user. The token is then sent as `Authorization: Bearer …`, exercising the production `JwtAuthenticationFilter` end-to-end — including the `TenantContext` setup and `finally { TenantContext.clear(); }` thread-local management.

---

## 2. Test Layout

```
backend/src/test/java/com/workhub/backend/
├── service/                     ← unit tests (Mockito only)
│   ├── AuthServiceTest.java
│   ├── ProjectServiceTest.java
│   ├── ReportServiceTest.java
│   ├── TaskServiceTest.java
│   └── UserServiceTest.java
└── integration/                 ← integration tests (real DB / Kafka)
    ├── TenantIsolationIT.java
    ├── RbacIT.java
    ├── TransactionRollbackIT.java
    ├── ConcurrencyIT.java
    ├── MessagingReliabilityIT.java
    └── ObservabilityIT.java
```

The `IT` suffix is a long-standing Maven/Surefire convention for **integration test**. Putting them in their own `integration` sub-package makes the boundary visible in the package tree.

---

## 3. Unit Tests (Service Layer)

The unit tests use `MockitoExtension`, mock every collaborator, and exercise only the service class in isolation. They are fast (milliseconds) and run on every build. They are *not* what Section 6 of the rubric grades — Section 6 grades integration tests — but they protect the service-level invariants the integration tests cannot drill into cheaply.

Coverage at a glance:

| File | Service under test | Cases covered |
|---|---|---|
| `AuthServiceTest` | `AuthService` | login happy path, unknown email → `BadCredentialsException`, wrong password → `BadCredentialsException`, `getCurrentUser` happy path + not-found |
| `ProjectServiceTest` | `ProjectService` | (CRUD + tenant scoping) |
| `TaskServiceTest` | `TaskService` | create with existing project, create auto-creates project when missing, blank title throws `IllegalArgumentException`, get/list/delete/update-status happy paths and not-found |
| `ReportServiceTest` | `ReportService` | enqueue happy path (verifies `TransactionSynchronizationManager.registerSynchronization` is called so Kafka publish runs only after commit), enqueue with missing project → `ResourceNotFoundException`, getReport happy path + not-found |
| `UserServiceTest` | `UserService` | (admin invite flows) |

Pattern repeated in every unit test:

```
1. Set TenantContext to a known UUID in @BeforeEach (services read tenantId from this ThreadLocal).
2. Mock repository return values with when(...).thenReturn(...).
3. Call the service method.
4. Assert the returned DTO + verify the right repository methods were invoked.
5. Clear TenantContext in @AfterEach (ThreadLocal hygiene).
```

---

## 4. Integration Tests — Section 6 Rubric Mapping

Every integration test class shares the same skeleton:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SomethingIT {
    @MockitoBean ReportProducer reportProducer;   // stubbed where Kafka isn't being tested
    @Autowired MockMvc           mockMvc;
    @Autowired JwtService        jwtService;
    @Autowired ...Repository     ...;

    @BeforeEach void setUp()    { /* seed tenant / user / project / token */ }
    @AfterEach  void tearDown() { /* delete in FK-safe order, OR rely on @Transactional auto-rollback */ }
    @Test       void someBehaviour() throws Exception { /* mockMvc + assertions */ }
}
```

The choice of `@Transactional` on the test class vs. manual `@AfterEach` cleanup is **never accidental** — each test class explains why in a class-level comment. The short version:

- `@Transactional` on the test class = Spring opens one outer transaction per test, the service joins it via `PROPAGATION_REQUIRED`, and Spring rolls everything back at the end. **Cheap and clean** — but the test cannot observe the difference between "committed" and "rolled back", because everything is in-memory until the outer rollback. Used for `TenantIsolationIT` and `RbacIT`.
- **No** `@Transactional` on the test class = service runs in its own transaction, commits are real, and the test must clean up manually. Used for `TransactionRollbackIT`, `ConcurrencyIT`, `MessagingReliabilityIT`, `ObservabilityIT` — anywhere the test needs to inspect the true committed DB state.

### 4.A — Tenant Isolation (4 marks)

**Rubric (PDF §6.A):** integration tests using MockMvc. Create data under Tenant B, call endpoints using Tenant A JWT. Expect 403 or 404. Required: 3 tests covering cross-tenant read, cross-tenant update, cross-tenant list.

**File:** [`TenantIsolationIT.java`](backend/src/test/java/com/workhub/backend/integration/TenantIsolationIT.java)

**Setup (`@BeforeEach`):**
1. Save Tenant A and Tenant B (different UUIDs).
2. Save one `TENANT_ADMIN` user under each tenant.
3. Generate `tokenA` and `tokenB` via `JwtService.generateToken(...)` — these tokens embed the correct `tenantId` claim.
4. Save a Project under Tenant B (`projectB`) and a Task under that project (`taskB`).

**Test 1 — Cross-tenant read (`GET /projects/{id}`):**
- Action: `mockMvc.perform(get("/projects/{id}", projectB.getId()).header("Authorization", "Bearer " + tokenA))`.
- Expected: `404 Not Found`. Spring extracts Tenant A's id from the JWT, the repository query is `findByIdAndTenant_Id(projectId, tenantA)`, which returns empty, the service throws `ResourceNotFoundException`, the global handler maps it to 404.
- Why 404 not 403? The team decided that *leaking the existence of a foreign resource is itself a leak* — 403 would tell Tenant A that "this project exists, you just can't see it". 404 is indistinguishable from "no such project". This is the safer choice and is documented in `TENANT-ISOLATION-PROOF.md`.

**Test 2 — Cross-tenant update (`PATCH /tasks/{id}`):**
- Action: `PATCH /tasks/{taskB.id}` with Tenant A's JWT and a body `{"status":"DONE"}`.
- Expected: `404 Not Found`. The service query uses `findByIdAndTenant_Id(taskId, tenantA)` → empty → `ResourceNotFoundException`. Crucially, the task is **not** mutated — the test re-reads the row and asserts the status is still its original value.

**Test 3 — Cross-tenant list (`GET /projects` and `GET /tasks`):**
- Action: list projects as Tenant A.
- Expected: `200 OK` with an **empty array** — not 403, not 404, because listing is always allowed; what matters is that no Tenant B data leaks into the response. The test asserts `$.length() == 0`.

**Why this satisfies the rubric:** all three patterns the PDF asks for are present, the verdict (404) is consistent with the documented decision, and the list test specifically guards against the silent leak which the PDF flags as a **Hard Gate (Phase 2 capped at 50% on any tenant leak)**.

---

### 4.B — RBAC (3 marks)

**Rubric (PDF §6.B):** integration tests for 401/403 and role restriction. Required: 3 tests — missing token → 401, wrong role → 403, admin allowed → 200/201.

**File:** [`RbacIT.java`](backend/src/test/java/com/workhub/backend/integration/RbacIT.java)

**Setup (`@BeforeEach`):**
1. Save Tenant.
2. Save one `TENANT_ADMIN` user and one `TENANT_USER` user under it.
3. Generate `adminToken` and `userToken`.

**Test 1 — Missing token → 401 Unauthorized:**
- Action: `GET /projects` with no `Authorization` header.
- Expected: 401. The `JwtAuthenticationFilter` sees no token, lets the chain continue, and the request hits the protected endpoint with no `Authentication` in the `SecurityContext`. Spring Security's `JwtAuthenticationEntryPoint` writes 401.

**Test 2 — Wrong role → 403 Forbidden:**
- Action: `POST /projects` with `userToken` (TENANT_USER) and a valid project body.
- Expected: 403. The controller method is `@PreAuthorize("hasRole('TENANT_ADMIN')")`. Spring Security's method-level access decision throws `AccessDeniedException`, which is translated to 403 by the security filter chain.

**Test 3 — Admin allowed → 201 Created:**
- Action: same `POST /projects` but with `adminToken`.
- Expected: 201 plus the JSON body of the created project. Proves the admin-only path actually works end-to-end (auth + tenant context + persistence).

The three responses (401, 403, 201) exactly match the rubric.

---

### 4.C — Transaction Rollback (2 marks)

**Rubric (PDF §6.C):** integration or service-level integration test with real DB. Perform a multi-step operation that throws midway. Assert DB contains **no partial writes**. Required: 1 test.

**File:** [`TransactionRollbackIT.java`](backend/src/test/java/com/workhub/backend/integration/TransactionRollbackIT.java)

**The production scenario being tested.** `TaskService.createTask()` is `@Transactional` and does two writes in one transaction:
1. **Write 1** — if the requested `projectId` doesn't exist, the service auto-creates a `Project` and saves it. (`projectRepository.save(autoProject)`).
2. **Validation** — the service then validates the task title. A blank title throws `IllegalArgumentException`.
3. **Write 2** — `taskRepository.save(task)` — **never reached** if validation fails.

When step 2 throws, Spring's `@Transactional` AOP proxy rolls back the entire transaction, which **must include Write 1**. The auto-created Project should not survive in the database.

**Why no `@Transactional` on the test class.** This is the key design decision. If the test class were `@Transactional`, Spring would open an outer transaction; `PROPAGATION_REQUIRED` on `createTask` would make the service join that outer transaction rather than start its own. When the service throws, Spring would mark the *outer* transaction as rollback-only, but no rollback would actually happen until the test method ends. In the meantime, Hibernate's first-level cache would still hold the auto-created Project in memory. The post-throw `findAllByTenant_Id` query would either:

- Hit the Hibernate cache and return the "rolled back" project (false negative — the test would *fail* to detect a real bug), OR
- Throw `TransactionSystemException: transaction is marked as rollback-only`.

Neither is the behaviour we want to assert. The fix is to drop `@Transactional` on the test class entirely. The service then runs in its own independent transaction; when it throws, that transaction is genuinely rolled back at commit time; the post-throw query opens a fresh transaction and sees the true committed DB state.

**Setup (`@BeforeEach`):** save Tenant + TENANT_ADMIN user, generate token. These are committed by Spring Data immediately.

**Teardown (`@AfterEach`):** `userRepository.deleteById(admin.getId())` then `tenantRepository.deleteById(tenant.getId())`. Manual because there is no outer transaction to roll back.

**Test flow:**
1. Pick a random `projectId` that does not exist in the DB.
2. `POST /projects/{nonExistentId}/tasks` with body `{"title":""}` and the admin token.
3. Assert response is `400 Bad Request` — `GlobalExceptionHandler` maps `IllegalArgumentException` to 400.
4. Assert `projectRepository.findAllByTenant_Id(tenant.getId())` returns an empty list — proving the auto-created project (Write 1) was rolled back along with the failed task save.

This single test exercises the exact "multi-step operation that throws midway → no partial writes" scenario the rubric asks for.

---

### 4.D — Concurrency (3 marks)

**Rubric (PDF §6.D):** multi-thread integration test against real DB. Run N concurrent updates to the same row. Assert final value is correct OR conflicts handled correctly. Required: 1 test.

**File:** [`ConcurrencyIT.java`](backend/src/test/java/com/workhub/backend/integration/ConcurrencyIT.java)

**The production mechanism being tested.** The `Task` entity carries `@Version @Column(nullable = false) private Long version;`. Hibernate sets `version=0` on the initial insert and bumps it on every successful update. The generated UPDATE statement is `UPDATE tasks SET ... WHERE id=? AND version=?`. If two threads both read the row at `version=0` and both call `taskRepository.save(task)`, the first save commits cleanly and increments version to 1; the second save's WHERE clause matches zero rows (because version is now 1), Hibernate detects this, throws `ObjectOptimisticLockingFailureException`, and `GlobalExceptionHandler.handleGeneral` maps it to HTTP 500.

So in any race involving N concurrent updates: exactly one wins per version. The final `task.version` value equals the number of successful (200) responses.

**Why no `@Transactional` on the test class.** Same reasoning as 4.C. We need the post-race `findById` to open a fresh transaction and read the *true committed* version number, not a Hibernate-cached snapshot.

**Setup (`@BeforeEach`):** seed Tenant → User → Project → Task (with version=0), generate admin token. All committed.

**Teardown (`@AfterEach`):** delete in FK-safe order: Task → Project → User → Tenant.

**Test flow:**
1. Choose `THREAD_COUNT = 5`.
2. Create a `CountDownLatch(1)` used as a starting gun, a synchronized `List<Integer>` to collect results, and a `FixedThreadPool` sized 5.
3. Submit 5 tasks. Each one (a) awaits the latch, (b) performs `PATCH /tasks/{id}` with the admin token and body `{"status":"IN_PROGRESS"}`, (c) records the HTTP status code into the shared list.
4. `latch.countDown()` — all five threads fire as simultaneously as the OS scheduler allows.
5. `executor.shutdown(); executor.awaitTermination(10, SECONDS)` — wait for every thread to finish recording.
6. Compute `successCount` (status 200) and `conflictCount` (status 500).
7. Assertions:
   - `statusCodes.size() == 5` — every thread reported.
   - `successCount ≥ 1` — at least one thread won the race (otherwise the test setup is broken).
   - `successCount + conflictCount == 5` — every response is either a success or an optimistic-lock conflict (no other failure).
   - `finalTask.version == successCount` — the version increment count equals the number of successful updates, which is the rigorous statement of "no lost updates".

This single test proves the production code handles concurrent writes correctly using the rubric's preferred mechanism (optimistic locking via `@Version`).

---

### 4.E — Messaging Reliability (2 marks)

**Rubric (PDF §6.E):** Testcontainers Kafka + Awaitility. Publish message, await consumer processing, assert DB state updated. Required: **1 test to show retry/idempotency**.

**File:** [`MessagingReliabilityIT.java`](backend/src/test/java/com/workhub/backend/integration/MessagingReliabilityIT.java)

**The production reliability mechanisms.** The messaging stack already has both required behaviours:

| Mechanism | Location | What it does |
|---|---|---|
| **Idempotency** | [`ReportConsumer.java`](backend/src/main/java/com/workhub/backend/messaging/ReportConsumer.java) | `ProcessedMessage` table whose primary key is `messageId`. The listener does a cheap `existsById(messageId)` pre-check; if present, it logs "duplicate skipped" and returns. If absent, the transactional `process()` method does `saveAndFlush(ProcessedMessage)` *before* the business logic. A second consumer winning the race gets `DataIntegrityViolationException` on the PK collision, which rolls back its transaction — leaving the first consumer's commit intact. |
| **Retry + DLT** | [`KafkaConfig.java`](backend/src/main/java/com/workhub/backend/messaging/KafkaConfig.java) | `DefaultErrorHandler` with `FixedBackOff(1000L, 3L)` — three retries spaced one second apart. After the last retry, `DeadLetterPublishingRecoverer` republishes the failed record onto `reports.generate.DLT`, where `ReportDltListener` records it for later inspection. |

The rubric only requires **one** of {idempotency, retry} to be demonstrated. The test demonstrates **idempotency** because it is the most observable: publish the same `messageId` twice, the consumer must process it exactly once, the `Report` row's status must end up `COMPLETED` exactly once, and the `processed_messages` table must contain exactly one row.

**Why Testcontainers and not the local docker-compose Kafka.** The rubric explicitly names Testcontainers. The benefits over the docker-compose broker:

- The container is ephemeral; topics start empty for every test JVM. No stale messages from a previous run can leak in.
- The bootstrap port is random and injected via `@DynamicPropertySource`, so the test can run alongside the dev stack without port clashes.
- CI runners (GitHub Actions) ship with Docker preinstalled — no extra setup, the test works out of the box.

**Annotations and wiring:**

```java
@SpringBootTest(webEnvironment = MOCK)
@Testcontainers
class MessagingReliabilityIT {
    @Container
    static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void overrideKafkaBootstrap(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }
    ...
}
```

- `@Testcontainers` is the JUnit 5 extension that drives the `@Container` lifecycle.
- `ConfluentKafkaContainer` is the modern, KRaft-mode replacement for the legacy `org.testcontainers.containers.KafkaContainer`. No ZooKeeper needed.
- `@DynamicPropertySource` runs *before* the Spring context starts and rewrites `spring.kafka.bootstrap-servers` to point at the container's randomly-allocated port. This is what makes the producer and consumer talk to the ephemeral broker instead of the dev one.
- No `@AutoConfigureMockMvc` — this test exercises the producer bean directly, not the REST surface.
- No `@MockitoBean ReportProducer` — we are testing the *real* producer here, that is the whole point.
- No `@Transactional` — the consumer runs on its own Kafka listener thread and commits its own transaction; the test needs to read that committed state from a separate thread.

**Setup (`@BeforeEach`):** create Tenant, TENANT_ADMIN user, Project, and a `Report` row in `PENDING` status.

**Teardown (`@AfterEach`):** wipe `processed_messages`, then delete Report → Project → User → Tenant in FK order.

**Test flow:**
1. Build a single `ReportJobMessage` with a freshly generated `messageId`.
2. `reportProducer.publish(msg)` — once.
3. `reportProducer.publish(msg)` — **same message a second time**. This is the duplicate the idempotency mechanism must catch.
4. `await().atMost(15, SECONDS).untilAsserted(() -> { Report r = reportRepository.findById(...).orElseThrow(); assertThat(r.getStatus()).isEqualTo(ReportStatus.COMPLETED); assertThat(r.getPayload()).isNotBlank(); });` — Awaitility polls the DB until the consumer has finished processing the first message. The polling interval and timeout absorb the unavoidable lag of an async listener.
5. `Thread.sleep(2000)` — give the second consumer invocation time to also run and *skip*. Without this, the test could finish before the duplicate is even dequeued, making the idempotency assertion moot.
6. Assertions:
   - `processedMessageRepository.existsById(messageId)` is true — the marker row was written.
   - `processedMessageRepository.count() == 1` — only **one** marker exists despite two publishes, proving the duplicate was skipped.
   - `finalReport.status == COMPLETED` and unchanged — the duplicate did not double-process and corrupt the payload.

This is one test class with one `@Test` method and it ticks every box on the rubric: Testcontainers ✓ Awaitility ✓ publish ✓ await ✓ DB assertion ✓ idempotency demonstrated ✓.

---

### 4.F — Observability Verification (1 mark)

**Rubric (PDF §6.F):** integration test hits Actuator endpoints. `/actuator/health` is OK. Readiness/liveness endpoints are available. Required: 1 test.

**File:** [`ObservabilityIT.java`](backend/src/test/java/com/workhub/backend/integration/ObservabilityIT.java)

**Production configuration backing this test:**
- `spring-boot-starter-actuator` is on the classpath (`pom.xml`).
- `management.endpoints.web.exposure.include = health,info,metrics,prometheus` in `application.yaml`.
- `management.endpoint.health.probes.enabled = true` activates the separate liveness and readiness endpoints.
- `management.health.livenessstate.enabled = true` and `management.health.readinessstate.enabled = true` register the corresponding health indicators.
- `micrometer-registry-prometheus` provides `/actuator/prometheus` for scraping (covers PDF §4.6 "Metrics via Micrometer").
- `SecurityConfig` permits `/actuator/health`, `/actuator/health/**`, and `/actuator/info` without JWT — necessary because Kubernetes probes will not carry an `Authorization` header.

**Why no setup or teardown.** The test only hits stateless endpoints. There is nothing to seed and nothing to clean. No JWT either (the endpoints are public).

**Test flow** — one `@Test` method with three sequential assertions:

1. `GET /actuator/health` → 200 OK, JSON body contains `"status": "UP"`. Proves the aggregate health endpoint is available.
2. `GET /actuator/health/liveness` → 200, `"status": "UP"`. Proves the liveness probe is wired and reports healthy.
3. `GET /actuator/health/readiness` → 200, `"status": "UP"`. Proves the readiness probe is wired and reports healthy.

All three checks live inside a single `@Test` method because the rubric is explicit: "Required: 1 test". The three endpoint checks are the three things that test is required to prove.

---

## 5. Hard Gates Compliance

The PDF defines three hard gates that can zero portions of the grade. The integration tests directly defend against each one.

| Gate | Where it lives | What protects us |
|---|---|---|
| "If any tenant leak is found → Phase 2 capped at 50%" | PDF §5, Phase 2 Rubric | `TenantIsolationIT` — 3 distinct cross-tenant attempts (read / update / list) all expected to be denied. |
| "If no integration tests → Enterprise Testing = 0" | PDF §6 | Six `*IT` files under `integration/`, every Section 6 sub-rubric covered. |
| "If tests don't run in CI → CI score in Phase 3 = 0" | PDF §6 + §5 Phase 3 | The GitHub Actions workflow at `.github/workflows/ci.yml` runs `./mvnw verify` on every push — Surefire executes unit tests, Failsafe executes the `*IT` classes inside the same job. |

---

## 6. Running the Tests

### Prerequisites

- **Docker Desktop running.** Postgres (port 5433) and dev-stack Kafka (port 9092) come from `docker-compose up -d`; Testcontainers needs the Docker socket as well.
- **Java 25** and the bundled `./mvnw` wrapper. No global Maven install needed.

### Commands

Bring up the supporting infrastructure once per work session:

```bash
docker-compose up -d
```

Run **everything** (unit + integration):

```bash
cd backend
./mvnw test --no-transfer-progress
```

Run **only the integration tests** (Section 6 only):

```bash
./mvnw test -Dtest="*IT" --no-transfer-progress
```

Run a single class — for example to debug the messaging test:

```bash
./mvnw test -Dtest="MessagingReliabilityIT" --no-transfer-progress
```

Combine multiple classes:

```bash
./mvnw test -Dtest="TenantIsolationIT,RbacIT,TransactionRollbackIT,ConcurrencyIT,MessagingReliabilityIT,ObservabilityIT" --no-transfer-progress
```

### Expected timing on a laptop

| Suite | Approx. wall-clock |
|---|---|
| Single unit test | < 1 s |
| All unit tests | 5–10 s |
| `ObservabilityIT` | ~15 s (Spring context startup dominates) |
| `TenantIsolationIT`, `RbacIT`, `TransactionRollbackIT`, `ConcurrencyIT` | ~15–20 s each |
| `MessagingReliabilityIT` | 25–35 s (pulls/starts the Kafka container the first time) |

---

## 7. Final Section 6 Score

| Sub-rubric | Marks available | Tests | File |
|---|---:|---:|---|
| A — Tenant Isolation | 4 | 3 | `TenantIsolationIT` |
| B — RBAC | 3 | 3 | `RbacIT` |
| C — Transaction Rollback | 2 | 1 | `TransactionRollbackIT` |
| D — Concurrency | 3 | 1 | `ConcurrencyIT` |
| E — Messaging Reliability | 2 | 1 | `MessagingReliabilityIT` |
| F — Observability | 1 | 1 | `ObservabilityIT` |
| **Total** | **15** | **10** | |
