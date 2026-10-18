# How to Run LearnHub

A step-by-step guide to starting the backend platform foundation, poking at it with `curl`, and
running the automated test suites. Every command below has been run against this repo.

For the architecture and the "why", see [README.md](README.md). This file is the hands-on companion.

## 1. Prerequisites

- A container runtime with **Docker Compose v2**. No local JDK or Maven is needed to run the
  platform — the service images are built inside containers.
- A **bash** shell for the scripts. On Windows, use Git Bash or WSL.
  - **If Docker itself runs inside WSL** (not Docker Desktop for Windows), run every command in this
    guide from inside WSL — `wsl` then `cd` into the repo — not from PowerShell or Git Bash on the
    Windows side. Windows-side tools cannot reach a Docker daemon that only exists inside WSL.
- The ports in the table below must be free (`scripts/up.sh` checks this for you and tells you
  exactly which port is taken if not).

## 2. Step-by-step: start everything

```bash
# 1. Clone and enter the repo (skip if you already have it)
git clone <this-repo-url> sdd-microservices
cd sdd-microservices

# 2. Copy the example settings and fill in your own values
cp .env.example .env
#    Edit .env: every PASSWORD value starts as "change-me-..."; replace them with anything.
#    Do not commit .env — it's git-ignored on purpose.

# 3. Start the whole platform with one command
scripts/up.sh
```

`scripts/up.sh` checks three things before it starts a single container, in order, and stops with a
clear message naming the exact problem if any of them fail:

1. the container runtime is actually running,
2. every setting in `.env` is present and non-empty,
3. every host port the platform needs is free (or already held by this project's own containers,
   in which case it's skipped — running `scripts/up.sh` a second time is always safe).

Then it runs `docker compose up --build --wait`, so it does not return until every component reports
healthy. First run builds four images from scratch and can take several minutes; later runs reuse the
images and are much faster.

When it finishes you'll see a summary:

```
==> Platform is up
NAME                          STATUS
learnhub-api-gateway-1        Up (healthy)
learnhub-config-server-1      Up (healthy)
learnhub-discovery-server-1   Up (healthy)
learnhub-echo-service-1       Up (healthy)
learnhub-mailpit-1            Up (healthy)
learnhub-postgres-1           Up (healthy)
learnhub-rabbitmq-1           Up (healthy)

  Gateway            http://localhost:9080
  Registry           http://localhost:9761
  Config server      http://localhost:9888
  Echo service       http://localhost:9099/api/v1/echo/ping
  RabbitMQ console   http://localhost:9673
  Mailpit            http://localhost:9825
```

**One thing to know:** a service can report "healthy" a few seconds before it has actually
registered with the registry, and the gateway only refreshes its view of the registry every 5
seconds. So a request through the gateway can briefly answer `503` for up to ~30 seconds right after
startup finishes. That's the registry doing its job, not a failure — wait a moment and retry.

### Stop, restart, reset

| Action | Command | Effect |
|--------|---------|--------|
| Stop | `docker compose down` | Containers removed, **data kept** |
| Restart | `scripts/up.sh` | Starts again, same data |
| Reset | `docker compose down -v` | Containers removed, **all data wiped** |

### What's running, and what isn't (yet)

This spec built the **platform foundation**: the registry, the config server, the gateway, and a
placeholder echo service that proves the plumbing works. The seven business services (auth, course,
enrollment, payment, certificate, review, notification) each already have an **empty database and
login** provisioned (step 6 below), ready for their own specs to fill in — but none of their code
exists yet, so there's nothing to call for them.

| Component | Host port | What it's for |
|-----------|-----------|----------------|
| **api-gateway** | 9080 | The only entry point for clients |
| discovery-server (Eureka) | 9761 | Service registry dashboard |
| config-server | 9888 | Serves central settings from `backend/config-repo` |
| echo-service | 9099 | Placeholder service (also reachable directly, for comparison) |
| PostgreSQL | 9432 | One empty database per planned business service |
| RabbitMQ (AMQP / management) | 9672 / 9673 | Message broker (no queues defined yet) |
| Mailpit (SMTP / web UI) | 9025 / 9825 | Catches outgoing mail locally; nothing leaves the machine |

## 3. Try it: curl walkthrough

All of these go **through the gateway** on port 9080, which is the only address a real client (or
the future frontend) is allowed to use.

### 3.1 Call the public echo endpoint

```bash
curl -i http://localhost:9080/api/v1/echo/ping
```

```
HTTP/1.1 200 OK
Content-Type: application/json
X-Correlation-Id: 59a85f63-70c5-4289-b90c-def5f4cf6fd5

{"instanceId":"echo-service:947e39b33558","port":9099,"greeting":"Hello from LearnHub","correlationId":"59a85f63-70c5-4289-b90c-def5f4cf6fd5"}
```

Note the `X-Correlation-Id` header: you didn't send one, so the gateway generated a UUID, forwarded
it to the service, and the service echoed it back in the body too.

### 3.2 Send your own correlation ID

```bash
curl -i -H "X-Correlation-Id: demo-corr-123456" http://localhost:9080/api/v1/echo/ping
```

A well-formed ID (8–64 characters of letters, digits, `.`, `_`, `-`) is reused as-is — the response
header and the `correlationId` field in the body both come back as `demo-corr-123456`. Send a
malformed one (e.g. `-H "X-Correlation-Id: bad id!"`) and the gateway silently replaces it with a
fresh UUID instead.

### 3.3 Hit a protected route (no login exists yet, so everything protected is refused)

```bash
curl -i http://localhost:9080/api/v1/echo/secure/ping
```

```
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{"type":"about:blank","title":"Unauthorized","status":401,"detail":"This route requires authentication.","instance":"/api/v1/echo/secure/ping","correlationId":"03936726-447c-434a-a387-8c68c85e8221"}
```

Every route is protected unless it's explicitly marked public, and every error the gateway produces
has this same shape (RFC 7807) with a `correlationId` you can use to find it in the logs.

### 3.4 Hit an unknown path

```bash
curl -i http://localhost:9080/nope
```

```
HTTP/1.1 404 Not Found
{"type":"about:blank","title":"Not Found","status":404,"detail":"No route matches this path.","instance":"/nope","correlationId":"..."}
```

### 3.5 Trigger the rate limit

The gateway allows 200 requests per client address before refilling at 100/second, so a normal curl
loop won't trip it — you need a real burst, fired in parallel so it outpaces the refill:

```bash
seq 1 600 | xargs -P 100 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  http://localhost:9080/api/v1/echo/ping | sort | uniq -c
```

```
    363 200
    237 429
```

A `429` response carries a `Retry-After` header telling you how long to wait. The exact split
depends on your machine's speed; what matters is that some requests are refused.

### 3.6 Change a central setting and watch it take effect live

```bash
# Before:
curl -s http://localhost:9080/api/v1/echo/ping | grep -o '"greeting":"[^"]*"'

# Edit the setting (no commit, no restart needed):
sed -i 's/greeting: .*/greeting: Hello from the updated config!/' backend/config-repo/echo-service.yml

# Wait a few seconds, then check again:
sleep 5
curl -s http://localhost:9080/api/v1/echo/ping | grep -o '"greeting":"[^"]*"'
```

The new greeting appears within about 10 seconds — the service polls the config server every 5
seconds and refreshes itself when something actually changed. Put the original line back afterwards
if you want to leave the repo as you found it.

### 3.7 Look at the registry dashboard

```bash
curl -s -H "Accept: application/json" http://localhost:9761/eureka/apps | python3 -m json.tool | head -30
```

Or just open <http://localhost:9761> in a browser. You'll see `API-GATEWAY`, `CONFIG-SERVER` and
`ECHO-SERVICE` listed, each with its instance ID and status.

### 3.8 Confirm the business-service databases exist (empty, waiting for their own specs)

```bash
source .env
docker compose exec postgres psql -U "$POSTGRES_USER" -d postgres -c '\l' \
  | grep -E 'auth|course|enrollment|payment|certificate|review|notification'
```

You'll see one database per service, each owned by a role of the same name, with no tables — that's
expected; this spec only provisions the empty databases.

### 3.9 Send and view a test email

```bash
printf 'From: demo@learnhub.test\r\nTo: inbox@learnhub.test\r\nSubject: Hello\r\n\r\nTest message.\r\n' \
  | curl -s --url smtp://localhost:9025 --mail-from demo@learnhub.test --mail-rcpt inbox@learnhub.test -T -
```

Then open <http://localhost:9825> — your message is sitting in the Mailpit inbox. Nothing is ever
actually delivered outside the machine.

### 3.10 Run two copies of the echo service and watch traffic spread across them

```bash
docker compose --profile scale up -d echo-service-b

# Give it ~30s to register, then fire 20 requests and see both instance IDs show up:
for i in $(seq 1 20); do
  curl -s http://localhost:9080/api/v1/echo/ping | grep -o '"instanceId":"[^"]*"'
done | sort | uniq -c

# Tear the second copy down again when you're done:
docker compose --profile scale rm -sf echo-service-b
```

## 4. Automated tests

### 4.1 Unit and integration tests (needs Docker, for Testcontainers)

```bash
mvn -f backend/pom.xml verify
```

This builds and tests every module (shared libraries, registry, config server, echo service,
gateway, and the infrastructure checks) — around 160 tests in total. `verify` (not `test`) is
needed because several tests spin up real PostgreSQL and RabbitMQ containers via Testcontainers.

To run just one module, e.g. the gateway:

```bash
mvn -f backend/pom.xml -pl api-gateway verify
```

### 4.2 Smoke tests against the running stack

Start the platform first (`scripts/up.sh`), then run whichever of these you want:

```bash
scripts/smoke-test.sh                 # health, databases, broker, mail, gateway, registry, rate limit
scripts/smoke-test.sh --scale         # also: two echo copies, load spread, a crashed copy, timed eviction
scripts/smoke-test.sh --config-down   # also: a service with no config server fails fast and says why
scripts/smoke-test.sh --auth-profile  # also: registry/config server require login outside "local"
scripts/smoke-test.sh --lifecycle     # also: data survives a restart, and `down -v` wipes it
```

Each check prints `PASS` or `FAIL` with a reason; the script exits non-zero if anything failed.
**Run one mode at a time** — several of them stop and restart containers, and running two at once
will make them interfere with each other.

### 4.3 Preflight script tests (no Docker needed, uses a fake `docker`)

```bash
bash scripts/tests/up-preflight.test.sh
```

Proves `scripts/up.sh` correctly detects a missing runtime, a missing or empty setting, and an
occupied port — all without starting a single real container.

**Run this with the real platform stopped** (`docker compose down`). If the real stack is up, its
containers really do hold the ports being tested, but the fake `docker` this test injects has no way
to know those containers are "this project's own", so it reports spurious port conflicts.

### 4.4 Shell script linting

```bash
docker run --rm -v "$PWD:/mnt" koalaman/shellcheck:stable -S warning \
  scripts/up.sh scripts/smoke-test.sh scripts/tests/up-preflight.test.sh \
  scripts/tests/fakebin/docker backend/docker/db-init/provision.sh
```

## 5. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `scripts/up.sh` says a setting is missing | `.env` wasn't created, or a value was left as `change-me-...` and then deleted | `cp .env.example .env` and fill in every value |
| `scripts/up.sh` says a port is in use | Something else on your machine is using one of the ports in the table above | Stop the other process, or run `docker compose down` if an older LearnHub stack is still up |
| Gateway returns `503` right after startup | Normal — see the note in step 2 | Wait ~30 seconds and retry |
| `docker: command not found` | You're on Windows and Docker only runs inside WSL | Run commands from inside WSL (`wsl`), not PowerShell/Git Bash |
| `mvn verify` fails with a Docker connection error | Maven is running outside WSL while Docker is only inside WSL | Run Maven from inside WSL too |

## 6. See also

- [README.md](README.md) — architecture, port table, how to add a new service, governance
- [specs/001-platform-foundation/quickstart.md](specs/001-platform-foundation/quickstart.md) — the
  original scenario-by-scenario validation guide this file was adapted from
- [specs/001-platform-foundation/checklists/success-criteria.md](specs/001-platform-foundation/checklists/success-criteria.md) —
  measured results for every success criterion
