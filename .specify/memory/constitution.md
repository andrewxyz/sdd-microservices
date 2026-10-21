<!--
Sync Impact Report (temporary; remove before committing the amended constitution)

Version change: 1.1.0 → 1.2.0
Bump rationale: MINOR. Three principles added and several sections materially expanded. No
principle was removed, and no existing rule was redefined in a backward-incompatible way.

Modified principles:
- I. Monorepo Layout and Independent Deployability → unchanged title; `frontend/` is now defined
  as its own application root with its own package.json instead of an empty placeholder
- X. Simplicity → unchanged title; Vitest and Testing Library added to the approved
  test-scope-only libraries alongside WireMock

Added sections:
- XI. Frontend Stack and Conventions
- XII. Frontend Session and Security
- XIII. Frontend-Backend Boundary

Removed sections: none

Other updates:
- Preamble: the frontend is no longer described as "later"; its rules now live in this document
- Technology Stack and Port Map: the "Frontend (future)" placeholder is replaced with the decided
  stack; test tooling is split into backend and frontend; the frontend host port is fixed at 9100
  (internal 80, nginx) in place of "9xxx (TBD)"
- Development Workflow: specs 001-005 recorded complete, review-service recorded as deferred, the
  student web app named as the next spec, instructor/admin/review UI deferred

Templates reviewed:
- .specify/templates/plan-template.md - no change needed (its Constitution Check is generic)
- .specify/templates/spec-template.md - no change needed
- .specify/templates/tasks-template.md - no change needed
- .specify/templates/checklist-template.md - no change needed

Follow-up TODOs (deferred code changes, out of scope for this command):
- scripts/up.sh: the PORTS list does not yet include "9100:frontend"; add it with the frontend spec
- docker-compose.yml: no frontend service yet; add it with the frontend spec
-->

# LearnHub Constitution

LearnHub is an online learning platform built as a monorepo: a Spring Cloud microservices backend
and a React frontend. This constitution governs both.

## Core Principles

### I. Monorepo Layout and Independent Deployability

The repository root is `sdd-microservices` and MUST be organised as follows:

- `backend/` holds every backend service, one folder per service (for example
  `backend/discovery-server`, `backend/config-server`, `backend/api-gateway`,
  `backend/auth-service`, `backend/course-service`). It is a self-contained Maven multi-module
  build with its parent POM at `backend/pom.xml`. The shared libraries (`shared-events`,
  `platform-common`), the `config-repo` folder and the Docker build files MUST live inside
  `backend/`.
- `frontend/` holds the React app and is its own application root, with its own `package.json`,
  its own lockfile and its own build. It MUST NOT be a Maven module and MUST NOT appear in
  `backend/pom.xml`. A `.gitkeep` placeholder MAY exist until the first frontend spec creates the
  app.
- The repo root holds the Spec Kit folders (`.specify`, `specs`), the single `docker-compose.yml`,
  `.env.example`, the README, CI configuration, `scripts/` (repo-level helper scripts such as the
  one-command startup script, smoke tests and script tests), and the repo hygiene files
  `.gitignore`, `.dockerignore` and `.gitattributes`. Nothing else belongs at the root.

Every spec MUST state whether it covers backend, frontend or both, and MUST name the folders it
touches. Backend and frontend MUST be buildable, testable and deployable independently: neither
build may depend on files in the other folder.

### II. Service Ownership and Data Isolation

The backend consists of three infrastructure services (`discovery-server`, `config-server`,
`api-gateway`) and seven business services (`auth`, `course`, `enrollment`, `payment`,
`certificate`, `review`, `notification`).

- Each business service MUST own exactly one bounded context and its own PostgreSQL database.
- A service MUST NOT read another service's database, and cross-service joins are forbidden. Data
  from another context is obtained only through its API (Feign) or through events.
- Shared libraries are limited to `shared-events` (the event envelope) and `platform-common`
  (cross-cutting technical code such as error handling, correlation ID and logging support).
  Domain logic MUST NOT go in a shared library.

### III. Discovery and Configuration

- Every service except the Eureka server MUST register with Eureka under its service name.
  Hosts and ports MUST NOT be hardcoded; Feign clients and gateway routes MUST use service names
  resolved through Spring Cloud LoadBalancer.
- `discovery-server` and `config-server` start first and MUST be configured locally (no remote
  config dependency).
- `api-gateway` and all business services MUST load configuration from the config server using a
  non-optional import (`spring.config.import=configserver:`, never `optional:`) with fail-fast and
  retry enabled.

### IV. Gateway as the Only Entry Point

The `api-gateway` is the only entry point for clients, including the frontend. It MUST:

- validate JWTs, add a correlation ID to every request, apply CORS and apply rate limits;
- pass the authenticated user identity to services as HTTP headers;
- list public routes explicitly. Every route not listed as public MUST require a valid JWT
  (protected by default).

Services MUST NOT be reachable by clients except through the gateway. The only contract between
frontend and backend is the gateway's HTTP API, and each service MUST document its endpoints with
OpenAPI so the frontend can rely on it. The frontend MUST NOT call a service directly.

### V. Synchronous and Asynchronous Communication

- Synchronous calls MUST use OpenFeign, and every Feign client MUST have timeouts, a Resilience4j
  circuit breaker and a fallback.
- Asynchronous events MUST use RabbitMQ through one topic exchange named `learning.events`.
  Routing keys follow `<domain>.<event>` (for example `payment.succeeded`).
- Every event MUST use the same versioned envelope from `shared-events` with the fields `eventId`,
  `type`, `version`, `occurredAt`, `correlationId` and `payload`.
- Each consumer MUST have its own queue and its own dead-letter queue, MUST be idempotent using
  `eventId`, and MUST retry three times with backoff before dead-lettering.

### VI. Test-First Quality (NON-NEGOTIABLE)

- Tests MUST be written before implementation: tests are written, seen to fail, then the code is
  written to make them pass (Red-Green-Refactor).
- Backend integration tests MUST use Testcontainers for PostgreSQL and RabbitMQ. H2 MUST NOT be
  used for persistence tests.
- Every Feign client MUST have a contract test, and every event MUST have a serialization test.
- A feature is not done until its acceptance scenarios pass in CI. Failing or skipped acceptance
  scenarios block completion.

### VII. Security and Secrets

- Passwords MUST be hashed with BCrypt. Access tokens MUST be short-lived JWTs, paired with refresh
  tokens.
- Secrets MUST NOT be committed. Configuration uses environment variables, and a committed
  `.env.example` documents every variable with non-secret placeholder values.
- Eureka and the config server MUST require basic auth in every profile other than `local`. The
  one exemption is the liveness and readiness health probe endpoints (`/actuator/health/**`), which
  MAY be reachable without a login in every profile so container health checks work. Every other
  endpoint of those two servers MUST require basic auth outside `local`.

### VIII. Errors and Observability

- All HTTP errors from APIs that clients can reach (the gateway, the business services and any
  placeholder service behind the gateway) MUST use RFC 7807 problem details and MUST include a
  `correlationId`, so the frontend handles errors in one way. The infrastructure servers (Eureka and
  the config server) are not reachable by clients and MAY use their framework-default error
  responses.
- Every service MUST expose Actuator health with liveness and readiness probes.
- Logs MUST be structured and MUST carry the correlation ID.

### IX. Conventions

- Backend base package is `com.example.learnhub.<service>` with `api`, `application`, `domain` and
  `infrastructure` layers.
- REST paths MUST start with `/api/v1`.
- Database schema changes MUST be made with Flyway migrations.
- All versions MUST be defined in the parent POM (`backend/pom.xml`) and nowhere else.
- All host ports MUST follow the port map below.

### X. Simplicity

The simplest design that meets the spec MUST be chosen. No extra service, library or technology
may be introduced without amending this constitution. The approved test-scope-only libraries beyond
JUnit 5 and Testcontainers are WireMock (stubbing HTTP upstreams in backend tests) and, on the
frontend, Vitest and Testing Library.

### XI. Frontend Stack and Conventions

- The frontend MUST be a React 19 single-page app, written in TypeScript with `strict` mode
  enabled, built with Vite and routed with React Router.
- Styling MUST be plain CSS. A UI component framework MUST NOT be added.
- A state-management library MUST NOT be added. React's own state and context, plus the router's
  own data APIs, are sufficient for the screens in scope.
- Every new runtime dependency MUST carry a one-line justification in the plan that introduces it.
  A dependency that only saves a small amount of code MUST be rejected.
- Frontend tests MUST use Vitest with Testing Library, and MUST cover logic — the API client,
  session handling and checkout above all — rather than rendered output. Snapshot tests MUST NOT be
  the primary assertion for any behaviour.
- The backend smoke tests remain the platform's integration gate. Frontend unit and component tests
  are additional, and a green frontend test run alone MUST NOT be treated as integration evidence.

### XII. Frontend Session and Security

- The access token MUST be held in memory only. The refresh token MUST be held in `sessionStorage`.
  Neither token may be written to `localStorage`, to a script-readable cookie, or to a URL.
- Text received from the server MUST be rendered as text. `dangerouslySetInnerHTML`, or any
  equivalent that turns server-provided content into markup, MUST NOT be used.
- Token refresh MUST be single-flight: concurrent requests that meet a 401 MUST await one shared
  refresh call. The backend rotates refresh tokens and treats reuse of a spent one as theft by
  revoking the whole session, so a second concurrent refresh would log the user out.
- Every error shown to a user MUST include the `correlationId` from the problem-details response as
  a reference code, so a user's report can be traced to the backend logs.

### XIII. Frontend-Backend Boundary

- The frontend MUST reach the backend only through the gateway, and only through the same-origin
  path prefix `/api/`. An absolute URL to the gateway or to any service MUST NOT appear in frontend
  code.
- Both the Vite dev server and the production container MUST proxy `/api/` to the gateway. Because
  every call is therefore same-origin, the frontend MUST NOT depend on CORS, and no browser origin
  needs to be added to the gateway's CORS configuration for it.
- Frontend API types MUST be generated from the backend contract OpenAPI files under
  `specs/*/contracts/`, not hand-written, so that a contract change surfaces as a type error rather
  than a runtime surprise.

## Technology Stack and Port Map

**Backend**: Java 21; Maven multi-module; Spring Boot 3.5.15; Spring Cloud 2025.0.3; PostgreSQL 16;
RabbitMQ 4.3; Mailpit (local email only). Spring Cloud components: Eureka, Config Server, Gateway,
OpenFeign, LoadBalancer, Resilience4j.

**Frontend**: React 19; TypeScript in strict mode; Vite; React Router; plain CSS. No UI component
framework and no state-management library. Served in production by nginx inside the frontend
container.

**Test tooling**: backend — JUnit 5 and Testcontainers (PostgreSQL, RabbitMQ), plus WireMock as a
test-scope-only library for stubbing HTTP upstreams. Frontend — Vitest and Testing Library. These
are the only test tools named in this constitution; adding another requires an amendment.

**Accepted risk**: Spring Boot 3.5.15 and Spring Cloud 2025.0.3 are the final open-source releases
of their lines. A later spec MAY upgrade to Spring Boot 4 with Spring Cloud 2025.1.x.

**Port map**: All host ports are in the 9xxx range. Third-party images keep their internal ports,
and containers talk to each other on internal ports only. The port check in `scripts/up.sh` MUST
cover every host port in this table.

| Component | Host port | Notes |
|-----------|-----------|-------|
| api-gateway | 9080 | Only client entry point |
| discovery-server (Eureka) | 9761 | |
| config-server | 9888 | |
| auth-service | 9001 | |
| course-service | 9002 | |
| enrollment-service | 9003 | |
| payment-service | 9004 | |
| certificate-service | 9005 | |
| review-service | 9006 | Implemented (007-review-service) |
| notification-service | 9007 | |
| echo-service | 9099 | Platform verification service |
| frontend | 9100 | Internal 80 (nginx); the Vite dev server also uses 9100 |
| PostgreSQL | 9432 | Internal 5432 |
| RabbitMQ AMQP | 9672 | Internal 5672 |
| RabbitMQ management | 9673 | Internal 15672 |
| Mailpit SMTP | 9025 | Internal 1025 |
| Mailpit web UI | 9825 | Internal 8025 |

## Development Workflow

- One `docker-compose.yml` at the repo root MUST start the whole platform, with health checks and
  startup order (discovery and config servers first).
- Specs 001 through 005 are complete: platform foundation, user auth, course catalog, checkout and
  enrollment, and progress with certificates and notifications. Spec 006 (the student web app) is
  complete. Spec 007 (`review-service`) is complete: enrolled students rate and comment on courses,
  edit or delete their own review, and anyone can browse a course's reviews and aggregate rating.
- Instructor screens, administrator screens and the review UI come later, if at all. They MUST NOT
  be folded into the student web app spec.
- Each spec MUST go through specify, clarify, plan, tasks and implement on its own branch.
- Every plan MUST include a Constitution Check. Any deviation from this constitution MUST be
  recorded in the plan with a written justification.

## Governance

This constitution supersedes all other practices. Amendments require a documented change to this
file, a version bump and a note of any affected specs or plans. Versioning follows semantic
versioning: MAJOR for removing or redefining a principle in a backward-incompatible way, MINOR for
adding a principle or section or materially expanding guidance, PATCH for clarifications and
wording. Adding a technology, service or the frontend stack is a MINOR amendment at least. Every
plan review and pull request MUST verify compliance with this constitution, and unjustified
complexity MUST be rejected.

**Version**: 1.2.1 | **Ratified**: 2026-10-17 | **Last Amended**: 2026-10-20
