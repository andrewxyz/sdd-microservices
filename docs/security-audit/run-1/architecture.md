# Architecture summary (run-1, standard profile, scope: api-gateway + auth-service)

Source: sdd-microservices @ ea2fd6c. All paths are repository-relative. GW = `backend/api-gateway/src/main/java/com/example/learnhub/gateway`, AUTH = `backend/auth-service/src/main/java/com/example/learnhub/auth`, CFG = `backend/config-repo`.

## 1. Product, principals, authority, protected resources
LearnHub is an online-course platform (Spring Cloud microservices + React SPA). In scope: the **api-gateway** (sole client entry point) and **auth-service** (accounts, JWT issuance, refresh tokens, roles).
Principals: anonymous visitor (public routes only), STUDENT (self-registered, `AUTH/application/RegisterAccountService.java:38-39`), INSTRUCTOR and ADMINISTRATOR (set only through role change), and any caller on the Docker network.
Protected resources: accounts and password hashes, refresh tokens, role assignment, the HS256 signing secret, route/CORS/role policy in `CFG/api-gateway.yml`, and the identity headers (`X-User-Id`, `X-User-Role`) that every downstream service trusts.

## 2. Comparable-software baseline
Spring Cloud Gateway (WebFlux) with a **custom** nimbus-jose-jwt HS256 verifier rather than the Spring Security resource server (`backend/pom.xml:55` comment). Comparable gateways verify a signature plus `iss`/`aud`; this one checks signature, `exp`, `sub`, `role` only. Calibration only, never a reason to dismiss a finding.

## 3. Stack, deployment paths, offline limits
Java 21, Spring Boot 3.5.15, Maven multi-module, PostgreSQL + Flyway (auth), Docker Compose only (`docker-compose.yml`). Gateway routes, CORS, role gating and rate limits come solely from `CFG/api-gateway.yml` served by the config server (hot-reloaded). Profile `SPRING_PROFILES_ACTIVE` defaults to `local` in `.env.example:8`; `CFG/application-local.yml:11` adds actuator `refresh`. **No OS-enforced sandbox exists on this Windows host, so no target code is executed (source-only run).** Auth-service ITs need Docker/Testcontainers; gateway tests are WireMock loopback.

## 4. Entry surfaces and key paths
- **Gateway filter chain** (`GW/GatewayConfiguration.java:36-91`): CorrelationId -> JwtAuthentication -> RateLimit. Inbound `X-User-Id/X-User-Role` are stripped on every route (`GW/security/IdentityHeaders.java:19-26`), set from verified claims, `Authorization` removed (`GW/filter/JwtAuthenticationGlobalFilter.java:50-78`). Route policy via metadata `public`/`roles`; missing metadata = protected (`:93-125`).
- **JwtVerifier** (`GW/security/JwtVerifier.java:25-63`): MAC signature, `exp`, `sub`, string `role`; no `iss/aud/nbf`, role not checked against the enum.
- **Rate limit** (`GW/ratelimit/*`): key = TCP peer address only (`RemoteAddressClientResolver.java`), in-memory buckets, idle eviction 10 min; behind nginx all browsers share one bucket. Auth filter runs before the limiter.
- **Auth controllers** (`AUTH/api/*`): register, login, refresh, logout, me, role change (`PATCH /api/v1/auth/accounts/{id}/role`), and **`GET /api/v1/internal/accounts/{id}`** (no auth in service; no gateway route). Services read identity from plain `@RequestHeader` and have no Spring Security (`auth-service/pom.xml:48`).
- Request-body records declare `@NotBlank/@NotNull` but controllers show no `@Valid` (RegisterController:38, LoginController).
- Refresh: SHA-256-hashed opaque token, rotation read-check-save without visible transaction or lock (`AUTH/application/RotateRefreshTokenService.java:36-59`); reuse returns 401 with no family revocation. Logout revokes one refresh token only; access JWTs live 900 s.
- Login throttle: 5 failures/60 s per email and per `getRemoteAddr()` (the gateway), in memory, keys never evicted (`AUTH/infrastructure/LoginAttemptWindow.java:19-20`).
- Bootstrap admin from `ADMIN_EMAIL/ADMIN_PASSWORD` when no admin exists (`BootstrapAdministrator.java:28-51`), no strength check.
- Edge: nginx `/api/` proxy forwards client `X-Forwarded-For`, `Host=$host`, does not strip identity headers (`frontend/nginx/default.conf.template:16-21`); HTTP only.

## 5. Trust boundaries and strongest visible control
1. Client -> gateway: strip-then-inject identity, default-deny routes, HS256 signature + `exp`.
2. Gateway -> services: **trust of `X-User-*` headers with no service-side authentication**; ports 9001 and 9080 are published on all interfaces (`docker-compose.yml:183-184, 441-442`), so a direct caller bypasses the gateway (deployment fact).
3. Admin authority: enforced **only** by gateway route metadata (`CFG/api-gateway.yml:55-61`); the service never reads the role header.
4. Operator trust: config-repo edits and `refresh` can change route policy; shared JWT secret in env (`JWT_SIGNING_SECRET`, `.env.example:37`).

## 6. Starting paths
`backend/api-gateway/src/main/java/.../gateway/{filter,security,ratelimit,error}`, `backend/auth-service/src/main/java/.../auth/{api,application,infrastructure,domain}`, `backend/config-repo/{api-gateway,application,application-local,auth-service}.yml`, `docker-compose.yml`, `.env.example`, `frontend/nginx/default.conf.template`, `backend/auth-service/src/main/resources/db/migration`.

## 7. Prior coverage
No prior run exists for this repo; every unit is `none`. No carried confirmations; no exclusions.

## 8. Companion selection
Selected: **WEB-PROTOCOL-AND-AUTH.md** (HTTP gateway, JWT, refresh/session lifecycle, forwarded-header trust) and **RESOURCE-EXHAUSTION-AND-AVAILABILITY.md** (rate limiter, throttle map, pre-auth bcrypt work). Not selected, with reasons in each ledger unit: MEMORY-SAFETY-AND-BINARY (pure Java), AI-AND-LLM (no model), CLIENT-SIDE (SPA is out of scope; CORS reviewed as a gateway configuration item), SUPPLY-CHAIN (no CI/release in scope), CLOUD-AND-DEPLOYMENT (Compose only, reviewed under ordinary classes), PROTOCOLS-RPC-AND-MESSAGING (no broker use in scope), DATA-ISOLATION (no multi-tenant data in these services), DESKTOP-MOBILE-AND-LOCAL-IPC (none).
