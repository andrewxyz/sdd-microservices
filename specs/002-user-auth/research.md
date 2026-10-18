# Research: User Accounts, Login, and Gateway-Enforced Access

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-18

No technical brief was supplied with `/speckit-plan` for this feature, so every decision below was made
here, bound by the constitution (which already mandates BCrypt, short-lived JWTs with refresh tokens, and
gateway-enforced, protected-by-default routing) and by the two clarifications already recorded in spec.md.
There is no `NEEDS CLARIFICATION` remaining.

## R1. JWT signing algorithm and key distribution

- **Decision**: HMAC-SHA256 (HS256) with a single shared secret, read from an environment variable
  (`JWT_SIGNING_SECRET`, minimum 32 bytes) by both auth-service (signs) and api-gateway (verifies).
- **Rationale**: Principle X requires the simplest design that meets the spec. The gateway and auth-service
  are both trusted, same-deployment components (neither is reachable by clients except through the gateway
  itself), so there is no boundary that asymmetric signing (RS256/ES256) would protect that a shared secret
  does not already protect equally well. A shared secret avoids keystore/JWK provisioning, rotation tooling,
  and a public-key distribution step, none of which the spec asks for.
- **Alternatives considered**: RS256/ES256 with auth-service holding a private key and the gateway holding
  only the public key. Rejected for this feature: it is the textbook choice when the verifier is a different
  trust boundary (e.g., a third-party API), which does not apply here, and it adds key-pair generation and
  distribution tasks with no corresponding requirement in spec.md. Revisit if a future spec lets a party
  outside this platform verify tokens.

## R2. Refresh token lifecycle (rotation and reuse detection)

- **Decision**: Every successful `/refresh` call issues a new access token **and** a new refresh token, and
  marks the presented refresh token `used`. The refresh table stores a SHA-256 hash of the token value, never
  the raw value. A refresh token already marked `used` or `revoked` is rejected (FR-011), independent of
  whether it has also expired.
- **Rationale**: FR-009 and FR-011 only make sense together as single-use, rotating refresh tokens: FR-011
  presupposes a token can become "already used," which requires each use to produce a replacement, otherwise
  a 30-day refresh token could be exercised only once in its entire life. This is also the OWASP-recommended
  refresh-token pattern (rotation + reuse detection) and needs no additional library.
- **Alternatives considered**: Long-lived, reusable refresh tokens (no rotation). Rejected: cannot satisfy
  FR-011 as written, and gives a stolen refresh token a 30-day window instead of a one-shot window.

## R3. Login-attempt throttling (FR-008, SC-005)

- **Decision**: auth-service tracks failed login attempts in memory, keyed separately by normalized email
  and by source IP, in a 1-minute sliding window. Once a key reaches 5 failures inside the window, further
  attempts against that key return 429 (same problem-details shape used elsewhere) until the window rolls
  over, satisfying SC-005's "refused by the 10th consecutive failure" with margin.
- **Rationale**: This is domain-specific brute-force protection, distinct from the gateway's existing
  IP-keyed token-bucket rate limiter (which protects all routes generically and must not be reused for this,
  per-account purpose). In-memory, single-instance state mirrors the gateway's own rate limiter design
  (`TokenBucketRateLimiter`, no Redis) and needs no new dependency. auth-service runs as a single instance in
  this feature (no `-b` scaled copy is requested, unlike echo-service), so in-memory state does not need to
  be shared across instances yet.
- **Alternatives considered**: A persisted (database) attempt counter, which would survive a restart and
  work across future scaled instances. Rejected for now as unnecessary complexity (Principle X); call out as
  a documented limitation if auth-service is ever scaled horizontally.

## R4. Identity propagation and header-spoofing prevention

- **Decision**: The gateway strips any inbound `X-User-Id` and `X-User-Role` headers from every request
  before routing it, regardless of whether the route is public or protected. For a protected route, after
  successful token validation it sets `X-User-Id` (the account's UUID) and `X-User-Role` (one of `STUDENT`,
  `INSTRUCTOR`, `ADMINISTRATOR`) and removes the inbound `Authorization` header before forwarding downstream.
- **Rationale**: FR-020 requires the destination service to receive identity and role but never the token
  itself. Stripping client-supplied copies of the same header names first is necessary or a caller could
  simply forge `X-User-Id: <victim>` on a request that never goes through real authentication logic (for
  example, a public route that is also handled by a service with a protected sibling route sharing code).
- **Alternatives considered**: Trusting the network boundary (only the gateway can reach services) without
  stripping. Rejected: defense in depth is cheap here (a few lines in one filter) and removes a whole class
  of spoofing bugs if a route's public/protected metadata is ever misconfigured.

## R5. Route-level role enforcement

- **Decision**: Extend gateway route metadata with an optional `roles` key (a list of role names). A route
  with no `roles` key requires only a valid token (any role); a route with `roles` present requires the
  token's role to be one of the listed values, else 403. This is additive to the existing `public` metadata
  key from the platform-foundation feature; no existing route's behavior changes.
- **Rationale**: FR-019 requires entry-point role enforcement, but gateway routes are matched by path
  prefix, not by individual endpoint. The one role-restricted action in this spec (changing another
  account's role) gets its own narrow, earlier-ordered route (`/api/v1/auth/accounts/**`) ahead of the
  general `/api/v1/auth/**` catch-all, the same pattern already used for `echo-public` vs. `echo` in
  `api-gateway.yml`. No per-endpoint routing table is needed beyond that.
- **Alternatives considered**: Enforcing the role check only inside auth-service (no gateway-level role
  metadata). Rejected: FR-019 explicitly scopes this to "the entry point," and the platform-foundation
  precedent (gateway owns all access-control decisions) argues for keeping it there; auth-service MAY still
  re-check defensively, but that is not required by this spec.

## R6. First-administrator bootstrap

- **Decision**: An `ApplicationRunner` in auth-service runs once at startup: if no account with the
  administrator role exists, it creates one from `ADMIN_EMAIL` and `ADMIN_PASSWORD` environment variables
  (validated non-empty, same `${VAR:?message}`-style required-setting convention as the rest of the
  platform), hashing the password the same way registration does. If an administrator already exists, the
  runner does nothing and does not re-read the variables' values into the account.
- **Rationale**: Satisfies FR-015 idempotently (safe to run on every restart, matching the db-init script's
  own idempotency pattern from platform-foundation) without a special-cased registration path or a manual
  database step.
- **Alternatives considered**: A Flyway seed migration. Rejected: migrations run once per schema version and
  cannot read live environment values at the time the platform actually starts in a given environment, and
  re-running to pick up a changed `ADMIN_PASSWORD` would require a new migration each time.

## R7. Password hashing

- **Decision**: `org.springframework.security:spring-security-crypto`'s `BCryptPasswordEncoder` with its
  default strength (10). Added to `backend/pom.xml` dependency management (no version pinned beyond what
  `spring-boot-starter-parent` already manages, consistent with how every other Spring library in this
  project is versioned).
- **Rationale**: Principle VII mandates BCrypt by name; this is the standard, minimal Spring library that
  provides it, already present on the classpath of any project using Spring Security's crypto module without
  pulling in full Spring Security's filter-chain machinery (auth-service does not need a servlet security
  filter chain; its HTTP layer is plain Spring MVC controllers using `platform-common`'s existing
  correlation/error-handling support, same as echo-service).
- **Alternatives considered**: None; the constitution names the algorithm, leaving no real alternative to
  evaluate.

## R8. Problem-details contract gains a 403 status

- **Decision**: Extend the existing, shared `problem-details.schema.json`
  (`specs/001-platform-foundation/contracts/problem-details.schema.json`) status enum to include `403`, and
  add a fixed `detail` phrase for it in the gateway's `GatewayProblem` class. The schema is not duplicated
  for this feature; it is the one contract every HTTP error in the platform already uses.
- **Rationale**: FR-019 is the first requirement in the platform to produce a 403, so the enum, written
  before any feature needed 403, simply did not include it yet. Adding the value is a backward-compatible
  schema widening, not a new contract.
- **Alternatives considered**: A separate auth-only error schema. Rejected: Principle VIII requires one
  error shape for every client-reachable service; forking it would violate that for no benefit.

## R9. JWT library

- **Decision**: `com.nimbusds:nimbus-jose-jwt`, used directly (sign in auth-service, parse/verify in
  api-gateway), with its version pinned in `backend/pom.xml` dependency management.
- **Rationale**: It is the JOSE/JWT library Spring itself depends on wherever it supports JWTs
  (`spring-security-oauth2-jose`), so it is already a well-exercised, Spring-ecosystem-standard choice rather
  than an unfamiliar third library. Using it directly (without pulling in the full OAuth2 resource-server
  filter chain) keeps the gateway's existing custom `GlobalFilter`-based design — already used for
  correlation ID, route access and rate limiting — consistent, rather than mixing it with Spring Security's
  reactive security filter chain for just this one concern.
- **Alternatives considered**: `io.jsonwebtoken:jjwt` (equally common, not already implied by the Spring
  stack); Spring Security's full OAuth2 Resource Server support (`spring-security-oauth2-resource-server`),
  rejected because it is designed around an external authorization server and introspection/JWK-set
  endpoints, which is more machinery than one shared-secret, same-deployment issuer needs.
