# Research: Checkout and Enrollment

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-18

No technical brief was supplied with `/speckit-plan` for this feature; every decision below was made
here, bound by the constitution and by the one clarification already recorded in spec.md. No
`NEEDS CLARIFICATION` remains.

## R1. Making a successful payment durable before it is ever published (FR-025)

- **Decision**: Transactional outbox. `payment-service` writes a row to its own `outbox_events` table
  in the *same database transaction* as the `payments` row transitioning to `SUCCEEDED`. A poller
  (fixed delay, short — on the order of a couple of seconds) selects unpublished outbox rows, publishes
  each as a `payment.succeeded` event (wrapped in the shared `EventEnvelope`) to the existing
  `learning.events` exchange with RabbitMQ publisher confirms enabled, and marks a row published only
  once the broker has acknowledged it. A row that fails to publish (broker unreachable) is simply
  picked up again on the next poll. The poller is a plain `ScheduledExecutorService` started from a
  `@PostConstruct` method and stopped from `@PreDestroy` — the same pattern `platform-common`'s own
  `ConfigWatcher` already uses — not Spring's `@Scheduled`/`@EnableScheduling`, which
  `infra-tests`'s `NoWiringCodeTest` forbids in every business service's source (reserved for
  platform-common's own config-polling wiring).
- **Rationale**: FR-025 requires that a payment is never lost "even if messaging ... is temporarily
  unavailable at that moment" — not just "even if the *consumer* is down." A plain `RabbitTemplate.send`
  call made directly inside the checkout request, with nothing else recording the fact, would lose the
  event forever if the broker itself was briefly unreachable at the exact moment of success, because
  nothing durable would remain to retry from. Writing the outbox row in the same transaction as the
  payment row means the two facts ("payment succeeded" and "this needs to be announced") can never
  become inconsistent with each other — a committed payment always has a durable outbox row, and a
  rolled-back payment attempt never does.
- **Alternatives considered**: Publish directly from the checkout request and accept the small gap
  (a broker outage at that exact instant loses the event). Rejected: it directly contradicts FR-025's
  explicit wording. A full two-phase-commit or a change-data-capture pipeline (e.g. Debezium) was also
  considered and rejected as unjustified complexity (Principle X) for one event type at this scale; the
  outbox-table-plus-poller is the standard, minimal way to get the same guarantee.

## R2. The simulated payment provider is a pure, in-process function (FR-007)

- **Decision**: `SimulatedPaymentProvider` is a plain Java component, not an HTTP client and not a
  Feign call. It maps a caller-supplied `paymentToken` string to a deterministic outcome: a constant
  token for "always succeeds," one for "always declines," and one for "provider temporarily
  unavailable" (which the provider surfaces as a checked outcome, not a network exception — see R4 for
  why `course-service`'s lookup *is* a real Feign call but this deliberately is not). The exact token
  values are an implementation-level, not user-facing, string constant (e.g. `sim-success`,
  `sim-decline`, `sim-unavailable`), documented in data-model.md.
- **Rationale**: The spec explicitly says this is a *simulated* provider, and nothing in the spec asks
  for network-failure characteristics to be modeled realistically — only that the three outcomes exist
  and are selectable by the caller. Making it a real, over-the-network dependency (even to a stub
  service) would add a whole extra service, a whole extra Feign client with its own circuit breaker, and
  a whole extra set of test infrastructure for zero behavioral benefit, which Principle X forbids.
- **Alternatives considered**: A separate `fake-payment-provider` microservice reached over Feign.
  Rejected as the textbook case of unjustified complexity this constitution's simplicity principle
  exists to prevent — there is no real external system to simulate talking to here, only a chooseable
  outcome.

## R3. "Already enrolled, cannot pay twice" is enforced from `payment-service`'s own data (FR-004)

- **Decision**: In this feature, an enrollment can only ever be created as the eventual result of a
  `SUCCEEDED` payment (R1, R6). So "the user is already enrolled" and "the user already has a
  `SUCCEEDED` payment for this course" are the same fact, one hop earlier and available synchronously,
  without needing to ask `enrollment-service` at all. `payment-service` refuses a checkout whenever a
  `SUCCEEDED` payment already exists for `(userId, courseId)`, enforced as a database-level guarantee:
  a Postgres **partial unique index** on `payments(user_id, course_id) WHERE status = 'SUCCEEDED'`.
  Two concurrent first-time checkouts for the same user and course race safely — whichever commits
  first wins the index; the second's commit fails with a constraint violation, which the service catches
  and turns into the same 409 "already enrolled" response.
- **Rationale**: Calling out to `enrollment-service` instead would reintroduce exactly the race this
  decision avoids: enrollment is created asynchronously, up to 5 seconds after a payment succeeds
  (FR-014), so a second checkout attempted inside that window would see "not yet enrolled" from
  `enrollment-service` and be wrongly allowed to pay again. Checking `payment-service`'s own,
  synchronously-consistent data closes that gap entirely and needs no new Feign client.
- **Alternatives considered**: A synchronous Feign call from `payment-service` to `enrollment-service`.
  Rejected: strictly worse (introduces the race above, plus an extra network dependency) for no
  benefit, since the data needed is already local.

## R4. Two Feign clients: `course-service`→ and `enrollment-service`-bound, with circuit breaker and fallback (constitution Principle V)

- **Decision**: `payment-service` gets a `CourseLookupClient` Feign client calling `course-service`'s
  existing internal endpoint (`GET /api/v1/internal/courses/{id}`, built in 003, unchanged) to read a
  course's status, price, currency and instructor at checkout time (FR-002, FR-003, FR-006).
  `course-service` gets a new `EnrollmentLookupClient` Feign client calling a new internal endpoint on
  `enrollment-service` (`GET /api/v1/internal/enrollments/{courseId}/{userId}`) to check enrollment for
  the new lesson-content-gating endpoint (FR-021 to FR-024). Both clients: use `lb://` service-name
  resolution (constitution Principle III); set an explicit connect and read timeout short enough that a
  stuck dependency fails fast (2 seconds, matching the gateway's own existing per-route timeout
  precedent from platform-foundation); wrap calls in a Resilience4j circuit breaker
  (`spring-cloud-starter-circuitbreaker-resilience4j`, `feign.circuitbreaker.enabled=true`) configured
  with a sliding window and failure-rate threshold; and declare a `@FeignClient(fallback = ...)` class
  that turns a timeout, a connection failure or an open circuit into a clear, typed outcome rather than
  an unhandled exception — translated at the call site into a `409`/`404` (for `CourseLookupClient`,
  depending on what the fallback can still determine) or a `503` problem-details response (for
  `EnrollmentLookupClient`, per FR-024's explicit "refused with a clear, temporary error" requirement).
- **Rationale**: this is exactly what constitution Principle V mandates ("every Feign client MUST have
  timeouts, a Resilience4j circuit breaker and a fallback") — not a feature-specific choice, but the
  first feature to actually need a synchronous cross-service call and so the first to exercise it. Using
  the `spring-cloud-starter-circuitbreaker-resilience4j` + Feign's own circuit-breaker integration is the
  standard Spring Cloud pairing and needs no extra dependency version (already covered by the imported
  BOM), consistent with Principle X.
- **Verification plan**: tasks.md includes a WireMock-backed contract test for each client (stubbing
  success, 404, 5xx and a deliberately slow response to trip the breaker), satisfying Principle VI's
  "every Feign client MUST have a contract test."
- **Implementation finding**: `spring.cloud.openfeign.circuitbreaker.enabled=true` alone names each
  Resilience4j circuit breaker and time limiter per Feign *method* (e.g. `CourseLookupClientsummaryUUID`),
  not per `@FeignClient` name. A `resilience4j.circuitbreaker.instances.<client-name>` /
  `resilience4j.timelimiter.instances.<client-name>` config block keyed by the client's name therefore
  silently never applies, and every call falls back to Resilience4j's own 1-second default time-limiter
  timeout — discovered when `CourseLookupClientContractTest`'s plain-200 case failed with a spurious
  timeout. Fixed by also setting `spring.cloud.openfeign.circuitbreaker.group.enabled=true`, which groups
  breakers by the `@FeignClient` name instead, matching the config keys above.

## R5. Lesson content for enrolled students is a new, additive endpoint (FR-021 to FR-024)

- **Decision**: `GET /api/v1/courses/{id}/content` is a new endpoint on `course-service`, separate from
  003's existing `GET /api/v1/courses/{id}/full` (which remains the owner/admin *editing* view, with
  `version`/`ETag` and unchanged semantics). The new endpoint serves a *reading* view: the owning
  instructor and administrators get it unconditionally (FR-023, same `OwnershipGuard` check 003 already
  built); every other caller must be enrolled, checked via `EnrollmentLookupClient` (R4); a confirmed
  non-enrollment is a `403`; an unconfirmable check (circuit open, timeout) is a `503` problem-details
  response, never the content itself (FR-024). A course that is not published and whose caller is
  neither owner nor admin yields the same `404` 003 already uses for any unpublished/unknown course,
  since such a course was never checkout-eligible either.
- **Rationale**: 003's `/full` endpoint already has a settled meaning (the owner/admin editing view) and
  a settled contract; overloading it with a third caller type (an enrolled, non-owning student) would
  either change its response shape conditionally on role (confusing, and a breaking risk to 003's
  existing contract and tests) or silently add fields nothing in 003 anticipated. A new, purpose-built
  path keeps 003's contract untouched and gives this feature's "can an enrolled student read this
  course" question one unambiguous, independently testable answer.
- **Alternatives considered**: Adding a query parameter or role-conditional branch to `/full`. Rejected
  for the reason above — it would make one endpoint's contract depend on who's asking in a way 003 never
  designed for, where a new path costs nothing extra.

## R6. The `payment.succeeded` consumer: idempotent, retried, dead-lettered (constitution Principle V)

- **Decision**: `enrollment-service` declares its own durable queue
  (`enrollment-service.payment-succeeded`) bound to the existing `learning.events` topic exchange on
  routing key `payment.succeeded`, plus its own dead-letter queue
  (`enrollment-service.payment-succeeded.dlq`) bound to a dead-letter exchange
  (`learning.events.dlx`). The listener container is configured with a stateless retry interceptor
  (three attempts, short backoff) and a `RepublishMessageRecoverer` targeting the DLQ once attempts are
  exhausted, with `defaultRequeueRejected(false)` so a failing message is retried a bounded number of
  times and then moved aside — never requeued forever, and never blocking the messages behind it in the
  same queue. Before acting on an event, the listener checks a small `processed_events` table (primary
  key `event_id`); if the id is already present it acknowledges and does nothing further (idempotent
  replay); otherwise it inserts the `Enrollment` row and the `processed_events` row in one transaction.
  A malformed message (fails to deserialize into `EventEnvelope`) throws before reaching this check and
  is retried and dead-lettered by the same mechanism — no separate "malformed" code path is needed.
- **Rationale**: this is constitution Principle V's consumer rule stated almost verbatim ("each consumer
  MUST have its own queue and its own dead-letter queue, MUST be idempotent using `eventId`, and MUST
  retry three times with backoff before dead-lettering") — again, not a choice made for this feature,
  but the first feature with an actual consumer to apply it to. `RepublishMessageRecoverer` plus
  `defaultRequeueRejected(false)` is the standard Spring AMQP combination that avoids the classic
  poison-message problem (a message that fails forever blocking every message behind it), directly
  satisfying FR-027.
- **Verification plan**: tasks.md's `PaymentSucceededConsumerIT` and `DeadLetterIT` run against a real
  Testcontainers RabbitMQ broker (not a mock), proving the retry count, the DLQ landing, and that an
  unrelated, valid message processed after a poison one is not delayed or lost.

## R7. Gateway routes for the five new client-facing endpoints

- **Decision**: All five are protected (no `public: true` metadata — every one requires a valid token);
  none needs role metadata, since every caller-specific rule (ownership of a payment, enrollment status,
  "not your own course") is enforced inside the owning service, the same split 003 already established
  (its R9) and reuses here unchanged:
  - `POST /api/v1/checkouts` → `payment-service`.
  - `GET /api/v1/payments/**` → `payment-service` (covers both the list and the by-id lookup; ownership
    enforced in-service).
  - `GET /api/v1/enrollments` → `enrollment-service`.
  - `GET /api/v1/courses/*/content` → `course-service`, ordered before the existing
    `courses-manage` (`/api/v1/courses/**`) catch-all from 003 so the narrower, two-segment shape is
    matched first — not a security requirement here (both routes target the same service with no
    differing role rule), but kept consistent with 002/003's established discipline of giving every
    route an unambiguous, order-independent-where-possible shape.
- **Rationale**: identical mechanism to every prior feature's gateway additions; no new filter or
  metadata concept is needed.
- **Verification plan**: tasks.md extends `RealGatewayConfigTest` and adds a routing IT in the same
  style as 003's `CourseRoutingIT`, loading the real YAML into a real gateway context.

## R8. Module layout and dependencies

- **Decision**: `backend/payment-service` and `backend/enrollment-service`, each structured exactly
  like `course-service` (`api`/`application`/`domain`/`infrastructure`), with the same base starter set
  (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`,
  `flyway-core` + `flyway-database-postgresql`, `org.postgresql:postgresql`,
  `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`, `spring-retry` +
  `spring-boot-starter-aop`, `spring-boot-starter-actuator`, `platform-common`), plus, new to both:
  `spring-boot-starter-amqp` (already managed by `spring-boot-starter-parent`),
  `spring-cloud-starter-openfeign` and `spring-cloud-starter-circuitbreaker-resilience4j` (both already
  managed by the imported `spring-cloud-dependencies` BOM). `course-service` adds the same Feign and
  circuit-breaker starters (it did not need them before this feature). Neither new service needs a
  security library — like `course-service`, they only read the gateway-forwarded `X-User-Id`/
  `X-User-Role` headers (`platform-common` `IdentityHeaders`, unchanged since 002).
- **Rationale**: every dependency is already an approved, versioned-once choice from `backend/pom.xml`
  or its imported BOMs; nothing needs a new version pin.

## R9. Checkout idempotency storage (FR-010 to FR-012)

- **Decision**: The `payments` table carries a unique constraint on `(user_id, idempotency_key)`. A
  checkout request first looks up that pair; if found, it compares the stored `course_id` and
  `payment_token` against the incoming request — an exact match replays the stored result unchanged
  (FR-011); any difference is refused with `409` (FR-012) without touching the payment provider or the
  course lookup again. If no row exists yet, checkout proceeds (R2, R3) and inserts the new row; a
  unique-constraint violation at commit time (two racing requests under the same key) is caught and
  re-read as if it had been found on the first lookup, so 20 simultaneous identical requests always
  converge on exactly one stored outcome (SC-003).
- **Rationale**: a plain, per-user unique database constraint is the simplest mechanism that is also
  correct under real concurrency (it does not depend on application-level locking), and it composes
  cleanly with R3's partial-unique "no double enrollment" constraint — the two guard different
  dimensions (same request replayed vs. same course bought twice) without conflicting.
- **Alternatives considered**: An in-memory or Redis-backed idempotency cache. Rejected: adds a new
  piece of infrastructure (Redis is not in the approved stack, Principle X) for a guarantee the existing
  database already provides durably and for free.
