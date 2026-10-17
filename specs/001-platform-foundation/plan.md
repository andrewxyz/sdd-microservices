# Implementation Plan: Backend Platform Foundation

**Branch**: `001-platform-foundation` | **Date**: 2026-10-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-platform-foundation/spec.md`

## Summary

Deliver the backend foundation of LearnHub: one command (`scripts/up.sh`) starts a local platform made of
a Eureka registry, a Spring Cloud Config server, a WebFlux API gateway and a placeholder echo service,
plus PostgreSQL (seven empty per-service databases), RabbitMQ and Mailpit. Services find each other by
name, read all settings from `backend/config-repo`, and pick up edits within about 10 seconds through a
5-second polling watcher (no Spring Cloud Bus). The gateway routes `/api/v1/<name>/**`, adds and returns
`X-Correlation-Id`, applies CORS and a per-IP in-memory rate limit, and returns RFC 7807 problem details
for 401/404/429/503/504. Two shared libraries hold the event envelope (`shared-events`) and cross-cutting
technical code (`platform-common`).

Two behaviors were verified empirically on the target versions in a throwaway spike (see
[research.md](research.md), R1 and R2): a `ContextRefresher.refresh()` really updates the gateway's CORS
origins, routes and bound properties, and CORS preflight never reaches the gateway's global filters. A third
fact, from the Eureka source, changes a stated expectation: a crashed instance is evicted after about
65 seconds, not 30 (R5; see Constitution Check notes and the open points in research.md).

## Technical Context

**Language/Version**: Java 21 (build verified locally with OpenJDK 21.0.9)

**Primary Dependencies**: Spring Boot 3.5.15, Spring Cloud 2025.0.3 (Gateway 4.3.5 WebFlux, Netflix Eureka
server/client, Config server/client, LoadBalancer, Resilience4j declared but unused until the first Feign
client), spring-retry + spring-boot-starter-aop (config fail-fast retry), Spring Security (basic auth outside
`local`), Micrometer context-propagation 1.1.4 (managed by Boot), Jackson. Versions live only in
`backend/pom.xml`.

**Storage**: PostgreSQL 16 (seven empty databases and logins, no tables); RabbitMQ 4.3 (broker only, no
queue layout); Mailpit (local mail catcher). Named volumes for PostgreSQL and RabbitMQ data.

**Testing**: JUnit 5, Testcontainers (PostgreSQL provisioning script run twice; RabbitMQ envelope round-trip
on `learning.events`), WireMock (gateway tests, test scope only), `scripts/smoke-test.sh` against the
running stack, GitHub Actions CI. Tests are written first (constitution VI).

**Target Platform**: Linux containers via Docker Compose; developer machines on Windows, macOS and Linux.
`scripts/*.sh` run in bash (Git Bash or WSL on Windows). No local JDK or Maven is required to start the
platform (images are built in containers).

**Project Type**: Backend microservices monorepo (Maven multi-module, `backend/`); `frontend/` untouched.

**Performance Goals**: whole platform healthy in under 3 minutes (SC-001); service visible in registry
within 30 s of start and gone within 30 s of a graceful stop (SC-002); config edit effective within 10 s
(SC-003); 503 within 5 s when no healthy copy exists (SC-005); startup precheck failure within 30 s (SC-009).

**Constraints**: connect timeout 2 s, response timeout 5 s; config poll every 5 s; Eureka tuning (5 s
eviction, self-preservation off, 5 s registry fetch, 10 s renewal, 30 s expiry, client health check on);
no Redis, no Spring Cloud Bus, no tracing or log-aggregation tools; secrets only from environment values.

**Scale/Scope**: single-machine local environment; 4 Spring services + 3 third-party containers + 1
one-shot job (+1 optional echo copy); 2 shared libraries; 4 config-repo files; 0 business tables.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design (results below).*

| # | Principle | Result | Notes |
|---|-----------|--------|-------|
| I | Monorepo layout, independent deployability | **PASS with deviation D1** | All services, shared libs, `config-repo` and Docker build files are under `backend/`; `frontend/` untouched (a `.gitkeep` keeps the empty folder in git). Root additions beyond the constitution's list: `scripts/`, `.gitignore`, `.dockerignore` (D1). |
| II | Service ownership, data isolation | **PASS** | Seven empty databases/logins provisioned; no service uses them yet. Shared code limited to `shared-events` and `platform-common`; neither contains domain logic. |
| III | Discovery and configuration | **PASS** | Discovery and config servers configured locally; gateway and echo use non-optional `configserver:` import with fail-fast + retry; Eureka/config URLs from environment; routes use `lb://` service names. |
| IV | Gateway as only entry point | **PASS with deviation D4** | CORS, correlation ID, rate limit, explicit public routes, protected by default. JWT validation and identity headers are deferred to the auth spec (spec out-of-scope); until then protected routes answer 401. OpenAPI documented for echo. |
| V | Sync/async communication | **PASS (limited scope)** | Envelope, exchange name and routing-key naming rule in `shared-events`; Feign interceptor in `platform-common`. No Feign client or consumer exists yet, so timeout/circuit-breaker/DLQ rules apply from the first spec that adds one. |
| VI | Test-first (NON-NEGOTIABLE) | **PASS** | Tasks will order each test before its implementation. Testcontainers for PostgreSQL and RabbitMQ; no H2. Envelope serialization test included; Feign contract tests N/A (no Feign client). Acceptance scenarios gated in CI. |
| VII | Security and secrets | **PASS** | All secrets from environment; `.env.example` committed with placeholders; `.env` git-ignored; basic auth on Eureka and config server outside `local`. BCrypt/JWT deferred with auth. |
| VIII | Errors and observability | **PASS** | RFC 7807 + `correlationId` from gateway handler and servlet advice; liveness/readiness probes on every Spring service; ECS structured logs with correlation ID in MDC. |
| IX | Conventions | **PASS with deviation D3** | Base package `com.example.learnhub.<service>`; `/api/v1` paths; single version source; port map followed. Layer packages created only where code exists in infrastructure services and echo (D3). Flyway: no schema exists yet, so no migrations (first business service adds them). |
| X | Simplicity | **PASS with deviation D2** | No extra services. WireMock is a test-scope library not named in the constitution (D2). |

**Gate result**: no unjustified violations. Four deviations are recorded below. D1 and D2 need a
constitution amendment when this feature is merged (proposed in Complexity Tracking).

**Post-design re-check**: unchanged. Design artifacts introduced no new deployable component and no library
beyond those already listed.

## Project Structure

### Documentation (this feature)

```text
specs/001-platform-foundation/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── problem-details.schema.json
│   ├── event-envelope.schema.json
│   └── echo-service.openapi.yaml
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
sdd-microservices/
├── docker-compose.yml                 # whole platform; profile "scale" adds echo-service-b
├── .env.example                       # every setting, placeholder values only
├── .gitignore                         # ignores .env, target/, IDE files
├── .dockerignore
├── README.md                          # start/stop/restart/reset, port table, add a service, change a setting
├── scripts/
│   ├── up.sh                          # preflight (runtime, settings, ports) then compose up --build --wait
│   └── smoke-test.sh                  # exercises the running stack end to end
├── .github/workflows/ci.yml           # backend build+tests, up.sh, smoke test
├── frontend/.gitkeep                  # folder stays empty
├── specs/                             # Spec Kit
└── backend/
    ├── pom.xml                        # parent: spring-boot-starter-parent + Spring Cloud BOM; ONLY place for versions
    ├── docker/
    │   ├── Dockerfile                 # multi-stage, ARG MODULE, build context = backend/
    │   └── db-init/provision.sh       # idempotent roles + databases for 7 services
    ├── config-repo/
    │   ├── application.yml            # shared defaults (eureka client, actuator, ecs logging, retry)
    │   ├── application-local.yml      # local overrides (refresh endpoint, no auth)
    │   ├── api-gateway.yml            # routes, CORS origins, rate limits, timeouts
    │   └── echo-service.yml           # greeting, port
    ├── shared-events/                 # EventEnvelope record, exchange name, routing-key rule; Jackson only
    │   └── src/{main,test}/java/com/example/learnhub/events/
    ├── platform-common/
    │   └── src/{main,test}/java/com/example/learnhub/common/
    │       ├── config/                # reactive-safe: ConfigWatcher auto-configuration
    │       ├── correlation/           # CorrelationIds (regex, generation); servlet filter (optional deps)
    │       ├── error/                 # ProblemDetail advice (servlet only)
    │       └── feign/                 # correlation-forwarding RequestInterceptor (conditional)
    ├── discovery-server/              # Eureka server, local config, basic auth outside local
    ├── config-server/                 # native backend over /config-repo, registers with Eureka
    ├── api-gateway/                   # WebFlux gateway: filters, error handler, rate limiter
    │   └── src/{main,test}/java/com/example/learnhub/gateway/
    └── echo-service/                  # GET /api/v1/echo/ping, GET /api/v1/echo/secure/ping
        └── src/{main,test}/java/com/example/learnhub/echo/
```

**Structure Decision**: Backend and frontend as separate top-level folders, where `backend/` is a self-contained Maven multi-module
build (one folder per service plus two shared libraries), `frontend/` is reserved and empty, and
repo-root files are limited to startup, example settings, README, CI and ignore files. This matches
constitution Principle I; the extra root files are listed as D1.

## Complexity Tracking

| Deviation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **D1**: root `scripts/`, `.gitignore`, `.dockerignore`, `frontend/.gitkeep` (Principle I: "nothing else belongs at the root") | The spec requires one command from the repo root (FR-001) and the plan needs `.gitignore` for `.env` and `.dockerignore` for build speed. | Putting scripts under `backend/` breaks "one command from the repo root" and mixes frontend-relevant tooling into the backend folder. Proposal: PATCH amendment to Principle I listing `scripts/` and ignore files. |
| **D2**: WireMock as a test-scope library (Principle X: no technology without amending) | The gateway needs controllable upstream behavior (slow, refused, healthy copies) that the real echo service cannot produce on demand. | Hand-written stub servers or mocks duplicate WireMock for no gain. Proposal: MINOR amendment adding WireMock to the allowed test tooling. |
| **D3**: layer packages (`api/application/domain/infrastructure`) only where code exists, for infrastructure services and echo (Principle IX) | These services have no domain; empty `domain/` and `application/` packages add noise (YAGNI, Principle X). | Creating empty layers to satisfy the letter of the rule. The full four-layer layout applies to business services from the next spec. |
| **D4**: JWT validation and user-identity headers not implemented (Principle IV) | Spec explicitly puts login and token checks out of scope; protected routes simply return 401. | Implementing auth now pulls the auth service into this feature. |
