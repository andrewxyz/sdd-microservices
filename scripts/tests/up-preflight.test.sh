#!/usr/bin/env bash
# Tests the three preflight checks in scripts/up.sh: container runtime, required settings, host ports.
#
# No real containers are started: a fake `docker` on PATH answers the probes and records whether
# `docker compose up` was ever reached, which is how each case proves preflight stopped first.
#
# Usage: bash scripts/tests/up-preflight.test.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FAKEBIN="$REPO_ROOT/scripts/tests/fakebin"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"; [ -n "${LISTENER_PID:-}" ] && kill "$LISTENER_PID" 2>/dev/null' EXIT

PASSED=0
FAILED=0

pass() { printf 'PASS  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf 'FAIL  %s\n     %s\n' "$1" "${2:-}"; FAILED=$((FAILED + 1)); }

# Every variable up.sh requires, with values that are present and non-empty.
write_full_env() {
  cat >"$1" <<'EOF'
SPRING_PROFILES_ACTIVE=local
EUREKA_USER=eureka
EUREKA_PASSWORD=change-me-eureka
CONFIG_USER=config
CONFIG_PASSWORD=change-me-config
POSTGRES_USER=learnhub
POSTGRES_PASSWORD=change-me-postgres
AUTH_DB_PASSWORD=change-me-auth
COURSE_DB_PASSWORD=change-me-course
ENROLLMENT_DB_PASSWORD=change-me-enrollment
PAYMENT_DB_PASSWORD=change-me-payment
CERTIFICATE_DB_PASSWORD=change-me-certificate
REVIEW_DB_PASSWORD=change-me-review
NOTIFICATION_DB_PASSWORD=change-me-notification
ANALYTICS_DB_PASSWORD=change-me-analytics
RABBITMQ_DEFAULT_USER=learnhub
RABBITMQ_DEFAULT_PASS=change-me-rabbit
JWT_SIGNING_SECRET=change-me-jwt-signing-secret-32-bytes-minimum
ADMIN_EMAIL=admin@learnhub.example
ADMIN_PASSWORD=change-me-admin
EOF
}

# Runs up.sh with the fake docker and a given .env, capturing output, exit code and elapsed seconds.
run_up() {
  local env_file="$1"; shift
  local log="$WORK/out.txt"
  : >"$WORK/docker-calls.txt"

  local started=$SECONDS
  (
    cd "$REPO_ROOT" || exit 1
    PATH="$FAKEBIN:$PATH" \
    LEARNHUB_ENV_FILE="$env_file" \
    FAKE_DOCKER_LOG="$WORK/docker-calls.txt" \
    "$@" bash scripts/up.sh
  ) >"$log" 2>&1
  RUN_EXIT=$?
  RUN_ELAPSED=$((SECONDS - started))
  RUN_OUTPUT="$(cat "$log")"
  RUN_CALLS="$(cat "$WORK/docker-calls.txt")"
}

assert_no_containers_started() {
  case "$RUN_CALLS" in
    *"compose up"*) return 1 ;;
    *) return 0 ;;
  esac
}

if [ ! -f "$REPO_ROOT/scripts/up.sh" ]; then
  echo "scripts/up.sh does not exist yet"
  exit 1
fi

# ---------------------------------------------------------------- runtime check
write_full_env "$WORK/full.env"
run_up "$WORK/full.env" env FAKE_DOCKER_DOWN=1
if [ "$RUN_EXIT" -eq 0 ]; then
  fail "runtime down: must fail" "exit was 0"
elif ! grep -qiE "docker|container runtime" <<<"$RUN_OUTPUT"; then
  fail "runtime down: message must name the container runtime" "$RUN_OUTPUT"
elif ! assert_no_containers_started; then
  fail "runtime down: must not start containers" "$RUN_CALLS"
elif [ "$RUN_ELAPSED" -ge 30 ]; then
  fail "runtime down: must fail within 30s" "took ${RUN_ELAPSED}s"
else
  pass "runtime down is reported and nothing is started"
fi

# ------------------------------------------------------------- settings check
grep -v -e '^CONFIG_PASSWORD=' -e '^AUTH_DB_PASSWORD=' "$WORK/full.env" >"$WORK/missing.env"
echo 'REVIEW_DB_PASSWORD=' >>"$WORK/missing.env"   # present but empty counts as missing
run_up "$WORK/missing.env"
if [ "$RUN_EXIT" -eq 0 ]; then
  fail "missing settings: must fail" "exit was 0"
elif ! grep -q "CONFIG_PASSWORD" <<<"$RUN_OUTPUT"; then
  fail "missing settings: must name CONFIG_PASSWORD" "$RUN_OUTPUT"
elif ! grep -q "AUTH_DB_PASSWORD" <<<"$RUN_OUTPUT"; then
  fail "missing settings: must name AUTH_DB_PASSWORD" "$RUN_OUTPUT"
elif ! grep -q "REVIEW_DB_PASSWORD" <<<"$RUN_OUTPUT"; then
  fail "missing settings: an empty value must be reported too" "$RUN_OUTPUT"
elif ! assert_no_containers_started; then
  fail "missing settings: must not start containers" "$RUN_CALLS"
elif [ "$RUN_ELAPSED" -ge 30 ]; then
  fail "missing settings: must fail within 30s" "took ${RUN_ELAPSED}s"
else
  pass "every missing or empty setting is named, and nothing is started"
fi

# ----------------------------------------------------------------- port check
if command -v java >/dev/null 2>&1; then
  java "$REPO_ROOT/scripts/tests/Listen.java" 9761 >"$WORK/listener.txt" 2>&1 &
  LISTENER_PID=$!
  for _ in $(seq 1 50); do
    grep -q "listening" "$WORK/listener.txt" 2>/dev/null && break
    sleep 0.2
  done

  run_up "$WORK/full.env"
  if [ "$RUN_EXIT" -eq 0 ]; then
    fail "occupied port: must fail" "exit was 0"
  elif ! grep -q "9761" <<<"$RUN_OUTPUT"; then
    fail "occupied port: must name the port" "$RUN_OUTPUT"
  elif ! grep -q "discovery-server" <<<"$RUN_OUTPUT"; then
    fail "occupied port: must name the component that needs it" "$RUN_OUTPUT"
  elif ! assert_no_containers_started; then
    fail "occupied port: must not start containers" "$RUN_CALLS"
  elif [ "$RUN_ELAPSED" -ge 30 ]; then
    fail "occupied port: must fail within 30s" "took ${RUN_ELAPSED}s"
  else
    pass "an occupied port names the port and the component, and nothing is started"
  fi

  # Same occupied port, but now claimed by this project's own containers: a second start is harmless.
  run_up "$WORK/full.env" env FAKE_OWN_PORTS=9761
  if [ "$RUN_EXIT" -ne 0 ]; then
    fail "own port: a second start must proceed" "$RUN_OUTPUT"
  elif assert_no_containers_started; then
    fail "own port: must reach docker compose up" "$RUN_CALLS"
  else
    pass "a port held by this project's own containers is skipped, so a second start proceeds"
  fi

  kill "$LISTENER_PID" 2>/dev/null
  LISTENER_PID=""
else
  echo "SKIP  port checks (no java on PATH)"
fi

# ------------------------------------------------------------------- happy path
run_up "$WORK/full.env"
if [ "$RUN_EXIT" -ne 0 ]; then
  fail "happy path: must succeed" "$RUN_OUTPUT"
elif assert_no_containers_started; then
  fail "happy path: must reach docker compose up" "$RUN_CALLS"
else
  pass "with everything in place, preflight passes and the stack is started"
fi

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
