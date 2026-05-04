# Observability

WorkHub exposes Spring Boot Actuator endpoints, Micrometer metrics, and a
per-request correlation ID propagated through logs.

## Endpoint matrix

| Endpoint                          | Auth required | Purpose                                |
|-----------------------------------|---------------|----------------------------------------|
| `/actuator/health`                | No (public)   | Aggregate application health           |
| `/actuator/health/liveness`       | No (public)   | K8s liveness probe                     |
| `/actuator/health/readiness`      | No (public)   | K8s readiness probe                    |
| `/actuator/info`                  | No (public)   | Build info                             |
| `/actuator/metrics`               | **Yes (JWT)** | Micrometer metric names + values       |
| `/actuator/prometheus`            | **Yes (JWT)** | Prometheus scrape endpoint             |

Health/liveness/readiness are public so Kubernetes probes and external load
balancers can scrape them without holding a token. Public health responses hide
component details to avoid exposing DB, disk, or host internals. Metrics and
Prometheus are behind JWT because they expose application internals (request
counts, JVM state, DB pool stats). See [`SecurityConfig`](backend/src/main/java/com/workhub/backend/security/SecurityConfig.java).

Configured in [`application.yaml`](backend/src/main/resources/application.yaml):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: never
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

## How to verify

```bash
# Health (public)
curl -s localhost:8080/actuator/health | jq
# Expect: {"status":"UP"}

curl -s localhost:8080/actuator/health/liveness | jq
# Expect: {"status":"UP"}

curl -s localhost:8080/actuator/health/readiness | jq
# Expect: {"status":"UP"}

# Metrics (JWT required)
JWT=$(curl -s -X POST localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@acme.com","password":"password123"}' | jq -r .token)

curl -s -H "Authorization: Bearer $JWT" localhost:8080/actuator/metrics | jq
curl -s -H "Authorization: Bearer $JWT" \
  localhost:8080/actuator/metrics/http.server.requests | jq
curl -s -H "Authorization: Bearer $JWT" localhost:8080/actuator/prometheus | head -20
```

## Correlation ID

[`CorrelationIdFilter`](backend/src/main/java/com/workhub/backend/security/CorrelationIdFilter.java)
runs at the highest precedence (before `JwtAuthenticationFilter`). For every
request:

1. If the client sends `X-Correlation-Id`, that value is reused.
2. Otherwise a fresh UUID is generated.
3. The value is placed in SLF4J MDC under key `correlationId`.
4. The same value is echoed in the response header `X-Correlation-Id`.
5. The MDC entry is cleared in a `finally` block at the end of the request.

The log pattern in `application.yaml` includes the MDC value on every line:

```yaml
logging:
  pattern:
    level: "%5p [%X{correlationId:-}]"
```

### Demo

```bash
curl -i -H "X-Correlation-Id: demo-trace-1" \
  -H "Authorization: Bearer $JWT" localhost:8080/auth/me
```

Response includes `X-Correlation-Id: demo-trace-1`. The application log shows:

```
INFO  [demo-trace-1]  c.w.backend.controller.AuthController  : ...
DEBUG [demo-trace-1]  o.s.security.web.FilterChainProxy       : ...
```

Use this to trace a single request across the full filter chain, controller,
service, and DB layer.

## Async / Kafka observability

When a report job is enqueued by the HTTP request and processed asynchronously,
the consumer log entries do **not** carry the original request's correlation
ID (they run on a Kafka listener thread, not the request thread). They do log
`reportId` and `tenantId` so the async work can be correlated through the
report ID returned in the 202 response.

Example happy path log lines:

```
INFO  [abc-123]    c.w.b.messaging.ReportProducer  : Publishing report job messageId=... reportId=... tenantId=...
INFO  []           c.w.b.messaging.ReportConsumer  : Report COMPLETED reportId=... tenantId=...
```
