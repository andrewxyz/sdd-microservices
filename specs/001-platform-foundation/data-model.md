# Data Model: Backend Platform Foundation

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

The platform stores no business data in this feature. The only persisted data are the seven empty service
databases and the RabbitMQ volume. Everything below is configuration or in-memory state, described as
entities so later specs and tests share one vocabulary.

## Service

A runnable platform component.

| Field | Rule |
|-------|------|
| `name` | Unique, lowercase kebab-case (`api-gateway`, `echo-service`); equals `spring.application.name` and the registry key |
| `kind` | `infrastructure` (discovery-server, config-server, api-gateway), `placeholder` (echo-service), `business` (later) |
| `port` | From the constitution port table; identical inside and outside its container |
| `healthEndpoints` | liveness and readiness probes, exposed by shared defaults |

Relationships: a Service has 1..n **Registry entries** (copies) and, unless it is a local-config service, one
**Central setting set**.

## Registry entry

A record that one copy of a service is alive.

| Field | Rule |
|-------|------|
| `serviceName` | Service `name` |
| `instanceId` | `<name>:<HOSTNAME or random UUID>`; unique per copy |
| `address` | IP and port (prefer-ip-address) |
| `status` | `UP`, `DOWN`, `OUT_OF_SERVICE`, `STARTING` |
| `lease` | renewal 10 s; expiry 30 s configured (effective about 60 s, see research R5) |

State transitions: `STARTING` -> `UP` (readiness + client health check) -> `DOWN` (health fails) ->
removed. Removal happens immediately on graceful shutdown (deregister, so well inside the 30 s of SC-002)
and on lease expiry plus eviction run (about 65 s, bounded by 90 s in SC-002) after a crash. The gateway
sees changes after its next 5 s registry fetch.

## Central setting set

The configuration a service receives, assembled by the config server from `backend/config-repo`.

| Layer | Source file | Precedence |
|-------|-------------|------------|
| Shared defaults | `application.yml` | lowest |
| Service-specific | `<service-name>.yml` | middle |
| Environment override | `application-<profile>.yml` (for example `application-local.yml`) | highest |

The order is the one Spring Cloud Config's native backend actually returns, measured by
`ConfigServerNativeIT` on 2026-10-17: `application-local.yml`, then `echo-service.yml`, then
`application.yml`. An environment override therefore beats a service's own file, which is the useful
behavior (a profile can adjust any service), but it means a key set in both `application-local.yml` and
`<service>.yml` takes the `local` value. A service-and-profile file (`<service>-<profile>.yml`) would sit
above all three; none is used in this feature.

Refreshable keys (rebound on refresh without restart):

| Key group | Owner | Example |
|-----------|-------|---------|
| Gateway routes | api-gateway | `spring.cloud.gateway.server.webflux.routes[*]` |
| Allowed CORS origins | api-gateway | `...globalcors.cors-configurations.[/**].allowed-origins` |
| Rate limits | api-gateway | `platform.gateway.rate-limit.capacity`, `...refill-per-second`, `...idle-eviction` |
| Timeouts | api-gateway | connect 2 s, response 5 s |
| Echo greeting | echo-service | `echo.greeting` |

Watcher state (in memory, per config client): `lastGoodHash` (string), `lastPollAt`, `lastError`.
Transitions: `IDLE` -> poll -> (hash unchanged: `IDLE`) | (hash changed: `REFRESHING` -> `IDLE`) | (poll
fails: `IDLE`, keep `lastGoodHash`, log warning). The first poll records the hash without refreshing.

Validation: if the config server is unreachable at startup, the client retries for a bounded period, then
fails with a message that names the config server URL (FR-017).

## Route

A mapping from a path prefix to a service, defined in `api-gateway.yml`.

| Field | Rule |
|-------|------|
| `id` | Unique; equals the service short name |
| `path` | `/api/v1/<short-name>/**` |
| `uri` | `lb://<registered-service-name>` (never a host or port) |
| `metadata.public` | Boolean or String `"true"` marks a public route; absent or anything else means protected |
| `order` | Lower runs first; a narrow public route must precede a broader protected one |

Rules: protected routes answer 401 for every request until the auth spec; unmatched paths answer 404.

A service whose paths are a mix of public and protected needs one route per access level, because
`metadata.public` applies to a whole route. This feature therefore has two, both to `lb://echo-service`:

| id | path | public | order |
|----|------|--------|-------|
| `echo-public` | `/api/v1/echo/ping` | `true` | 0 |
| `echo` | `/api/v1/echo/**` | absent (protected) | 1 |

Protected-by-default is preserved: the catch-all is the protected one, so any echo path not listed
explicitly as public is protected.

## Correlation ID

| Field | Rule |
|-------|------|
| header | `X-Correlation-Id` on request and response |
| format | `^[A-Za-z0-9._-]{8,64}$` |
| origin | client value if it matches, else a generated UUID |
| propagation | gateway -> service (header) -> logs (MDC key `correlationId`) -> response header -> error bodies |

## Rate-limit bucket (gateway, in memory)

| Field | Rule |
|-------|------|
| `key` | client remote IP address only; `X-Forwarded-For` ignored |
| `tokens` | token bucket, capacity and refill from central settings |
| `lastSeen` | used for idle eviction |

Rules: clients sharing one address share one bucket; all buckets are cleared when rate-limit settings
change; an empty bucket answers 429 with `Retry-After`. Not persisted, single gateway instance.

## Problem details (error body)

Defined by [contracts/problem-details.schema.json](contracts/problem-details.schema.json). Fields:
`type`, `title`, `status`, `detail`, `instance`, `correlationId` (always present). Statuses produced by the
gateway: 401, 404, 429, 503, 504 (500 as fallback). `Content-Type` is `application/problem+json`.

## Event envelope

Defined by [contracts/event-envelope.schema.json](contracts/event-envelope.schema.json) and implemented as
an immutable record in `shared-events`.

| Field | Type | Rule |
|-------|------|------|
| `eventId` | UUID | unique; used by consumers for idempotency |
| `type` | string | `<domain>.<event>`, lowercase; equals the routing key |
| `version` | integer | at least 1; event schema version |
| `occurredAt` | timestamp | UTC, ISO-8601 |
| `correlationId` | string | same format as the Correlation ID |
| `payload` | object | opaque to the envelope |

Constants: exchange `learning.events` (topic). No domain routing keys are defined in this feature.

## Service database

| Field | Rule |
|-------|------|
| `name` / `role` | one each for `auth`, `course`, `enrollment`, `payment`, `certificate`, `review`, `notification` |
| `password` | from the environment variable for that service; re-applied on every start |
| `schema` | empty; no tables or migrations in this feature |

Provisioning is idempotent: create role/database only if missing, always re-apply the password.

## Environment settings (`.env.example`)

Names only; every value is a placeholder.

| Group | Variables |
|-------|-----------|
| Mode | `SPRING_PROFILES_ACTIVE` (default `local`) |
| Eureka / config-server basic auth | `EUREKA_USER`, `EUREKA_PASSWORD`, `CONFIG_USER`, `CONFIG_PASSWORD` |
| PostgreSQL | `POSTGRES_USER`, `POSTGRES_PASSWORD`, `AUTH_DB_PASSWORD`, `COURSE_DB_PASSWORD`, `ENROLLMENT_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `CERTIFICATE_DB_PASSWORD`, `REVIEW_DB_PASSWORD`, `NOTIFICATION_DB_PASSWORD` |
| RabbitMQ | `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS` |

Rules: all are required and non-empty (compose `${VAR:?message}` and `up.sh`); `.env` is git-ignored and
only `.env.example` is committed.

`EUREKA_URL` and `CONFIG_SERVER_URL` are **not** `.env` variables. Compose derives them from internal
container names and the credentials above (`http://${EUREKA_USER}:${EUREKA_PASSWORD}@discovery-server:9761/eureka/`
and `http://config-server:9888`), so a password is never written in two places.
