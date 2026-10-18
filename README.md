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
| auth-service | 9001 | reserved, not yet built |
| course-service | 9002 | reserved, not yet built |
| enrollment-service | 9003 | reserved, not yet built |
| payment-service | 9004 | reserved, not yet built |
| certificate-service | 9005 | reserved, not yet built |
| review-service | 9006 | reserved, not yet built |
| notification-service | 9007 | reserved, not yet built |
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
