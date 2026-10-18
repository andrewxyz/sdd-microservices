# Implementation Plan: User Accounts, Login, and Gateway-Enforced Access

**Branch**: `002-user-auth` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-user-auth/spec.md`. No additional technical brief was
given to `/speckit-plan`; every technical decision below was made here and is recorded, with rationale, in
[research.md](research.md).

**Scope**: Backend only. Touches `backend/auth-service` (new), `backend/api-gateway` (gateway filters and
`backend/config-repo/api-gateway.yml`), `backend/config-repo/auth-service.yml` (new), `backend/pom.xml`
(two new managed dependencies), the shared `problem-details.schema.json` from platform-foundation (widened,
not forked), and `docker-compose.yml`/`.env.example` (new service entry and three new environment
variables). `frontend/` and every other business service are untouched.

## Summary

Adds a new `auth-service` (port 9001, per the constitution's port table) that owns accounts, credentials
and refresh-token sessions in its own `auth` database, and turns the gateway's existing "protected by
default" stub (which currently 401s every non-public route unconditionally) into real enforcement: a
custom JWT-verifying `GlobalFilter`, extending the same filter-chain design already used for correlation
ID, route access and rate limiting, checks a shared-secret-signed access token, enforces an optional
per-route role requirement, and forwards the caller's identity as headers instead of the token itself. The
existing echo-service secure path needs no code changes — it starts working the moment the gateway can
issue a real 200 instead of its current unconditional 401.

## Technical Context

**Language/Version**: Java 21 (unchanged from platform-foundation)

**Primary Dependencies**: Spring Boot 3.5.15 / Spring Cloud 2025.0.3 (unchanged versions, same BOM);
Spring Data JPA + Flyway (new to this feature, standard for a service with its own relational schema);
`spring-security-crypto` for BCrypt (R7); `com.nimbusds:nimbus-jose-jwt` for signing/verifying JWTs (R9) —
both added to `backend/pom.xml` dependency management, no other module's dependencies change.

**Storage**: PostgreSQL 16, database `auth` (already created, empty, by platform-foundation's `db-init`;
no docker-compose change needed for the database itself).

**Testing**: JUnit 5 and Testcontainers (PostgreSQL), the same tools already approved; no new test library.

**Target Platform**: Linux containers via the existing `docker-compose.yml`, registered with the existing
Eureka server and loading config from the existing config server — no new infrastructure.

**Project Type**: Backend microservice addition (new module) plus a targeted extension of an existing
module (api-gateway).

**Performance Goals**: No new numeric target beyond the gateway's existing budget: auth requests must fit
inside the gateway's existing 2s connect / 5s response timeouts, same as every other routed call.

**Constraints**: `JWT_SIGNING_SECRET` must be at least 32 bytes (HS256 minimum for a non-trivial secret,
R1); auth-service must start successfully only when able to verify or create exactly one administrator
account (FR-015); no password or token value may reach a log line (FR-024) — verified by a dedicated test,
not just code review.

**Scale/Scope**: Single auth-service instance for this feature (no `-b` scaled copy, unlike echo-service);
an account may hold any number of concurrent sessions (spec.md Clarifications).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design below.*

| Principle | Check | Result |
|---|---|---|
| I. Monorepo layout | New module at `backend/auth-service`; nothing added at the repo root beyond the already-permitted set | Pass |
| II. Service ownership & data isolation | auth-service owns its own `auth` database; no cross-service joins; no other service's data is read | Pass |
| III. Discovery & configuration | Registers with Eureka as `auth-service`; loads config via non-optional `spring.config.import=configserver:` with fail-fast + retry, same as echo-service | Pass |
| IV. Gateway as only entry point | This feature is what makes that principle real for the first time: JWT validation, identity-as-headers, explicit public-route listing | Pass (implements, doesn't just comply) |
| V. Sync/async communication | Not exercised: this feature adds no Feign client and publishes no event. Nothing to violate | N/A |
| VI. Test-first | tasks.md (next command) sequences tests before implementation per story, as in platform-foundation | Pass (enforced at tasks/implement time) |
| VII. Security & secrets | BCrypt (R7); short-lived JWT (15 min) + refresh token (30 days, FR-006); `JWT_SIGNING_SECRET`/`ADMIN_EMAIL`/`ADMIN_PASSWORD` via env vars, added to `.env.example` with placeholders, never committed as real values | Pass |
| VIII. Errors & observability | Reuses `platform-common`'s `ProblemDetailsAdvice` and correlation filter (servlet part, like echo-service); gateway's 401/403 responses use the same shared `problem-details.schema.json`, now widened to include 403 (R8) | Pass |
| IX. Conventions | Package `com.example.learnhub.auth` with `api`/`application`/`domain`/`infrastructure`; `/api/v1` paths; Flyway migrations; all new dependency versions added only in `backend/pom.xml`; port 9001 per the existing table | Pass |
| X. Simplicity | See Complexity Tracking below — two libraries are added, both the direct, minimal fulfillment of principles the constitution already mandates (BCrypt, JWT), not new technology choices | Pass, with a recorded note (not a violation) |

**Complexity Tracking**: `spring-security-crypto` and `nimbus-jose-jwt` are not named in the constitution's
Technology Stack list. They are called out here per the governance clause ("any deviation ... MUST be
recorded with a written justification") even though neither is a deviation: Principle VII already mandates
BCrypt-hashed passwords and short-lived JWTs, and the Technology Stack section does not separately enumerate
every Spring Boot starter already in use (e.g., `spring-boot-starter-web` is not listed either). These two
libraries are the smallest standard implementation of an already-mandated requirement, not an additional
technology choice requiring its own amendment. Flag for the user to object if a formal amendment is still
wanted before `/speckit-tasks`.

## Project Structure

### Documentation (this feature)

```text
specs/002-user-auth/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   ├── auth-service.openapi.yaml
│   └── jwt-claims.schema.json
└── tasks.md              # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                  # + spring-security-crypto, nimbus-jose-jwt (dependencyManagement)
├── config-repo/
│   └── auth-service.yml                     # new: refreshable settings (JWT lifetimes handled as constants; DB creds, admin bootstrap flag source)
├── auth-service/                            # new module
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/example/learnhub/auth/
│       │   ├── AuthServiceApplication.java
│       │   ├── api/                         # REST controllers: Register, Login, Refresh, Logout, Me, RoleChange
│       │   ├── application/                 # use-case services: RegisterAccount, Authenticate, RotateRefreshToken, ChangeRole, BootstrapAdministrator
│       │   ├── domain/                      # Account, RefreshTokenRecord, Role, password-strength rule, JWT claim building
│       │   └── infrastructure/              # JPA repositories, BCrypt adapter, Nimbus JWT signer/verifier, LoginAttemptWindow (R3)
│       ├── main/resources/
│       │   ├── application.yml              # service name, config-import, actuator
│       │   └── db/migration/
│       │       ├── V1__create_accounts.sql
│       │       └── V2__create_refresh_tokens.sql
│       └── test/java/com/example/learnhub/auth/
│           ├── RegisterAccountIT.java       # Testcontainers Postgres
│           ├── LoginFlowIT.java             # login, generic-error parity, throttling (R3)
│           ├── RefreshTokenRotationIT.java  # rotation + reuse detection (R2)
│           ├── RoleChangeIT.java            # last-administrator guard (FR-014)
│           ├── BootstrapAdministratorIT.java# idempotent on restart (R6)
│           └── NoSecretsInLogsTest.java     # FR-024 guard
│
└── api-gateway/
    ├── src/main/java/com/example/learnhub/gateway/
    │   ├── filter/
    │   │   └── JwtAuthenticationGlobalFilter.java   # replaces RouteAccessGlobalFilter's unconditional 401 (R4, R5)
    │   ├── security/
    │   │   ├── JwtVerifier.java                     # Nimbus-based verify + claim extraction (R1, R9)
    │   │   └── IdentityHeaders.java                 # X-User-Id / X-User-Role constants + stripping (R4)
    │   ├── error/GatewayProblem.java                # + 403 case (R8)
    │   └── GatewayConfiguration.java                # wire the new filter at the existing ROUTE_ACCESS_ORDER slot
    └── src/test/java/com/example/learnhub/gateway/
        ├── filter/JwtAuthenticationFilterTest.java  # valid/expired/tampered/missing tokens; role-restricted route
        └── RealGatewayConfigTest.java                # extended: new auth routes parse with expected order/metadata

docker-compose.yml       # + auth-service entry (port 9001), depends_on discovery+config, healthcheck
.env.example             # + JWT_SIGNING_SECRET, ADMIN_EMAIL, ADMIN_PASSWORD placeholders
backend/config-repo/api-gateway.yml  # + auth-public, auth-admin (roles: [ADMINISTRATOR]), auth routes
```

## Constitution Check (post-design)

Re-checked after Phase 1 design above: no new conflicts. The `roles` route-metadata extension and the
403-widened shared schema are additive changes to existing, already-compliant mechanisms, not new
exceptions. Gate still passes.

## Phase 0 and Phase 1 outputs

- [research.md](research.md) — 9 decisions (R1–R9), no `NEEDS CLARIFICATION` remaining.
- [data-model.md](data-model.md) — `Account`, `RefreshTokenRecord`, `AccessToken` (claims only, not
  persisted), `LoginAttemptWindow` (in-memory only).
- [contracts/auth-service.openapi.yaml](contracts/auth-service.openapi.yaml) and
  [contracts/jwt-claims.schema.json](contracts/jwt-claims.schema.json), plus a widened
  `specs/001-platform-foundation/contracts/problem-details.schema.json` (403 added, R8).
- [quickstart.md](quickstart.md) — curl walkthrough of all five user stories plus a no-regression check
  against `scripts/smoke-test.sh`.
