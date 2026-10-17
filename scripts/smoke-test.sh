#!/usr/bin/env bash
# Exercises the running platform end to end. Start it with scripts/up.sh first.
#
#   scripts/smoke-test.sh              default checks
#   scripts/smoke-test.sh --lifecycle  also proves data survives a restart and is wiped by down -v
#
# Every check prints PASS or FAIL; any FAIL makes the script exit non-zero. CI runs every mode, so
# no acceptance scenario goes ungated (constitution Principle VI).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

LIFECYCLE=0
for arg in "$@"; do
  case "$arg" in
    --lifecycle) LIFECYCLE=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

PASSED=0
FAILED=0
pass() { printf 'PASS  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf 'FAIL  %s\n      %s\n' "$1" "${2:-}"; FAILED=$((FAILED + 1)); }

SERVICES="auth course enrollment payment certificate review notification"

psql_admin() {
  docker compose exec -T postgres psql -U "${POSTGRES_USER:-learnhub}" -d postgres -tAc "$1" 2>/dev/null
}

# ------------------------------------------------------------------ health
if docker compose ps --format '{{.Service}} {{.State}} {{.Health}}' >/tmp/learnhub-ps.txt 2>&1; then
  unhealthy="$(awk '$1 != "db-init" && $3 != "healthy" {print $1" ("$2" "$3")"}' /tmp/learnhub-ps.txt)"
  if [ -z "$unhealthy" ]; then
    pass "every long-running service reports healthy"
  else
    fail "every long-running service reports healthy" "$unhealthy"
  fi
else
  fail "docker compose ps" "could not list services"
fi

db_init_exit="$(docker inspect --format '{{.State.ExitCode}}' "$(docker compose ps -aq db-init 2>/dev/null)" 2>/dev/null)"
if [ "$db_init_exit" = "0" ]; then
  pass "db-init completed successfully"
else
  fail "db-init completed successfully" "exit code was '${db_init_exit:-unknown}'"
fi

# --------------------------------------------------------------- databases
missing_dbs=""
for service in $SERVICES; do
  found="$(psql_admin "SELECT 1 FROM pg_database WHERE datname = '$service'")"
  [ "$found" = "1" ] || missing_dbs="$missing_dbs $service"
done
if [ -z "$missing_dbs" ]; then
  pass "all seven service databases exist"
else
  fail "all seven service databases exist" "missing:$missing_dbs"
fi

missing_roles=""
for service in $SERVICES; do
  found="$(psql_admin "SELECT 1 FROM pg_roles WHERE rolname = '$service'")"
  [ "$found" = "1" ] || missing_roles="$missing_roles $service"
done
if [ -z "$missing_roles" ]; then
  pass "all seven service logins exist"
else
  fail "all seven service logins exist" "missing:$missing_roles"
fi

# ---------------------------------------------------------------- rabbitmq
if curl -fsS -u "${RABBITMQ_DEFAULT_USER:-learnhub}:${RABBITMQ_DEFAULT_PASS:-}" \
     http://localhost:9673/api/overview >/dev/null 2>&1; then
  pass "RabbitMQ management answers on 9673"
elif curl -fsS -o /dev/null -w '%{http_code}' http://localhost:9673 2>/dev/null | grep -q '^[23]'; then
  pass "RabbitMQ management answers on 9673"
else
  fail "RabbitMQ management answers on 9673" "no response"
fi

# ------------------------------------------------------------------- mail
mail_subject="learnhub-smoke-$$"
if printf 'From: smoke@learnhub.test\r\nTo: inbox@learnhub.test\r\nSubject: %s\r\n\r\nhello\r\n' \
     "$mail_subject" >/tmp/learnhub-mail.txt &&
   curl -fsS --url smtp://localhost:9025 --mail-from smoke@learnhub.test \
        --mail-rcpt inbox@learnhub.test -T /tmp/learnhub-mail.txt >/dev/null 2>&1; then
  sleep 1
  if curl -fsS "http://localhost:9825/api/v1/messages" 2>/dev/null | grep -q "$mail_subject"; then
    pass "a message sent to Mailpit SMTP appears in its web API"
  else
    fail "a message sent to Mailpit SMTP appears in its web API" "subject not found"
  fi
else
  fail "a message sent to Mailpit SMTP appears in its web API" "could not send"
fi

# FR-008: nothing is relayed off the machine.
if docker compose config 2>/dev/null | grep -qi "MP_SMTP_RELAY"; then
  fail "Mailpit relays nowhere" "a relay is configured in docker-compose.yml"
else
  pass "Mailpit relays nowhere, so local mail stays on the machine"
fi

# --------------------------------------------------- second start is harmless
# Compare the set of services, not container ids: db-init is expected to re-run on every start
# (FR-007), so it legitimately gets a new container each time.
before_services="$(docker compose ps --format '{{.Service}}' | sort)"
before_count="$(docker compose ps -q | wc -l)"
if scripts/up.sh >/tmp/learnhub-second-up.log 2>&1; then
  after_services="$(docker compose ps --format '{{.Service}}' | sort)"
  after_count="$(docker compose ps -q | wc -l)"
  still_healthy="$(docker compose ps --format '{{.Service}} {{.Health}}' | awk '$2 != "healthy" {print $1}')"

  if [ "$before_services" != "$after_services" ] || [ "$before_count" != "$after_count" ]; then
    fail "running up.sh again starts no duplicate containers" \
         "services before: $(echo "$before_services" | tr '\n' ' ')/ after: $(echo "$after_services" | tr '\n' ' ')"
  elif [ -n "$still_healthy" ]; then
    fail "running up.sh again leaves everything healthy" "not healthy: $still_healthy"
  else
    pass "running up.sh again while the stack is up succeeds and starts no duplicates"
  fi
else
  fail "running up.sh again while the stack is up succeeds" "$(tail -5 /tmp/learnhub-second-up.log)"
fi

# ----------------------------------------------------------------- lifecycle
if [ "$LIFECYCLE" -eq 1 ]; then
  marker="smoke_marker_$$"
  psql_admin "CREATE TABLE IF NOT EXISTS $marker (id int)" >/dev/null

  docker compose down >/dev/null 2>&1
  scripts/up.sh >/tmp/learnhub-restart.log 2>&1
  if [ "$(psql_admin "SELECT 1 FROM information_schema.tables WHERE table_name = '$marker'")" = "1" ]; then
    pass "stored data survives docker compose down and a restart"
  else
    fail "stored data survives docker compose down and a restart" "marker table is gone"
  fi

  docker compose down -v >/dev/null 2>&1
  scripts/up.sh >/tmp/learnhub-reset.log 2>&1
  if [ "$(psql_admin "SELECT 1 FROM information_schema.tables WHERE table_name = '$marker'")" = "1" ]; then
    fail "docker compose down -v wipes stored data" "marker table is still there"
  else
    pass "docker compose down -v wipes stored data"
  fi
fi

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
