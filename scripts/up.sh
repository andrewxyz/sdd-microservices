#!/usr/bin/env bash
# Starts the whole LearnHub platform (FR-001).
#
# Three preflight checks run before any container starts, so a problem is reported clearly instead of
# surfacing as a half-started stack: the container runtime, then required settings, then host ports.
# Each names exactly what is wrong and finishes well inside the 30 seconds SC-009 allows.
#
# Usage: scripts/up.sh [extra docker compose up arguments]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ENV_FILE="${LEARNHUB_ENV_FILE:-$REPO_ROOT/.env}"
WAIT_TIMEOUT="${LEARNHUB_WAIT_TIMEOUT:-300}"

# Host port -> the component that needs it, from the constitution's port table. 9002-9007 are
# still reserved for business services and nothing binds them yet.
PORTS=(
  "9080:api-gateway"
  "9761:discovery-server"
  "9888:config-server"
  "9001:auth-service"
  "9099:echo-service"
  "9432:postgres"
  "9672:rabbitmq"
  "9673:rabbitmq-management"
  "9025:mailpit-smtp"
  "9825:mailpit-ui"
)

REQUIRED_SETTINGS=(
  SPRING_PROFILES_ACTIVE
  EUREKA_USER EUREKA_PASSWORD
  CONFIG_USER CONFIG_PASSWORD
  POSTGRES_USER POSTGRES_PASSWORD
  AUTH_DB_PASSWORD COURSE_DB_PASSWORD ENROLLMENT_DB_PASSWORD PAYMENT_DB_PASSWORD
  CERTIFICATE_DB_PASSWORD REVIEW_DB_PASSWORD NOTIFICATION_DB_PASSWORD
  RABBITMQ_DEFAULT_USER RABBITMQ_DEFAULT_PASS
  JWT_SIGNING_SECRET ADMIN_EMAIL ADMIN_PASSWORD
)

die() {
  printf '\nERROR: %s\n' "$1" >&2
  shift
  for line in "$@"; do
    printf '  %s\n' "$line" >&2
  done
  printf '\nNothing was started.\n' >&2
  exit 1
}

step() { printf '==> %s\n' "$1"; }

# ----------------------------------------------------------- 1. container runtime
step "Checking the container runtime"
if ! command -v docker >/dev/null 2>&1; then
  die "docker was not found on PATH." \
      "Install Docker, or if it lives in WSL, run this script from inside WSL."
fi
if ! docker info >/dev/null 2>&1; then
  die "The container runtime is not running (docker info failed)." \
      "Start Docker Desktop, or 'sudo service docker start' inside WSL, then try again."
fi
if ! docker compose version >/dev/null 2>&1; then
  die "Docker Compose v2 is not available ('docker compose version' failed)." \
      "This project needs Compose v2, not the older docker-compose script."
fi

# --------------------------------------------------------------- 2. required settings
step "Checking required settings"
if [ ! -f "$ENV_FILE" ]; then
  die "No settings file at $ENV_FILE." \
      "Copy the example and fill it in:  cp .env.example .env"
fi

# Read the file without executing it, so a stray command in .env cannot run.
declare -A SETTINGS=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"                       # tolerate a CRLF file edited on Windows
  [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
  [[ "$line" != *=* ]] && continue
  key="${line%%=*}"
  value="${line#*=}"
  key="$(printf '%s' "$key" | tr -d '[:space:]')"
  SETTINGS["$key"]="$value"
done <"$ENV_FILE"

missing=()
for name in "${REQUIRED_SETTINGS[@]}"; do
  value="${SETTINGS[$name]:-}"
  if [ -z "${value//[[:space:]]/}" ]; then
    missing+=("$name")
  fi
done

if [ ${#missing[@]} -gt 0 ]; then
  lines=()
  for name in "${missing[@]}"; do
    lines+=("$name is missing or empty in $ENV_FILE")
  done
  lines+=("" "Every name is listed with a placeholder in .env.example.")
  die "${#missing[@]} required setting(s) are not set." "${lines[@]}"
fi

# ------------------------------------------------------------------ 3. host ports
step "Checking host ports"

# Ports this project's own containers already publish: if the stack is already up, re-running this
# script must be harmless rather than a port clash (FR-004).
own_ports=""
if own_ps="$(docker compose ps --format '{{.Publishers}}' 2>/dev/null)"; then
  own_ports="$(printf '%s' "$own_ps" | grep -oE '[0-9]+->' | tr -d '>-' || true)"
fi
# The fake docker used by the preflight test prints plain "0.0.0.0:9761->9761/tcp" lines.
if [ -z "$own_ports" ] && own_ps="$(docker compose ps 2>/dev/null)"; then
  own_ports="$(printf '%s' "$own_ps" | grep -oE ':[0-9]+->' | tr -d ':>-' || true)"
fi

port_in_use() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && exec 3>&- && return 0
  return 1
}

clashes=()
for entry in "${PORTS[@]}"; do
  port="${entry%%:*}"
  component="${entry#*:}"
  if printf '%s\n' $own_ports | grep -qx "$port"; then
    continue   # already ours
  fi
  if port_in_use "$port"; then
    clashes+=("port $port is already in use, and $component needs it")
  fi
done

if [ ${#clashes[@]} -gt 0 ]; then
  die "${#clashes[@]} required port(s) are occupied." \
      "${clashes[@]}" \
      "" \
      "Stop whatever is using them, or run 'docker compose down' if an older stack is still up."
fi

# ---------------------------------------------------------------------- 4. start
step "Starting the platform (building images if needed)"

# `docker compose up --wait` exits 1 on a clean start even when everything worked, because the
# one-shot db-init container ends in "exited" rather than "running" and nothing depends on it with
# service_completed_successfully. Measured on Compose 2.40.3: the message is literally
# "container learnhub-db-init-1 exited (0)" while every other service reports Healthy.
#
# So a non-zero exit is not conclusive. Check the real state instead: the run succeeded when every
# long-running service is healthy and db-init exited 0. Anything else is a genuine failure.
compose_up_ok=0
docker compose --env-file "$ENV_FILE" up --build --wait --wait-timeout "$WAIT_TIMEOUT" "$@" || compose_up_ok=$?

unhealthy=""
while IFS=$'\t' read -r service state health; do
  [ -z "$service" ] && continue
  if [ "$service" = "db-init" ]; then
    continue   # judged by its exit code below
  fi
  if [ "$health" != "healthy" ]; then
    unhealthy="$unhealthy  $service (state: ${state:-unknown}, health: ${health:-none})"$'\n'
  fi
done < <(docker compose ps --format '{{.Service}}\t{{.State}}\t{{.Health}}' 2>/dev/null)

db_init_container="$(docker compose ps -aq db-init 2>/dev/null || true)"
db_init_exit=""
if [ -n "$db_init_container" ]; then
  db_init_exit="$(docker inspect --format '{{.State.ExitCode}}' "$db_init_container" 2>/dev/null || true)"
fi

if [ -n "$unhealthy" ] || { [ -n "$db_init_exit" ] && [ "$db_init_exit" != "0" ]; }; then
  printf '\nERROR: the platform did not come up healthy within %ss.\n\n' "$WAIT_TIMEOUT" >&2
  [ -n "$unhealthy" ] && printf 'Not healthy:\n%s\n' "$unhealthy" >&2
  [ -n "$db_init_exit" ] && [ "$db_init_exit" != "0" ] &&
    printf 'Database provisioning failed (db-init exit code %s).\n\n' "$db_init_exit" >&2
  docker compose ps -a >&2 || true
  printf '\nLogs:  docker compose logs <service>\n' >&2
  exit 1
fi

if [ "$compose_up_ok" -ne 0 ]; then
  printf '    (docker compose up reported %s, which is the known one-shot db-init case; every\n' "$compose_up_ok"
  printf '     service is healthy and db-init exited 0, so the platform is up.)\n'
fi

step "Platform is up"
docker compose ps
cat <<'EOF'

  Gateway            http://localhost:9080   (added in the gateway story)
  Registry           http://localhost:9761
  Config server      http://localhost:9888
  Echo service       http://localhost:9099/api/v1/echo/ping
  RabbitMQ console   http://localhost:9673
  Mailpit            http://localhost:9825

  Stop:   docker compose down
  Reset:  docker compose down -v     (wipes stored data)

  Note: services register with the registry a few seconds after they report healthy, so a request
  through the gateway can answer 503 for up to about 30 seconds after this message. That is the
  registry doing its job, not a failure; retry and it clears.
EOF
