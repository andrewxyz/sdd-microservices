# Tasks: User Accounts, Login, and Gateway-Enforced Access

**Input**: Design documents from `specs/002-user-auth/` (plan.md, spec.md, research.md, data-model.md,
contracts/, quickstart.md)

**Tests**: Included and mandatory — constitution Principle VI ("Test-First Quality") is NON-NEGOTIABLE:
tests are written, seen to fail, then made to pass. Within every story below, test tasks precede and
must fail before the implementation tasks that make them pass.

**Organization**: Grouped by user story (spec.md priorities), in the dependency-aware order spec.md
itself states: US1 and US3 are both P1, but US3 explicitly depends on US1 (a real token must exist
before enforcement can be exercised), so US3 is sequenced right after US1 and before the P2 stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no unmet dependency)
- **[Story]**: US1–US5, per spec.md
- Every task names its exact file path

## Path Conventions

Backend-only feature (plan.md Scope). New module `backend/auth-service/`, following the same
`api`/`application`/`domain`/`infrastructure` layering as every other LearnHub service (constitution
Principle IX). Touches `backend/api-gateway/`, `backend/pom.xml`, `backend/config-repo/`,
`docker-compose.yml`, `.env.example` and `README.md` at the repo root. `frontend/` is untouched.

---

## Phase 1: Setup

**Purpose**: Scaffold the new module and its infrastructure wiring. Nothing here is independently
testable yet; it only makes the module buildable and deployable.

- [X] T001 Add dependency-management entries to `backend/pom.xml`: `spring-boot-starter-data-jpa`,
      `flyway-core`, `org.postgresql:postgresql`, `org.springframework.security:spring-security-crypto`,
      and `com.nimbusds:nimbus-jose-jwt` with an explicit pinned version (research.md R7, R9). This is
      the only place any of these versions are declared (constitution Principle IX).
- [X] T002 Create `backend/auth-service/pom.xml`: parent `backend/pom.xml`, artifact `auth-service`,
      depending on `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`,
      `spring-boot-starter-actuator`, `spring-cloud-starter-netflix-eureka-client`,
      `spring-cloud-starter-config`, `spring-retry` + `spring-boot-starter-aop` (fail-fast config import,
      matching echo-service), `platform-common`, `flyway-core`, `org.postgresql:postgresql`,
      `spring-security-crypto`, `nimbus-jose-jwt`, plus `testcontainers` + `testcontainers-postgresql`
      and `junit-jupiter` in test scope.
- [X] T003 Add `auth-service` to the `<modules>` list in `backend/pom.xml`.
- [X] T004 [P] Create `backend/auth-service/src/main/resources/application.yml`: `spring.application.name:
      auth-service`, `server.port: 9001` (constitution port table), non-optional
      `spring.config.import=configserver:${CONFIG_SERVER_URL}` with fail-fast and retry enabled, Eureka
      client pointed at `${EUREKA_SERVER_URL}`, actuator health exposed — mirroring
      `backend/echo-service/src/main/resources/application.yml`.
- [X] T005 [P] Create `backend/config-repo/auth-service.yml` with the service's refreshable settings
      placeholder (none required by this feature beyond the defaults every service gets); this file's
      presence is what config-server serves to `auth-service` on `spring.config.import`.
- [X] T006 [P] Add an `auth-service` entry to `docker-compose.yml`, based on echo-service's entry but —
      unlike echo-service — actually connecting to a database (analyze finding I1): build arg
      `MODULE=auth-service`, port `9001:9001`, `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/auth`,
      `SPRING_DATASOURCE_USERNAME=auth`, `SPRING_DATASOURCE_PASSWORD=${AUTH_DB_PASSWORD:?AUTH_DB_PASSWORD is required}`
      (reusing the existing `.env.example` variable from platform-foundation, not a new one), plus the
      same `CONFIG_SERVER_URL`/`CONFIG_USER`/`CONFIG_PASSWORD`/`EUREKA_URL` pattern echo-service already
      uses, and `JWT_SIGNING_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`; `depends_on` discovery-server,
      config-server **and postgres**, all `condition: service_healthy`; a healthcheck against
      `/actuator/health/readiness`, matching echo-service's probe path.
- [X] T007 [P] Add `JWT_SIGNING_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` to `.env.example` with
      non-secret placeholder values (constitution Principle VII).
- [X] T008 Create `backend/auth-service/src/main/java/com/example/learnhub/auth/AuthServiceApplication.java`:
      a plain `@SpringBootApplication` main class with no registration, config-polling or health code of
      its own (all of that comes from `platform-common` and the starters), matching echo-service's
      `EchoApplication`.
- [X] T009 [P] Add `com.nimbusds:nimbus-jose-jwt` (no version — managed by `backend/pom.xml`, T001) to
      `backend/api-gateway/pom.xml`, needed by the gateway's token verifier.

**Checkpoint**: `mvn -f backend/pom.xml -pl auth-service -am compile` succeeds; nothing is testable yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core schema, entities and cross-cutting JWT/password plumbing that every user story below
builds on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T010 Create Flyway migration `backend/auth-service/src/main/resources/db/migration/V1__create_accounts.sql`:
      table `accounts` — `id UUID PRIMARY KEY`, `email VARCHAR(320) NOT NULL`, a unique index on
      `lower(email)` (data-model.md: "unique index on the lowercased value"), `password_hash VARCHAR(100)
      NOT NULL`, `role VARCHAR(20) NOT NULL CHECK (role IN ('STUDENT','INSTRUCTOR','ADMINISTRATOR'))`
      (data-model.md: "One of STUDENT, INSTRUCTOR, ADMINISTRATOR"), `created_at TIMESTAMPTZ NOT NULL
      DEFAULT now()`.
- [X] T011 Create Flyway migration `backend/auth-service/src/main/resources/db/migration/V2__create_refresh_tokens.sql`:
      table `refresh_tokens` — `id UUID PRIMARY KEY`, `account_id UUID NOT NULL REFERENCES
      accounts(id)`, `token_hash VARCHAR(64) NOT NULL` with a unique index (data-model.md: "SHA-256 hash
      of the refresh token value; the raw value is never stored"), `issued_at TIMESTAMPTZ NOT NULL`,
      `expires_at TIMESTAMPTZ NOT NULL`, `used_at TIMESTAMPTZ NULL`, `revoked_at TIMESTAMPTZ NULL`.
- [X] T012 [P] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/domain/Role.java`:
      enum `STUDENT`, `INSTRUCTOR`, `ADMINISTRATOR` (data-model.md Account.role).
- [X] T013 [P] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/domain/Account.java`:
      JPA entity mapped to `accounts` (T010) with fields `id`, `email` (always stored lowercased),
      `passwordHash`, `role` (T012), `createdAt`; `passwordHash` MUST NOT be exposed by any `toString()`
      or accidental serialization (FR-024).
- [X] T014 [P] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/domain/RefreshTokenRecord.java`:
      JPA entity mapped to `refresh_tokens` (T011) with fields `id`, `accountId`, `tokenHash`,
      `issuedAt`, `expiresAt`, `usedAt`, `revokedAt`.
- [X] T015 [P] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/AccountRepository.java`:
      Spring Data JPA repository on `Account` with `findByEmailIgnoreCase`, `existsByEmailIgnoreCase`,
      `countByRole(Role)`.
- [X] T016 [P] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/RefreshTokenRepository.java`:
      Spring Data JPA repository on `RefreshTokenRecord` with `findByTokenHash`.
- [X] T017 [P] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/PasswordHasher.java`:
      wraps `BCryptPasswordEncoder` at its default strength (research.md R7); `hash(raw)` and
      `matches(raw, hash)`, never logging either argument.
- [X] T018 Create `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/JwtProperties.java`:
      reads `JWT_SIGNING_SECRET` from the environment; fails application startup with a clear message if
      it is absent or shorter than 32 bytes (plan.md Constraints, research.md R1).
- [X] T019 Create `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/JwtIssuer.java`:
      signs an access token with `com.nimbusds:nimbus-jose-jwt` (HS256, `JwtProperties`'s secret),
      claims exactly `sub` (account id), `role`, `iat`, `exp = iat + 900` seconds (FR-006; shape per
      `contracts/jwt-claims.schema.json`).
- [X] T020 [P] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/security/IdentityHeaders.java`:
      constants `X-User-Id` / `X-User-Role`, plus a method that strips both from an inbound
      `ServerHttpRequest` unconditionally, on every route (research.md R4).
- [X] T021 [P] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/security/JwtVerifier.java`:
      verifies a token's HS256 signature and expiry with the same shared secret (`JwtProperties`-style
      env read), returning either the verified `(accountId, role)` or a typed failure reason
      (missing/malformed/tampered/expired) — never a stack trace or the token value in any exception
      message (FR-024).
- [X] T022 Add a `FORBIDDEN` case to `detailFor()` in
      `backend/api-gateway/src/main/java/com/example/learnhub/gateway/error/GatewayProblem.java`:
      `"Your role does not meet this route's access requirement."` (research.md R8).
- [X] T023 Widen the `status` enum in
      `specs/001-platform-foundation/contracts/problem-details.schema.json` to include `403` if not
      already applied (research.md R8 — already done during planning; verify it is present).

**Checkpoint**: Foundation compiles and migrates (`V1`/`V2`) against a Testcontainers Postgres; no
story-level behavior exists yet.

---

## Phase 3: User Story 1 - Register and log in (Priority: P1) 🎯 MVP

**Goal**: A person can create an account and log in through the gateway's new public auth routes,
receiving an access token and a refresh token.

**Independent Test**: Register a new account with a unique email and a password meeting the strength
rule, then log in with those exact credentials and receive both tokens — needs nothing from any other
story in this feature.

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [X] T024 [P] [US1] Write `backend/auth-service/src/test/java/com/example/learnhub/auth/RegisterAccountIT.java`
      (Testcontainers Postgres): registering succeeds with 201 and `role: STUDENT` (FR-004); registering
      an email differing only by letter case from an existing one returns 409 without revealing the
      case-only nature of the conflict (FR-002); a password under 10 characters, and separately a
      password with no digit, each return 422 naming which part of "minimum 10 characters, containing at
      least one letter and one digit" (FR-003) was not met.
- [X] T025 [P] [US1] Write `backend/auth-service/src/test/java/com/example/learnhub/auth/LoginFlowIT.java`
      (Testcontainers Postgres): correct credentials return 200 with `accessToken`, `refreshToken` and
      `expiresInSeconds: 900`; a wrong password and a login for an unregistered email both return the
      identical 401 body (FR-007); 6 rapid failed attempts against the same email within 60 seconds
      return 429 on the 6th (research.md R3: "5 failures inside the window").
- [X] T026 [P] [US1] Write `backend/auth-service/src/test/java/com/example/learnhub/auth/NoSecretsInLogsTest.java`:
      attach a Logback `ListAppender` to the root logger, perform a register and a login with a known
      password, then assert no captured log event's formatted message or arguments contain the raw
      password, the issued access token, or the issued refresh token (FR-024).

### Implementation for User Story 1

- [X] T027 [US1] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/domain/PasswordStrengthRule.java`:
      `validate(password)` returns pass or a specific failure reason, enforcing verbatim "minimum 10
      characters, containing at least one letter and one digit" (FR-003).
- [X] T028 [US1] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/LoginAttemptWindow.java`:
      in-memory sliding 60-second window, keyed independently by normalized email and by source IP; a
      key at or above 5 failures in the window is throttled (data-model.md LoginAttemptWindow,
      research.md R3).
- [X] T029 [US1] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/application/RegisterAccountService.java`:
      lowercase the email, reject (409) if `AccountRepository.existsByEmailIgnoreCase`, reject (422) if
      `PasswordStrengthRule` fails, else hash via `PasswordHasher` and save an `Account` with
      `role = STUDENT` (FR-001 to FR-004).
- [X] T030 [US1] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java`:
      look up the account by email; on any failure (unknown email or wrong password) record it in
      `LoginAttemptWindow` and return one generic unauthorized result regardless of cause (FR-007); if
      the window is already tripped for this email or source IP, return throttled (429) before touching
      the password at all; on success, issue an access token (`JwtIssuer`) and a new
      `RefreshTokenRecord` (`issuedAt = now`, `expiresAt = now + 30 days`, FR-006), returning both.
- [X] T031 [US1] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/api/RegisterController.java`:
      `POST /api/v1/auth/register`, delegates to `RegisterAccountService`, maps outcomes to 201/409/422
      problem-details responses via `platform-common`'s existing `ProblemDetailsAdvice`.
- [X] T032 [US1] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/api/LoginController.java`:
      `POST /api/v1/auth/login`, delegates to `AuthenticateService`, maps outcomes to 200/401/429.
- [X] T033 [US1] Add route `auth-public` to `backend/config-repo/api-gateway.yml`: `Path=/api/v1/auth/register,
      /api/v1/auth/login, /api/v1/auth/refresh`, `metadata.public: true`, ordered before the general
      `auth` catch-all added in US3 (mirrors the existing `echo-public`/`echo` pattern, research.md R5).
- [X] T034 [US1] Run `RegisterAccountIT`, `LoginFlowIT` and `NoSecretsInLogsTest` (T024–T026) and confirm
      they now pass (Red→Green checkpoint, constitution Principle VI).

**Checkpoint**: Register and log in work end to end through the gateway, independent of every other
story.

---

## Phase 4: User Story 3 - Entry point enforces access on every route (Priority: P1)

**Goal**: The gateway actually validates access tokens instead of unconditionally refusing every
protected route, so the existing echo secure path — and every future protected route — works.

**Independent Test**: Using a token obtained from US1, call the existing echo secure path with no token
(401), a tampered token (401), and the valid token (200) — no other story's code is required.

### Tests for User Story 3 ⚠️ write first, confirm they fail

- [X] T035 [P] [US3] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/filter/JwtAuthenticationFilterTest.java`:
      build real `Route` objects (`Route.async()...build()`, no Mockito, per constitution) for a
      protected route with no `roles` metadata; assert 401 for a missing `Authorization` header, a
      malformed token, a token signed with a different secret (tampered), and an expired token
      (`exp` in the past); assert a request with a valid token reaches the downstream filter with
      `X-User-Id` and `X-User-Role` request headers set and the `Authorization` header removed
      (FR-017, FR-018, FR-020).
- [X] T036 [P] [US3] Extend `backend/api-gateway/src/test/java/com/example/learnhub/gateway/RealGatewayConfigTest.java`:
      parse the real `api-gateway.yml` (T033) and assert the `auth-public` route exists with
      `metadata.public: true` and an order before the `auth` catch-all (T040).
- [X] T037 [US3] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/auth/EchoSecureAccessIT.java`
      (reuses the existing WireMock-stubbed echo upstream pattern from `GatewayBehaviourIT`): calling the
      already-shipped secure echo path with no token returns 401; with a valid token returns 200, and the
      stubbed upstream received `X-User-Id`/`X-User-Role` and no `Authorization` header (FR-022).
- [X] T037a [P] [US3] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/filter/JwtLogSafetyTest.java`
      (analyze finding G1): attach a Logback `ListAppender`, drive `JwtAuthenticationGlobalFilter`
      through a missing-token case, a tampered-token case, and an expired-token case, and assert no
      captured log event's formatted message or arguments contain the raw `Authorization` header value or
      any substring of the token (FR-024/SC-008 apply to the gateway, not only auth-service).

### Implementation for User Story 3

- [X] T038 [US3] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/filter/JwtAuthenticationGlobalFilter.java`:
      for a public route, strip inbound `X-User-Id`/`X-User-Role` (`IdentityHeaders`, T020) and continue;
      for a protected route, require `Authorization: Bearer <token>`, verify with `JwtVerifier` (T021),
      return 401 problem details for any failure (missing/malformed/tampered/expired, FR-018), otherwise
      set `X-User-Id`/`X-User-Role` from the verified claims, remove `Authorization`, and continue
      (FR-016, FR-017, FR-020).
- [X] T039 [US3] In `backend/api-gateway/src/main/java/com/example/learnhub/gateway/GatewayConfiguration.java`,
      replace the `RouteAccessGlobalFilter` bean at the existing `ROUTE_ACCESS_ORDER` slot with
      `JwtAuthenticationGlobalFilter` (T038); remove `RouteAccessGlobalFilter` and its
      now-fully-superseded unconditional-401 test once the new filter's tests (T035) cover the same
      public/protected distinction.
- [X] T040 [US3] Add the general protected route `auth` to `backend/config-repo/api-gateway.yml`:
      `Path=/api/v1/auth/**`, no `public` metadata, ordered after `auth-public` (T033) so only
      register/login/refresh stay public and everything else under `/api/v1/auth/**` is protected by
      default (FR-016, FR-017).
- [X] T041 [US3] Run `JwtAuthenticationFilterTest`, the extended `RealGatewayConfigTest`,
      `EchoSecureAccessIT` and `JwtLogSafetyTest` (T035–T037, T037a) and confirm they now pass.

**Checkpoint**: Every protected route (including the pre-existing echo secure path) now enforces real
tokens; US1 + US3 together already deliver the spec's core access-control value.

---

## Phase 5: User Story 2 - Stay logged in and log out (Priority: P2)

**Goal**: A refresh token renews a session without re-entering credentials, and logout (or reuse of a
spent token) reliably ends a session.

**Independent Test**: Using US1's login, refresh once to get a new pair, confirm the old refresh token is
now rejected, then log out and confirm the new one is rejected too.

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [X] T042 [P] [US2] Write `backend/auth-service/src/test/java/com/example/learnhub/auth/RefreshTokenRotationIT.java`
      (Testcontainers): a valid, unused refresh token returns a new access token and a new refresh token
      (FR-009); presenting the original token again afterward returns 401 (FR-011; data-model.md: "a
      token can become already used").
- [X] T043 [P] [US2] Write `backend/auth-service/src/test/java/com/example/learnhub/auth/LogoutIT.java`
      (Testcontainers): logout revokes the session's refresh token (FR-010); presenting that revoked
      token to `/refresh` afterward returns 401 (FR-011); a second, independent login's refresh token for
      the same account keeps working after the first session logs out (spec.md Clarifications,
      2026-10-18: concurrent sessions are independent).

### Implementation for User Story 2

- [X] T044 [US2] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/application/RotateRefreshTokenService.java`:
      hash the presented token, look it up by `tokenHash`; reject (401) unless
      `usedAt IS NULL AND revokedAt IS NULL AND expiresAt > now()` (data-model.md RefreshTokenRecord
      acceptance condition); otherwise mark it used, issue a new access token and a new
      `RefreshTokenRecord`, and return the new pair (research.md R2).
- [X] T045 [US2] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/application/RevokeSessionService.java`:
      hash the presented refresh token, set `revokedAt` on the matching record only if it belongs to the
      authenticated caller's account (FR-010).
- [X] T046 [US2] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/api/RefreshController.java`:
      `POST /api/v1/auth/refresh` (public), delegates to `RotateRefreshTokenService`, maps outcomes to
      200/401.
- [X] T047 [US2] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/api/LogoutController.java`:
      `POST /api/v1/auth/logout` (protected, falls under the `auth` catch-all from T040), reads
      `X-User-Id` (set by the gateway, T038) to scope revocation to the caller's own account, delegates
      to `RevokeSessionService`, maps outcomes to 204/401.
- [X] T048 [US2] Run `RefreshTokenRotationIT` and `LogoutIT` (T042–T043) and confirm they now pass.

**Checkpoint**: Refresh and logout work against real sessions created by US1, independent of US4/US5.

---

## Phase 6: User Story 4 - Roles and the first administrator (Priority: P2)

**Goal**: A fresh platform starts with a working administrator account, and only an administrator can
change another account's role.

**Independent Test**: Start the platform fresh, log in as the bootstrapped administrator, change a
student's role, and confirm a non-administrator gets 403 attempting the same call.

### Tests for User Story 4 ⚠️ write first, confirm they fail

- [X] T049 [P] [US4] Write `backend/auth-service/src/test/java/com/example/learnhub/auth/BootstrapAdministratorIT.java`
      (Testcontainers): starting the application context against an empty database creates exactly one
      `ADMINISTRATOR` account from `ADMIN_EMAIL`/`ADMIN_PASSWORD` (FR-015); starting it again against the
      now-non-empty database creates no second administrator and does not alter the existing one
      (research.md R6).
- [X] T050 [P] [US4] Write `backend/auth-service/src/test/java/com/example/learnhub/auth/RoleChangeIT.java`
      (Testcontainers): an administrator changing another account's role succeeds, and the account's
      *next* issued token carries the new role while an already-issued token keeps its old role until it
      expires (spec.md Clarifications, 2026-10-18); a change that would leave zero administrator accounts
      returns 409 (FR-014).
- [X] T051 [P] [US4] Extend `backend/api-gateway/src/test/java/com/example/learnhub/gateway/filter/JwtAuthenticationFilterTest.java`
      (T035): a route whose metadata has `roles: [ADMINISTRATOR]` returns 403 for a valid token with
      role `STUDENT`, and passes through for a valid token with role `ADMINISTRATOR` (FR-019,
      research.md R5).

### Implementation for User Story 4

- [X] T052 [US4] Extend `JwtAuthenticationGlobalFilter` (T038) to read an optional `roles` list from the
      matched route's metadata; after a token verifies, if the list is present and the token's role is
      not in it, return 403 via the widened `GatewayProblem` (T022); otherwise continue unchanged
      (research.md R5).
- [X] T053 [US4] Add route `auth-admin` to `backend/config-repo/api-gateway.yml`:
      `Path=/api/v1/auth/accounts/**`, `metadata.roles: [ADMINISTRATOR]`, ordered before the general
      `auth` catch-all (T040) so only this narrower path carries the role restriction.
- [X] T054 [US4] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/application/BootstrapAdministrator.java`
      as a Spring `ApplicationRunner`: if `AccountRepository.countByRole(ADMINISTRATOR) == 0`, require
      `ADMIN_EMAIL` and `ADMIN_PASSWORD` to be non-empty (fail fast with a named-setting message
      otherwise, matching the platform's `${VAR:?message}` convention) and create one administrator via
      the same hashing path as registration; otherwise do nothing (FR-015, research.md R6).
- [X] T055 [US4] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/application/ChangeRoleService.java`:
      load the target account; reject (409) if the change would reduce the `ADMINISTRATOR` count to zero
      (FR-014); otherwise update the role (FR-013 — the caller's own administrator-only access was
      already enforced by the gateway, T052/T053).
- [X] T056 [US4] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/api/RoleChangeController.java`:
      `PATCH /api/v1/auth/accounts/{accountId}/role`, delegates to `ChangeRoleService`, maps outcomes to
      200/404/409.
- [X] T057 [US4] Run `BootstrapAdministratorIT`, `RoleChangeIT`, and the extended
      `JwtAuthenticationFilterTest` (T049–T051) and confirm they now pass.

**Checkpoint**: Roles are enforced end to end, both at the entry point (403) and in auth-service's own
invariants (409 for zero administrators), independent of US5.

---

## Phase 7: User Story 5 - View own account (Priority: P3)

**Goal**: A logged-in person can see their own profile with no sensitive data in it.

**Independent Test**: Log in, call `/me`, confirm the response matches the account and contains no
password or token field.

### Tests for User Story 5 ⚠️ write first, confirm it fails

- [X] T058 [P] [US5] Write `backend/auth-service/src/test/java/com/example/learnhub/auth/ViewOwnAccountIT.java`
      (Testcontainers): `GET /api/v1/auth/me` with a given `X-User-Id` returns `id`, `email`, `role`,
      `createdAt`; the response JSON contains no field named `password`, `passwordHash`, `token`,
      `accessToken` or `refreshToken` (FR-023).

### Implementation for User Story 5

- [X] T059 [US5] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/application/ViewOwnAccountService.java`:
      load the account for the caller's `X-User-Id`, map to a response containing only `id`, `email`,
      `role`, `createdAt`.
- [X] T060 [US5] Create `backend/auth-service/src/main/java/com/example/learnhub/auth/api/MeController.java`:
      `GET /api/v1/auth/me` (protected — falls under the `auth` catch-all from T040, no new gateway route
      needed), reads `X-User-Id`, delegates to `ViewOwnAccountService`.
- [X] T061 [US5] Run `ViewOwnAccountIT` (T058) and confirm it now passes.

**Checkpoint**: All five user stories are independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T062 [P] Update `README.md`: add `auth-service` to the "how to add a service" walkthrough (port
      9001, `backend/config-repo/auth-service.yml`) and document the three new `.env.example` variables.
- [X] T063 [P] Run `scripts/smoke-test.sh` against the full platform with `auth-service` included and
      confirm the existing platform-foundation checks (SC-009) still pass unchanged.
- [X] T064 Walk through `specs/002-user-auth/quickstart.md` section by section against the running
      platform; fix any command whose real output does not match what is documented.
- [X] T065 Create `specs/002-user-auth/checklists/success-criteria.md` (mirroring
      `specs/001-platform-foundation/checklists/success-criteria.md`'s format) recording, for each of
      SC-001 to SC-009, which automated test or quickstart step verifies it, and the measured result.
- [X] T066 [P] Run shellcheck (the existing Docker-image approach) on any shell script touched by this
      feature.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup. BLOCKS every user story.
- **US1 (Phase 3)**: Depends on Foundational only.
- **US3 (Phase 4)**: Depends on Foundational; its tests need a real token, which in practice comes from
  US1, so implement US1 first even though both are P1 (spec.md states this dependency explicitly).
- **US2 (Phase 5)**: Depends on Foundational and on US1 existing (needs a login to refresh/log out).
- **US4 (Phase 6)**: Depends on Foundational and on US3's `JwtAuthenticationGlobalFilter` existing (it
  extends that filter with role checking) and US1 (administrator must be able to log in).
- **US5 (Phase 7)**: Depends on Foundational, US1 (needs a login) and US3 (route must be enforced to be
  meaningfully "protected").
- **Polish (Phase 8)**: Depends on all five stories.

### Within Each User Story

- Tests are written and confirmed failing before implementation (constitution Principle VI).
- Domain/infrastructure classes before application services; application services before controllers;
  gateway route config changes land alongside the controller that needs them.

### Parallel Opportunities

- All Setup tasks marked `[P]` (T004–T007, T009).
- Foundational tasks marked `[P]` (T012–T017, T020–T021) — different files, no cross-dependency.
- US1's three test files (T024–T026) in parallel; US3's T035/T036 in parallel; US2's T042/T043 in
  parallel; US4's T049–T051 in parallel.
- Once Foundational (Phase 2) is done, US1 and the gateway-side groundwork for US3 could be staffed in
  parallel, but US3's tests need a real token from US1 to be meaningful end to end — sequence them if
  working solo.

---

## Parallel Example: User Story 1

```bash
# Tests together:
Task: "RegisterAccountIT in backend/auth-service/src/test/java/com/example/learnhub/auth/RegisterAccountIT.java"
Task: "LoginFlowIT in backend/auth-service/src/test/java/com/example/learnhub/auth/LoginFlowIT.java"
Task: "NoSecretsInLogsTest in backend/auth-service/src/test/java/com/example/learnhub/auth/NoSecretsInLogsTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1: Setup
2. Phase 2: Foundational (blocks everything)
3. Phase 3: User Story 1
4. **STOP and VALIDATE**: register + log in through the gateway, independently
5. Demo if ready — this alone proves the new module, its database, and JWT issuance all work

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. US1 → validate → demo (MVP)
3. US3 → validate → demo (entry-point enforcement now real; echo secure path finally works)
4. US2 → validate → demo (refresh/logout)
5. US4 → validate → demo (roles, first administrator)
6. US5 → validate → demo (own-profile view)
7. Polish

## Notes

- `[P]` tasks touch different files with no unmet dependency.
- Every story's independent test is phrased so it can be run with nothing from a later-priority story.
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.
- Avoid: skipping the "write test, watch it fail" step — Principle VI is non-negotiable, not a default.
