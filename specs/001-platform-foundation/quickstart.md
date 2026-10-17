# Quickstart: Backend Platform Foundation

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Contracts**: [contracts/](contracts/)

A run-and-verify guide for the finished feature. Each scenario names the requirement it proves. Run all
commands from the repo root in bash (Git Bash or WSL on Windows). `scripts/smoke-test.sh` automates most
of these; this guide is the manual version.

## Prerequisites

- A container runtime with Docker Compose v2 running. No local JDK or Maven is needed.
- Free host ports: 9080, 9761, 9888, 9099, 9432, 9672, 9673, 9025, 9825.
- `cp .env.example .env` and replace the placeholder values (names are listed in
  [data-model.md](data-model.md#environment-settings-envexample)).

Ports follow the constitution's port table; the gateway is `http://localhost:9080`, the registry dashboard
`http://localhost:9761`, Mailpit `http://localhost:9825`, RabbitMQ management `http://localhost:9673`.

## 1. Start, stop, restart, reset (US1: FR-001 to FR-005, SC-001, SC-008, SC-009)

| Step | Command | Expected |
|------|---------|----------|
| Start | `scripts/up.sh` | Preflight passes, images build, every component reports healthy in under 3 minutes (see research R12 on cold builds) |
| Status | `docker compose ps` | All long-running services `healthy`; `db-init` exited with code 0 |
| Restart | `scripts/up.sh` again | Healthy again, no errors, data still present |
| Stop | `docker compose down` | Containers removed, volumes kept |
| Reset | `docker compose down -v` | Containers and data wiped; next start behaves like a clean checkout |

Failure checks (each must stop before any container starts and finish in under 30 seconds):

- Delete or blank one value in `.env` and run `scripts/up.sh`: the message names that setting.
- Hold a port (for example run any listener on 9080) and run `scripts/up.sh`: the message names port 9080
  and `api-gateway`.
- Stop the container runtime and run `scripts/up.sh`: the message says the runtime is not running.

## 2. Discovery by name (US2: FR-010 to FR-014, SC-002, SC-006)

1. Open `http://localhost:9761` or run
   `curl -s -H 'Accept: application/json' http://localhost:9761/eureka/apps`.
   Expect `API-GATEWAY`, `CONFIG-SERVER` and `ECHO-SERVICE` within 30 seconds of start.
2. Start the second copy: `docker compose --profile scale up -d echo-service-b`.
   Expect two `ECHO-SERVICE` instances within 30 seconds.
3. Spread check:
   `for i in $(seq 100); do curl -s http://localhost:9080/api/v1/echo/ping; done | grep -o '"instanceId":"[^"]*"' | sort | uniq -c`
   Expect two instance IDs, each with at least 40 hits.
4. Graceful stop: `docker compose stop echo-service-b`. Expect the instance gone from the dashboard within
   30 seconds.
5. Crash: `docker kill <echo-service-b container>`. Expect brief 503 responses through the gateway for that
   instance, then removal from the registry after about 65 to 70 seconds (research R5; this is longer than
   30 seconds by design of the Eureka lease logic).
6. Registry outage: `docker compose stop discovery-server`. Expect `/api/v1/echo/ping` to keep answering 200.
   Then `docker compose start discovery-server`. Expect all instances listed again without restarting them.

## 3. Central configuration (US3: FR-015 to FR-018, SC-003)

1. Read the greeting: `curl -s http://localhost:9080/api/v1/echo/ping`.
2. Edit `echo.greeting` in `backend/config-repo/echo-service.yml` and save. No commit, no restart.
3. Repeat the call. Expect the new greeting within 10 seconds.
4. CORS and rate-limit values in `backend/config-repo/api-gateway.yml` change the same way (verified on the
   target versions; research R1).
5. Config server down at start: `docker compose stop config-server`, then
   `docker compose up -d --no-deps --force-recreate echo-service`. Expect echo to retry briefly, then exit
   with a message saying central configuration could not be reached (check `docker compose logs echo-service`).
   Restore with `scripts/up.sh`.
6. Login outside local: with `SPRING_PROFILES_ACTIVE` set to a non-`local` profile, `curl -i http://localhost:9888/echo-service/default`
   and the registry dashboard return 401 without credentials and succeed with `CONFIG_USER`/`EUREKA_USER`
   credentials. Under `local` no login is needed.

## 4. Gateway behavior (US4: FR-019 to FR-025, SC-004, SC-005)

Expected statuses for `http://localhost:9080` (every error body is `application/problem+json` matching
[problem-details.schema.json](contracts/problem-details.schema.json) and carries `correlationId`):

| Check | Command | Expected |
|-------|---------|----------|
| Routed | `curl -i http://localhost:9080/api/v1/echo/ping` | 200, body per [echo-service.openapi.yaml](contracts/echo-service.openapi.yaml), `X-Correlation-Id` header |
| Valid client ID reused | `curl -i -H 'X-Correlation-Id: abc12345' .../ping` | response header `abc12345`; echo body `correlationId` `abc12345` |
| Malformed ID replaced | `curl -i -H 'X-Correlation-Id: bad id!' .../ping` | a new UUID in the response header |
| Unknown path | `curl -i http://localhost:9080/nope` | 404 |
| Protected route | `curl -i http://localhost:9080/api/v1/echo/secure/ping` | 401 |
| No healthy copy | `docker compose stop echo-service`, then call `/ping` | 503 in under 5 seconds |
| Rate limit | call `/ping` in a tight loop until the limit in `api-gateway.yml` is exceeded | 429 problem details; recovers after refill |
| Preflight allowed | `curl -i -X OPTIONS -H 'Origin: <allowed origin>' -H 'Access-Control-Request-Method: GET' .../api/v1/echo/secure/ping` | 200 with CORS headers, never 401 |
| Preflight refused | same with an origin not in the allowed list | 403 |
| Log carries ID | `docker compose logs api-gateway echo-service` | structured (ECS) lines containing the correlation ID of a request you made |

Clients on the same address share one rate limit (X-Forwarded-For is ignored). From the host through Docker
Desktop, all requests appear from one address, so per-address behavior is covered by the gateway tests
rather than by this manual check.

## 5. Skeleton for new services (US5: FR-026 to FR-030, SC-007)

Follow the "add a service" section of the README: new module under `backend/`, parent POM entry, a
`config-repo/<name>.yml`, a route in `api-gateway.yml`, and a Compose entry. Expected: the new service
registers, loads its settings, reports health and is routable without registration, configuration or health
code. The echo source check and the version check (`mvn -f backend/pom.xml verify`) enforce this.

Event envelope: the `shared-events` tests serialize an envelope, validate it against
[event-envelope.schema.json](contracts/event-envelope.schema.json), and round-trip it through RabbitMQ on
`learning.events`.

## 6. Supporting services (FR-006 to FR-009)

- Databases: `docker compose exec postgres psql -U "$POSTGRES_USER" -c '\l'` lists `auth`, `course`,
  `enrollment`, `payment`, `certificate`, `review`, `notification`. Running `scripts/up.sh` again must not
  fail on existing roles or databases.
- Mail: send a message to `smtp://localhost:9025` (for example with `curl --url smtp://localhost:9025
  --mail-from a@example.com --mail-rcpt b@example.com -T message.txt`) and see it at
  `http://localhost:9825`. Nothing is delivered elsewhere.
- Secrets: `git grep -n -i -E 'password|secret' -- . ':!.env.example'` shows no real secret; `.env` is ignored.

## Full automated pass

`mvn -f backend/pom.xml verify` (unit and Testcontainers tests), then `scripts/up.sh` and
`scripts/smoke-test.sh`. CI runs exactly this sequence.
