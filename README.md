# LearnHub

An online learning platform built as a monorepo: a Spring Cloud microservices backend now, and a
React frontend later.

This repository currently contains the **backend platform foundation**: the registry, the config
server, the API gateway, a placeholder echo service, and the local environment they run in. There is
no business domain yet. The point of this stage is that one command starts everything, and every
service added later plugs in without extra setup.

- `backend/` — every service and shared library, one folder per service, a self-contained Maven build
- `frontend/` — reserved for the React app; empty for now
- `specs/` — the specification, plan and tasks for each feature
- `.specify/` — Spec Kit, including the project constitution

## Prerequisites

- A container runtime with **Docker Compose v2**. No local JDK or Maven is needed to run the
  platform: the images are built inside containers.
- A **bash** shell for the scripts. On Windows use Git Bash or WSL. If Docker runs inside WSL rather
  than Docker Desktop, run these commands from inside WSL.
- The host ports in the table below must be free.

## Start, stop, restart, reset

```bash
cp .env.example .env     # then replace every change-me value
scripts/up.sh            # start everything
```

`scripts/up.sh` checks three things before starting any container, so a problem is reported clearly
rather than as a half-started stack: the container runtime is running, every required setting is
present and non-empty, and every host port is free. Each failure names exactly what is wrong.

| Action | Command | Effect |
|--------|---------|--------|
| Start | `scripts/up.sh` | Builds images if needed and waits until everything is healthy |
| Stop | `docker compose down` | Stops and removes containers. **Stored data is kept.** |
| Restart | `scripts/up.sh` | Starts again with the data still in place |
| Reset | `docker compose down -v` | Removes containers **and wipes all stored data** |

Running `scripts/up.sh` a second time while the platform is already up is harmless: it skips ports
its own containers already hold and leaves everything running.

**Healthy is not immediately routable.** Services register with the registry shortly *after* they
report healthy, and the gateway refreshes its view of the registry every five seconds. So for up to
about 30 seconds after startup finishes, a request through the gateway can return 503 because it has
no instance to route to yet. That is the registry working as intended; wait a moment and retry.

After a start:

| What | Where |
|------|-------|
| **Gateway (the only entry point for clients)** | <http://localhost:9080/api/v1/echo/ping> |
| Registry dashboard | <http://localhost:9761> |
| Config server | <http://localhost:9888> |
| Echo service | <http://localhost:9099/api/v1/echo/ping> |
| RabbitMQ console | <http://localhost:9673> |
| Mailpit (caught email) | <http://localhost:9825> |

Check it is all working:

```bash
scripts/smoke-test.sh              # health, databases, broker, mail, gateway, registry
scripts/smoke-test.sh --scale      # also: two echo copies, load spread, a crashed copy
scripts/smoke-test.sh --lifecycle  # also: data survives a restart, and down -v wipes it
```

## Ports

Every host port is in the 9xxx range. Third-party images keep their own internal ports and are
mapped into that range; the Spring services use the same port inside and outside their container.

| Component | Host port | Container port |
|-----------|-----------|----------------|
| api-gateway | 9080 | 9080 |
| discovery-server (Eureka) | 9761 | 9761 |
| config-server | 9888 | 9888 |
| auth-service | 9001 | 9001 |
| course-service | 9002 | 9002 |
| enrollment-service | 9003 | 9003 |
| payment-service | 9004 | 9004 |
| certificate-service | 9005 | 9005 |
| review-service | 9006 | reserved, not yet built |
| notification-service | 9007 | 9007 |
| echo-service | 9099 | 9099 |
| PostgreSQL | 9432 | 5432 |
| RabbitMQ (AMQP) | 9672 | 5672 |
| RabbitMQ (management) | 9673 | 15672 |
| Mailpit (SMTP) | 9025 | 1025 |
| Mailpit (web UI) | 9825 | 8025 |

## Changing a central setting

All settings live in `backend/config-repo/`, which the config server serves straight from the working
tree. **Edit a file there and a running service picks the change up within about ten seconds** — no
commit, no rebuild, no restart, and no manual trigger. Each service polls the config server every
five seconds and refreshes itself when the settings actually change.

| File | Applies to |
|------|-----------|
| `application.yml` | Shared defaults for every service |
| `<service-name>.yml` | One service, for example `echo-service.yml` |
| `application-<profile>.yml` | One environment, for example `application-local.yml` |

Precedence runs lowest to highest: `application.yml`, then `<service-name>.yml`, then
`application-<profile>.yml`. So a profile file can override a service's own value.

Try it: change `echo.greeting` in `backend/config-repo/echo-service.yml`, then call
<http://localhost:9099/api/v1/echo/ping> again and watch the greeting change.

Under the `local` profile only, `POST /actuator/refresh` on a service forces the update immediately.

Secrets never go in `backend/config-repo`. They come from environment variables listed in
`.env.example`, and `.env` itself is git-ignored.

## Adding a service

1. **Create the module** under `backend/<name>-service/`. Copy `backend/echo-service/` as the
   template: an application class, one `application.yml`, and nothing else in the way of wiring.
2. **Register it** in `backend/pom.xml` under `<modules>`. Declare no versions in the new POM; the
   parent POM is the only place versions live.
3. **Add its settings** at `backend/config-repo/<name>-service.yml`.
4. **Add a gateway route** in `backend/config-repo/api-gateway.yml`, pointing at
   `lb://<name>-service`. Mark a route `metadata: {public: true}` only if it needs no login;
   everything else is protected by default.
5. **Add a Compose entry** in `docker-compose.yml`, using its reserved port from the table above and
   the same build, health check and `depends_on` shape as `echo-service`.

That is all. Registration with the registry, central configuration, health probes, structured logging
and correlation IDs all arrive from `platform-common` and the shared config; you write no code for
them. `NoWiringCodeTest` enforces that no service adds its own.

A service with its own database (like `auth-service`, the first one) additionally needs
`SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD` environment variables in its Compose entry, pointing at
its own database (already created, empty, by `db-init`), and a Flyway migration under
`src/main/resources/db/migration/` for its schema.

## Accounts and login

`auth-service` (port 9001, behind the gateway at `/api/v1/auth/**`) owns accounts and sessions.
`register`, `login` and `refresh` are public; every other path — including changing another account's
role, restricted to administrators — requires a valid access token, which the gateway verifies and
replaces with `X-User-Id`/`X-User-Role` headers before forwarding the request. See
`specs/002-user-auth/contracts/auth-service.openapi.yaml` for the full endpoint contract and
`specs/002-user-auth/quickstart.md` for a curl walkthrough of the whole flow.

The first administrator is created automatically at startup from `ADMIN_EMAIL`/`ADMIN_PASSWORD`; the
HS256 token-signing secret is `JWT_SIGNING_SECRET` (at least 32 bytes). All three are in `.env.example`.

## Course catalog

`course-service` (port 9002, behind the gateway at `/api/v1/courses/**`, `/api/v1/my/courses` and
`/api/v1/categories/**`) owns courses, lessons and categories. Listing and viewing a published course
are public; everything else — creating and managing a course and its lessons, publishing, archiving,
and category administration — requires a valid token, with course ownership enforced by the service
itself (the gateway only tells it "this caller is authenticated," plus an administrator-only rule for
category mutations). Every mutating request needs an `If-Match` header carrying the resource's current
version, so two concurrent edits can never silently overwrite each other — the service responds `409`
to a stale one instead. A separate, gateway-unreachable endpoint
(`GET /api/v1/internal/courses/{id}`) lets other backend services look up a course directly. See
`specs/003-course-catalog/contracts/course-service.openapi.yaml` for the full endpoint contract and
`specs/003-course-catalog/quickstart.md` for a curl walkthrough of the whole flow.

## Checkout and enrollment

`payment-service` (port 9004, behind the gateway at `POST /api/v1/checkouts` and `/api/v1/payments/**`)
checks out a published course: free courses succeed immediately, paid ones go through an in-process
simulated payment provider driven by a caller-supplied test token (`sim-success`, `sim-decline`,
`sim-unavailable`). Every checkout carries a client-supplied idempotency key, so resending the same
request — including many times at once — never charges twice, and reusing the key with a different
request is refused. A successful payment is recorded durably (a transactional outbox) before it is ever
announced, so it is never lost even if the broker is briefly unreachable.

`enrollment-service` (port 9003, behind the gateway at `GET /api/v1/enrollments`) consumes the resulting
`payment.succeeded` event and creates the enrollment, idempotently and within a few seconds; a message
that cannot be processed is retried three times and then set aside in a dead-letter queue rather than
blocking the messages behind it. `course-service` gains one new, additive endpoint,
`GET /api/v1/courses/{id}/content`, which serves full lesson content to the owning instructor, an
administrator, or a confirmed-enrolled student — and refuses with a temporary error, never the content
itself, if enrollment status cannot be confirmed. See
`specs/004-checkout-enrollment/contracts/` for the full endpoint contracts and
`specs/004-checkout-enrollment/quickstart.md` for a curl walkthrough of the whole flow.

## Progress, certificates, and notifications

`enrollment-service` (still port 9003, now also behind the gateway at
`PUT`/`DELETE /api/v1/enrollments/{courseId}/lessons/{lessonId}/complete` and
`GET /api/v1/enrollments/{courseId}/progress`) lets an enrolled student mark or unmark a lesson complete
and see completed/total lessons and a percentage; a non-enrolled caller is refused, and marking the same
lesson twice changes nothing. Completing a course's last lesson flips the enrollment to completed exactly
once, permanently — even under ten concurrent completions of its last few lessons, and even if lessons are
later unmarked and remarked.

`certificate-service` (port 9005, behind the gateway at `GET /api/v1/certificates/mine` and the public
`GET /api/v1/certificates/verify/{code}`) consumes the resulting `course.completed` event and issues
exactly one certificate per student per course, normally within a few seconds, carrying a 16-character
verification code, the course title, and the completion date. A student can list their own certificates;
anyone can check a certificate by its code and see whether it is valid, the course title, and the date —
nothing about who holds it. The verification endpoint has its own, stricter rate limit, since it is
public and its code is guessable at scale.

`notification-service` (port 9007) consumes `enrollment.created` and `certificate.issued` and sends a
plain-text email for each, captured locally by Mailpit (`http://localhost:9825`) and never delivered
anywhere else. A redelivered event never sends a second email; a notification that keeps failing, or a
message that cannot even be parsed, is retried three times and then set aside in a dead-letter queue
rather than blocking the messages behind it. See
`specs/005-progress-certificates-notifications/contracts/` for the full endpoint contracts and
`specs/005-progress-certificates-notifications/quickstart.md` for a curl walkthrough of the whole flow.

## Building and testing

```bash
mvn -f backend/pom.xml verify     # unit tests, plus Testcontainers tests that need Docker
```

Tests come first here: the project constitution makes test-first development non-negotiable, and
integration tests use Testcontainers rather than an in-memory database.

## Governance

`.specify/memory/constitution.md` holds the rules this codebase is held to: service boundaries, the
gateway as the only entry point, test-first development, how errors and logging work, and the port
table. Changing one of those rules means amending the constitution, not working around it.
