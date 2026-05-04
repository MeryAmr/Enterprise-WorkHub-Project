#!/usr/bin/env bash
# Comprehensive Phase 2 verification script.
# Requires: backend running on localhost:8080, docker compose services running, curl, jq.

set -euo pipefail

BASE=${BASE:-http://localhost:8080}
KAFKA_CONTAINER=${KAFKA_CONTAINER:-workhub-kafka}
POSTGRES_CONTAINER=${POSTGRES_CONTAINER:-workhub-postgres}

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

PASS_COUNT=0

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS: %s\n' "$1" >&2
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "Missing command: $1"
}

new_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then
    uuidgen | tr '[:upper:]' '[:lower:]'
  else
    cat /proc/sys/kernel/random/uuid
  fi
}

assert_eq() {
  local actual=$1
  local expected=$2
  local label=$3
  if [ "$actual" != "$expected" ]; then
    fail "$label expected=$expected actual=$actual"
  fi
  pass "$label"
}

assert_json_eq() {
  local file=$1
  local jq_expr=$2
  local expected=$3
  local label=$4
  local actual
  actual=$(jq -r "$jq_expr" "$file")
  assert_eq "$actual" "$expected" "$label"
}

request() {
  local method=$1
  local url=$2
  local token=${3:-}
  local body=${4:-}
  local out=$5
  local status

  if [ -n "$token" ] && [ -n "$body" ]; then
    status=$(curl -s -o "$out" -w '%{http_code}' -X "$method" "$BASE$url" \
      -H "Authorization: Bearer $token" \
      -H "Content-Type: application/json" \
      -d "$body")
  elif [ -n "$token" ]; then
    status=$(curl -s -o "$out" -w '%{http_code}' -X "$method" "$BASE$url" \
      -H "Authorization: Bearer $token")
  elif [ -n "$body" ]; then
    status=$(curl -s -o "$out" -w '%{http_code}' -X "$method" "$BASE$url" \
      -H "Content-Type: application/json" \
      -d "$body")
  else
    status=$(curl -s -o "$out" -w '%{http_code}' -X "$method" "$BASE$url")
  fi

  printf '%s' "$status"
}

login() {
  local email=$1
  local out=$2
  local status
  status=$(request POST /auth/login "" "{\"email\":\"$email\",\"password\":\"password123\"}" "$out")
  assert_eq "$status" "200" "login $email"
  jq -r .token "$out"
}

poll_report_completed() {
  local token=$1
  local report_id=$2
  local out=$3

  for i in 1 2 3 4 5 6 7 8 9 10; do
    request GET "/reports/$report_id" "$token" "" "$out" >/dev/null
    local status
    status=$(jq -r .status "$out")
    printf '  report poll %s: %s\n' "$i" "$status"
    if [ "$status" = "COMPLETED" ]; then
      pass "async report completed"
      return 0
    fi
    if [ "$status" = "FAILED" ]; then
      fail "report entered FAILED state"
    fi
    sleep 1
  done

  fail "report did not complete within timeout"
}

require_cmd curl
require_cmd jq
require_cmd docker

printf '== Phase 2 full verification against %s ==\n' "$BASE"

ACME_LOGIN="$TMP_DIR/acme-login.json"
GLOBEX_LOGIN="$TMP_DIR/globex-login.json"
ALICE_LOGIN="$TMP_DIR/alice-login.json"

JWT_A=$(login admin@acme.com "$ACME_LOGIN")
JWT_B=$(login admin@globex.com "$GLOBEX_LOGIN")
JWT_USER=$(login alice@acme.com "$ALICE_LOGIN")

ACME_ME="$TMP_DIR/acme-me.json"
status=$(request GET /auth/me "$JWT_A" "" "$ACME_ME")
assert_eq "$status" "200" "auth/me with token returns 200"
ACME_USER_ID=$(jq -r .id "$ACME_ME")

NO_TOKEN="$TMP_DIR/no-token.json"
status=$(request GET /projects "" "" "$NO_TOKEN")
assert_eq "$status" "401" "missing token on protected endpoint returns 401"

USER_FORBIDDEN="$TMP_DIR/user-forbidden.json"
status=$(request POST /projects "$JWT_USER" '{"name":"Should Fail"}' "$USER_FORBIDDEN")
assert_eq "$status" "403" "tenant user cannot create project"

ACME_PROJECT="$TMP_DIR/acme-project.json"
status=$(request POST /projects "$JWT_A" '{"name":"Full Check Acme Project"}' "$ACME_PROJECT")
assert_eq "$status" "201" "tenant admin can create project"
PROJ_A=$(jq -r .id "$ACME_PROJECT")

TASK1="$TMP_DIR/task1.json"
TASK2="$TMP_DIR/task2.json"
TASK3="$TMP_DIR/task3.json"
status=$(request POST "/projects/$PROJ_A/tasks" "$JWT_A" '{"title":"Plan"}' "$TASK1")
assert_eq "$status" "201" "create task 1"
status=$(request POST "/projects/$PROJ_A/tasks" "$JWT_A" '{"title":"Build"}' "$TASK2")
assert_eq "$status" "201" "create task 2"
status=$(request POST "/projects/$PROJ_A/tasks" "$JWT_A" '{"title":"Ship"}' "$TASK3")
assert_eq "$status" "201" "create task 3"
TASK_A=$(jq -r .id "$TASK1")

PATCH_TASK="$TMP_DIR/patch-task.json"
status=$(request PATCH "/tasks/$TASK_A" "$JWT_USER" '{"status":"DONE"}' "$PATCH_TASK")
assert_eq "$status" "200" "tenant user can update task status"
assert_json_eq "$PATCH_TASK" ".status" "DONE" "task status update persisted"

GLOBEX_PROJECT="$TMP_DIR/globex-project.json"
status=$(request POST /projects "$JWT_B" '{"name":"Globex Secret Project"}' "$GLOBEX_PROJECT")
assert_eq "$status" "201" "globex admin can create project"
PROJ_B=$(jq -r .id "$GLOBEX_PROJECT")

GLOBEX_TASK="$TMP_DIR/globex-task.json"
status=$(request POST "/projects/$PROJ_B/tasks" "$JWT_B" '{"title":"Globex Confidential"}' "$GLOBEX_TASK")
assert_eq "$status" "201" "globex admin can create task"
TASK_B=$(jq -r .id "$GLOBEX_TASK")

CROSS_READ="$TMP_DIR/cross-read.json"
status=$(request GET "/projects/$PROJ_B" "$JWT_A" "" "$CROSS_READ")
assert_eq "$status" "404" "cross-tenant project read returns 404"

CROSS_UPDATE="$TMP_DIR/cross-update.json"
status=$(request PATCH "/tasks/$TASK_B" "$JWT_A" '{"status":"DONE"}' "$CROSS_UPDATE")
assert_eq "$status" "404" "cross-tenant task update returns 404"

CROSS_TASK_LIST="$TMP_DIR/cross-task-list.json"
status=$(request GET "/projects/$PROJ_B/tasks" "$JWT_A" "" "$CROSS_TASK_LIST")
assert_eq "$status" "404" "cross-tenant task list by project returns 404"

ACME_LIST="$TMP_DIR/acme-list.json"
status=$(request GET /projects "$JWT_A" "" "$ACME_LIST")
assert_eq "$status" "200" "tenant project list returns 200"
if jq -e --arg id "$PROJ_B" '.[] | select(.id == $id)' "$ACME_LIST" >/dev/null; then
  fail "tenant project list leaked Globex project"
fi
pass "tenant project list does not leak Globex project"

ENQUEUE="$TMP_DIR/enqueue.json"
status=$(request POST "/projects/$PROJ_A/generate-report" "$JWT_A" "" "$ENQUEUE")
assert_eq "$status" "202" "generate report returns 202"
assert_json_eq "$ENQUEUE" ".status" "PENDING" "generated report starts PENDING"
REPORT_ID=$(jq -r .reportId "$ENQUEUE")

FINAL_REPORT="$TMP_DIR/final-report.json"
poll_report_completed "$JWT_A" "$REPORT_ID" "$FINAL_REPORT"
assert_json_eq "$FINAL_REPORT" ".payload | contains(\"total tasks\")" "true" "completed report has payload"
TENANT_A=$(jq -r .tenantId "$FINAL_REPORT")

CROSS_REPORT="$TMP_DIR/cross-report.json"
status=$(request GET "/reports/$REPORT_ID" "$JWT_B" "" "$CROSS_REPORT")
assert_eq "$status" "404" "cross-tenant report read returns 404"

HEALTH="$TMP_DIR/health.json"
status=$(request GET /actuator/health "" "" "$HEALTH")
assert_eq "$status" "200" "public health returns 200"
assert_json_eq "$HEALTH" ".status" "UP" "public health status is UP"
if jq -e '.components' "$HEALTH" >/dev/null; then
  fail "public health leaked component details"
fi
pass "public health hides component details"

READY="$TMP_DIR/readiness.json"
status=$(request GET /actuator/health/readiness "" "" "$READY")
assert_eq "$status" "200" "public readiness returns 200"
assert_json_eq "$READY" ".status" "UP" "readiness status is UP"

LIVE="$TMP_DIR/liveness.json"
status=$(request GET /actuator/health/liveness "" "" "$LIVE")
assert_eq "$status" "200" "public liveness returns 200"
assert_json_eq "$LIVE" ".status" "UP" "liveness status is UP"

METRICS_NO_TOKEN="$TMP_DIR/metrics-no-token.json"
status=$(request GET /actuator/metrics "" "" "$METRICS_NO_TOKEN")
assert_eq "$status" "401" "metrics without token returns 401"

PROM_NO_TOKEN="$TMP_DIR/prom-no-token.txt"
status=$(request GET /actuator/prometheus "" "" "$PROM_NO_TOKEN")
assert_eq "$status" "401" "prometheus without token returns 401"

METRICS="$TMP_DIR/metrics.json"
status=$(request GET /actuator/metrics "$JWT_A" "" "$METRICS")
assert_eq "$status" "200" "metrics with token returns 200"

PROM="$TMP_DIR/prometheus.txt"
status=$(request GET /actuator/prometheus "$JWT_A" "" "$PROM")
assert_eq "$status" "200" "prometheus with token returns 200"

CORR_HEADERS="$TMP_DIR/correlation.headers"
curl -s -D "$CORR_HEADERS" -o /dev/null \
  -H "X-Correlation-Id: full-check-trace" \
  -H "Authorization: Bearer $JWT_A" \
  "$BASE/auth/me"
if tr -d '\r' < "$CORR_HEADERS" | grep -q '^X-Correlation-Id: full-check-trace$'; then
  pass "correlation ID response header is echoed"
else
  fail "correlation ID response header was not echoed"
fi

MSG_ID=$(new_uuid)
MANUAL_MSG=$(jq -nc \
  --arg messageId "$MSG_ID" \
  --arg tenantId "$TENANT_A" \
  --arg projectId "$PROJ_A" \
  --arg reportId "$REPORT_ID" \
  --arg requestedBy "$ACME_USER_ID" \
  '{messageId:$messageId,tenantId:$tenantId,projectId:$projectId,reportId:$reportId,requestedBy:$requestedBy}')

printf '%s\n%s\n' "$MANUAL_MSG" "$MANUAL_MSG" | docker exec -i "$KAFKA_CONTAINER" \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic reports.generate >/dev/null
sleep 2

PROCESSED_COUNT=$(docker exec "$POSTGRES_CONTAINER" psql -U workhub -d workhub -At \
  -c "select count(*) from processed_messages where message_id = '$MSG_ID';")
assert_eq "$PROCESSED_COUNT" "1" "duplicate Kafka message processed once"

FINAL_AFTER_REPLAY="$TMP_DIR/final-after-replay.json"
status=$(request GET "/reports/$REPORT_ID" "$JWT_A" "" "$FINAL_AFTER_REPLAY")
assert_eq "$status" "200" "report still readable after idempotency replay"
assert_json_eq "$FINAL_AFTER_REPLAY" ".status" "COMPLETED" "report remains COMPLETED after idempotency replay"

printf '\nAll Phase 2 checks passed (%s assertions).\n' "$PASS_COUNT"
