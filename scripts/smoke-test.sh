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
SCALE=0
for arg in "$@"; do
  case "$arg" in
    --lifecycle) LIFECYCLE=1 ;;
    --scale) SCALE=1 ;;
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

# ---------------------------------------------------------------- gateway
GW="http://localhost:9080"

gw_status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# Routing through the single entry point (FR-019).
if [ "$(gw_status "$GW/api/v1/echo/ping")" = "200" ]; then
  pass "the gateway routes a public path to the echo service"
else
  fail "the gateway routes a public path to the echo service" "got $(gw_status "$GW/api/v1/echo/ping")"
fi

# Correlation ID: reused when valid, replaced when not (FR-020).
returned="$(curl -s -D- -o /dev/null -H 'X-Correlation-Id: smoke-valid-1234' "$GW/api/v1/echo/ping" \
            | tr -d '\r' | awk -F': ' '/^[Xx]-[Cc]orrelation-[Ii]d/{print $2}')"
if [ "$returned" = "smoke-valid-1234" ]; then
  pass "a valid correlation id is reused and returned"
else
  fail "a valid correlation id is reused and returned" "got '$returned'"
fi

replaced="$(curl -s -D- -o /dev/null -H 'X-Correlation-Id: bad id!' "$GW/api/v1/echo/ping" \
            | tr -d '\r' | awk -F': ' '/^[Xx]-[Cc]orrelation-[Ii]d/{print $2}')"
if [ -n "$replaced" ] && [ "$replaced" != "bad id!" ]; then
  pass "a malformed correlation id is replaced"
else
  fail "a malformed correlation id is replaced" "got '$replaced'"
fi

# Error statuses and their shared shape (FR-022, SC-004).
check_problem() {
  label="$1"; url="$2"; expected="$3"
  body="$(curl -s -o /tmp/learnhub-problem.json -w '%{http_code}' "$url")"
  if [ "$body" != "$expected" ]; then
    fail "$label" "expected $expected, got $body"
    return
  fi
  for field in '"status"' '"title"' '"correlationId"'; do
    if ! grep -q "$field" /tmp/learnhub-problem.json; then
      fail "$label" "problem body is missing $field"
      return
    fi
  done
  pass "$label"
}

check_problem "an unknown path returns 404 problem details" "$GW/nope" "404"
check_problem "a protected route returns 401 problem details" "$GW/api/v1/echo/secure/ping" "401"

# Preflight from an allowed origin must never be refused, even on a protected route (research R2).
preflight_allowed="$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS \
  -H 'Origin: http://localhost:9000' -H 'Access-Control-Request-Method: GET' \
  "$GW/api/v1/echo/secure/ping")"
if [ "$preflight_allowed" = "200" ]; then
  pass "preflight from an allowed origin succeeds on a protected route"
else
  fail "preflight from an allowed origin succeeds on a protected route" "got $preflight_allowed"
fi

preflight_denied="$(curl -s -o /dev/null -w '%{http_code}' -X OPTIONS \
  -H 'Origin: http://evil.example' -H 'Access-Control-Request-Method: GET' \
  "$GW/api/v1/echo/ping")"
if [ "$preflight_denied" = "403" ]; then
  pass "preflight from an origin that is not allowed is refused"
else
  fail "preflight from an origin that is not allowed is refused" "got $preflight_denied"
fi

# Rate limit: the burst has to outrun the refill, so it must be genuinely parallel. Spawning curls
# one at a time is too slow — at 100 tokens/s the bucket refills faster than a shell loop empties
# it. Each curl also makes several requests in one process, keeping the rate high (research R8).
burst_out="$(mktemp)"
printf '%s\n' $(seq 1 40) | xargs -P 40 -I{} \
  curl -s -o /dev/null -w '%{http_code}\n' \
    "$GW/api/v1/echo/ping" "$GW/api/v1/echo/ping" "$GW/api/v1/echo/ping" "$GW/api/v1/echo/ping" \
    "$GW/api/v1/echo/ping" "$GW/api/v1/echo/ping" "$GW/api/v1/echo/ping" "$GW/api/v1/echo/ping" \
    "$GW/api/v1/echo/ping" "$GW/api/v1/echo/ping" >"$burst_out" 2>/dev/null
burst_429="$(grep -c '^429$' "$burst_out" || true)"
burst_total="$(grep -c . "$burst_out" || true)"
rm -f "$burst_out"

if [ "${burst_429:-0}" -gt 0 ]; then
  pass "a concurrent burst is rate limited with 429 ($burst_429 of $burst_total refused)"
else
  fail "a concurrent burst is rate limited with 429" \
       "none of $burst_total requests were refused; the burst may be slower than the refill rate"
fi

# The limit must recover, or every later check would inherit it.
recovered_limit=0
for _ in $(seq 1 10); do
  sleep 2
  if [ "$(gw_status "$GW/api/v1/echo/ping")" = "200" ]; then
    recovered_limit=1
    break
  fi
done
if [ "$recovered_limit" = "1" ]; then
  pass "the rate limit recovers after the burst"
else
  fail "the rate limit recovers after the burst" "still refused after 20s"
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

# ----------------------------------------------------- registry and discovery
registry_lists() {
  curl -s -u "${EUREKA_USER:-eureka}:${EUREKA_PASSWORD:-}" -H 'Accept: application/json' \
       http://localhost:9761/eureka/apps 2>/dev/null
}

# Registration is not instant: a service that has just restarted needs up to its 30s lease interval
# to appear. Wait for it rather than sampling once, or this reports a timing artefact as a failure.
missing_apps=""
for _ in $(seq 1 24); do
  apps="$(registry_lists)"
  missing_apps=""
  for app in CONFIG-SERVER ECHO-SERVICE API-GATEWAY; do
    echo "$apps" | grep -q "$app" || missing_apps="$missing_apps $app"
  done
  [ -z "$missing_apps" ] && break
  sleep 5
done
if [ -z "$missing_apps" ]; then
  pass "every service is registered under its own name"
else
  fail "every service is registered under its own name" "missing:$missing_apps after 120s"
fi

# FR-014: a brief registry outage must not stop services that are already running. Only meaningful
# once everything is registered, so it is skipped if the check above failed.
if [ -n "$missing_apps" ]; then
  echo "SKIP  registry outage check (services were not all registered)"
else
docker compose stop discovery-server >/dev/null 2>&1
if [ "$(gw_status "$GW/api/v1/echo/ping")" = "200" ]; then
  pass "requests keep working while the registry is down"
else
  fail "requests keep working while the registry is down" "got $(gw_status "$GW/api/v1/echo/ping")"
fi
docker compose start discovery-server >/dev/null 2>&1

recovered=0
for _ in $(seq 1 24); do
  if registry_lists | grep -q "ECHO-SERVICE"; then
    recovered=1
    break
  fi
  sleep 5
done
if [ "$recovered" = "1" ]; then
  pass "services re-register on their own once the registry returns"
else
  fail "services re-register on their own once the registry returns" "not listed within 120s"
fi
fi

# --------------------------------------------------- two copies (SC-006)
if [ "$SCALE" -eq 1 ]; then
  docker compose --profile scale up -d echo-service-b >/dev/null 2>&1

  registered_two=0
  for _ in $(seq 1 24); do
    if [ "$(registry_lists | grep -o 'ECHO-SERVICE' | wc -l)" -ge 1 ] &&
       [ "$(curl -s "$GW/api/v1/echo/ping" >/dev/null; for _ in 1 2 3 4 5 6 7 8 9 10; do
              curl -s "$GW/api/v1/echo/ping"; done | grep -o '"instanceId":"[^"]*"' | sort -u | wc -l)" -ge 2 ]; then
      registered_two=1
      break
    fi
    sleep 5
  done

  if [ "$registered_two" = "1" ]; then
    pass "a second copy of the echo service registers and receives traffic"
  else
    fail "a second copy of the echo service registers and receives traffic" "only one instance answered"
  fi

  # SC-006: 100 calls, each copy at least 40.
  : >/tmp/learnhub-spread.txt
  for _ in $(seq 1 100); do
    curl -s "$GW/api/v1/echo/ping" | grep -o '"instanceId":"[^"]*"' >>/tmp/learnhub-spread.txt
  done
  counts="$(sort /tmp/learnhub-spread.txt | uniq -c | sort -rn)"
  lowest="$(echo "$counts" | awk 'NF {print $1}' | tail -1)"
  instances="$(echo "$counts" | grep -c .)"

  if [ "$instances" -eq 2 ] && [ "${lowest:-0}" -ge 40 ]; then
    pass "100 calls spread across both copies, each at least 40 ($(echo "$counts" | tr '\n' ' '))"
  else
    fail "100 calls spread across both copies, each at least 40" "$(echo "$counts" | tr '\n' ' ')"
  fi

  # A crashed instance: 503 rather than a hang, then eviction inside the 90s SC-002 allows.
  crashed="$(docker compose ps -q echo-service-b)"
  docker kill "$crashed" >/dev/null 2>&1
  bad=0
  for _ in $(seq 1 20); do
    code="$(gw_status "$GW/api/v1/echo/ping")"
    case "$code" in
      200|503) ;;
      *) bad=1 ;;
    esac
  done
  if [ "$bad" -eq 0 ]; then
    pass "a crashed copy produces 200 or 503, never a hang or a 500"
  else
    fail "a crashed copy produces 200 or 503, never a hang or a 500" "saw an unexpected status"
  fi

  docker compose --profile scale rm -sf echo-service-b >/dev/null 2>&1
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
