# NEEDS VALIDATION: unresolved leads (run-1)

These leads are source-grounded and independently re-checked, but cannot be settled from source alone. They are **not confirmed vulnerabilities and carry no severity**. Each lists the decisive missing fact, a bounded local check (dummy data, isolated, no external network) and an owner-observed check on a non-production stack. Nothing here asks anyone to send audit traffic to a live system.

## 1. ADMINISTRATOR-only role-change route (and the unrouted internal account lookup) may be reachable through a non-normalized path that the gateway matches differently from Tomcat

- **Fingerprint:** `api-gateway/route-role-metadata-path-normalization-bypass`
- **Affected boundary:** Gateway role policy versus servlet path normalisation
- **Why this priority:** If it holds, any signed-in student could promote themselves to ADMINISTRATOR, and the unauthenticated internal lookup could be reached. Cheap to settle: one gateway-plus-stub test on a throwaway stack.

**Description.** The ADMINISTRATOR restriction on PATCH /api/v1/auth/accounts/{id}/role exists only as metadata on the auth-admin route (Path=/api/v1/auth/accounts/**); auth-service never reads X-User-Role and has no Spring Security. The gateway is WebFlux with segment-based PathPattern matching and no visible normalization filter. A path such as /api/v1/auth//accounts/{id}/role or /api/v1/auth/./accounts/{id}/role has an empty or dot segment, so it should not match auth-admin. It then falls to the catch-all auth route (order 4, no roles metadata), where any valid token (including STUDENT) passes and identity headers are injected. The raw path is forwarded: nginx proxy_pass has no URI part and the gateway forwards the request path. Tomcat in auth-service would normally collapse // and resolve dot segments before Spring MVC maps the request, which would reach RoleChangeController and let a STUDENT change any account's role, including their own to ADMINISTRATOR. Variant assessed: the same mechanism with /api/v1/auth/../internal/accounts/{uuid} (or encoded %2e%2e) could reach the unauthenticated InternalAccountController, which returns an account's id and email by UUID. This is the same root cause (gateway policy evaluated on a differently parsed path than the servlet container routes on), so it is folded into this fingerprint, with a lower-impact sink. Reachability is not established from source. Spring Cloud Gateway/PathPattern handling of empty and dot segments, Netty request-URI handling, and Tomcat normalization/rejection (including encoded dots and encoded slashes) are from general knowledge and not verified in this repo.

**Claimed root cause.** Authorization is enforced by path-predicate-keyed gateway route metadata, so the policy is evaluated on a differently parsed path than the one the downstream servlet container routes on, and the service itself performs no authorization and trusts the gateway.

**Source trace.**

1. *entrypoint* `backend/config-repo/api-gateway.yml:59` (route auth-admin): Role gate (roles: [ADMINISTRATOR], line 61) attached only to the literal predicate Path=/api/v1/auth/accounts/**.
2. *propagation* `backend/config-repo/api-gateway.yml:67` (route auth): Catch-all Path=/api/v1/auth/** with no roles metadata and no public flag; a path that misses auth-admin because of an empty or dot segment lands here.
3. *propagation* `backend/api-gateway/src/main/java/com/example/learnhub/gateway/filter/JwtAuthenticationGlobalFilter.java:65` (filter()): requiredRoles is read only from the matched route's metadata; for the auth route it is empty, so any valid token passes and X-User-Id/X-User-Role are injected from the token.
4. *sink* `backend/auth-service/src/main/java/com/example/learnhub/auth/api/RoleChangeController.java:30` (changeRole()): If the servlet container normalizes the forwarded path to /api/v1/auth/accounts/{id}/role, the PatchMapping runs; it reads no identity or role header and changes the role.

**Verified evidence.**

- `backend/auth-service/src/main/java/com/example/learnhub/auth/api/RoleChangeController.java:14`: Javadoc states role enforcement happens at the gateway auth-admin route, not in the service.
- `backend/config-repo/api-gateway.yml:59`: The only role restriction is the Path predicate /api/v1/auth/accounts/**; route auth at line 67 is the fallback.
- `frontend/nginx/default.conf.template:17`: proxy_pass ${GATEWAY_URL} has no URI part, so the original request URI is forwarded unchanged. Gateway port 9080 is also published directly.
- `backend/auth-service/src/main/java/com/example/learnhub/auth/api/InternalAccountController.java:25`: Variant sink: unauthenticated GET /api/v1/internal/accounts/{id} returning id and email; protected only by the absence of a gateway route, so by the same path-matching assumption.

**Exact blockers (why it is unresolved).**

- Not executed (source-only run, no sandbox): unverified whether Spring Cloud Gateway PathPattern in the pinned version fails to match auth-admin for // and /./ segments, and whether the gateway forwards the raw path unchanged to the lb:// backend.
- Unverified whether the embedded Tomcat in auth-service (Spring Boot 3.5.15) normalizes // and /./ and /../ before Spring MVC mapping, or rejects them (for example encoded dots or slashes) with 400, which would refute the variant and possibly the main claim.

**Bounded local check.** In a sandbox with no external network and dummy data, start the gateway with the real config-repo routes and a loopback stub auth-service built on the same embedded Tomcat and Spring MVC stack (or the real auth-service with a Testcontainers dummy DB). Mint a STUDENT token with a dummy secret. Send PATCH /api/v1/auth/accounts/{dummyId}/role, then the // and /./ variants, and record the matched route id and HTTP status for each (expect 403 on the canonical path). Also send GET /api/v1/auth/../internal/accounts/{dummyId} and the %2e%2e form. Add a unit test of the auth-admin Path predicate against the non-normalized paths.

**Owner-observed check (non-production only).** Owner-observed check on a non-production stack only: with a throwaway STUDENT token and a throwaway target account, send the canonical and non-normalized role-change requests through nginx and directly to the gateway, and compare the matched route and status. Do not run against production. Fix direction if confirmed: reject or normalize paths containing empty or dot segments in an early gateway filter, and enforce ADMINISTRATOR in auth-service from X-User-Role (and require a service-to-service credential on /api/v1/internal/**).

---

## 2. Directly published service ports let a network caller bypass the gateway and forge X-User-Id

- **Fingerprint:** `deploy/service-ports-published-bypass-gateway-header-trust`
- **Affected boundary:** Gateway-only authentication versus directly published service ports
- **Why this priority:** If the ports are reachable from an untrusted network, identity headers can be forged and the gateway's checks skipped entirely. Settled by one reachability check from a second host.

**Description.** docker-compose.yml publishes auth-service (9001:9001), the other business services (9002-9007), echo-service (9099) and the gateway on all host interfaces. The services have no Spring Security and read X-User-Id from a plain @RequestHeader. A caller who reaches 9001 directly can send any account UUID in X-User-Id to GET /api/v1/auth/me and read that account. The same caller can read id and email from the unauthenticated GET /api/v1/internal/accounts/{id}, which is protected only because no gateway route targets it. Admin role gating is also enforced only by gateway route metadata, which direct callers skip. Needs a UUID to target. POST /logout also needs a valid refresh token, so it is weaker than /me.

**Claimed root cause.** Authentication and identity binding exist only at the gateway. Services accept caller-supplied identity headers, and compose publishes their ports to the host instead of keeping them on the internal network.

**Source trace.**

1. *entrypoint* `docker-compose.yml:184` (auth-service ports 9001:9001): Port mapping without a 127.0.0.1 bind, so it is published on all host interfaces. Similar mappings exist for course (217), enrollment (254), payment (294), certificate (334), review (370), notification (407), echo (149) and the gateway (442).
2. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/api/MeController.java:24` (MeController.me): The caller account id is taken from the X-User-Id request header with no service-side authentication, then passed to viewOwnAccountService.view.
3. *sink* `backend/auth-service/src/main/java/com/example/learnhub/auth/api/InternalAccountController.java:26` (InternalAccountController.summary): Returns id and email for any account UUID with no authentication. The class comment (lines 10-14) says protection is only the absence of a gateway route.

**Verified evidence.**

- `backend/auth-service/src/main/java/com/example/learnhub/auth/api/LogoutController.java:32`: Logout binds callerAccountId from the raw X-User-Id header. It also requires a refreshToken body, which limits abuse.
- `backend/config-repo/api-gateway.yml:55`: The auth-admin route carries the role restriction as gateway route metadata only. Identity header stripping lives in the gateway Java code, which direct callers bypass.

**Exact blockers (why it is unresolved).**

- Whether host ports 9001-9007 and 9099 are reachable from an untrusted network depends on host firewall and deployment topology. This is a deployment fact outside the source. The compose file looks like a local development stack, which lowers likelihood.
- Not executed: no OS-enforced sandbox on this host, so the header-forgery result was not observed. The source shows no authentication filter in the service.

**Bounded local check.** In a sandboxed local compose stack with dummy accounts, send GET /api/v1/auth/me to port 9001 with X-User-Id set to a dummy account UUID and no Authorization header, and check for a 200 with that account's data. Also GET /api/v1/internal/accounts/{dummy-uuid} on 9001.

**Owner-observed check (non-production only).** Owner checks from a second host or network segment whether ports 9001-9007 and 9099 accept connections. Then remove the ports mappings for every service except the gateway and frontend, or bind them to 127.0.0.1, so services are reachable only on the compose network.

---

## 3. Refresh token single-use rotation is a non-atomic read-check-write, allowing concurrent double redemption

- **Fingerprint:** `auth-service/refresh-token-rotation-non-atomic-double-spend`
- **Affected boundary:** Refresh-token single-use rotation under concurrency
- **Why this priority:** A stolen refresh token could yield an independent session if the attacker wins a race. Needs a stolen token, so it is narrower; the fix is small and standard.

**Description.** POST /api/v1/auth/refresh is public. RotateRefreshTokenService.rotate() loads the record by hash, checks usedAt/revokedAt/expiry in memory, then calls markUsed and save, and then issues a new access JWT and a new persisted refresh token. The method has no @Transactional. The repository is a plain JpaRepository. The entity has no @Version. The schema has no single-use constraint. Two simultaneous requests that present the same refresh token can both read usedAt==null and both receive a fresh token pair. A thief who holds a stolen token, and who races the legitimate holder or a second thief, could keep an independent session. A sequential replay is refused with 401, and the token family is not revoked. Impact is limited to one narrow race window on a token the attacker must already hold. This is a source-confirmed missing atomicity control, but the race has not been observed.

**Claimed root cause.** findByTokenHash, isUsable, markUsed and save run as separate steps. There is no transaction, @Version, row lock or conditional UPDATE ... WHERE used_at IS NULL, so concurrent readers can both see usedAt==null. The save is a blind update.

**Source trace.**

1. *entrypoint* `backend/auth-service/src/main/java/com/example/learnhub/auth/api/RefreshController.java:26` (RefreshController.refresh): Public endpoint (POST /api/v1/auth/refresh) passes the client-supplied refresh token to rotate().
2. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/RotateRefreshTokenService.java:38` (RotateRefreshTokenService.rotate): Loads the record by hash. Line 41 then checks isUsable(now) on the in-memory snapshot, with no lock or transaction.
3. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/domain/RefreshTokenRecord.java:53` (RefreshTokenRecord.isUsable): The check is an in-memory test of usedAt==null && revokedAt==null && expiresAt.isAfter(now). The entity has no @Version, so a later save does not detect a concurrent update.
4. *sink* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/RotateRefreshTokenService.java:48` (RotateRefreshTokenService.rotate): markUsed and save at lines 48-49. Lines 51-56 then issue a new JWT and persist a new refresh token. Every request that passed the earlier check reaches this point.

**Verified evidence.**

- `backend/auth-service/src/main/java/com/example/learnhub/auth/application/RotateRefreshTokenService.java:36`: rotate() has no @Transactional. A repo-wide grep found @Transactional only on AccountLookupService (readOnly). spring.jpa.open-in-view is false (application.yml:26), so each repository call runs in its own short transaction.
- `backend/auth-service/src/main/resources/db/migration/V2__create_refresh_tokens.sql:13`: Only a unique index on token_hash. Nothing enforces single use at the DB level.
- `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/RefreshTokenRepository.java:10`: Plain derived finder findByTokenHash. There is no @Lock, no @Modifying conditional update, and no atomic compare-and-set.

**Exact blockers (why it is unresolved).**

- No code can be executed in this source-only run, so the race has not been observed against PostgreSQL.
- The decisive fact is whether two concurrent same-token requests both return 200 with default Hibernate and PostgreSQL READ COMMITTED behaviour. Source says they can, but the timing window needs a runtime observation.
- Practical exploitability needs an attacker who already holds a valid unused refresh token and can race the legitimate holder. This is a deployment threat-model fact, not visible in source.

**Bounded local check.** In an isolated PostgreSQL or Testcontainers integration test with dummy accounts, log in once. Then release N=2..10 simultaneous POST /api/v1/auth/refresh calls with the same refresh token from a CountDownLatch. Assert that exactly one returns 200 and the rest return 401. Count refresh_tokens rows with used_at set versus new rows issued. Fix: replace the read-check-write with one conditional UPDATE refresh_tokens SET used_at=? WHERE token_hash=? AND used_at IS NULL AND revoked_at IS NULL AND expires_at>?, and treat 0 rows as 401. Alternatively add @Version plus @Transactional, or take a pessimistic lock. Optionally revoke the account's tokens on detected reuse.

---

## 4. Login throttle keyed by the gateway's address lets one anonymous caller lock out all logins

- **Fingerprint:** `auth-service/LoginAttemptWindow-shared-gateway-address-key-global-lockout`
- **Affected boundary:** Login throttle address key versus the gateway proxy address
- **Why this priority:** One anonymous caller could keep every user's login returning 429. Availability only, but trivially cheap to attempt if the address assumption is true.

**Description.** AuthenticateService.login throttles on the lowercased email and on HttpServletRequest.getRemoteAddr(). A grep of backend/ finds no forward-headers strategy or X-Forwarded-For handling in auth-service or config-repo. The only forwarded-header mention is a comment in the gateway's RemoteAddressClientResolver saying it ignores X-Forwarded-For. For gateway-routed logins, getRemoteAddr() is therefore very likely the gateway's address for every client. Every failure, including an unknown email, is recorded against both keys. Five failures in 60 s would then make every login platform-wide return 429 until the window rolls, and about 5 requests per minute sustains it. The per-email key also allows targeted lockout of a chosen victim, which is a common trade-off and is not claimed separately. The gateway address seen by auth-service is a deployment fact (Docker network, proxy behaviour) that source cannot settle.

**Claimed root cause.** The per-address throttle key is the immediate TCP peer, which is the gateway and not the end client. The throttle has no separate budget for the proxy address and no trusted client-IP derivation.

**Source trace.**

1. *entrypoint* `backend/auth-service/src/main/java/com/example/learnhub/auth/api/LoginController.java:28` (LoginController.login): Unauthenticated POST /api/v1/auth/login passes httpRequest.getRemoteAddr() as sourceIp. The route is public in the gateway config.
2. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java:50` (AuthenticateService.login): Returns 429 before any credential check if either the email key or the sourceIp key is throttled.
3. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java:58` (AuthenticateService.login): Every failure, including an unknown email, is recorded against both the email key and the sourceIp key (lines 58-59).
4. *sink* `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/LoginAttemptWindow.java:35` (LoginAttemptWindow.isThrottled): Throttled when 5 or more failures fall inside the 60 s window for the key. If the key is the shared gateway address, this applies to all callers.

**Verified evidence.**

- `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/LoginAttemptWindow.java:19`: THRESHOLD=5 and WINDOW_SECONDS=60 (line 20) apply uniformly to any key, including a shared address. There is no per-proxy exemption, and clear() is called only for the email key.
- `backend/config-repo/api-gateway.yml:45`: The auth-public route forwards register/login/refresh to auth-service via the gateway (lb://auth-service). It has no login-specific limit or X-Forwarded handling in this file.
- `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/RemoteAddressClientResolver.java:8`: The comment says X-Forwarded-For is deliberately ignored. A backend-wide grep found no forward-headers configuration in auth-service, so getRemoteAddr() is not rewritten from forwarded headers.

**Exact blockers (why it is unresolved).**

- The remote address auth-service sees for gateway-forwarded requests is a deployment fact (Docker network, gateway outbound address, any proxy hop) that cannot be observed from source. No server.forward-headers-strategy is set in the repository, which makes the gateway address likely, but this is unverified.
- No target code may be executed in this source-only run, so the 429 behaviour was not observed.

**Bounded local check.** In an isolated fixture using the existing MockMvc/unit setup, call AuthenticateService.login 5 times with unknown emails and the same sourceIp. Then log in with a valid dummy account and a different email from that sourceIp and expect 429. Then repeat with a different sourceIp and expect success. This shows that the shared key is the lockout mechanism.

**Owner-observed check (non-production only).** In a test deployment the owner checks that auth-service sees the gateway container address as getRemoteAddr() for requests from two different clients, for example via a debug log. They then send 5 bad logins from client A and confirm that client B's valid login gets 429 for up to 60 s. This should be run only in a non-production environment.

---

## 5. Actuator refresh endpoint is exposed without authentication on the gateway and auth-service ports under the default local profile

- **Fingerprint:** `gateway/actuator-refresh-exposed-unauthenticated-in-default-profile`
- **Affected boundary:** Actuator management endpoint versus gateway authentication
- **Why this priority:** Under the default local profile an anonymous caller may trigger a config refresh and clear rate-limit state. Impact is bounded (operator-controlled config); depends on the profile in use.

**Description.** SPRING_PROFILES_ACTIVE is documented as local in .env.example and compose requires it for every service. application-local.yml adds refresh to the exposed actuator endpoints, on the application port (9080 gateway, 9001 auth-service, both published). Neither service has a Spring Security web filter chain (auth-service has only spring-security-crypto; the gateway has no security starter). The JWT filter is a GlobalFilter that reads GATEWAY_ROUTE_ATTR, and the rate limiter is also a GlobalFilter, so both are source-expected not to run for actuator handler requests. An anonymous network caller could likely POST /actuator/refresh. ContextRefresher.refresh() should publish EnvironmentChangeEvent even with no changed keys, and GatewayConfiguration.onConfigurationRefresh resets both rate-limit buckets. Impact is bounded: refresh re-reads config the operator already controls, and the only observed side effect is clearing throttle state and a refresh-scope rebuild. The profile is a documented developer convenience, and application-local.yml says refresh stays closed outside it.

**Claimed root cause.** A state-changing management endpoint is enabled by a profile that is the shipped default, on the application port, with no authentication layer; the JWT filter and rate limiter exist only in the gateway route filter chain.

**Source trace.**

1. *entrypoint* `backend/config-repo/application-local.yml:11` (management.endpoints.web.exposure.include): The local profile adds refresh to the exposed actuator endpoints for every config client.
2. *propagation* `.env.example:8` (SPRING_PROFILES_ACTIVE): The documented default is local; docker-compose.yml passes it to the gateway (line 434) and auth-service (line 172).
3. *propagation* `docker-compose.yml:442` (api-gateway.ports): Port 9080 is published and, with no management.server.port found in the checked yml files, the actuator shares it. Auth-service publishes 9001 at line 184.
4. *sink* `backend/api-gateway/src/main/java/com/example/learnhub/gateway/GatewayConfiguration.java:108` (GatewayConfiguration.onConfigurationRefresh): On EnvironmentChangeEvent both token-bucket limiters are reset (lines 110-111), clearing every client's consumed budget.

**Verified evidence.**

- `backend/config-repo/application.yml:30`: The base exposure list is health,info, so refresh is open only under the local profile.
- `backend/api-gateway/src/main/java/com/example/learnhub/gateway/filter/JwtAuthenticationGlobalFilter.java:49`: Authentication is a GlobalFilter keyed on GATEWAY_ROUTE_ATTR, which is set only for matched gateway routes.
- `backend/auth-service/pom.xml:48`: Only spring-security-crypto is present, so no web filter chain protects the actuator. Actuator is declared at line 73.
- `backend/api-gateway/pom.xml:36`: spring-cloud-starter-config and the actuator starter (line 28) are present, so the refresh endpoint is available when exposed.

**Exact blockers (why it is unresolved).**

- Whether ContextRefresher.refresh() publishes EnvironmentChangeEvent when no property changed depends on the spring-cloud-context version. It is believed to publish always but was not executed or confirmed.
- That actuator requests bypass the gateway GlobalFilters (including the rate limiter) on this WebFlux gateway is source-inferred, not observed.
- The deployed profile and the network reachability of ports 9080 and 9001 are outside the source.

**Bounded local check.** In an isolated sandbox with no external network, run the gateway with the local profile and a file-based config. Send POST /actuator/refresh without Authorization and record the status. Then exhaust the rate-limit bucket on a routed path, call refresh again, and check whether the next routed request succeeds, which confirms a bucket reset with no property change.

**Owner-observed check (non-production only).** Owner on a non-production deployment started with the .env.example profile: send an unauthenticated POST to /actuator/refresh on ports 9080 and 9001 from a non-local host and record the status codes and whether rate limiting resets.

---

## 6. Gateway rate-limit bucket keyed on the nginx peer address lets one client exhaust the budget for all users

- **Fingerprint:** `gateway-ratelimit-shared-bucket-behind-proxy`
- **Affected boundary:** Per-client rate limit versus a single proxy peer address
- **Why this priority:** One client could drain the shared bucket and cause 429 for everyone. A documented design trade-off; real impact depends on the deployed network path.

**Description.** The gateway rate limiter keys only on the TCP peer address and deliberately ignores X-Forwarded-For. In the shipped compose topology the SPA's nginx proxies /api/ to the gateway, so all browser users appear as nginx's address and share one general bucket (capacity 200, 100 tokens/s) and one strict bucket for certificate verification (capacity 10, 2 tokens/s). A single unauthenticated client sending a sustained rate above the refill rate to a public route could empty the bucket and cause 429 for other users, including login and refresh. The auth filter runs before the limiter, but public routes need no token. Source confirms the keying and the shared-bucket mechanics. Whether real users share one peer address, and whether an external edge limiter exists, depends on deployment.

**Claimed root cause.** RemoteAddressClientResolver returns the immediate peer address as the only quota dimension, and the compose topology funnels all end users through one peer (nginx), so quota scope does not match the principal. The accepted trade-off is documented in the class comment, which makes this a design limitation that is exploitable as availability loss.

**Source trace.**

1. *entrypoint* `frontend/nginx/default.conf.template:16` (location /api/): All browser API traffic is proxied to the gateway from the nginx container, so the gateway sees one remote address (nginx).
2. *propagation* `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/RemoteAddressClientResolver.java:25` (RemoteAddressClientResolver.resolve): Returns the peer host address; X-Forwarded-For is deliberately ignored.
3. *propagation* `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/RateLimitGlobalFilter.java:57` (RateLimitGlobalFilter.filter): Passes that single key to tryConsume on the general bucket, or the strict bucket for routes with rate-limit: strict (lines 56-58).
4. *sink* `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/TokenBucketRateLimiter.java:30` (TokenBucketRateLimiter.tryConsume): One shared bucket per key; when empty (line 40-44) every caller sharing the key gets false and the filter returns 429.

**Verified evidence.**

- `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/RemoteAddressClientResolver.java:8`: Comment documents that X-Forwarded-For is ignored and clients behind one proxy share one limit (accepted trade-off).
- `backend/config-repo/api-gateway.yml:220`: General bucket capacity 200, refill 100/s; strict bucket capacity 10, refill 2/s (lines 226-228).
- `backend/config-repo/api-gateway.yml:49`: Login, register and refresh are public routes that pass through the same limiter, so draining the bucket blocks authentication for all users sharing the key.
- `docker-compose.yml:462`: The frontend service sets GATEWAY_URL to the api-gateway container, so nginx is the gateway's peer for SPA traffic.

**Exact blockers (why it is unresolved).**

- The deployed network path is not visible from source. If users reach the gateway directly or through an ingress preserving distinct peer addresses (the gateway also publishes 9080), buckets are per-user and the issue does not arise.
- Whether any upstream or edge limiter outside the repository restricts a single client is not visible.

**Bounded local check.** In an isolated fixture with the gateway behind an nginx built from default.conf.template, send requests from client A just above the refill rate to a public route and confirm client B receives 429 with Retry-After. Use small bounded volumes and dummy clients only.

**Owner-observed check (non-production only).** Owner checks which peer address the gateway sees for two different browser clients, and whether an external edge limiter or trusted-proxy handling sits in front of nginx. Do not saturate production to test.

---

## 7. Last-administrator guard in ChangeRoleService is a non-atomic check-then-act, so concurrent demotions can leave zero administrators

- **Fingerprint:** `auth-service/ChangeRoleService-last-admin-check-then-act-race`
- **Affected boundary:** Last-administrator invariant under concurrent role changes
- **Why this priority:** Two administrators demoting each other at once could leave no administrator. Requires administrator access and a narrow race; bootstrap may recover on restart.

**Description.** ChangeRoleService.changeRole loads the target, counts administrators and then saves the new role as separate statements. The class and method have no @Transactional (the only @Transactional in auth-service main is AccountLookupService, read-only). The accounts table has no lock, @Version or constraint that enforces a minimum number of administrators. With exactly two administrators A and B, two concurrent PATCH requests (A demotes B, B demotes A) can both read count=2, both pass the guard and both commit. The platform is then left with no administrator, violating FR-014. Only administrators can trigger it (the gateway enforces the role), so impact is limited to an administrative lockout or denial of service. BootstrapAdministrator checks countByRole > 0 at startup and may recreate an admin, but that is only a possible recovery; it was not verified here. The race window is narrow, and whether it is hit in practice depends on runtime timing, which source cannot settle.

**Claimed root cause.** The invariant 'at least one ADMINISTRATOR' is enforced by an application-level read (countByRole) followed by a write (save) with no transaction, row or advisory lock, optimistic version, or database constraint.

**Source trace.**

1. *entrypoint* `backend/auth-service/src/main/java/com/example/learnhub/auth/api/RoleChangeController.java:30` (RoleChangeController.changeRole): PATCH /api/v1/auth/accounts/{accountId}/role passes the path id and the requested role straight to the service. Administrator-only access is enforced at the gateway by route metadata, not here.
2. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/ChangeRoleService.java:29` (ChangeRoleService.changeRole): The guard reads target.getRole() and accounts.countByRole(ADMINISTRATOR) <= 1 (line 31) in an unlocked, non-transactional method. Concurrent requests can observe the same count.
3. *sink* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/ChangeRoleService.java:38` (ChangeRoleService.changeRole): target.setRole(newRole) (line 37) then accounts.save(target) (line 38) commits independently of the other request, so both demotions can persist.

**Verified evidence.**

- `backend/auth-service/src/main/java/com/example/learnhub/auth/application/ChangeRoleService.java:31`: The count check is the only enforcement of the invariant. The method has no @Transactional, lock or version check.
- `backend/auth-service/src/main/resources/db/migration/V1__create_accounts.sql:6`: The only DB constraint on role is a CHECK on the allowed values. Nothing enforces a minimum number of administrators and there is no version column.
- `backend/auth-service/src/test/java/com/example/learnhub/auth/RoleChangeIT.java:102`: The last-admin guard is tested only with sequential calls (409 on demoting the last admin). There is no concurrent case.

**Exact blockers (why it is unresolved).**

- Source-only run: no code execution is permitted, so the race has not been demonstrated.
- Unverified: that two requests overlap between the count read and the save under Spring Data's per-call autocommit on PostgreSQL READ COMMITTED. This is expected from the code but needs a runtime observation.

**Bounded local check.** In a Testcontainers Postgres IT for auth-service, seed two ADMINISTRATOR dummy accounts A and B. Fire two concurrent changeRole calls (A to STUDENT, B to STUDENT) from separate threads, with a barrier before the count read, over many iterations. Record whether countByRole(ADMINISTRATOR) ever reaches 0. Re-run after adding the fix.

**Owner-observed check (non-production only).** Owner check on a non-production stack with two dummy admins: issue the two PATCH requests concurrently through the gateway and query the administrator count. Fix: make changeRole @Transactional and lock the administrator rows (for example SELECT ... FOR UPDATE on all ADMINISTRATOR rows) or take pg_advisory_xact_lock before counting.

---

## 8. Account existence can be enumerated through register 409 and login BCrypt timing

- **Fingerprint:** `auth-service/account-enumeration-register-409-and-login-timing`
- **Affected boundary:** Account-existence disclosure by response and timing
- **Why this priority:** The register 409 is a deliberate product requirement (owner decision); the login timing gap is unmeasured and bounded by the login throttle.

**Description.** POST /api/v1/auth/register returns 409 'An account with this email already exists.' for an existing email before any password-strength check. This oracle is documented as deliberate (FR-002) and is shared by most self-registration flows. Separately, AuthenticateService.login skips the BCrypt comparison when the email is unknown, although the 401 body and status are identical and the code comment and Javadoc claim FR-007 indistinguishability. The BCrypt skip is read directly from source. The size of the timing gap, and whether it is observable through the nginx and gateway path and the shared rate-limit bucket, was not measured because this run cannot execute code.

**Claimed root cause.** Account existence is checked and revealed before validation. The login unknown-account path does no equivalent hashing work (no dummy BCrypt compare), so response time differs from the wrong-password path.

**Source trace.**

1. *entrypoint* `backend/auth-service/src/main/java/com/example/learnhub/auth/api/RegisterController.java:38` (RegisterController.register): Public POST /api/v1/auth/register takes an attacker-chosen email and password. There is no @Valid on the parameter.
2. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/RegisterAccountService.java:29` (RegisterAccountService.register): existsByEmailIgnoreCase runs first. On a hit it throws 409 at line 31, before the password-strength check at line 34.
3. *sink* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java:56` (AuthenticateService.login): The condition `account == null || !passwordHasher.matches(...)` short-circuits when the account is missing, so BCrypt runs only for existing accounts. Both failure paths then return the same 401.

**Verified evidence.**

- `backend/auth-service/src/main/java/com/example/learnhub/auth/application/RegisterAccountService.java:31`: Returns 409 with an explicit existing-account message. The comment at line 30 shows it is deliberate (FR-002).
- `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java:19`: The Javadoc claims indistinguishability (FR-007). The code at lines 55-60 makes body and status identical but not timing.
- `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java:50`: The login throttle (5 failures per 60 s, per email and per source IP) counts unknown and known emails the same way. This limits probing volume but is not a timing control. The source IP is the gateway address.

**Exact blockers (why it is unresolved).**

- Register-time enumeration is an explicit product requirement (FR-002). The owner must decide whether it is accepted risk. It is not a code defect on its own.
- The login timing delta (BCrypt cost factor in PasswordHasher against a DB lookup) is derived from source, not measured. Whether it is distinguishable after nginx, the gateway and network jitter, under the per-email and per-IP login throttle and the shared gateway rate-limit bucket, needs an execution observation. This run cannot execute code.
- The throttle is applied before the lookup and counts unknown emails too. After 5 failures per email or per IP the timing probe is blocked. The exploitable sample budget per email is therefore small, and this must be measured.

**Bounded local check.** In an isolated fixture with the real PasswordHasher cost factor, call AuthenticateService.login with a wrong password for one known and one unknown email. Use many samples, reset LoginAttemptWindow between runs, and compare the medians and the separation of the two distributions. Also confirm the 5-failure throttle caps probes per email.

**Owner-observed check (non-production only).** The owner decides whether the 409 on register is accepted risk. In a test environment, measure login latency for known and unknown emails through the full nginx and gateway path, limited to the throttle budget. If the gap is distinguishable, add a constant-work dummy BCrypt compare on the unknown-account path.

---

## 9. Login throttle map never evicts keys; attacker-chosen email keys (up to the body-size cap) are retained until restart

- **Fingerprint:** `auth-service/LoginAttemptWindow-unbounded-failure-map`
- **Affected boundary:** In-memory throttle map growth versus heap
- **Why this priority:** Corrected by verification: growth is bounded to roughly 5 new keys per minute through the gateway, so this matters only for very large keys or direct callers. Lowest priority.

**Description.** LoginAttemptWindow keeps failure timestamps in a ConcurrentHashMap. The only removal is clear(email) after a successful login for that email. Expired timestamps are filtered on read, but the key and its list are never evicted. Every failed login with a distinct email adds a permanent entry keyed by the lowercased, unvalidated email string. The hunter's growth-rate claim is wrong. AuthenticateService.login checks isThrottled(sourceIp) before it records anything, and the source IP is the shared gateway address (getRemoteAddr). After 5 failures in 60 s every login gets 429 and no new key is recorded, so new keys arrive at about 5 per minute, not 100 per second. The gateway rate limit does not set the rate. Retention therefore only matters for very large keys, or for several distinct source addresses reaching auth-service directly on its published port. A 1 MB email key (nginx default body cap; the source does not set client_max_body_size) retained at about 5 per minute is roughly 300 MB per hour. Whether that is exhaustion depends on deployed heap and on whether the body cap is larger on other paths.

**Claimed root cause.** No TTL eviction, size cap, or key-length bound on the in-memory throttle map, and the email key is attacker-chosen. Growth is bounded only by the per-source-IP 5 failures per 60 s throttle and by upstream body-size limits.

**Source trace.**

1. *entrypoint* `backend/auth-service/src/main/java/com/example/learnhub/auth/api/LoginController.java:27` (LoginController.login): Unauthenticated POST /api/v1/auth/login. The email is a plain @RequestBody string; there is no @Valid and no length bound, and the source IP comes from getRemoteAddr().
2. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java:50` (AuthenticateService.login): The throttle check on email and source IP runs first. After 5 failures per key within 60 s the request gets 429 and nothing is recorded, which limits the insertion rate.
3. *propagation* `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java:58` (AuthenticateService.login): An unknown email or wrong password calls recordFailure with the lowercased email and with the source IP.
4. *sink* `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/LoginAttemptWindow.java:40` (LoginAttemptWindow.recordFailure): compute() inserts a new map entry per distinct key. Only clear(key) at line 49 removes entries, and it is called only after a successful login for that email.

**Verified evidence.**

- `backend/auth-service/src/main/java/com/example/learnhub/auth/infrastructure/LoginAttemptWindow.java:23`: Plain ConcurrentHashMap with no scheduled cleanup, size cap, or key-length limit. recentFailures (line 57) filters expired entries on read but never removes the key.
- `backend/auth-service/src/main/java/com/example/learnhub/auth/application/AuthenticateService.java:50`: isThrottled(sourceIp) precedes recording, so the shared gateway address key blocks further inserts after 5 failures per 60 s. This is the main rate bound.
- `frontend/nginx/default.conf.template:16`: The /api/ proxy sets no client_max_body_size, so nginx's default 1 MB body cap applies if that edge is used. Auth-service has no explicit body or JSON size setting in the reviewed source.

**Exact blockers (why it is unresolved).**

- Auth-service heap limit and container memory limit are deployment facts. docker-compose.yml shows no memory or JVM option in the reviewed grep, and the restart policy is not source-visible for this service.
- Effective request-body and email-length cap when bypassing nginx are not source-visible. Auth-service port 9001 is published per the architecture summary, and the Spring Boot/Jackson default string limit is believed to be about 20 MB (unverified here).
- Actual insertion rate behind the shared gateway key (about 5 per 60 s) versus direct callers with distinct source addresses needs observation. Race windows between the isThrottled check and recordFailure under concurrency are unverified.

**Bounded local check.** In an isolated fixture with an injected Clock, call recordFailure with N distinct keys, advance the clock past 60 s, and assert that the map size and retained key bytes do not shrink. Separately drive AuthenticateService with a stubbed repository and confirm that the per-IP throttle caps new-key insertions at about 5 per 60 s.

**Owner-observed check (non-production only).** Owner reads the auth-service heap and container memory limits and restart policy. Owner also checks the body-size limit on the gateway and on direct port 9001, then computes retained bytes per hour as key size times 5 per minute per source address. Do not send oversized bodies to a live service.

---
