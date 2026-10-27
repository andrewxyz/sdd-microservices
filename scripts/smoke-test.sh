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
cd "$REPO_ROOT" || exit 1

LIFECYCLE=0
SCALE=0
CONFIG_DOWN=0
AUTH_PROFILE=0
for arg in "$@"; do
  case "$arg" in
    --lifecycle) LIFECYCLE=1 ;;
    --scale) SCALE=1 ;;
    --config-down) CONFIG_DOWN=1 ;;
    --auth-profile) AUTH_PROFILE=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

PASSED=0
FAILED=0
pass() { printf 'PASS  %s\n' "$1"; PASSED=$((PASSED + 1)); }
fail() { printf 'FAIL  %s\n      %s\n' "$1" "${2:-}"; FAILED=$((FAILED + 1)); }

SERVICES="auth course enrollment payment certificate review notification analytics"

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
  mailpit_messages="$(curl -fsS "http://localhost:9825/api/v1/messages" 2>/dev/null || true)"
  if printf '%s' "$mailpit_messages" | grep -q "$mail_subject"; then
    pass "a message sent to Mailpit SMTP appears in its web API"
  else
    fail "a message sent to Mailpit SMTP appears in its web API" "subject not found"
  fi
else
  fail "a message sent to Mailpit SMTP appears in its web API" "could not send"
fi

# FR-008: nothing is relayed off the machine.
# Captured rather than piped straight into grep -q: under pipefail a SIGPIPE from the writer would
# make a match look like a miss, and here that would wrongly report PASS.
compose_config="$(docker compose config 2>/dev/null || true)"
if printf '%s' "$compose_config" | grep -qi "MP_SMTP_RELAY"; then
  fail "Mailpit relays nowhere" "a relay is configured in docker-compose.yml"
else
  pass "Mailpit relays nowhere, so local mail stays on the machine"
fi

# ---------------------------------------------------------------- gateway
GW="http://localhost:9080"

gw_status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

# Waits until the gateway can actually reach a service. Needed after anything that recreates a
# container, because registration and the gateway's registry fetch are both periodic: the platform
# reports healthy before it is routable, and SC-002 allows 30s for that to settle.
wait_for_routable() {
  for _ in $(seq 1 24); do
    [ "$(gw_status "$GW/api/v1/echo/ping")" = "200" ] && return 0
    sleep 5
  done
  return 1
}

# A healthy gateway is not yet a routing gateway. Registration and the gateway's registry fetch are
# both periodic, so for a few seconds after start the gateway is up but has no instance to route to
# and correctly answers 503. SC-002 allows 30s for a service to appear, so wait that out before
# asserting anything that depends on routing; failing here would be reporting the clock, not a bug.
if wait_for_routable; then
  pass "the gateway routes a public path to the echo service"
else
  fail "the gateway routes a public path to the echo service" \
       "still $(gw_status "$GW/api/v1/echo/ping") after 120s"
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
# The gateway serves from its cached registry during an outage, so the cache has to be warm first:
# confirm routing works, then take the registry away and check it keeps working.
# up.sh above may have rebuilt and recreated containers, so wait for routing to settle again before
# taking the registry away: this test is about surviving an outage, not about startup timing.
if ! wait_for_routable; then
  fail "requests keep working while the registry is down" \
       "the gateway could not route within 120s, so the outage test could not start"
else
  docker compose stop discovery-server >/dev/null 2>&1

  outage_failures=0
  for _ in $(seq 1 6); do
    [ "$(gw_status "$GW/api/v1/echo/ping")" = "200" ] || outage_failures=$((outage_failures + 1))
    sleep 3
  done

  if [ "$outage_failures" -eq 0 ]; then
    pass "requests keep working throughout an 18 second registry outage"
  else
    fail "requests keep working while the registry is down" \
         "$outage_failures of 6 requests failed during the outage"
  fi

  docker compose start discovery-server >/dev/null 2>&1
fi

recovered=0
for _ in $(seq 1 24); do
  if printf "%s" "$(registry_lists)" | grep -q "ECHO-SERVICE"; then
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

  # How many distinct echo instances the gateway is actually routing to right now, judged by the
  # gateway's own cached view (which is what SC-002 cares about), not by text-matching Eureka's raw
  # JSON: the app-level "ECHO-SERVICE" key appears exactly once regardless of instance count, so
  # counting its occurrences cannot distinguish one copy from two.
  distinct_routed_instances() {
    for _ in $(seq 1 10); do curl -s "$GW/api/v1/echo/ping"; done 2>/dev/null \
      | grep -o '"instanceId":"[^"]*"' | sort -u | wc -l
  }

  registered_two=0
  for _ in $(seq 1 24); do
    if [ "$(distinct_routed_instances)" -ge 2 ]; then
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

  # SC-002, graceful stop: gone from routing within 30 seconds.
  docker compose --profile scale stop echo-service-b >/dev/null 2>&1
  gone_in=-1
  started=$SECONDS
  for _ in $(seq 1 10); do
    [ "$(distinct_routed_instances)" -le 1 ] && { gone_in=$((SECONDS - started)); break; }
    sleep 3
  done
  if [ "$gone_in" -ge 0 ] && [ "$gone_in" -le 30 ]; then
    pass "a gracefully stopped copy is gone from routing within ${gone_in}s (SC-002 allows 30s)"
  else
    fail "a gracefully stopped copy is gone from routing within 30s" "still routed to after ${gone_in}s"
  fi

  docker compose --profile scale start echo-service-b >/dev/null 2>&1
  for _ in $(seq 1 12); do
    [ "$(distinct_routed_instances)" -ge 2 ] && break
    sleep 5
  done

  # SC-002, crash: 503 rather than a hang while the lease is still live, then gone within 90s.
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

  evicted_in=-1
  started=$SECONDS
  for _ in $(seq 1 18); do
    [ "$(distinct_routed_instances)" -le 1 ] && { evicted_in=$((SECONDS - started)); break; }
    sleep 5
  done
  if [ "$evicted_in" -ge 0 ] && [ "$evicted_in" -le 90 ]; then
    pass "a crashed copy is evicted from routing within ${evicted_in}s (SC-002 allows 90s)"
  else
    fail "a crashed copy is evicted from routing within 90s" "still routed to after 90s"
  fi

  docker compose --profile scale rm -sf echo-service-b >/dev/null 2>&1
fi

# --------------------------------------------- live config edit (FR-016, SC-003)
CONFIG_FILE="backend/config-repo/echo-service.yml"
if [ -f "$CONFIG_FILE" ]; then
  original="$(cat "$CONFIG_FILE")"
  # Always put the file back, even if a check below fails or the script is interrupted.
  restore_config() { printf '%s\n' "$original" >"$CONFIG_FILE"; }
  trap restore_config EXIT

  new_greeting="smoke-greeting-$$"
  printf 'echo:\n  greeting: %s\n' "$new_greeting" >"$CONFIG_FILE"

  landed=0
  started=$SECONDS
  for _ in $(seq 1 20); do
    if curl -s "$GW/api/v1/echo/ping" 2>/dev/null | grep -q "$new_greeting" && true; then
      landed=$((SECONDS - started))
      break
    fi
    sleep 1
  done
  restore_config

  if [ "$landed" -gt 0 ] || curl -s "$GW/api/v1/echo/ping" | grep -q "$new_greeting"; then
    if [ "${landed:-99}" -le 10 ]; then
      pass "a central config edit reaches the running service in ${landed}s (SC-003 allows 10s)"
    else
      fail "a central config edit reaches the running service within 10s" "took ${landed}s"
    fi
  else
    fail "a central config edit reaches the running service within 10s" "never arrived within 20s"
  fi
fi

# ------------------------------------------ config server unreachable (FR-017)
if [ "$CONFIG_DOWN" -eq 1 ]; then
  docker compose stop config-server >/dev/null 2>&1

  # Recreate echo-service with --no-deps so Compose does not pull the config server back up first.
  docker compose up -d --no-deps --force-recreate echo-service >/dev/null 2>&1

  exited=0
  for _ in $(seq 1 20); do
    state="$(docker inspect --format '{{.State.Status}}' "$(docker compose ps -aq echo-service)" 2>/dev/null)"
    [ "$state" = "exited" ] && { exited=1; break; }
    sleep 3
  done

  if [ "$exited" = "1" ]; then
    pass "a service exits rather than starting without its central settings"
  else
    fail "a service exits rather than starting without its central settings" \
         "still '$state' after 60s"
  fi

  # Capture first, then match. Piping a long-running command into `grep -q` under `set -o pipefail`
  # reports failure even on a match: grep exits at the first hit, the writer gets SIGPIPE, and its
  # non-zero status becomes the pipeline's. That produced a false negative here.
  echo_logs="$(docker compose logs echo-service 2>&1 || true)"
  if printf '%s' "$echo_logs" | grep -qiE "config|9888"; then
    pass "the failure message names the configuration server"
  else
    fail "the failure message names the configuration server" "no mention in the logs"
  fi

  scripts/up.sh >/tmp/learnhub-config-restore.log 2>&1
  wait_for_routable || true
fi

# ------------------------------------- login required outside local (FR-018)
if [ "$AUTH_PROFILE" -eq 1 ]; then
  SPRING_PROFILES_ACTIVE=dev scripts/up.sh >/tmp/learnhub-auth-up.log 2>&1 || true

  registry_anon="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9761/eureka/apps)"
  config_anon="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9888/echo-service/dev)"
  if [ "$registry_anon" = "401" ] && [ "$config_anon" = "401" ]; then
    pass "outside local, the registry and config server refuse anonymous access"
  else
    fail "outside local, the registry and config server refuse anonymous access" \
         "registry=$registry_anon config=$config_anon"
  fi

  registry_auth="$(curl -s -o /dev/null -w '%{http_code}' \
    -u "${EUREKA_USER}:${EUREKA_PASSWORD}" http://localhost:9761/eureka/apps)"
  config_auth="$(curl -s -o /dev/null -w '%{http_code}' \
    -u "${CONFIG_USER}:${CONFIG_PASSWORD}" http://localhost:9888/echo-service/dev)"
  if [ "$registry_auth" = "200" ] && [ "$config_auth" = "200" ]; then
    pass "the configured credentials are accepted"
  else
    fail "the configured credentials are accepted" "registry=$registry_auth config=$config_auth"
  fi

  # Health probes stay open in every profile, or the container health checks could not work.
  probe="$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9761/actuator/health/readiness)"
  if [ "$probe" = "200" ]; then
    pass "health probes stay open outside local, so health checks keep working"
  else
    fail "health probes stay open outside local" "got $probe"
  fi

  # Services must still start: their own client credentials have to work against a protected server.
  if wait_for_routable; then
    pass "services still register and load settings under a protected profile"
  else
    fail "services still register and load settings under a protected profile" "gateway cannot route"
  fi

  scripts/up.sh >/tmp/learnhub-local-restore.log 2>&1
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

# ---------------------------------------------------------------- frontend (SC-009)
FE="http://localhost:9100"

fe_html="$(curl -s -o /dev/null -w '%{http_code}' "$FE/")"
if [ "$fe_html" = "200" ]; then
  pass "frontend root returns HTTP 200"
else
  fail "frontend root returns HTTP 200" "got $fe_html (is the frontend container running?)"
fi

fe_api="$(curl -s -o /dev/null -w '%{http_code}' "$FE/api/v1/courses")"
if [ "$fe_api" = "200" ]; then
  pass "GET /api/v1/courses proxied through the frontend container returns 200"
else
  fail "GET /api/v1/courses proxied through the frontend container returns 200" "got $fe_api"
fi

# -------------------------------------------- instructor dashboard (spec 009)
# A student pays for and finishes a published course; the instructor's figures must follow within 10
# seconds, a second instructor and a student must be kept out, and the analytics queue must be drained
# (research.md, quickstart.md section 3).
ENV_FILE="${LEARNHUB_ENV_FILE:-$REPO_ROOT/.env}"
if [ -f "$ENV_FILE" ]; then
  # shellcheck source=/dev/null
  set -a
  . "$ENV_FILE"
  set +a
fi

json_string() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }

dash_post() { # dash_post <path> <token> <json-body>
  curl -s -X POST "$GW$1" -H "Authorization: Bearer $2" -H 'Content-Type: application/json' -d "$3"
}

dash_login() { # dash_login <email> <password> -> prints an access token
  curl -s -X POST "$GW/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$2\"}" | json_string accessToken
}

dash_register() { # dash_register <email> <password> -> prints the account id
  curl -s -X POST "$GW/api/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$2\"}" | json_string id
}

dash_etag() { # dash_etag <token> <course-id> -> prints the course's current ETag
  curl -s -D - -o /dev/null -H "Authorization: Bearer $1" "$GW/api/v1/courses/$2/full" \
    | grep -i '^etag:' | tr -d '\r' | awk '{print $2}'
}

dash_code() { # dash_code <token-or-empty> <path> -> prints the HTTP status
  if [ -n "$1" ]; then
    curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $1" "$GW$2"
  else
    curl -s -o /dev/null -w '%{http_code}' "$GW$2"
  fi
}

dash_suffix="$$-$(date +%s)"
dash_password="Smoke-password-1"
dash_price=2999
admin_token="$(dash_login "${ADMIN_EMAIL:-admin@learnhub.example}" "${ADMIN_PASSWORD:-change-me-admin-password-1}")"

if [ -z "$admin_token" ]; then
  fail "instructor dashboard scenario can start" "could not sign in as the administrator"
else
  category_id="$(dash_post /api/v1/categories "$admin_token" "{\"name\":\"Smoke $dash_suffix\"}" | json_string id)"

  instructor_a_id="$(dash_register "smoke-a-$dash_suffix@learnhub.example" "$dash_password")"
  instructor_b_id="$(dash_register "smoke-b-$dash_suffix@learnhub.example" "$dash_password")"
  dash_register "smoke-s-$dash_suffix@learnhub.example" "$dash_password" >/dev/null
  for account in "$instructor_a_id" "$instructor_b_id"; do
    curl -s -X PATCH "$GW/api/v1/auth/accounts/$account/role" -H "Authorization: Bearer $admin_token" \
      -H 'Content-Type: application/json' -d '{"role":"INSTRUCTOR"}' >/dev/null
  done
  # A role change takes effect at the account's next sign-in.
  token_a="$(dash_login "smoke-a-$dash_suffix@learnhub.example" "$dash_password")"
  token_b="$(dash_login "smoke-b-$dash_suffix@learnhub.example" "$dash_password")"
  token_s="$(dash_login "smoke-s-$dash_suffix@learnhub.example" "$dash_password")"

  course_id="$(dash_post /api/v1/courses "$token_a" \
    "{\"title\":\"Smoke dashboard course\",\"description\":\"Created by the smoke test.\",\"categoryId\":\"$category_id\",\"priceCents\":$dash_price,\"level\":\"BEGINNER\"}" \
    | json_string id)"

  if [ -z "$course_id" ] || [ -z "$token_a" ] || [ -z "$token_b" ] || [ -z "$token_s" ]; then
    fail "instructor dashboard scenario can start" "could not create the accounts or the course"
  else
    for lesson in 1 2; do
      curl -s -X POST "$GW/api/v1/courses/$course_id/lessons" -H "Authorization: Bearer $token_a" \
        -H "If-Match: $(dash_etag "$token_a" "$course_id")" -H 'Content-Type: application/json' \
        -d "{\"title\":\"Lesson $lesson\",\"durationMinutes\":5,\"content\":\"Smoke lesson $lesson.\"}" >/dev/null
    done
    curl -s -X PATCH "$GW/api/v1/courses/$course_id/publish" -H "Authorization: Bearer $token_a" \
      -H "If-Match: $(dash_etag "$token_a" "$course_id")" >/dev/null

    lesson_ids="$(curl -s -H "Authorization: Bearer $token_a" "$GW/api/v1/courses/$course_id/full" \
      | grep -o '"lessons":\[.*' | grep -o '"id":"[^"]*"' | cut -d'"' -f4)"

    dash_post /api/v1/checkouts "$token_s" \
      "{\"courseId\":\"$course_id\",\"idempotencyKey\":\"smoke-$dash_suffix\",\"paymentToken\":\"sim-success\"}" >/dev/null
    enrolled=0
    for _ in $(seq 1 20); do
      if [ "$(dash_code "$token_s" "/api/v1/enrollments/$course_id/progress")" = "200" ]; then
        enrolled=1
        break
      fi
      sleep 1
    done
    for lesson_id in $lesson_ids; do
      curl -s -o /dev/null -X PUT -H "Authorization: Bearer $token_s" \
        "$GW/api/v1/enrollments/$course_id/lessons/$lesson_id/complete"
    done

    expected='"enrollments":1,"revenueCents":'"$dash_price"',"completions":1,"certificates":1'
    summary=""
    seen=0
    for _ in $(seq 1 10); do
      summary="$(curl -s -H "Authorization: Bearer $token_a" "$GW/api/v1/instructor/stats/summary")"
      if printf '%s' "$summary" | grep -q "$expected"; then
        seen=1
        break
      fi
      sleep 1
    done
    if [ "$enrolled" -eq 1 ] && [ "$seen" -eq 1 ]; then
      pass "instructor sees enrollments 1, revenue $dash_price, completions 1 and certificates 1 within 10 seconds"
    else
      fail "instructor sees enrollments 1, revenue $dash_price, completions 1 and certificates 1 within 10 seconds" \
        "enrolled=$enrolled summary=$summary"
    fi

    code_b="$(dash_code "$token_b" "/api/v1/instructor/stats/courses/$course_id")"
    if [ "$code_b" = "404" ]; then
      pass "a second instructor gets 404 for the course"
    else
      fail "a second instructor gets 404 for the course" "got $code_b"
    fi
    summary_b="$(curl -s -H "Authorization: Bearer $token_b" "$GW/api/v1/instructor/stats/summary")"
    if printf '%s' "$summary_b" | grep -q '"enrollments":0,"revenueCents":0'; then
      pass "a second instructor sees none of the first instructor's figures"
    else
      fail "a second instructor sees none of the first instructor's figures" "$summary_b"
    fi

    code_s="$(dash_code "$token_s" "/api/v1/instructor/stats/summary")"
    if [ "$code_s" = "403" ]; then
      pass "a student gets 403 on the dashboard"
    else
      fail "a student gets 403 on the dashboard" "got $code_s"
    fi
    code_anon="$(dash_code "" "/api/v1/instructor/stats/summary")"
    if [ "$code_anon" = "401" ]; then
      pass "an anonymous caller gets 401 on the dashboard"
    else
      fail "an anonymous caller gets 401 on the dashboard" "got $code_anon"
    fi

    # The management API refreshes its counters every few seconds, so allow it a little time.
    drained=0
    queue_state=""
    dlq_state=""
    for _ in $(seq 1 15); do
      queue_state="$(curl -s -u "${RABBITMQ_DEFAULT_USER:-learnhub}:${RABBITMQ_DEFAULT_PASS:-}" \
        http://localhost:9673/api/queues/%2F/analytics.q)"
      dlq_state="$(curl -s -u "${RABBITMQ_DEFAULT_USER:-learnhub}:${RABBITMQ_DEFAULT_PASS:-}" \
        http://localhost:9673/api/queues/%2F/analytics.q.dlq)"
      if printf '%s' "$queue_state" | grep -q '"messages_unacknowledged":0' \
        && printf '%s' "$dlq_state" | grep -q '"messages":0'; then
        drained=1
        break
      fi
      sleep 1
    done
    if [ "$drained" -eq 1 ]; then
      pass "analytics.q holds no unacknowledged messages and analytics.q.dlq is empty"
    else
      fail "analytics.q holds no unacknowledged messages and analytics.q.dlq is empty" \
        "queue: ${queue_state:0:200} dlq: ${dlq_state:0:200}"
    fi
  fi
fi

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
