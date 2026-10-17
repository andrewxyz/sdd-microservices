# LearnHub Constitution

LearnHub is an online learning platform built as a monorepo: a Spring Cloud microservices backend
now, and a React frontend later. This constitution governs both; frontend-specific detail is added
by amendment when the first frontend spec is written.

## Core Principles

### I. Monorepo Layout and Independent Deployability

The repository root is `sdd-microservices` and MUST be organised as follows:

- `backend/` holds every backend service, one folder per service (for example
  `backend/discovery-server`, `backend/config-server`, `backend/api-gateway`,
  `backend/auth-service`, `backend/course-service`). It is a self-contained Maven multi-module
  build with its parent POM at `backend/pom.xml`. The shared libraries (`shared-events`,
  `platform-common`), the `config-repo` folder and the Docker build files MUST live inside
  `backend/`.
- `frontend/` will hold the React app. It is empty until a frontend spec builds it; a `.gitkeep`
  placeholder MAY exist while it has no other content.
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

The `api-gateway` is the only entry point for clients, including the future frontend. It MUST:

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
may be introduced without amending this constitution. The only approved test-scope-only library
beyond JUnit 5 and Testcontainers is WireMock, used to stub HTTP upstreams in gateway tests.

## Technology Stack and Port Map

**Backend**: Java 21; Maven multi-module; Spring Boot 3.5.15; Spring Cloud 2025.0.3; PostgreSQL 16;
RabbitMQ 4.3; Mailpit (local email only). Spring Cloud components: Eureka, Config Server, Gateway,
OpenFeign, LoadBalancer, Resilience4j.

**Test tooling**: JUnit 5 and Testcontainers (PostgreSQL, RabbitMQ), plus WireMock as a
test-scope-only library for stubbing HTTP upstreams in gateway tests. These are the only test tools
named in this constitution; adding another requires an amendment.

**Accepted risk**: Spring Boot 3.5.15 and Spring Cloud 2025.0.3 are the final open-source releases
of their lines. A later spec MAY upgrade to Spring Boot 4 with Spring Cloud 2025.1.x.

**Frontend (future)**: React. Language, build tool, state management and testing stack are
deliberately undecided and MUST be set by a later amendment before the first frontend spec. The
gateway-only and OpenAPI-contract rules in Principle IV already apply.

**Port map**: All host ports are in the 9xxx range. Third-party images keep their internal ports,
and containers talk to each other on internal ports only.

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
| review-service | 9006 | |
| notification-service | 9007 | |
| echo-service | 9099 | Platform verification service |
| PostgreSQL | 9432 | Internal 5432 |
| RabbitMQ AMQP | 9672 | Internal 5672 |
| RabbitMQ management | 9673 | Internal 15672 |
| Mailpit SMTP | 9025 | Internal 1025 |
| Mailpit web UI | 9825 | Internal 8025 |
| frontend | 9xxx (TBD) | Assigned in the frontend spec |

## Development Workflow

- One `docker-compose.yml` at the repo root MUST start the whole platform, with health checks and
  startup order (discovery and config servers first).
- Backend specs MUST be built in this order: platform foundation, gateway routing, auth, course,
  payment and enrollment, certificate and notification, review, then resilience and dead-letter
  handling. Frontend specs start only once the backend foundation and auth are stable.
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

**Version**: 1.1.0 | **Ratified**: 2026-10-17 | **Last Amended**: 2026-10-17
