#!/usr/bin/env bash
# Phase 2 demo: login → create project + tasks → enqueue async report → poll → done.
# Requires: jq, curl. Backend running on localhost:8080.

set -euo pipefail

BASE=${BASE:-http://localhost:8080}

echo "==> Login as Acme admin"
JWT_A=$(curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@acme.com","password":"password123"}' | jq -r .token)
echo "Acme JWT acquired"

echo "==> Login as Globex admin"
JWT_B=$(curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@globex.com","password":"password123"}' | jq -r .token)
echo "Globex JWT acquired"

echo "==> Acme creates project"
PROJ=$(curl -s -X POST "$BASE/projects" \
  -H "Authorization: Bearer $JWT_A" \
  -H "Content-Type: application/json" \
  -d '{"name":"Phase 2 Demo Project"}' | jq -r .id)
echo "Project id: $PROJ"

echo "==> Acme adds 3 tasks"
for title in "Plan" "Build" "Ship"; do
  curl -s -X POST "$BASE/projects/$PROJ/tasks" \
    -H "Authorization: Bearer $JWT_A" \
    -H "Content-Type: application/json" \
    -d "{\"title\":\"$title\"}" | jq -c '{id, title, status}'
done

echo "==> Acme enqueues async report (POST /projects/{id}/generate-report)"
ENQ=$(curl -s -X POST "$BASE/projects/$PROJ/generate-report" \
  -H "Authorization: Bearer $JWT_A" \
  -H "X-Correlation-Id: demo-phase2-$(date +%s)")
echo "$ENQ" | jq
REPORT_ID=$(echo "$ENQ" | jq -r .reportId)

echo "==> Polling report status until COMPLETED (Kafka consumer doing the work)"
for i in 1 2 3 4 5 6 7 8 9 10; do
  STATUS=$(curl -s -H "Authorization: Bearer $JWT_A" \
    "$BASE/reports/$REPORT_ID" | jq -r .status)
  echo "  [$i] status=$STATUS"
  if [ "$STATUS" = "COMPLETED" ] || [ "$STATUS" = "FAILED" ]; then break; fi
  sleep 1
done

echo "==> Final report payload"
curl -s -H "Authorization: Bearer $JWT_A" "$BASE/reports/$REPORT_ID" | jq

echo "==> Tenant isolation: Globex cannot see Acme's report"
curl -i -H "Authorization: Bearer $JWT_B" "$BASE/reports/$REPORT_ID" | head -1

echo "==> Tenant isolation: Globex cannot read Acme's project"
curl -i -H "Authorization: Bearer $JWT_B" "$BASE/projects/$PROJ" | head -1

echo "==> Observability: actuator health (public)"
curl -s "$BASE/actuator/health" | jq

echo "==> Observability: readiness probe (public)"
curl -s "$BASE/actuator/health/readiness" | jq

echo "==> Observability: metrics (JWT)"
curl -s -H "Authorization: Bearer $JWT_A" \
  "$BASE/actuator/metrics/http.server.requests" | jq '.name, .measurements'

echo "==> Done"
