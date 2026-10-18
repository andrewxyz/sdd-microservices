# How to Run LearnHub

A step-by-step guide to starting the backend platform, poking at it with `curl`, and running the
automated test suites. Every command below has been run against this repo.

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
#    Also set JWT_SIGNING_SECRET (at least 32 characters), ADMIN_EMAIL and ADMIN_PASSWORD —
#    auth-service uses these to sign tokens and to create the platform's first administrator.
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
learnhub-auth-service-1       Up (healthy)
learnhub-config-server-1      Up (healthy)
learnhub-course-service-1     Up (healthy)
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

**On a truly fresh database** (first-ever start, or after `docker compose down -v`), `auth-service`
also creates the platform's first administrator account from `ADMIN_EMAIL`/`ADMIN_PASSWORD` — no
manual step needed; see §3.4 below.

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

The platform now has the **platform foundation** (registry, config server, gateway, a placeholder
echo service), **auth-service** (real accounts, login, refresh/logout, roles, and the gateway's real
JWT enforcement at the entry point), and **course-service** (the course catalog: browsing, authoring,
publishing, categories). The remaining five business services (enrollment, payment, certificate,
review, notification) each already have an **empty database and login** provisioned (step 3.12 below),
ready for their own specs to fill in — but none of their code exists yet, so there's nothing to call
for them.

| Component | Host port | What it's for |
|-----------|-----------|----------------|
| **api-gateway** | 9080 | The only entry point for clients |
| discovery-server (Eureka) | 9761 | Service registry dashboard |
| config-server | 9888 | Serves central settings from `backend/config-repo` |
| **auth-service** | 9001 | Accounts, login, roles — reached through the gateway at `/api/v1/auth/**` |
| **course-service** | 9002 | Courses, lessons, categories — reached through the gateway at `/api/v1/courses/**`, `/api/v1/my/courses`, `/api/v1/categories/**` |
| echo-service | 9099 | Placeholder service (also reachable directly, for comparison) |
| PostgreSQL | 9432 | One database per business service; `auth` and `course` have real tables now, the rest are still empty |
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

### 3.3 Register and log in

`register`, `login` and `refresh` are the only public paths under `/api/v1/auth/**`; everything else
needs a token.

```bash
curl -s -X POST http://localhost:9080/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"howto@example.com","password":"correct-horse-1"}'
```

```json
{"id":"ac940ead-e941-4dc0-b34f-6e9dfd5d97e2","email":"howto@example.com","role":"STUDENT","createdAt":"2026-10-18T09:13:09.313121009Z"}
```

A password under 10 characters, or one with no digit, is refused with a `422` naming which rule
failed (try `-d '{"email":"x@example.com","password":"short"}'`). Registering the same email again —
even with different letter case — is refused with `409`.

```bash
TOKENS=$(curl -s -X POST http://localhost:9080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"howto@example.com","password":"correct-horse-1"}')
echo "$TOKENS"
```

```json
{"accessToken":"eyJhbGciOiJIUzI1NiJ9...","refreshToken":"y1mfTt3RXbsjasdO812i_9GU5d6YmrL4xpZiS3YdLbI","expiresInSeconds":900}
```

Save the access token for the next few steps:

```bash
ACCESS=$(echo "$TOKENS" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
REFRESH=$(echo "$TOKENS" | grep -o '"refreshToken":"[^"]*' | cut -d'"' -f4)
```

A wrong password or an unregistered email both return the exact same `401` body, so a caller can't
tell which was wrong. Six rapid failed attempts against the same email inside a minute get a `429` on
the sixth, before the password is even checked again.

### 3.4 Hit a protected route — now it actually works

```bash
echo "--- no token ---"
curl -i http://localhost:9080/api/v1/echo/secure/ping

echo "--- with the token from 3.3 ---"
curl -s http://localhost:9080/api/v1/echo/secure/ping -H "Authorization: Bearer $ACCESS"
```

```
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json

{"type":"about:blank","title":"Unauthorized","status":401,"detail":"This route requires authentication.","instance":"/api/v1/echo/secure/ping","correlationId":"03936726-447c-434a-a387-8c68c85e8221"}
```

```json
{"correlationId":"27aeefaa-65e3-4058-b925-0ad8669438c1","instanceId":"echo-service:f550c5209312","port":9099,"greeting":"Hello from LearnHub"}
```

Every error the gateway produces has the same shape (RFC 7807) with a `correlationId` you can use to
find it in the logs. With a valid token, the gateway verifies it, strips the `Authorization` header,
and forwards `X-User-Id`/`X-User-Role` instead — the echo service never sees the raw token.

### 3.5 View your own profile

```bash
curl -s http://localhost:9080/api/v1/auth/me -H "Authorization: Bearer $ACCESS"
```

```json
{"id":"ac940ead-e941-4dc0-b34f-6e9dfd5d97e2","email":"howto@example.com","role":"STUDENT","createdAt":"2026-10-18T09:13:09.313121Z"}
```

No password or token field anywhere in the response.

### 3.6 Refresh and log out

Every successful refresh issues a brand-new refresh token and retires the old one — so the old one
cannot be used a second time, even though it hasn't expired:

```bash
NEW=$(curl -s -X POST http://localhost:9080/api/v1/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
echo "$NEW"

echo "--- reusing the old (now-spent) refresh token ---"
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:9080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$REFRESH\"}"
```

```json
{"accessToken":"eyJhbGciOiJIUzI1NiJ9...","refreshToken":"bi02bZsXG8ki_NGWxJA5Hx6w4y1btLtKYfhCzdO0sAo","expiresInSeconds":900}
```
```
401
```

Log out (needs the *new* access token from the refresh above) and the *new* refresh token stops
working too:

```bash
NEW_ACCESS=$(echo "$NEW" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
NEW_REFRESH=$(echo "$NEW" | grep -o '"refreshToken":"[^"]*' | cut -d'"' -f4)

curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:9080/api/v1/auth/logout \
  -H "Authorization: Bearer $NEW_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$NEW_REFRESH\"}"
# 204

curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:9080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' -d "{\"refreshToken\":\"$NEW_REFRESH\"}"
# 401
```

Logging in again from a second "device" creates an independent session — logging one out never
affects the other.

### 3.7 Roles: the first administrator, and changing someone else's role

The platform already has one administrator, created automatically from `ADMIN_EMAIL`/`ADMIN_PASSWORD`
the first time it ever started against an empty database (no manual step):

```bash
source .env   # for ADMIN_EMAIL / ADMIN_PASSWORD

ADMIN_ACCESS=$(curl -s -X POST http://localhost:9080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

STUDENT_ID=$(curl -s http://localhost:9080/api/v1/auth/me -H "Authorization: Bearer $ACCESS" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

echo "--- a student tries to change their own role ---"
curl -s -o /dev/null -w '%{http_code}\n' -X PATCH "http://localhost:9080/api/v1/auth/accounts/$STUDENT_ID/role" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' -d '{"role":"INSTRUCTOR"}'
# 403

echo "--- an administrator changes it instead ---"
curl -s -o /dev/null -w '%{http_code}\n' -X PATCH "http://localhost:9080/api/v1/auth/accounts/$STUDENT_ID/role" \
  -H "Authorization: Bearer $ADMIN_ACCESS" -H 'Content-Type: application/json' -d '{"role":"INSTRUCTOR"}'
# 200
```

The role-change route is itself gateway-enforced (`metadata.roles: [ADMINISTRATOR]` in
`backend/config-repo/api-gateway.yml`), so a non-administrator never even reaches auth-service for
that call. The change applies the next time that account logs in; a token already issued before the
change keeps acting on the old role until it expires (at most 15 minutes).

### 3.8 Create a category, as an administrator

Categories are administrator-only; everyone else sees `403`.

```bash
source .env   # for ADMIN_EMAIL / ADMIN_PASSWORD

ADMIN_ACCESS=$(curl -s -X POST http://localhost:9080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

CAT=$(curl -s -X POST http://localhost:9080/api/v1/categories -H "Authorization: Bearer $ADMIN_ACCESS" \
  -H 'Content-Type: application/json' -d '{"name":"Web Development"}')
echo "$CAT"
CATEGORY_ID=$(echo "$CAT" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

echo "--- same name again (case-insensitive) ---"
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:9080/api/v1/categories \
  -H "Authorization: Bearer $ADMIN_ACCESS" -H 'Content-Type: application/json' -d '{"name":"web development"}'
# 409
```

```json
{"id":"54011ab3-485d-4e6d-84d4-c18e54c5e3b4","name":"Web Development","version":0}
```

### 3.9 Create a draft course, add a lesson, and publish it

You'll need an INSTRUCTOR account — register one and promote it the same way §3.7 promoted a student to
INSTRUCTOR. Publishing a draft missing a lesson is refused with a `422` naming exactly what's missing.

```bash
INSTR_ACCESS=$(curl -s -X POST http://localhost:9080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"instructor1@example.com","password":"correct-horse-1"}' | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)

DRAFT=$(curl -s -X POST http://localhost:9080/api/v1/courses -H "Authorization: Bearer $INSTR_ACCESS" \
  -H 'Content-Type: application/json' \
  -d "{\"title\":\"Intro to LearnHub\",\"description\":\"A starter course.\",\"categoryId\":\"$CATEGORY_ID\",\"priceCents\":0}")
echo "$DRAFT"
COURSE_ID=$(echo "$DRAFT" | grep -o '"id":"[^"]*' | cut -d'"' -f4)

get_etag() {
  curl -s -i "http://localhost:9080/api/v1/courses/$COURSE_ID/full" -H "Authorization: Bearer $INSTR_ACCESS" \
    | grep -i '^etag:' | sed 's/[Ee][Tt][Aa][Gg]: *//' | tr -d '\r'
}

echo "--- publish before any lesson exists ---"
curl -s -X PATCH "http://localhost:9080/api/v1/courses/$COURSE_ID/publish" \
  -H "Authorization: Bearer $INSTR_ACCESS" -H "If-Match: $(get_etag)"
```

```json
{"type":"about:blank","title":"Unprocessable Entity","status":422,"detail":"Not ready to publish: at least one lesson is required","instance":"...","correlationId":"..."}
```

```bash
curl -s -X POST "http://localhost:9080/api/v1/courses/$COURSE_ID/lessons" -H "Authorization: Bearer $INSTR_ACCESS" \
  -H "If-Match: $(get_etag)" -H 'Content-Type: application/json' \
  -d '{"title":"Welcome","durationMinutes":5,"content":"Full lesson text."}'

curl -s -X PATCH "http://localhost:9080/api/v1/courses/$COURSE_ID/publish" \
  -H "Authorization: Bearer $INSTR_ACCESS" -H "If-Match: $(get_etag)"
```

The response's `status` is now `PUBLISHED` and `publishedAt` is set.

### 3.10 Browse the public catalog — no lesson content anywhere

```bash
curl -s "http://localhost:9080/api/v1/courses?q=LearnHub"
curl -s "http://localhost:9080/api/v1/courses/$COURSE_ID"
```

```json
{"items":[{"id":"e4473c03-...","title":"Intro to LearnHub", ... ,"lessons":[{"title":"Welcome","position":0,"durationMinutes":5}]}],"page":0,"size":20,"totalItems":1}
```

Note there is no `content` field anywhere — only the owner/administrator view (`.../full`) includes it.

### 3.11 Ownership and the concurrency guard

Every mutating request needs an `If-Match` with the resource's current version (from the `ETag` header
on any read of `.../full`); a different caller, or a stale version, is refused.

```bash
echo "--- a student can't edit someone else's course ---"
STUDENT_ACCESS=$(curl -s -X POST http://localhost:9080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"student2@example.com","password":"correct-horse-1"}' | grep -o '"accessToken":"[^"]*' | cut -d'"' -f4)
curl -s -o /dev/null -w '%{http_code}\n' -X PUT "http://localhost:9080/api/v1/courses/$COURSE_ID" \
  -H "Authorization: Bearer $STUDENT_ACCESS" -H 'If-Match: "0"' -H 'Content-Type: application/json' \
  -d '{"title":"Hacked"}'
# 403

echo "--- a stale If-Match is refused even for the real owner ---"
curl -s -o /dev/null -w '%{http_code}\n' -X PUT "http://localhost:9080/api/v1/courses/$COURSE_ID" \
  -H "Authorization: Bearer $INSTR_ACCESS" -H 'If-Match: "0"' -H 'Content-Type: application/json' \
  -d '{"title":"Edited"}'
# 409
```

### 3.12 Archive, and confirm a non-draft can't be deleted

```bash
curl -s -X PATCH "http://localhost:9080/api/v1/courses/$COURSE_ID/archive" \
  -H "Authorization: Bearer $INSTR_ACCESS" -H "If-Match: $(get_etag)"

echo "--- gone from the public catalog, but still intact for the owner ---"
curl -s "http://localhost:9080/api/v1/courses?q=LearnHub"
# {"items":[],"page":0,"size":20,"totalItems":0}

echo "--- archived is not draft, so delete is still refused ---"
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE "http://localhost:9080/api/v1/courses/$COURSE_ID" \
  -H "Authorization: Bearer $INSTR_ACCESS"
# 409
```

### 3.13 The service-to-service lookup has no gateway route

```bash
echo "--- through the gateway: no route exists ---"
curl -s -i "http://localhost:9080/api/v1/internal/courses/$COURSE_ID" | head -1
# HTTP/1.1 404 Not Found

echo "--- directly on course-service's own port: works ---"
curl -s "http://localhost:9002/api/v1/internal/courses/$COURSE_ID"
# {"courseId":"...","status":"ARCHIVED","priceCents":0,"currency":"USD","instructorId":"...","lessonCount":1}
```

### 3.14 Hit an unknown path

```bash
curl -i http://localhost:9080/nope
```

```
HTTP/1.1 404 Not Found
{"type":"about:blank","title":"Not Found","status":404,"detail":"No route matches this path.","instance":"/nope","correlationId":"..."}
```

### 3.15 Trigger the rate limit

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

### 3.16 Change a central setting and watch it take effect live

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

### 3.17 Look at the registry dashboard

```bash
curl -s -H "Accept: application/json" http://localhost:9761/eureka/apps | python3 -m json.tool | head -30
```

Or just open <http://localhost:9761> in a browser. You'll see `API-GATEWAY`, `CONFIG-SERVER`,
`AUTH-SERVICE`, `COURSE-SERVICE` and `ECHO-SERVICE` listed, each with its instance ID and status.

### 3.18 Confirm the business-service databases

```bash
source .env
docker compose exec postgres psql -U "$POSTGRES_USER" -d postgres -c '\l' \
  | grep -E 'auth|course|enrollment|payment|certificate|review|notification'
```

Every service has its own database, owned by a role of the same name. `auth` and `course` now have
real tables — check with:

```bash
docker compose exec postgres psql -U auth -d auth -c '\dt'
docker compose exec postgres psql -U course -d course -c '\dt'
```

```
               List of relations
 Schema |         Name          | Type  | Owner
--------+------------------------+-------+-------
 public | accounts               | table | auth
 public | flyway_schema_history  | table | auth
 public | refresh_tokens         | table | auth
```

```
                List of relations
 Schema |         Name          | Type  | Owner
--------+-----------------------+-------+--------
 public | categories            | table | course
 public | courses               | table | course
 public | flyway_schema_history | table | course
 public | lessons               | table | course
```

The other five (`enrollment`, `payment`, `certificate`, `review`, `notification`) are still empty,
waiting for their own specs.

### 3.19 Send and view a test email

```bash
printf 'From: demo@learnhub.test\r\nTo: inbox@learnhub.test\r\nSubject: Hello\r\n\r\nTest message.\r\n' \
  | curl -s --url smtp://localhost:9025 --mail-from demo@learnhub.test --mail-rcpt inbox@learnhub.test -T -
```

Then open <http://localhost:9825> — your message is sitting in the Mailpit inbox. Nothing is ever
actually delivered outside the machine.

### 3.20 Run two copies of the echo service and watch traffic spread across them

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
**auth-service**, **course-service**, gateway, and the infrastructure checks) — around 280 tests in
total. `verify` (not `test`) is needed because several tests spin up real PostgreSQL and RabbitMQ
containers via Testcontainers.

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
| `api-gateway` or `auth-service` crash-loops on startup, logs `Could not resolve placeholder 'JWT_SIGNING_SECRET'` | `.env` is missing `JWT_SIGNING_SECRET` (or `ADMIN_EMAIL`/`ADMIN_PASSWORD` for auth-service) | Add it to `.env` (at least 32 characters), then `scripts/up.sh` again |
| Login/refresh/role-change return `401`/`403` unexpectedly | A stale access token (15-minute lifetime) or a role change that hasn't been picked up yet | Log in again for a fresh token; role changes apply on next login, not to a token already issued |
| A course edit/publish/archive/lesson/category change returns `409` with "If-Match" in the message | The request is missing the `If-Match` header, or it's stale | `GET .../full` (or the category) again to read its current `ETag`, then resend with that exact value |
| A course action returns `403` even though you're logged in | The caller is neither the course's owning instructor nor an administrator | Only the owner or an administrator can edit/publish/archive/delete a given course |

## 6. See also

- [README.md](README.md) — architecture, port table, how to add a new service, governance
- [specs/001-platform-foundation/quickstart.md](specs/001-platform-foundation/quickstart.md) and
  [checklists/success-criteria.md](specs/001-platform-foundation/checklists/success-criteria.md) —
  the platform foundation's own validation guide and measured results
- [specs/002-user-auth/quickstart.md](specs/002-user-auth/quickstart.md),
  [contracts/auth-service.openapi.yaml](specs/002-user-auth/contracts/auth-service.openapi.yaml) and
  [checklists/success-criteria.md](specs/002-user-auth/checklists/success-criteria.md) — the auth
  feature's own validation guide, endpoint contract, and measured results
- [specs/003-course-catalog/quickstart.md](specs/003-course-catalog/quickstart.md),
  [contracts/course-service.openapi.yaml](specs/003-course-catalog/contracts/course-service.openapi.yaml)
  and [checklists/success-criteria.md](specs/003-course-catalog/checklists/success-criteria.md) — the
  course catalog's own validation guide, endpoint contract, and measured results
