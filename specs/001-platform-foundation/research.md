# Research: Backend Platform Foundation

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-17

All items from the plan's Technical Context are resolved; no `NEEDS CLARIFICATION` remains. Items marked
**Verified** were checked by running code or reading source on the target versions (Spring Boot 3.5.15,
Spring Cloud 2025.0.3, which resolves Spring Cloud Gateway 4.3.5 and Micrometer context-propagation 1.1.4).
Items marked **Unverified** could not be checked here (no Docker in this environment) and carry a
verification task for implementation or CI.

## Verification spike (R1 to R3)

A throwaway Maven project (outside the repo) used Boot 3.5.15, the Spring Cloud 2025.0.3 BOM,
`spring-cloud-starter-gateway-server-webflux` and `spring-cloud-starter-loadbalancer`. Configuration came
from an imported file (`spring.config.import=file:./spike-config/config.yml`, declared in
`application.yml`), standing in for the config server. The test edited that file and called
`ContextRefresher.refresh()` directly, with no actuator and no Spring Cloud Bus. The spike code is not kept;
the same checks become permanent tests (see Test mapping).

### R1. Does the gateway's CORS configuration and route table update on refresh?

- **Decision**: Yes. Use refreshable gateway properties for CORS origins and routes, no fallback needed.
- **Evidence (Verified)**: before the edit, preflight from `http://a.example` returned 200 and from
  `http://b.example` 403; route `one` returned 200 and route `two` 404; greeting was `hello`. After editing
  the file and calling `refresh()` (changed keys reported: `...globalcors.cors-configurations[/**].allowed-origins[0]`,
  `...routes[1].id/uri/predicates[0]`, `spike.greeting`), `a.example` returned 403, `b.example` 200, route
  `two` 200, greeting `changed`. Property prefix on this version is `spring.cloud.gateway.server.webflux.*`.
- **Rationale**: Gateway properties are `@ConfigurationProperties` rebound on `EnvironmentChangeEvent`, and
  the gateway re-reads routes after a refresh.
- **Alternatives considered**: custom `CorsConfigurationSource` bean reading its own properties; rejected as
  unnecessary given the evidence. Kept as the documented fallback if a future Gateway upgrade regresses
  (the permanent refresh test would catch it).
- **Re-verified in repo on 2026-10-17** by the permanent guard test
  `backend/api-gateway/src/test/java/com/example/learnhub/gateway/refresh/ConfigRefreshIT.java`, which closes
  the gap the spike left: it asserts origin change (`a` 200 to 403, `b` 403 to 200), route **addition**
  (404 to 200), route **removal** (200 to 404) and property rebind in one refresh. No fallback is needed.
  If a later Spring Cloud upgrade regresses this, that test fails and the documented fallback applies.
- **Side finding (Verified)**: with `spring.config.import` supplied only as a test property, `refresh()`
  reported no changes. It worked when the import was in `application.yml`. Tests that exercise refresh must
  declare the import in a config file, as the real services do.

### R2. Does a CORS preflight avoid the gateway's global filters (no 401 on preflight)?

- **Decision**: Rely on the gateway's built-in CORS handling; no special-case code in the route-access filter.
  Keep a test asserting it.
- **Evidence (Verified)**: with a global filter at high precedence that returns 401 for every `/protected/**`
  request, `GET /protected/x` returned 401 while `OPTIONS /protected/x` with an allowed origin returned 200
  and with a disallowed origin 403. A preflight to a path that matches no route returned 403.
- **Rationale**: the gateway's handler mapping answers preflight before the filter chain runs, so route access
  and rate limiting never see it.
- **Alternatives considered**: explicit `OPTIONS` bypass in each filter (redundant, and would let a rate-limit
  bypass hide behind it).
- **Consequence**: preflight requests are not rate-limited (they never reach the limiter). Accepted: they carry
  no payload and cannot reach a service.

### R3. Default error statuses for upstream failures

- **Decision**: Implement a custom WebFlux error handler that maps connection failures to 503 and timeouts to 504.
- **Evidence (Verified)**, defaults before any custom handler: connection refused returned **500**; response
  timeout (route metadata `response-timeout=300` against a 2 s upstream) returned **504**; `lb://` service with
  no instances returned **503**; unmatched path returned **404**.
- **Rationale**: only connection refused needs remapping. This confirms the plan's requirement that the
  gateway maps connection-refused to 503 for the gap before eviction.
- **Alternatives considered**: retry filter for refused connections (hides the failure for up to the retry
  budget, risks exceeding the 5 s limit); rejected.
- **Handler mapping**: `ConnectException` and its Netty subclasses to 503; Netty connect and read timeouts and
  `TimeoutException` to 504; no instances (`NotFoundException`/503 from the load balancer) to 503; 404, 401
  and 429 emitted by gateway code; anything else to 500, all as `application/problem+json`.

## Decisions

### R4. Live config pickup by polling (no Bus)

- **Decision**: `platform-common` runs a watcher: every 5 s (`fixedDelay`, single thread) it GETs
  `${CONFIG_SERVER_URL}/{application}/{profiles}` with basic-auth credentials, hashes the canonical form of
  the returned property sources (names plus sorted key/value pairs; the `version` and `state` fields are
  ignored), and calls `ContextRefresher.refresh()` when the hash differs from the last good one.
- **Rationale**: bind-mount file events are unreliable on Docker Desktop for Windows and macOS, so push-style
  notification (Bus, filesystem watchers) cannot meet the 10 s target; polling at 5 s plus a refresh of well
  under a second keeps edits under 10 s. R1 shows `refresh()` is sufficient for the gateway and for bound
  properties.
- **Behavior details**:
  - Uses `java.net.http.HttpClient` and Jackson, which are present in servlet and reactive apps, so the
    watcher is reactive-safe and works in the gateway.
  - On config-server outage or any exception, keep the last good config, log a warning, retry next tick.
  - `fixedDelay` (not `fixedRate`) so a slow tick cannot overlap the next. Note that `refresh()` itself
    re-fetches config and, with `fail-fast` and retry enabled, can block for several seconds if the config
    server is down between the poll and the refresh; the single-thread scheduler contains this.
  - The first poll after startup records the hash without refreshing.
  - Disabled with `platform.config-watcher.enabled=false` for services that are not config clients
    (discovery-server and config-server).
- **Alternatives considered**: Spring Cloud Bus on RabbitMQ (explicitly excluded; adds a broker dependency to
  config delivery); config-server filesystem watch plus `/monitor` (unreliable on bind mounts); manual
  `/actuator/refresh` only (violates "no manual trigger"). The actuator refresh endpoint stays exposed under
  `local` for manual use.
- **Stale-bucket rule**: the rate limiter clears its buckets on `EnvironmentChangeEvent` so a changed limit
  applies immediately rather than after idle eviction.

### R5. Eureka timing and what "within 30 seconds" really covers

- **Decision**: apply the specified tuning (eviction 5 s, self-preservation off, registry fetch 5 s, renewal
  10 s, expiry 30 s, client health check on) and document the real crash-detection window.
- **Evidence (Verified)**: eureka-core 2.0.5 `Lease.java` sets `lastUpdateTimestamp = now + duration` on each
  renewal and treats a lease as expired when `now > lastUpdateTimestamp + duration`. Its own comment says the
  effective expiry is therefore `2 * duration` (a known minor bug). The version actually resolved by the Spring
  Cloud 2025.0.3 BOM must be confirmed when the module is created; the logic is assumed unchanged.
- **Consequence**: a **graceful stop** deregisters immediately, so the dashboard and routing drop the instance
  well inside 30 s (SC-002 holds). A **crash** is evicted after about 60 s of lease expiry plus up to 5 s for
  the next eviction run (about 65 s), and the gateway's cached registry adds up to 5 s more (about 70 s in
  the worst case). During that gap, calls to a dead instance get connection-refused, mapped to 503 (R3).
- **Resolved 2026-10-17**: the spec owner split the two cases rather than retuning the lease. FR-011, SC-002
  and the US2 acceptance scenario now read 30 seconds for a graceful stop and 90 seconds for a crash. The
  lease expiry stays at the specified 30 s, so the effective crash window is about 65 to 70 s, inside the
  90 s bound with margin for a slow machine.
- **Outage tolerance**: clients keep their cached registry during a Eureka outage and re-register on their own
  when it returns; the spring-cloud-netflix client does this by default. A permanent test stops and starts
  the registry and asserts routing continues and instances reappear.
- **Instance identity**: `eureka.instance.prefer-ip-address=true` and
  `instance-id=${spring.application.name}:${HOSTNAME:${random.uuid}}`, so two echo copies are distinct and
  reachable inside Docker networks.
- **Alternatives considered**: leave self-preservation on (rejected: it would stop eviction during a mass
  outage and blur the "disappear" test); tune lower than specified (not our call).

### R6. Native config backend, mounted from the host

- **Decision**: config-server runs the `native` profile with
  `spring.cloud.config.server.native.search-locations=file:/config-repo`; Compose mounts `backend/config-repo`
  read-only at `/config-repo` from the host working tree.
- **Rationale**: the native backend reads files on each request, so edits are visible with no commit
  (clarification Q2, FR-016). Read-only mount means a service cannot modify settings.
- **Layering**: clients receive `application.yml` (shared), `application-local.yml` (profile), `<app>.yml`
  (service) with service-specific and profile values winning over shared defaults (spec scenario US3-1).
- **Alternatives considered**: git backend (needs a commit, rejected by Q2); baking config into the image
  (needs a rebuild, rejected by FR-016).

### R7. Fail-fast, retry and credentials for config clients

- **Decision**: gateway and echo declare a small local `application.yml` with `spring.application.name`,
  `spring.config.import=configserver:${CONFIG_SERVER_URL}`, config-server credentials, and
  `spring.cloud.config.fail-fast=true` with `spring-retry` (needs `spring-boot-starter-aop`) and bounded
  retry settings. The import is non-optional (no `optional:` prefix).
- **Rationale**: matches Principle III. This file is configuration, not code; "a new service needs no extra
  code" (FR-029) is met because registration, health, structured logging and correlation handling come
  from the shared defaults and `platform-common`.
- **Alternatives considered**: an `EnvironmentPostProcessor` in `platform-common` injecting the import
  automatically so a new service needs only its name. Rejected for now: it must run before Spring Boot's
  config-data loading, is harder to debug, and saves six lines. Revisit if many services repeat the file.

### R8. Gateway design

- **Routes**: `backend/config-repo/api-gateway.yml` defines routes as `/api/v1/<short-name>/**` with
  `lb://<registered-service-name>` URIs. The path segment is the short name (`echo`), the registered name is
  `echo-service`; the path is forwarded unchanged because services own `/api/v1` paths (Principle IX).
- **Public routes**: route metadata `public: true`, parsed as Boolean or String (`"true"`). Anything else is
  protected and returns 401 problem details for every request until the auth spec.
- **Two routes per service when its paths are mixed (added 2026-10-17)**: echo's public `/ping` and protected
  `/secure/ping` share the `/api/v1/echo/` prefix, so one route cannot carry both access levels. The gateway
  declares a narrow public route first (`echo-public`, `Path=/api/v1/echo/ping`, `order: 0`) and a catch-all
  protected route second (`echo`, `Path=/api/v1/echo/**`, `order: 1`). Protected-by-default still holds: the
  catch-all is protected, so a new echo path is protected until someone lists it explicitly.
- **Filter order**: correlation ID, then route access, then rate limiter. Unauthorized requests are not
  rate-limited ahead of the 401 decision, and every error response already carries a correlation ID.
- **Correlation ID**: header `X-Correlation-Id`; a client value is reused only if it matches
  `^[A-Za-z0-9._-]{8,64}$`, otherwise a UUID is generated. Forwarded to the service and returned in the
  response. The regex and generator live in `platform-common` (`CorrelationIds`) so the gateway and servlet
  services agree.
- **Rate limiter**: in-memory token bucket per client remote address (clarification Q1), `X-Forwarded-For`
  ignored, idle-bucket eviction, no Redis, limits from refreshable properties. Behind Docker Desktop,
  requests from the host share one bridge address, so the 429 test uses an injectable address resolver
  instead of real source addresses. Single gateway instance only (high availability is out of scope).
- **Default limit values (decided 2026-10-17)**: capacity 200 tokens, refill 100 per second. Chosen so the
  two smoke checks that share one source address do not collide: the 100-call load-spread check (SC-006) and
  the 100-call problem-details sweep stay under the capacity, while the 429 check deliberately exceeds it by
  firing a concurrent burst of 400 requests. A limit low enough for a sequential `curl` loop to trip would
  break the spread check, so the 429 smoke check must use concurrency, not a serial loop.
- **Error handler**: custom `ErrorWebExceptionHandler` returning `application/problem+json` with `type`,
  `title`, `status`, `detail`, `instance` and `correlationId` for 401, 404, 429, 503, 504 (and 500 as
  fallback). Schema in [contracts/problem-details.schema.json](contracts/problem-details.schema.json),
  copied to `backend/platform-common/src/main/resources/schemas/` so every error test validates against the
  published schema instead of a hand-listed field set that could drift from it (the arrangement already used
  for the event envelope).
- **Timeouts**: connect 2 s, response 5 s as defaults in `api-gateway.yml`; the 5 s response limit also meets
  SC-005 for routes with no healthy copy because `lb://` with no instances fails immediately.
- **CORS**: global CORS from refreshable `globalcors` properties (R1); preflight handled before filters (R2).

### R9. Correlation ID in logs (reactive and servlet)

- **Servlet services** (echo, discovery, config): a filter in `platform-common` sets MDC `correlationId`
  and clears it afterwards.
- **Gateway**: Reactor context propagation. Enable with `spring.reactor.context-propagation=auto` (Boot
  3.2+) so the automatic hook is installed, and register an MDC accessor for the key:
  `new Slf4jThreadLocalAccessor("correlationId")`.
- **Evidence (Verified)**: `io.micrometer.context.integration.Slf4jThreadLocalAccessor` exists in
  context-propagation 1.1.4 with a constructor taking MDC keys. No `ThreadLocalAccessor` service file was
  found in the jar, so the accessor is **not** registered automatically and must be registered explicitly
  (`ContextRegistry.getInstance().registerThreadLocalAccessor(...)`). A test must assert a gateway log line
  carries the ID (spec requires it be verified by a test).
- **Logging format**: Boot structured logging, `logging.structured.format.console=ecs`, MDC entries included.
- **Alternatives considered**: Micrometer Tracing (excluded: tracing tools are out of scope); manually passing
  the ID through exchange attributes into log calls (error-prone, breaks the "no extra code" goal).

### R10. `platform-common` layout and conditional loading

- **Decision**: two auto-configurations registered in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
  1. `ConfigWatcherAutoConfiguration` - reactive-safe (JDK HTTP client, Jackson, `ContextRefresher`), no
     servlet types.
  2. `ServletSupportAutoConfiguration` - `@ConditionalOnWebApplication(type = SERVLET)` plus
     `@ConditionalOnClass` guards: correlation filter, RFC 7807 advice, and (separately conditional on
     `feign.RequestInterceptor`) the Feign interceptor. Servlet, Spring MVC and Feign dependencies are
     `optional` in the POM.
- **Rationale**: the gateway (WebFlux) depends on `platform-common` without pulling servlet classes, avoiding
  `NoClassDefFound` and the Boot web-application-type ambiguity. A context-loading test in the gateway module
  guards this.
- **Infrastructure servers**: discovery-server and config-server depend on `platform-common` for the servlet
  correlation filter only (watcher disabled), so every Spring service logs a correlation ID.

### R11. Echo service rules and enforcement

- **Decision**: exactly two endpoints, `GET /api/v1/echo/ping` (public: returns `instanceId`, `port`,
  `correlationId`, `greeting`) and `GET /api/v1/echo/secure/ping` (protected at the gateway). The greeting is
  bound through `@ConfigurationProperties`, so the watcher's refresh updates it without echo-specific refresh
  code. The enforcement check is a plain JUnit test that scans echo's sources and POM for registration,
  config-client wiring and health code (`@EnableDiscoveryClient`, `EurekaClient`, `@RefreshScope`,
  `HealthIndicator`, `/health` mappings); no ArchUnit (no new library).
- **Reachability**: `/secure/ping` always returns 401 through the gateway. Called directly it returns the same
  body as `/ping`, because authentication does not exist yet; the OpenAPI contract states this.
- **Second copy**: `echo-service-b` in Compose profile `scale`, same image, no host port, distinct hostname so
  instance IDs differ (needed for SC-006: 100 calls, at least 40% to each).

### R12. Compose design, health checks and startup order

- **Images**: runtime stage `eclipse-temurin:21-jre-alpine` (busybox `wget` for health checks, no extra
  package), build stage a Maven + JDK 21 image, non-root user. Third-party images are `postgres:16`,
  `rabbitmq:4.3-management` and `axllent/mailpit`.
- **Image existence**: `postgres:16` and the Temurin and Maven images are long-standing tags.
  `rabbitmq:4.3-management` was **Verified on 2026-10-17**: `EnvelopeBrokerRoundTripIT` pulled and ran it
  through Testcontainers successfully, so no exact `4.3.x` pin is needed. CI still pulls it explicitly so a
  tag that disappears upstream fails loudly rather than mid-test.
- **Health checks**: Spring services use `/actuator/health/readiness` with `start_period`; PostgreSQL
  `pg_isready`; RabbitMQ `rabbitmq-diagnostics ping`; Mailpit `/mailpit readyz`. On Eureka and the config
  server `/actuator/health/**` stays reachable without a login in every profile so these checks keep working
  outside `local`; constitution v1.1.0 Principle VII allows exactly this exemption and nothing wider.
- **Order** (`depends_on` with `condition: service_healthy`): discovery-server, then config-server, then
  api-gateway and echo-service (and echo-service-b). `db-init` depends on PostgreSQL being healthy and runs
  on every start.
- **Data**: named volumes for PostgreSQL and RabbitMQ; `docker compose down` keeps them, `down -v` wipes them.
- **`--wait` and the one-shot db-init (Verified 2026-10-17)**: community reports said `docker compose up
  --wait` can return exit code 1 when a one-shot container exits very quickly, even with exit code 0
  ([forum report](https://forums.docker.com/t/behavior-of-up-wait-changed/147699),
  [write-up](https://zenn.dev/takashiaihara/articles/979cb208ca4994)); the official docs do not address it.
  **Measured and not reproducible on the target setup.** The spike in
  [spikes/compose-wait/docker-compose.yml](spikes/compose-wait/docker-compose.yml) runs a long-running
  healthchecked service that `depends_on` a one-shot with `condition: service_completed_successfully`, in two
  variants: the one-shot exits instantly, and the one-shot sleeps 2 s first. Command:
  `docker compose --profile <instant|delayed> up --wait --wait-timeout 60`. On **Docker Compose 2.40.3**
  (Docker 29.1.3, WSL2 Ubuntu) **both variants exited 0** and the long-running service reached `Healthy`.
  **Decision**: `up.sh` uses plain `docker compose up --build --wait --wait-timeout 180` with no
  treat-failure-as-success fallback; a non-zero exit is a real failure and `up.sh` prints
  `docker compose ps -a` as a diagnostic before exiting non-zero. CI logs its Compose version, so a
  regression on an older/newer version shows up as a genuine CI failure rather than being masked.
- **Cold build time against SC-001 (resolved 2026-10-17)**: the spec owner split the budget. SC-001 now reads
  under 3 minutes with the images already built (the warm start) and under 10 minutes on a clean checkout that
  must build them. Mitigations still apply: BuildKit cache mount for `~/.m2` shared across image builds,
  builds run in parallel by Compose, one reactor build per image via `mvn -pl <MODULE> -am`. CI records both
  times (T141).
- **Windows**: `up.sh` and `smoke-test.sh` need bash (Git Bash or WSL); README says so. Port probing uses
  bash `/dev/tcp`; ports owned by this project's running containers are read from `docker compose ps` and
  skipped, so a second run is harmless.

### R13. Database provisioning script

- **Decision**: `backend/docker/db-init/provision.sh` runs in a one-shot `db-init` service on every start. For
  each of auth, course, enrollment, payment, certificate, review, notification it creates the role and the
  database only if missing (`SELECT ... WHERE NOT EXISTS`, `CREATE DATABASE` outside a transaction), and
  re-applies the password from the environment (`ALTER ROLE ... PASSWORD`). Role and database names equal the
  service name. No tables, no Flyway migrations yet (no schema exists; Principle IX applies from the first
  business service).
- **Test**: a Testcontainers PostgreSQL test runs the script twice and asserts both runs succeed and that the
  seven roles and databases exist exactly once.
- **Alternatives considered**: `/docker-entrypoint-initdb.d` scripts (run only on first volume creation, so
  password changes and later additions would not apply); rejected.

### R14. Environment settings and preflight

- **Decision**: required settings use `${VAR:?message}` in `docker-compose.yml` (the colon form also rejects
  empty values), and `up.sh` checks the same list first so it can report every missing setting by name within
  30 seconds before any container starts. `.env.example` lists every variable with placeholder values.
  Variables: PostgreSQL admin user and password, one password per service database, RabbitMQ user and
  password, Eureka and config-server basic-auth user and password, `SPRING_PROFILES_ACTIVE`.
- **`EUREKA_URL` and `CONFIG_SERVER_URL` are not `.env` variables (decided 2026-10-17)**: Compose builds both
  from internal container names and the credential variables (for example
  `http://${EUREKA_USER}:${EUREKA_PASSWORD}@discovery-server:9761/eureka/`), because the Eureka client takes
  its credentials inside the URL. Putting them in `.env` would duplicate the passwords in a second place and
  let the two drift. They stay out of `.env.example` for the same reason.
- **Port check**: every host port in the port table (9080, 9761, 9888, 9099, 9432, 9672, 9673, 9025, 9825)
  must be free; the message names the port and the component that needs it. 9001 to 9007 are reserved but
  not bound, since nothing runs there.
- **Rationale for two layers**: `up.sh` gives early, aggregated, readable errors; the compose-level `:?` is
  the safety net when someone runs `docker compose` directly.

### R15. Shared event envelope

- **Decision**: `shared-events` holds an immutable `EventEnvelope` record (`eventId` UUID, `type`, `version`
  integer starting at 1, `occurredAt` UTC instant, `correlationId`, `payload` JSON object), the exchange
  constant `learning.events`, and the routing-key naming rule (`<domain>.<event>`). `type` equals the routing
  key. Domain routing keys such as `payment.succeeded` are added by the specs that own those domains, not
  here, to keep the library free of domain knowledge. Schema:
  [contracts/event-envelope.schema.json](contracts/event-envelope.schema.json).
- **Tests**: Jackson serialization round-trip, schema conformance, and a Testcontainers RabbitMQ test that
  publishes an envelope to `learning.events` and reads it back. Queue and dead-letter layouts are out of
  scope; the test declares a throwaway queue.

### R16. CI

- **Decision**: GitHub Actions workflow with two jobs: (1) backend build and tests (`mvn verify` in
  `backend/`, Testcontainers uses the runner's Docker); (2) `scripts/up.sh` followed by **every** smoke mode,
  then `docker compose down -v`. The image pull and the cold and warm startup times are logged (R12). Shell
  scripts get `shellcheck`.
- **All smoke modes run in CI (added 2026-10-17)**: the default checks, `--lifecycle` and `--auth-profile`.
  Constitution Principle VI requires every acceptance scenario to pass in CI, and `--auth-profile` is the only
  check covering FR-018 (login required outside `local`), so omitting it would leave an acceptance scenario
  ungated.
- **Note**: `shellcheck` is a CI lint step, not a runtime dependency.

## Test mapping

| Requirement / criterion | Test | Level |
|-------------------------|------|-------|
| FR-016, SC-003 config edit within 10 s | edit `config-repo` file, poll echo `/ping` greeting | smoke + gateway/echo IT |
| FR-021, R1 CORS refresh | gateway test changes origins, refreshes, asserts preflight | gateway IT |
| R1 route refresh | add and remove a route via config change | gateway IT |
| R2 preflight never 401 | preflight on protected route | gateway IT (WireMock) |
| FR-020 correlation valid/malformed | valid reused, malformed replaced, returned and forwarded | gateway IT |
| R9 log carries ID | assert captured gateway log line contains `correlationId` | gateway IT |
| FR-024 429 per client address | injectable address resolver, two addresses | gateway unit |
| SC-005, R3 503/504 | refused port, timeout, no instances | gateway IT (WireMock) |
| SC-004 error structure | all of 401/404/429/503/504 validate against schema | gateway IT |
| SC-006 load spread | 100 calls, two echo copies, each at least 40% | smoke (needs `scale` profile) |
| FR-014 registry outage and recovery | stop and start discovery-server | smoke |
| crash case (R5) | kill echo copy, expect 503 then eviction | smoke |
| FR-007 idempotent provisioning | script run twice on Testcontainers PostgreSQL | integration |
| FR-027 envelope | serialization, schema, RabbitMQ round-trip | unit + integration |
| FR-003, SC-009 preflight | missing setting, occupied port | script test (bash) |
| FR-008 mail | send to Mailpit SMTP, read via web UI API | smoke |
| FR-029, SC-007 echo has no wiring code | source/POM scan | unit |
