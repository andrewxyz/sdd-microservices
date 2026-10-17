---

description: "Task list for the backend platform foundation (001-platform-foundation)"
---

# Tasks: Backend Platform Foundation

**Input**: Design documents from `/specs/001-platform-foundation/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Required. Constitution Principle VI (Test-First, NON-NEGOTIABLE) applies. Inside every group, test tasks come
before the implementation tasks they cover, and a "confirm red" task proves they fail first (a compile failure counts as
red). Two kinds of task are the exception: *guard tests* that check an existing framework behavior or rule are marked
"expected to PASS" in their description.

**Organization**: Setup, then Foundational (blocks every story), then one phase per user story in priority order
(US1, US2, US3, US4, US5), then Polish.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel with the other tasks of its group (different files, no unfinished dependency)
- **[Story]**: US1 to US5; omitted in Setup, Foundational and Polish
- Every task names its exact path. `backend/` module code lives under `src/main/java/com/example/learnhub/<service>/`
  and tests under `src/test/java/com/example/learnhub/<service>/` (base package per constitution Principle IX; services:
  `gateway`, `echo`, `discovery`, `configserver`; libraries: `events`, `common`; repo-level tests: `infra`).
- Maven: each module task also adds the module to `<modules>` in `backend/pom.xml`. Run commands as
  `mvn -f backend/pom.xml -pl <module> test` (unit) or `verify` (adds `*IT` integration tests; needs Docker).

## Design adjustments already folded into the spec, plan and research

`/speckit-analyze` ran after this list was first written, and every adjustment below is now **already recorded** in
`spec.md`, `plan.md`, `research.md` and `data-model.md` (constitution v1.1.0, 2026-10-17). They are kept here as a
reading aid; no task has to go back and sync them.

1. A test-only module `backend/infra-tests` holds the Testcontainers test for the provisioning script and the static
   checks on `docker-compose.yml`, `.env.example` and POM rules. It reads repo-root files, which plan deviation D5
   records.
2. Public and protected echo paths share the `/api/v1/echo/` prefix, so the gateway needs **two** routes: `echo-public`
   (`Path=/api/v1/echo/ping`, `metadata.public: true`, `order: 0`) and `echo` (`Path=/api/v1/echo/**`, protected,
   `order: 1`). See data-model.md "Route" and research R8.
3. `EUREKA_URL` and `CONFIG_SERVER_URL` are built by Compose from internal service names plus `EUREKA_USER`,
   `EUREKA_PASSWORD`, `CONFIG_USER` and `CONFIG_PASSWORD` (Eureka needs credentials inside the URL). `.env.example`
   lists only the credential variables, not the two URLs (research R14).
4. `/actuator/health/**` (probes only) is reachable without a login in every profile, so container health checks work
   outside `local`; every other Eureka and config-server endpoint still requires basic auth outside `local`
   (constitution v1.1.0 Principle VII).
5. `.gitattributes`, `scripts/` and `scripts/tests/` at the repo root are allowed by constitution v1.1.0 Principle I.
6. Acceptance scenario US2-3 (calls spread across two copies) can only be exercised through the gateway, so it is
   verified in US4 (T117). US2 verifies the two registrations.
7. Compose gains the gateway in US4 and `echo-service-b` in US2; US1's stack is registry, config server, echo skeleton,
   PostgreSQL, RabbitMQ, Mailpit and the one-shot `db-init`.

## Decisions that bound the tests (from the spec clarifications, 2026-10-17)

- **SC-001**: under 3 minutes with the images already built; under 10 minutes on a clean checkout that builds them.
- **SC-002 / FR-011**: 30 seconds for a graceful stop, 90 seconds for a crash.
- **FR-017**: a config client gives up after at most 30 seconds.
- **Rate limit**: capacity 200, refill 100 per second. The 429 check must fire a concurrent burst (400 requests), not a
  serial loop, or it would have to be set so low that the 100-call spread check would trip it (research R8).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: repo-level files and the early Docker Compose research check (plan research R12).

> **Constitution first**: `.specify/memory/constitution.md` was amended to **v1.1.0 on 2026-10-17, before any of
> these tasks run**, so the root files below (`scripts/`, `.gitignore`, `.dockerignore`, `.gitattributes`,
> `frontend/.gitkeep`) and WireMock as a test library are already permitted. Principle X forbids introducing a
> technology and amending afterwards, which is why the amendment is not a task here. Confirm the file reads
> `**Version**: 1.1.0` before starting; if it does not, stop and run `/speckit-constitution` first.

- [X] T001 [P] Create `.gitignore` at the repo root ignoring `.env`, `target/`, `*.iml`, `.idea/`, `.vscode/` and OS junk (must NOT ignore `.env.example`)
- [X] T002 [P] Create `.dockerignore` at the repo root excluding `.git`, `specs`, `frontend`, `.env`, `**/target`, `.github`
- [X] T003 [P] Create `.gitattributes` at the repo root setting `*.sh text eol=lf`, `backend/docker/** text eol=lf`, `Dockerfile text eol=lf`, `*.yml text eol=lf`
- [X] T004 [P] Create empty `frontend/.gitkeep` so the empty `frontend/` folder stays in git (constitution Principle I; nothing else goes in `frontend/`)
- [X] T005 [P] Research check R12 (does `docker compose up --wait` tolerate a one-shot container?): create `specs/001-platform-foundation/spikes/compose-wait/docker-compose.yml` with one long-running busybox service that has a healthcheck, one `oneshot` busybox service that exits 0 immediately, and a variant that sleeps 2 s then exits 0; make the long-running service `depends_on` the one-shot with `condition: service_completed_successfully`. Run `docker compose up --wait --wait-timeout 60` for each variant, then `docker compose down -v`, and keep the commands and exit codes
- [X] T006 Record the T005 result in `specs/001-platform-foundation/research.md` under R12: Compose version, exit code per variant, and the decision (if any variant returns non-zero, `scripts/up.sh` keeps the "inspect `docker compose ps -a`" fallback in T082; if all return 0, T082 uses plain `up --build --wait`); change the R12 "Unverified" marker to "Verified"

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: parent POM, an early gateway refresh check, `shared-events`, `platform-common` (reactive-safe watcher plus
servlet-only support), shared central config, `discovery-server`, `config-server` and the `echo-service` skeleton.

**⚠️ CRITICAL**: No user story starts until this phase is complete.

### Parent POM

- [X] T007 Create `backend/pom.xml`: `spring-boot-starter-parent` 3.5.15 as parent; properties `java.version` 21 and `spring-cloud.version` 2025.0.3; import `spring-cloud-dependencies` in `dependencyManagement`; manage `org.wiremock:wiremock-standalone` at one exact version resolved from Maven Central at the time this task runs (the newest stable 3.x; record the chosen version in the commit message, and never a range or `LATEST`) and the internal modules `shared-events` and `platform-common` (`${project.version}`) so child POMs never write a version; configure `maven-surefire-plugin` for `*Test` and `maven-failsafe-plugin` for `*IT`; leave `<modules>` empty (each module task adds its own). This is the ONLY file in the repo allowed to contain version numbers (Principle IX)

### Early verification of research R1 (CORS and routes update on refresh)

- [X] T008 Create the minimal `backend/api-gateway/pom.xml` (`spring-cloud-starter-gateway-server-webflux`, `spring-cloud-starter-loadbalancer`, `spring-boot-starter-actuator`; test scope `spring-boot-starter-test`, `reactor-test`, WireMock) with no `platform-common` dependency yet, and register `api-gateway` in `backend/pom.xml`
- [X] T009 Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/GatewayApplication.java` (plain `@SpringBootApplication`)
- [X] T010 Create guard test `backend/api-gateway/src/test/java/com/example/learnhub/gateway/refresh/ConfigRefreshIT.java` (expected to PASS: it verifies a framework assumption). Import config from a temp file declared in the test's `application.yml` (a test-property import is not re-read on refresh, see research R1), start with `globalcors` origin `http://a.example`, route `one` and a bound `@ConfigurationProperties` value; edit the file; call `ContextRefresher.refresh()`; assert origin `a` is refused and `b` allowed on preflight, a newly added route answers, a removed route answers 404, and the bound value changed. Property prefix is `spring.cloud.gateway.server.webflux.*`
- [X] T011 Run `mvn -f backend/pom.xml -pl api-gateway verify`. If T010 passes, add "re-verified in repo on <date>" to research R1 in `specs/001-platform-foundation/research.md`. If it fails, implement the documented fallback (a custom `CorsConfigurationSource` and route refresh listener reading refreshable properties) as a new task before US4

### shared-events (tests first)

- [X] T012 Create `backend/shared-events/pom.xml` (Jackson databind and jsr310 only; test scope JUnit, Testcontainers RabbitMQ, `com.rabbitmq:amqp-client`) and register it in `backend/pom.xml`
- [X] T013 [P] Write `backend/shared-events/src/test/java/com/example/learnhub/events/EventEnvelopeSerializationTest.java`: JSON round trip; serialized field set equals the schema's `properties` and `required` (`eventId`, `type`, `version`, `occurredAt`, `correlationId`, `payload`, no others); `eventId` is a UUID; `type` matches `^[a-z][a-z0-9]*\.[a-z][a-z0-9_]*$`; `version` is an integer "minimum 1"; `occurredAt` is UTC ISO-8601 ending in `Z`; `correlationId` matches `^[A-Za-z0-9._-]{8,64}$`; `payload` is an object; unknown fields rejected. Load the schema from classpath `schemas/event-envelope.schema.json`; also assert it equals `specs/001-platform-foundation/contracts/event-envelope.schema.json` when that file exists (use `Assumptions.assumeTrue`)
- [X] T014 [P] Write `backend/shared-events/src/test/java/com/example/learnhub/events/RoutingKeysTest.java`: `LearningEvents.EXCHANGE` equals `learning.events`; `RoutingKeys.isValid` accepts `payment.succeeded` and rejects `Payment.Succeeded`, `payment`, `payment.`, `.succeeded` and `payment.succeeded.now`
- [X] T015 [P] Write `backend/shared-events/src/test/java/com/example/learnhub/events/EnvelopeBrokerRoundTripIT.java`: Testcontainers RabbitMQ, declare topic exchange `learning.events` and a throwaway queue bound with `payment.*`, publish a serialized envelope with routing key equal to its `type`, consume it, assert equality
- [X] T016 Confirm red: run `mvn -f backend/pom.xml -pl shared-events verify`; T013 to T015 must fail (compile errors count)
- [X] T017 [P] Create `backend/shared-events/src/main/java/com/example/learnhub/events/EventEnvelope.java`: immutable record with the six fields, Jackson annotations, `occurredAt` as UTC `Instant`; no domain logic
- [X] T018 [P] Create `backend/shared-events/src/main/java/com/example/learnhub/events/LearningEvents.java` (`EXCHANGE = "learning.events"`) and `RoutingKeys.java` (`PATTERN` and `isValid`); define no domain routing keys (later specs add them)
- [X] T019 [P] Copy `specs/001-platform-foundation/contracts/event-envelope.schema.json` to `backend/shared-events/src/main/resources/schemas/event-envelope.schema.json`
- [X] T019a [P] Copy `specs/001-platform-foundation/contracts/problem-details.schema.json` to `backend/platform-common/src/main/resources/schemas/problem-details.schema.json`, so error tests validate against the published schema instead of restating its field list and drifting from it (the same arrangement T019 gives the envelope). Add a test in `backend/platform-common/src/test/java/com/example/learnhub/common/error/ProblemDetailsSchemaTest.java` asserting the copy is byte-identical to the contracts file when that file is present (`Assumptions.assumeTrue`)
- [X] T020 Run `mvn -f backend/pom.xml -pl shared-events verify`; T013 to T015 must now pass

### platform-common (tests first)

- [X] T021 Create `backend/platform-common/pom.xml`: dependencies `spring-boot-autoconfigure`, `spring-cloud-context`, Jackson, SLF4J; `<optional>true</optional>` on `spring-webmvc`, `jakarta.servlet-api` and `io.github.openfeign:feign-core`; test scope `spring-boot-starter-test`, `spring-boot-starter-web`, WireMock; register it in `backend/pom.xml`
- [X] T022 [P] Write `backend/platform-common/src/test/java/com/example/learnhub/common/correlation/CorrelationIdsTest.java`: `isValid` accepts 8 and 64 characters from `[A-Za-z0-9._-]`; rejects 7 characters, 65 characters, a space, `!`, a non-ASCII character and empty; `resolve(null|malformed)` returns a new UUID that itself matches `^[A-Za-z0-9._-]{8,64}$`; `resolve(valid)` returns the input unchanged; constants `HEADER = "X-Correlation-Id"`, `MDC_KEY = "correlationId"`
- [X] T023 [P] Write `backend/platform-common/src/test/java/com/example/learnhub/common/config/ConfigSourcesHasherTest.java`: same sources give the same hash; key order inside a source does not matter; a changed value changes the hash; the `version` and `state` fields of the config-server response are ignored
- [X] T024 [P] Write `backend/platform-common/src/test/java/com/example/learnhub/common/config/ConfigWatcherTest.java` with WireMock as config server and a fake refresher: polls `GET /{application}/{profiles}` with a basic-auth header; first poll records the hash and does NOT refresh; unchanged hash does not refresh; changed hash refreshes exactly once; a 500 response or connection failure keeps the last good hash, does not refresh and does not throw; the next successful poll after the outage with a changed body refreshes; ticks never overlap (`fixedDelay`); default interval is 5 seconds
- [X] T025 [P] Write `backend/platform-common/src/test/java/com/example/learnhub/common/config/ConfigWatcherAutoConfigurationTest.java` using `ApplicationContextRunner`: watcher bean exists when a `ContextRefresher` bean exists; absent with `platform.config-watcher.enabled=false`; context loads with `FilteredClassLoader` hiding `jakarta.servlet.Filter` and Spring MVC classes (proves the part is servlet-free and reactive-safe); also runs with `ReactiveWebApplicationContextRunner`
- [X] T026 [P] Write `backend/platform-common/src/test/java/com/example/learnhub/common/correlation/CorrelationIdFilterTest.java` (MockMvc): valid client ID reused; malformed ID replaced by a UUID; ID returned in the `X-Correlation-Id` response header; MDC key `correlationId` set during the request and cleared afterwards, including when the handler throws
- [X] T027 [P] Write `backend/platform-common/src/test/java/com/example/learnhub/common/error/ProblemDetailsAdviceTest.java` (MockMvc): an unhandled exception and a `ResponseStatusException` produce `application/problem+json` that validates against the schema loaded from classpath `schemas/problem-details.schema.json` (T019a) rather than a hand-listed field set, with `correlationId` equal to the response header and no stack trace or internals in `detail`
- [X] T028 [P] Write `backend/platform-common/src/test/java/com/example/learnhub/common/feign/CorrelationFeignInterceptorTest.java`: copies MDC `correlationId` to the outgoing `X-Correlation-Id` header; adds nothing when MDC is empty
- [X] T029 [P] Write `backend/platform-common/src/test/java/com/example/learnhub/common/autoconfigure/ServletSupportAutoConfigurationTest.java`: servlet runner registers the filter and advice; reactive runner registers none; `FilteredClassLoader(jakarta.servlet.Filter.class)` registers none and the context still loads; the Feign interceptor is registered only when `feign.RequestInterceptor` is on the classpath
- [X] T030 Confirm red: run `mvn -f backend/pom.xml -pl platform-common test`; T022 to T029 must fail
- [X] T031 [P] Create `backend/platform-common/src/main/java/com/example/learnhub/common/correlation/CorrelationIds.java`: `HEADER`, `MDC_KEY`, `isValid` using `^[A-Za-z0-9._-]{8,64}$`, `generate()` (UUID), `resolve(String)`; no servlet types
- [X] T032 [P] Create `backend/platform-common/src/main/java/com/example/learnhub/common/config/ConfigSourcesHasher.java` (canonical JSON of property-source names plus sorted key/value pairs, SHA-256, ignoring `version` and `state`)
- [X] T033 [P] Create `backend/platform-common/src/main/java/com/example/learnhub/common/config/ConfigWatcherProperties.java` (prefix `platform.config-watcher`: `enabled` default `true`, `interval` default `PT5S`, `uri` default `${CONFIG_SERVER_URL}`, `username` and `password` default to `spring.cloud.config.username` and `.password`)
- [X] T034 Create `backend/platform-common/src/main/java/com/example/learnhub/common/config/ConfigWatcher.java` using `java.net.http.HttpClient` and Jackson (no servlet or WebFlux types): single-thread scheduler with `fixedDelay`, keep last good hash on any failure and log a warning, first poll records only, call the refresher when the hash changes (depends on T032, T033)
- [X] T035 Create `backend/platform-common/src/main/java/com/example/learnhub/common/autoconfigure/ConfigWatcherAutoConfiguration.java`: reactive-safe, no servlet imports, `@ConditionalOnBean(ContextRefresher.class)` and `@ConditionalOnProperty(platform.config-watcher.enabled, matchIfMissing = true)`, wires `ContextRefresher::refresh` (depends on T034)
- [X] T036 [P] Create `backend/platform-common/src/main/java/com/example/learnhub/common/correlation/CorrelationIdFilter.java` (`OncePerRequestFilter`, sets and clears MDC, writes the response header; depends on T031)
- [X] T037 [P] Create `backend/platform-common/src/main/java/com/example/learnhub/common/error/ProblemDetailsAdvice.java` (`@RestControllerAdvice` extending `ResponseEntityExceptionHandler`, adds `correlationId`, RFC 7807)
- [X] T038 [P] Create `backend/platform-common/src/main/java/com/example/learnhub/common/feign/CorrelationFeignInterceptor.java` (`RequestInterceptor` forwarding the MDC ID)
- [X] T039 Create `backend/platform-common/src/main/java/com/example/learnhub/common/autoconfigure/ServletSupportAutoConfiguration.java` with `@ConditionalOnWebApplication(type = SERVLET)` and `@ConditionalOnClass` guards; the Feign interceptor sits in a nested configuration conditional on `feign.RequestInterceptor` (depends on T036 to T038)
- [X] T040 Create `backend/platform-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` listing both auto-configurations
- [X] T041 Run `mvn -f backend/pom.xml -pl platform-common test`; T022 to T029 must now pass

### Shared central configuration

- [X] T042 [P] Create `backend/config-repo/application.yml` (shared defaults for every config client): `eureka.client.service-url.defaultZone: ${EUREKA_URL}`, `registry-fetch-interval-seconds: 5`, `healthcheck.enabled: true`, `eureka.instance.lease-renewal-interval-in-seconds: 10`, `lease-expiration-duration-in-seconds: 30`, `prefer-ip-address: true`, `instance-id: ${spring.application.name}:${HOSTNAME:${random.uuid}}`; `management.endpoints.web.exposure.include: health,info`, `management.endpoint.health.probes.enabled: true`; `logging.structured.format.console: ecs`
- [X] T043 [P] Create `backend/config-repo/application-local.yml` exposing the `refresh` actuator endpoint only under the `local` profile (`management.endpoints.web.exposure.include: health,info,refresh`)

### discovery-server (tests first)

- [X] T044 Create `backend/discovery-server/pom.xml` (`spring-cloud-starter-netflix-eureka-server`, `spring-boot-starter-security`, `spring-boot-starter-actuator`, `platform-common`; test `spring-boot-starter-test`) and register it in `backend/pom.xml`
- [X] T045 [P] Write `backend/discovery-server/src/test/java/com/example/learnhub/discovery/DiscoveryServerSecurityTest.java`: profile `local` serves `/eureka/apps` and the dashboard without a login; a non-local profile (for example `dev`) returns 401 without credentials and 200 with `EUREKA_USER` and `EUREKA_PASSWORD`; `/actuator/health/liveness` and `/readiness` need no login in every profile
- [X] T046 [P] Write `backend/discovery-server/src/test/java/com/example/learnhub/discovery/DiscoveryServerConfigTest.java` asserting properties: `eureka.server.eviction-interval-timer-in-ms=5000`, `eureka.server.enable-self-preservation=false`, `eureka.client.register-with-eureka=false`, `eureka.client.fetch-registry=false`, `server.port=9761`, `platform.config-watcher.enabled=false`
- [X] T047 Confirm red: run `mvn -f backend/pom.xml -pl discovery-server test`; T045 and T046 must fail
- [X] T048 [P] Create `backend/discovery-server/src/main/java/com/example/learnhub/discovery/DiscoveryServerApplication.java` (`@EnableEurekaServer`)
- [X] T049 [P] Create `backend/discovery-server/src/main/java/com/example/learnhub/discovery/DiscoverySecurityConfig.java`: profile `local` permits all; other profiles require HTTP basic from `spring.security.user.*` (`${EUREKA_USER}`, `${EUREKA_PASSWORD}`) except `/actuator/health/**`; CSRF disabled for `/eureka/**`
- [X] T050 [P] Create `backend/discovery-server/src/main/resources/application.yml`: name `discovery-server`, port 9761, Eureka server tuning (5 s eviction, self-preservation off), no self-registration, local-only config (no config import), probes and ECS logging, watcher disabled
- [X] T051 Run `mvn -f backend/pom.xml -pl discovery-server test`; T045 and T046 must now pass

### config-server (tests first)

- [X] T052 Create `backend/config-server/pom.xml` (`spring-cloud-config-server`, `spring-cloud-starter-netflix-eureka-client`, `spring-boot-starter-security`, `spring-boot-starter-actuator`, `platform-common`; test `spring-boot-starter-test`) and register it in `backend/pom.xml`
- [X] T053 [P] Write `backend/config-server/src/test/java/com/example/learnhub/configserver/ConfigServerNativeIT.java`: a temp directory with `application.yml`, `application-local.yml` and `echo-service.yml` defining one shared key; `GET /echo-service/local` returns all three sources in the order `application-local.yml`, `echo-service.yml`, `application.yml`, so the profile value wins over the service value, which wins over the shared value (measured behavior, research R6); editing a file changes the next response with no restart and no commit (FR-016, clarification Q2)
- [X] T054 [P] Write `backend/config-server/src/test/java/com/example/learnhub/configserver/ConfigServerSecurityTest.java`: profile `local` needs no login; a non-local profile returns 401 without credentials and 200 with `CONFIG_USER` and `CONFIG_PASSWORD`; health probes need no login
- [X] T055 [P] Write `backend/config-server/src/test/java/com/example/learnhub/configserver/RealConfigRepoTest.java`: point the native backend at the real `backend/config-repo`; `GET /echo-service/local` returns the Eureka tuning from `application.yml` (`registry-fetch-interval-seconds` 5, `lease-renewal-interval-in-seconds` 10, `lease-expiration-duration-in-seconds` 30, `healthcheck.enabled` true) and the `refresh` exposure from `application-local.yml`
- [X] T056 Confirm red: run `mvn -f backend/pom.xml -pl config-server test`; T053 to T055 must fail
- [X] T057 [P] Create `backend/config-server/src/main/java/com/example/learnhub/configserver/ConfigServerApplication.java` (`@EnableConfigServer`)
- [X] T058 [P] Create `backend/config-server/src/main/java/com/example/learnhub/configserver/ConfigServerSecurityConfig.java` (same rules as T049 using `CONFIG_USER` and `CONFIG_PASSWORD`; the duplication is deliberate, no shared security code)
- [X] T059 [P] Create `backend/config-server/src/main/resources/application.yml`: name `config-server`, port 9888, `spring.profiles.include: native`, `spring.cloud.config.server.native.search-locations: file:${CONFIG_REPO_PATH:/config-repo}`, Eureka client settings from `${EUREKA_URL}` configured locally (not from central config), probes, ECS logging, watcher disabled
- [X] T060 Run `mvn -f backend/pom.xml -pl config-server test`; T053 to T055 must now pass

### echo-service skeleton

- [X] T061 Create `backend/echo-service/pom.xml` (`spring-boot-starter-web`, `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`, `spring-retry`, `spring-boot-starter-aop`, `spring-boot-starter-actuator`, `platform-common`; test `spring-boot-starter-test`, WireMock) and register it in `backend/pom.xml`
- [X] T062 [P] Create `backend/echo-service/src/main/java/com/example/learnhub/echo/EchoApplication.java` (plain `@SpringBootApplication`; no registration, config or health code)
- [X] T063 [P] Create `backend/echo-service/src/main/resources/application.yml` (configuration only): `spring.application.name: echo-service`, `server.port: 9099`, `spring.config.import: configserver:${CONFIG_SERVER_URL}` (non-optional, no `optional:` prefix), `spring.cloud.config.username/password` from `${CONFIG_USER}` and `${CONFIG_PASSWORD}`, `spring.cloud.config.fail-fast: true` and bounded retry
- [X] T064 Verify the design documents still match what was built in this phase: re-read the "Design adjustments" list at the top of this file against `specs/001-platform-foundation/plan.md`, `data-model.md` and `research.md` (all four were updated on 2026-10-17, so expect no change) and record any new drift found while implementing; update whichever file is wrong

**Checkpoint**: `mvn -f backend/pom.xml verify` passes for every module present (Docker required for the `*IT` tests). User stories can now begin.

---

## Phase 3: User Story 1 - Start the whole platform with one command (Priority: P1) 🎯 MVP

**Goal**: one command from the repo root brings up the registry, config server, echo skeleton, PostgreSQL (seven empty
databases), RabbitMQ and Mailpit in order and healthy; clear failures for runtime, settings and ports; restart keeps
data; reset wipes it.

**Independent Test**: on a clean checkout run `scripts/up.sh`; every component reports healthy within 3 minutes; stop and
restart; run `docker compose down -v`. Covers FR-001 to FR-009, SC-001, SC-008 to SC-010.

### Tests for User Story 1 (write first, confirm red) ⚠️

- [X] T065 [US1] Create `backend/infra-tests/pom.xml` (test-only module; dependencies Testcontainers PostgreSQL, `spring-boot-starter-test`, SnakeYAML) and register it in `backend/pom.xml`
- [X] T066 [P] [US1] Write `backend/infra-tests/src/test/java/com/example/learnhub/infra/ProvisionScriptIT.java`: Testcontainers `postgres:16`; copy `backend/docker/db-init/provision.sh` into the container and run it twice via `execInContainer`; both runs exit 0; exactly one role and one database for each of `auth`, `course`, `enrollment`, `payment`, `certificate`, `review`, `notification` (names equal the service name); each role logs in with its password; changing a password variable and rerunning re-applies it (new works, old fails); a role cannot connect to another service's database; no tables created
- [X] T067 [P] [US1] Write `backend/infra-tests/src/test/java/com/example/learnhub/infra/ComposeFileTest.java` (SnakeYAML over `docker-compose.yml`): every service has a `healthcheck` (except one-shot `db-init`); `postgres` and `rabbitmq` data use named volumes; host ports match the constitution table (config 9888, Eureka 9761, echo 9099, PostgreSQL `9432:5432`, RabbitMQ `9672:5672` and `9673:15672`, Mailpit `9025:1025` and `9825:8025`); `depends_on` orders discovery-server, then config-server, then echo-service with `condition: service_healthy`; `db-init` waits for a healthy PostgreSQL; `backend/config-repo` is mounted read-only into config-server; every required variable uses `${VAR:?message}`; the Mailpit service declares no relay or SMTP-forwarding setting (FR-008)
- [X] T068 [P] [US1] Write `backend/infra-tests/src/test/java/com/example/learnhub/infra/EnvExampleTest.java`: every `${VAR}` used in `docker-compose.yml` and in the required-settings list of `scripts/up.sh` appears in `.env.example`; every value is a placeholder (starts with `change-me`) or a non-secret default; `.gitignore` ignores `.env`; no `.env` is tracked
- [X] T069 [P] [US1] Write `scripts/tests/up-preflight.test.sh` (uses the fake `docker` and the listener, no real containers): runtime down gives a message naming the container runtime and exits before any `docker compose up`; a missing or empty setting gives a message naming every missing variable; an occupied port gives a message naming the port and its component (for example `9080 api-gateway`); ports held by this project's own running containers are skipped so a second start proceeds; every failure finishes in under 30 seconds and starts no container
- [X] T070 [P] [US1] Create `scripts/tests/fakebin/docker`: a shim controlled by environment variables (`FAKE_DOCKER_DOWN`, `FAKE_OWN_PORTS`) answering `docker info`, `docker compose version`, `docker compose ps` and recording any `docker compose up` call
- [X] T071 [P] [US1] Create `scripts/tests/Listen.java`: a JDK 21 single-file program (`java scripts/tests/Listen.java <port>`) that holds a TCP port until killed
- [X] T072 [P] [US1] Create `scripts/smoke-test.sh` with the US1 checks, each printing PASS or FAIL and exiting non-zero on any FAIL: every long-running service healthy and `db-init` exited 0; seven databases exist (via `docker compose exec postgres psql`); RabbitMQ management answers on 9673; a message sent to Mailpit SMTP on 9025 shows up in the web API on 9825 (FR-008); Mailpit delivers nowhere else, asserted by checking its container environment and `/api/v1/info` for any relay or forwarding configuration and failing if one is set; running `scripts/up.sh` a second time while the stack is already up exits 0, starts no duplicate container and leaves every service healthy (FR-004, the end-to-end counterpart of the preflight port-skip check in T069); with `--lifecycle`, a marker written to PostgreSQL survives `docker compose down` and `up`, and is gone after `docker compose down -v` and `up`
- [X] T073 [US1] Confirm red: run `mvn -f backend/pom.xml -pl infra-tests test` and `bash scripts/tests/up-preflight.test.sh`; T066 to T069 must fail

### Implementation for User Story 1

- [X] T074 [P] [US1] Create `backend/docker/db-init/provision.sh` (POSIX sh, `set -eu`, uses `psql`): for each of the seven services create the role only if missing, create the database only if missing (outside a transaction), `ALTER ROLE ... PASSWORD` from `<SERVICE>_DB_PASSWORD` on every run, `REVOKE CONNECT ... FROM PUBLIC` and grant connect only to the owner; safe to run any number of times; no tables
- [X] T075 [P] [US1] Create `backend/docker/Dockerfile`: build stage `maven:3.9-eclipse-temurin-21` with `ARG MODULE`, BuildKit cache mount for `/root/.m2`, `mvn -B -q -pl ${MODULE} -am -DskipTests package` with build context `backend/`; runtime stage `eclipse-temurin:21-jre-alpine`, non-root user, copies the module jar, `ENTRYPOINT ["java","-jar","/app/app.jar"]`
- [X] T076 [P] [US1] Create `.env.example` at the repo root listing every setting from the data model with placeholder values and no real secret: `SPRING_PROFILES_ACTIVE=local`, `EUREKA_USER`, `EUREKA_PASSWORD`, `CONFIG_USER`, `CONFIG_PASSWORD`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, one `<SERVICE>_DB_PASSWORD` for each of the seven services, `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`
- [X] T077 [US1] Create `docker-compose.yml` infrastructure part: `postgres:16` (`9432:5432`, named volume, `pg_isready` health check), `rabbitmq:4.3-management` (`9672:5672`, `9673:15672`, named volume, `rabbitmq-diagnostics ping`), Mailpit (`9025:1025`, `9825:8025`, `/mailpit readyz`, with no relay or SMTP-forwarding settings so mail never leaves the machine), and one-shot `db-init` (`postgres:16`, runs `backend/docker/db-init/provision.sh` on every start after PostgreSQL is healthy); all secrets as `${VAR:?message}`
- [X] T078 [US1] Extend `docker-compose.yml` with `discovery-server` (port 9761, build `backend/docker/Dockerfile` with `MODULE=discovery-server`), `config-server` (9888, mounts `./backend/config-repo:/config-repo:ro`, depends on a healthy discovery-server), and `echo-service` (9099, depends on healthy config-server and discovery-server); each with a `wget` readiness health check on `/actuator/health/readiness` and `start_period`; Compose builds `EUREKA_URL` (`http://${EUREKA_USER}:${EUREKA_PASSWORD}@discovery-server:9761/eureka/`) and `CONFIG_SERVER_URL` (`http://config-server:9888`) from internal names
- [X] T079 [US1] Create `scripts/up.sh` (bash, `set -euo pipefail`), part 1: resolve the repo root, load `.env` if present, and check the container runtime is running (`docker info` and Compose v2), exiting with a message that names the runtime before starting anything
- [X] T080 [US1] Extend `scripts/up.sh`, part 2: required-settings check over the full list of required variables; report every missing or empty one by name in a single message; exit before starting any container
- [X] T081 [US1] Extend `scripts/up.sh`, part 3: ports check for every host port in the table (9080 api-gateway, 9761 discovery-server, 9888 config-server, 9099 echo-service, 9432 postgres, 9672 rabbitmq, 9673 rabbitmq-management, 9025 mailpit-smtp, 9825 mailpit-ui) using bash `/dev/tcp`; skip ports published by this project's own running containers (read from `docker compose ps`) so a second start is harmless; name the port and component on failure; whole preflight finishes in under 30 seconds
- [X] T082 [US1] Extend `scripts/up.sh`, part 4: run `docker compose up --build --wait --wait-timeout 180`; apply the T006 decision (if `--wait` returned non-zero for one-shot containers, inspect `docker compose ps -a` and treat the run as successful when every long-running service is healthy and `db-init` exited 0, otherwise print the failing services and exit non-zero)
- [X] T083 [P] [US1] Create `.github/workflows/ci.yml` with two jobs: (1) backend: JDK 21, `mvn -f backend/pom.xml verify` (Testcontainers uses the runner's Docker); (2) stack: copy `.env.example` to `.env`, `scripts/up.sh`, then **every** smoke mode so no acceptance scenario is ungated (constitution Principle VI) — `scripts/smoke-test.sh` (default checks), `scripts/smoke-test.sh --lifecycle`, and `scripts/smoke-test.sh --auth-profile` (the only check covering FR-018) — always finishing with `docker compose down -v`; plus `shellcheck` on `scripts/**/*.sh` and `backend/docker/db-init/provision.sh`, a `docker pull rabbitmq:4.3-management` step (research R12), and logged timing of the cold (image-building) and warm `up.sh` runs against the two SC-001 budgets. Modes added by later stories (T098 to T100, T116 to T118) extend these same three invocations, so revisit this file at T132
- [X] T084 [US1] Create `README.md` at the repo root (first sections): what the repo is; prerequisites (container runtime, bash via Git Bash or WSL on Windows, `cp .env.example .env`); start with `scripts/up.sh`; stop with `docker compose down`; restart by running `scripts/up.sh` again; reset with `docker compose down -v` (wipes all stored data); the constitution port table
- [X] T085 [US1] Run `mvn -f backend/pom.xml -pl infra-tests verify`, `bash scripts/tests/up-preflight.test.sh`, then `scripts/up.sh` and `scripts/smoke-test.sh --lifecycle`; all must pass

**Checkpoint**: User Story 1 works on its own: the foundation starts with one command.

---

## Phase 4: User Story 2 - Services find each other by name (Priority: P1)

**Goal**: services register by name and show up and disappear within the stated times; two echo copies register with
distinct IDs; a short registry outage does not stop running services.

**Independent Test**: start the stack, open `http://localhost:9761`, add `echo-service-b`, stop and restart the registry.
Covers FR-010 to FR-014, SC-002. (The "calls spread across copies" scenario is verified in US4, T117.)

### Tests for User Story 2 (write first, confirm red) ⚠️

- [X] T086 [P] [US2] Write `backend/echo-service/src/test/java/com/example/learnhub/echo/EchoControllerTest.java` (WebMvcTest plus `platform-common`): `GET /api/v1/echo/ping` returns JSON matching `contracts/echo-service.openapi.yaml` with `instanceId`, `port`, `correlationId`, `greeting`; `/api/v1/echo/secure/ping` returns the same shape when called directly; the correlation ID comes from a valid header else is generated; unknown paths return an `application/problem+json` body
- [X] T086a [P] [US2] Write `backend/echo-service/src/test/java/com/example/learnhub/echo/StructuredLoggingTest.java` (FR-020, SC-004): with `logging.structured.format.console=ecs`, capture stdout during a request and assert each captured line parses as JSON and carries the ECS fields `@timestamp`, `log.level`, `message` and the MDC entry `correlationId` equal to the request's ID (not merely that the ID appears somewhere in the text)
- [X] T087 [P] [US2] Write `backend/infra-tests/src/test/java/com/example/learnhub/infra/NoWiringCodeTest.java`: for every service module listed in `backend/pom.xml` except `discovery-server`, `config-server`, `shared-events`, `platform-common` and `infra-tests`, scan `src/main/java` and `pom.xml` and fail on registration, config-client or health code (`@EnableDiscoveryClient`, `EurekaClient`, `DiscoveryClient` beans, `@RefreshScope`, `HealthIndicator`, `@EnableConfigServer`, mappings to `/health`); allow `spring.application.name`, `spring.config.import` and credentials only in `application.yml`
- [X] T087a [P] [US2] Write `backend/infra-tests/src/test/java/com/example/learnhub/infra/NoHardcodedAddressTest.java` (FR-012): scan every `backend/config-repo/*.yml` and every module's `src/main/resources/application*.yml` and fail on a literal host or port in a service address — any `http://` or `https://` URL whose host is not a `${...}` placeholder and is not `localhost` inside a comment, any `lb://` URI carrying a port, and any `eureka.client.service-url.*` or `spring.config.import` value not sourced from an environment variable. `server.port` and the gateway's own timeouts are allowed
- [X] T088 [P] [US2] Extend `backend/infra-tests/src/test/java/com/example/learnhub/infra/ComposeFileTest.java`: `echo-service-b` exists, is in profile `scale`, uses the same build with `MODULE=echo-service`, has a distinct hostname, publishes no host ports, and has the same health check and dependencies as `echo-service`
- [ ] T089 [US2] Extend `scripts/smoke-test.sh` with registry checks: `CONFIG-SERVER` and `ECHO-SERVICE` appear in `http://localhost:9761/eureka/apps` within 30 s; with `echo-service-b` started, two distinct `ECHO-SERVICE` instance IDs are listed within 30 s; after `docker compose stop echo-service-b` it disappears within 30 s; with `discovery-server` stopped, `http://localhost:9099/api/v1/echo/ping` still answers 200, and after it restarts both echo copies are listed again without restarting them
- [X] T090 [US2] Confirm red: run `mvn -f backend/pom.xml -pl echo-service,infra-tests test`; T086 to T088 must fail

### Implementation for User Story 2

- [X] T091 [P] [US2] Create `backend/echo-service/src/main/java/com/example/learnhub/echo/EchoProperties.java` (`@ConfigurationProperties("echo")` with `greeting`; no `@RefreshScope`)
- [X] T092 [US2] Create `backend/echo-service/src/main/java/com/example/learnhub/echo/EchoController.java`: `GET /api/v1/echo/ping` and `GET /api/v1/echo/secure/ping` only; response has `instanceId` (from `eureka.instance.instance-id`), `port`, `correlationId` (from MDC), `greeting`; logs the correlation ID (depends on T091)
- [X] T093 [P] [US2] Create `backend/config-repo/echo-service.yml` with `echo.greeting: Hello from LearnHub`
- [X] T094 [US2] Extend `docker-compose.yml` with `echo-service-b`: profile `scale`, same image and environment as `echo-service`, `hostname: echo-service-b`, no `ports`, same health check and `depends_on`
- [ ] T095 [US2] Run `mvn -f backend/pom.xml -pl echo-service,infra-tests test`, then `scripts/up.sh` and the registry part of `scripts/smoke-test.sh`; all must pass

**Checkpoint**: User Stories 1 and 2 both work independently.

---

## Phase 5: User Story 3 - Central configuration for every service (Priority: P2)

**Goal**: settings come from `backend/config-repo` with shared, service and environment layers; an edit reaches a running
service within about 10 seconds with no commit, restart or manual trigger; a missing config server fails clearly;
non-local profiles require a login.

**Independent Test**: change the echo greeting in `backend/config-repo/echo-service.yml` and watch `/api/v1/echo/ping`; start
echo with the config server down; start the stack under a non-local profile. Covers FR-015 to FR-018, SC-003.

### Tests for User Story 3 (write first, confirm red) ⚠️

- [ ] T096 [P] [US3] Write `backend/echo-service/src/test/java/com/example/learnhub/echo/EchoFailFastIT.java`: start the app with `spring.config.import=configserver:http://localhost:<closed port>`; the context fails within 30 seconds after bounded retry with a message naming the config server URL; the app never reaches a running state with missing settings
- [ ] T097 [P] [US3] Write `backend/echo-service/src/test/java/com/example/learnhub/echo/EchoLiveRefreshIT.java`: WireMock plays the config server returning `echo.greeting: first`; start echo with the real watcher at its default 5 s interval; change the stub to `second`; within 10 seconds `EchoProperties.greeting` is `second` with no restart and no manual trigger; with the stub returning 500 for a while the greeting stays `second` and refreshes again after recovery
- [ ] T098 [US3] Extend `scripts/smoke-test.sh` with the live-edit check (10 s): read the greeting through `http://localhost:9099/api/v1/echo/ping`, edit `echo.greeting` in `backend/config-repo/echo-service.yml`, poll until the new value appears and fail if it takes longer than 10 s, always restore the original file (trap on exit)
- [ ] T099 [US3] Extend `scripts/smoke-test.sh` with the config-server-down check: stop `config-server`, recreate `echo-service` with `--no-deps`, assert the container exits within 60 s and its log names the config server URL, then restore with `scripts/up.sh`
- [ ] T100 [US3] Extend `scripts/smoke-test.sh` with an `--auth-profile` mode: bring the stack up with `SPRING_PROFILES_ACTIVE=dev`, assert `http://localhost:9761/eureka/apps` and `http://localhost:9888/echo-service/default` return 401 without credentials and 200 with `EUREKA_USER` and `CONFIG_USER`, assert `echo-service` still registers and loads settings (proves client credentials work), then restore the `local` profile
- [ ] T101 [US3] Confirm red: run `mvn -f backend/pom.xml -pl echo-service verify`; T096 and T097 must fail or time out until the settings below are tuned

### Implementation for User Story 3

- [ ] T102 [US3] Tune retry and fail-fast in `backend/echo-service/src/main/resources/application.yml` (`spring.cloud.config.retry.max-attempts`, `initial-interval`, `multiplier`, `max-interval`) so failure happens within the T096 bound (about 30 s) and message text names the URL
- [ ] T103 [US3] Add the "Change a central setting" section to `README.md`: edit a file in `backend/config-repo` (no commit, no restart, effective within about 10 seconds), the three layers and their precedence, lowest to highest (`application.yml`, then `<service>.yml`, then `application-<profile>.yml`, so a profile file overrides a service's own value), which keys are live-refreshable, and the manual `/actuator/refresh` endpoint available under `local`
- [ ] T104 [US3] Run `mvn -f backend/pom.xml -pl echo-service verify`, then `scripts/smoke-test.sh` (live-edit and config-down checks) and `scripts/smoke-test.sh --auth-profile`; all must pass

**Checkpoint**: User Stories 1, 2 and 3 work independently.

---

## Phase 6: User Story 4 - One entry point for all clients (Priority: P2)

**Goal**: one gateway address routes `/api/v1/<name>/**`, returns and forwards a correlation ID, applies CORS and a per-IP
rate limit, and returns consistent RFC 7807 errors for 401, 404, 429, 503 and 504.

**Independent Test**: call the gateway on the echo route, an unknown path, a protected path, a stopped service and in a
burst; send preflight requests from allowed and disallowed origins. Covers FR-019 to FR-025, SC-004 to SC-006.

### Tests for User Story 4 (write first, confirm red) ⚠️

- [X] T105 [US4] Update `backend/api-gateway/pom.xml`: add `platform-common`, `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`, `spring-retry`, `spring-boot-starter-aop`, `micrometer` context-propagation (Boot-managed); keep test scope WireMock and `reactor-test`
- [X] T106 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/filter/CorrelationIdGlobalFilterIT.java` (WireMock upstream): client value matching `^[A-Za-z0-9._-]{8,64}$` is reused; malformed (`bad id!`, 7 and 65 characters) is replaced by a UUID; the ID reaches the upstream as `X-Correlation-Id` and is returned in the response header
- [X] T107 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/filter/RouteAccessFilterTest.java`: route metadata `public: true` parsed as Boolean and as String `"true"` passes; absent metadata, `false` or any other value returns 401 for every request; filter order is correlation ID, then route access, then rate limiter
- [X] T108 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/ratelimit/TokenBucketRateLimiterTest.java` (injectable clock and `ClientAddressResolver`): capacity then reject; refill over time; two addresses limited separately; clients sharing one address share one bucket; `X-Forwarded-For` ignored (key is the remote IP address only); idle buckets evicted; all buckets cleared on `EnvironmentChangeEvent`
- [X] T109 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/ratelimit/RateLimitFilterIT.java`: exceeding the limit returns 429 problem details with `Retry-After`; another address is unaffected; the limit recovers after refill
- [X] T110 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/error/GatewayErrorHandlerIT.java` (WireMock): 401 (protected route), 404 (no route), 429, 503 (refused connection and `lb://` with no instances, answered within 5 seconds), 504 (upstream slower than the 5 s response timeout) all return `application/problem+json` that validates against the schema loaded from classpath `schemas/problem-details.schema.json` (T019a, which also constrains `status` to `[401,404,429,500,503,504]`), with `correlationId` equal to the response header; unexpected exceptions return 500 in the same structure
- [X] T111 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/cors/CorsPreflightIT.java`: preflight from an allowed origin on a protected route returns 200 with CORS headers, never 401; from a disallowed origin returns 403; the preflight consumes no rate-limit token (research R2)
- [ ] T112 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/logging/CorrelationLogPropagationIT.java`: with `spring.reactor.context-propagation=auto` and the registered MDC accessor, a captured log line written inside the reactive chain parses as ECS JSON and carries `correlationId` equal to the request's ID (FR-020)
- [X] T113 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/refresh/RateLimitRefreshIT.java`: changing the rate-limit properties in the imported file and refreshing applies the new limit immediately (stale buckets cleared)
- [X] T114 [P] [US4] Write `backend/api-gateway/src/test/java/com/example/learnhub/gateway/RealGatewayConfigTest.java` loading the real `backend/config-repo/api-gateway.yml`: route `echo-public` is `Path=/api/v1/echo/ping`, `lb://echo-service`, `metadata.public: true`, `order: 0`; route `echo` is `Path=/api/v1/echo/**`, `lb://echo-service`, not public, `order: 1`; connect timeout 2000 ms and response timeout 5 s; allowed origins and rate limits present; no host or port appears in any URI
- [ ] T115 [P] [US4] Extend `backend/infra-tests/src/test/java/com/example/learnhub/infra/ComposeFileTest.java`: `api-gateway` exists on `9080:9080`, builds with `MODULE=api-gateway`, depends on healthy config-server and discovery-server, has a readiness health check
- [X] T116 [US4] Extend `scripts/smoke-test.sh` with gateway checks on `http://localhost:9080`: `/api/v1/echo/ping` 200 with `X-Correlation-Id`; valid client ID reused and malformed replaced; `/nope` 404; `/api/v1/echo/secure/ping` 401; every error body validates against the problem-details required fields; with `echo-service` stopped, `/api/v1/echo/ping` returns 503 in under 5 seconds; a **concurrent burst of 400 requests** (not a serial loop, see research R8) exceeds capacity 200 and yields 429, and the limit recovers afterwards so later checks are unaffected; preflight allowed 200 and disallowed 403
- [ ] T117 [US4] Extend `scripts/smoke-test.sh` with the load-spread check (US2 acceptance 3, SC-006): start `echo-service-b`, make 100 calls to `/api/v1/echo/ping`, count `instanceId`, fail if either copy gets fewer than 40
- [ ] T118 [US4] Extend `scripts/smoke-test.sh` with crash and outage checks: `docker kill` the `echo-service-b` container and expect 503 (never a hang) for calls that hit it and its removal from the registry within the 90 s that SC-002 allows for a crash (about 65 s in practice, research R5); with `discovery-server` stopped, `/api/v1/echo/ping` through the gateway keeps answering and routing resumes after restart
- [ ] T119 [US4] Confirm red: run `mvn -f backend/pom.xml -pl api-gateway,infra-tests verify`; T106 to T115 must fail

### Implementation for User Story 4

- [X] T120 [P] [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/RateLimiterProperties.java` (`@ConfigurationProperties("platform.gateway.rate-limit")`: `capacity`, `refill-per-second`, `idle-eviction`)
- [X] T121 [P] [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/ClientAddressResolver.java` (interface) and `RemoteAddressClientResolver.java` (remote IP address only; `X-Forwarded-For` ignored)
- [X] T122 [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/TokenBucketRateLimiter.java` (in-memory token bucket per client address, idle eviction, injectable clock, clears all buckets on `EnvironmentChangeEvent`; no Redis; depends on T120, T121)
- [X] T123 [P] [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/filter/CorrelationIdGlobalFilter.java` (highest precedence; uses `CorrelationIds` from `platform-common`; forwards the header and sets it on the response; writes the ID into the Reactor context)
- [X] T124 [P] [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/filter/RouteAccessGlobalFilter.java` (order after the correlation filter; reads route metadata `public` as Boolean or String; protected by default; throws a 401 problem)
- [X] T125 [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/filter/RateLimitGlobalFilter.java` (order after route access; uses the limiter; 429 with `Retry-After`; depends on T122)
- [X] T126 [P] [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/error/GatewayProblem.java` (builds the RFC 7807 body: `type`, `title`, `status`, `detail`, `instance`, `correlationId`; no internals in `detail`)
- [X] T127 [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/error/GatewayErrorHandler.java` (`ErrorWebExceptionHandler` ordered before the default: connection failures to 503, Netty and `TimeoutException` timeouts to 504, no instances to 503, 404, 401, 429 passthrough, anything else 500; `application/problem+json` with the correlation ID; depends on T126)
- [X] T128 [P] [US4] Create `backend/api-gateway/src/main/java/com/example/learnhub/gateway/logging/CorrelationContextConfiguration.java` (registers `new Slf4jThreadLocalAccessor("correlationId")` in the `ContextRegistry`; the accessor is not auto-registered, research R9)
- [X] T129 [P] [US4] Create `backend/config-repo/api-gateway.yml`: routes `echo-public` and `echo` (see T114); `globalcors` with `allowed-origins: ["http://localhost:9000", "http://localhost:5173"]` (placeholders for the future frontend, which gets its real 9xxx port in its own spec) and `allowed-methods: [GET, POST, PUT, PATCH, DELETE, OPTIONS]`; `platform.gateway.rate-limit.capacity: 200`, `refill-per-second: 100`, `idle-eviction: PT10M` (values fixed by research R8 so the 100-call spread and problem-details checks stay under the limit while a 400-request burst trips it); `spring.cloud.gateway.server.webflux.httpclient.connect-timeout: 2000` and `response-timeout: 5s`; `spring.reactor.context-propagation: auto`
- [X] T130 [P] [US4] Create `backend/api-gateway/src/main/resources/application.yml` (configuration only): `spring.application.name: api-gateway`, `server.port: 9080`, non-optional `spring.config.import: configserver:${CONFIG_SERVER_URL}`, credentials, fail-fast and the same bounded retry as echo
- [X] T131 [US4] Extend `docker-compose.yml` with `api-gateway` (`9080:9080`, build `MODULE=api-gateway`, depends on healthy config-server and discovery-server, readiness health check)
- [ ] T132 [US4] Run `mvn -f backend/pom.xml -pl api-gateway,infra-tests verify`, then `scripts/up.sh` and the full `scripts/smoke-test.sh`; all must pass

**Checkpoint**: User Stories 1 to 4 work independently; the platform is usable end to end.

---

## Phase 7: User Story 5 - A consistent skeleton for new services (Priority: P3)

**Goal**: versions come from one place; a new service needs no registration, config or health code; the event wrapper
exists and round-trips; README explains how to add a service.

**Independent Test**: follow the README steps for a throwaway service (done in T140). Covers FR-026 to FR-030, SC-007.
(Acceptance scenario 3, the envelope round trip, is covered by T013 and T015; scenario 4, the second echo copy, by T117.)

### Tests for User Story 5

- [X] T133 [P] [US5] Write guard test `backend/infra-tests/src/test/java/com/example/learnhub/infra/BuildRulesTest.java` (expected to PASS): only `backend/pom.xml` contains `<version>` elements outside the `<parent>` reference of child POMs; no child dependency or plugin declares a version; every module on disk is listed in `<modules>`; every service module uses `platform-common` without a version. `NoWiringCodeTest` (T087) already enforces the no-wiring-code rule for every service module, including future ones

### Implementation for User Story 5

- [ ] T134 [US5] Add the "Add a service" section to `README.md`: new module under `backend/` (copy `echo-service` as the template), register it in `backend/pom.xml` with no versions, add `backend/config-repo/<service>.yml`, add a route in `backend/config-repo/api-gateway.yml` (public or protected, `lb://<service-name>`), add a Compose entry on its reserved port (9001 to 9007), and the minimum `application.yml` (name, port, config import, credentials, no code)
- [ ] T135 [US5] Run `mvn -f backend/pom.xml -pl infra-tests test`; T133 must pass

**Checkpoint**: all five user stories are independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: clean-checkout walkthrough, open decisions, constitution amendment and confirmation of SC-001 to SC-010.

- [ ] T136 [P] Run `shellcheck` and fix findings in `scripts/up.sh`, `scripts/smoke-test.sh`, `scripts/tests/up-preflight.test.sh`, `scripts/tests/fakebin/docker` and `backend/docker/db-init/provision.sh`
- [ ] T137 Re-check the measured timings against the decided bounds and report any that do not hold: the crash eviction window from T118 against the 90 s in SC-002, and the cold and warm `up.sh` times from T141 against the 10-minute and 3-minute SC-001 budgets. If a bound is missed, raise it with the spec owner rather than quietly widening it in `specs/001-platform-foundation/spec.md`
- [ ] T138 Confirm `.specify/memory/constitution.md` still reads `**Version**: 1.1.0` and that nothing built in this feature relies on a rule outside it; re-read the Constitution Check table in `specs/001-platform-foundation/plan.md` against the finished code and update any row whose evidence changed (deviations D3, D4 and D5 are expected to remain)
- [ ] T139 README walkthrough on a clean checkout: clone the repo into a new empty directory, follow `README.md` literally (copy `.env.example`, start, stop, restart, reset, change a setting), and fix every gap or wrong command in `README.md`; keep the notes for T148
- [ ] T140 SC-007 walkthrough: follow the README "Add a service" section to add a throwaway `probe-service` (module, config-repo file, gateway route, Compose entry) with no registration, config or health code; confirm it registers, loads its settings, reports health and is routable through the gateway; then remove it. Record the result in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T141 Confirm SC-001: from a machine with no project images or volumes, time `scripts/up.sh` (cold, budget 10 minutes) and a second run (warm, budget 3 minutes); record both, the Compose version and the machine in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T142 Confirm SC-002: repeat start and graceful stop of `echo-service-b` five times (appearance and disappearance each under 30 s), then crash it twice with `docker kill` (disappearance under 90 s); record every measurement in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T143 Confirm SC-003: edit `echo.greeting` five times and measure time to effect; all under 10 s; record in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T144 Confirm SC-004: run the gateway error IT and the smoke gateway checks; every 401, 404, 429, 503 and 504 body validates against `specs/001-platform-foundation/contracts/problem-details.schema.json` and has `correlationId`; record in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T145 Confirm SC-005: measure the 503 response time with no healthy copy, ten runs; all under 5 s; record in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T146 Confirm SC-006: run the 100-call spread check three times; each copy gets at least 40 every time; record in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T147 Confirm SC-009 and SC-010: run `bash scripts/tests/up-preflight.test.sh` (failure messages under 30 s naming the setting or port) and `git grep -n -i -E 'password|secret|token' -- . ':!.env.example' ':!specs'`, then `mvn -f backend/pom.xml -pl infra-tests test` (`EnvExampleTest`); no real secret committed and every setting listed; record in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T148 Confirm SC-008: using only the T139 notes and `README.md`, stop, restart and reset the platform with no manual cleanup; record in `specs/001-platform-foundation/checklists/success-criteria.md`
- [ ] T149 Final full run: `mvn -f backend/pom.xml verify`, `scripts/up.sh`, `scripts/smoke-test.sh`, `scripts/smoke-test.sh --lifecycle`, `scripts/smoke-test.sh --auth-profile`, then `docker compose down -v`; all pass; mark every SC-001 to SC-010 row in `specs/001-platform-foundation/checklists/success-criteria.md` as confirmed or note exactly which are not
- [ ] T150 Update `specs/001-platform-foundation/quickstart.md` so every command, flag and file name matches the final scripts and so its stated bounds match the decided ones (crash eviction 90 s not "about 65 to 70 s", the two SC-001 budgets, the burst-based 429 check), and record the measured timings in `specs/001-platform-foundation/research.md` under R12

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies. T006 depends on T005.
- **Foundational (Phase 2)**: depends on Setup (only T001 to T004 are strictly needed); blocks every user story. Internal order: T007, then the module groups. The early gateway check (T008 to T011) needs only T007. `shared-events` and `platform-common` need only T007 and can run in parallel with each other. `config-server` tests (T055) need T042, T043. T064 closes the phase.
- **US1 (Phase 3)**: needs Foundational. T082 needs the T006 decision.
- **US2 (Phase 4)**: needs US1 (Compose, `up.sh`, smoke script).
- **US3 (Phase 5)**: needs US1 and US2 (echo controller and `echo-service.yml`).
- **US4 (Phase 6)**: needs US1 and US2; T117 and T118 need `echo-service-b` from US2.
- **US5 (Phase 7)**: needs US4 for the gateway part of the README; its tests need nothing new.
- **Polish (Phase 8)**: needs all stories; T141 to T148 need T139.

### Within each group

- Tests before implementation; confirm red; then implement; then run green.
- `smoke-test.sh`, `docker-compose.yml`, `scripts/up.sh`, `README.md` and `ComposeFileTest.java` are edited by several tasks; those tasks are sequential even when the tasks around them are `[P]`.

### Parallel Opportunities

- Setup: T001 to T005.
- Foundational: T013 to T015; T017 to T019a; T022 to T029; T031 to T033; T036 to T038; T042 and T043; T045 and T046; T048 to T050; T053 to T055; T057 to T059; T062 and T063. `shared-events` and `platform-common` groups can run side by side.
- US1: T066 to T072 (tests), T074 to T076 (implementation files).
- US2: T086, T086a, T087, T087a, T088 (T089 edits the shared smoke script); T091 and T093.
- US3: T096 and T097 (T098 edits the shared smoke script).
- US4: T106 to T115 (ten test files; T116 to T118 edit the shared smoke script); T120, T121, T123, T124, T126, T128 to T130.

### Parallel Example: User Story 4

```bash
# Launch the gateway tests together (after T105):
Task: "CorrelationIdGlobalFilterIT in backend/api-gateway/src/test/java/com/example/learnhub/gateway/filter/"
Task: "TokenBucketRateLimiterTest in backend/api-gateway/src/test/java/com/example/learnhub/gateway/ratelimit/"
Task: "GatewayErrorHandlerIT in backend/api-gateway/src/test/java/com/example/learnhub/gateway/error/"
Task: "CorsPreflightIT in backend/api-gateway/src/test/java/com/example/learnhub/gateway/cors/"

# Then the independent implementation files together:
Task: "RateLimiterProperties and ClientAddressResolver in backend/api-gateway/.../ratelimit/"
Task: "CorrelationIdGlobalFilter and RouteAccessGlobalFilter in backend/api-gateway/.../filter/"
Task: "GatewayProblem in backend/api-gateway/.../error/"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 Setup, then Phase 2 Foundational (run T011 early: if the refresh assumption fails, the fallback changes US4).
2. Phase 3 (US1). **Stop and validate**: `scripts/up.sh` brings the stack healthy; failure checks and reset behave.
3. Demo: a healthy platform with registry, config server, echo skeleton, databases, broker and mail catcher.

### Incremental Delivery

1. Foundation, then US1 (one command) as the MVP.
2. US2 adds name-based discovery and the second echo copy.
3. US3 adds proven live configuration (10 s), fail-fast and login outside local.
4. US4 adds the gateway, the first client-visible behavior and the contract the frontend relies on.
5. US5 documents and guards the skeleton; Polish confirms SC-001 to SC-010.

### Parallel Team Strategy

After Foundational, one developer takes US1 (infrastructure and scripts) while another prepares US4's gateway tests; US2 and US3 follow US1 because they extend the same Compose file, smoke script and README.

---

## Notes

- `[P]` means different files and no unfinished dependency.
- Docker is required for every `*IT`, Compose and smoke task; none of those could be run in the planning environment.
- Commit after each group; keep the working tree green at each checkpoint.
- Tasks T137 and T138 need a decision from the spec owner and a constitution amendment, so they are the only tasks that are not pure implementation.
