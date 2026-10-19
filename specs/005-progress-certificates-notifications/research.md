# Research: Lesson Progress, Certificates, and Email Notifications

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-19

Unlike 003 and 004, a detailed technical brief was supplied with `/speckit-plan` for this feature. Most
decisions below restate that brief directly, with rationale. R0 is not a technical decision at all — it
records two places where the brief's own shorthand no longer matches the already-shipped codebase, and
the correction made to reconcile them, in the same spirit as 004's own transparent corrections mid-build.

## R0. Two corrections to the brief's own shorthand

- **Internal endpoint path prefix**: the brief writes course-service's lookup as
  `GET /internal/v1/courses/{id}` and the new auth-service one the same way. The *actual*, already-shipped
  course-service endpoint (built in 003, consumed by `payment-service` since 004 and unchanged by this
  feature) is `GET /api/v1/internal/courses/{courseId}` — constitution Principle IX fixes `/api/v1` as
  the mandatory prefix for every REST path, with no exception carved out for internal ones (003's R6 only
  exempts internal paths from having a *gateway route*, not from the path-prefix convention itself).
  Renaming the shipped path would silently break `payment-service`'s existing `CourseLookupClient` —
  which this feature's own scope list never mentions touching. Read as shorthand, not a rename request:
  course-service's path is unchanged (only its response body grows, R3), and the new auth-service
  endpoint follows the identical, established convention: `GET /api/v1/internal/accounts/{id}`.
- **Enrollment-service's new migration number**: the brief labels its one enrollment-service schema change
  "V2", but 004 already shipped `V2__create_processed_events.sql` in this same module; Flyway version
  numbers can never be reused or inserted retroactively. The two new migrations this feature needs are
  `V3` (the `completed_at` column and the new `lesson_progress` table) and `V4` (enrollment-service's
  first `outbox_events` table, needed for R7 below) — both additive, neither touching V1/V2.

## R1. Lesson progress and one-time completion live in enrollment-service (the brief's own placement)

- **Decision**: `enrollment-service` gains `PUT`/`DELETE
  /api/v1/enrollments/{courseId}/lessons/{lessonId}/complete` and `GET
  /api/v1/enrollments/{courseId}/progress`, all protected and falling under the gateway's existing
  `enrollments` route (no new route needed, research.md R9). Marking looks up the caller's enrollment for
  `courseId` (404/403 if none — not enrolled, FR-003); validates `lessonId` against course-service's
  extended internal lookup (R3) — a lesson id not in that course's list is refused; inserts a
  `LessonProgress` row if one doesn't already exist for `(enrollment, lesson)` (an existing row makes the
  call a no-op, FR-004); unmarking deletes the row if present (absence is a no-op, FR-005) and never
  touches the enrollment's completion fact. After a successful mark, the same transaction re-counts
  progress against the course's current lesson count; if every lesson is now complete **and**
  `completed_at` is still null, a conditional update (`UPDATE enrollments SET completed_at = :now WHERE id
  = :id AND completed_at IS NULL`) attempts to claim completion; only the caller whose update actually
  changed a row (affected-row-count 1) writes the `course.completed` outbox row, in the same transaction
  (R7) — exactly the brief's "conditional update so concurrent requests produce one event."
- **Concurrency correctness**: the conditional update alone is not sufficient by itself under read-
  committed isolation when the *count* that decides "every lesson is complete" is computed from rows
  several concurrent transactions are inserting at once — two requests racing to mark the course's last
  two different lessons could each see a stale, pre-completion count and neither would ever claim
  completion. The mark-complete transaction therefore takes a pessimistic lock on the enrollment row
  (`SELECT ... FOR UPDATE`) before inserting progress and recomputing the count, serializing concurrent
  mark-complete calls for the *same* enrollment so the count a given request sees is always accurate as
  of the moment it runs — not just the write it personally made. This is what the brief's own "one
  `course.completed` under 10 parallel completions" test (tasks.md) actually requires to hold.
- **Rationale**: this mirrors 004's own `CheckoutService` discipline (a database-level guarantee, not an
  application-level lock spread across instances) while fixing the one gap a bare conditional update
  leaves open under concurrent *reads* of a count, not just concurrent *writes* of a single row.

## R2. A third event, `enrollment.created`, not named in the brief's shared-events bullet

- **Decision**: the brief's shared-events instruction names only `CourseCompletedPayload` and
  `CertificateIssuedPayload`, but its own notification-service instruction requires consuming
  `enrollment.created` for the "you are enrolled" email (User Story 6) — an event nothing in 004 ever
  published (enrollment-service was a pure consumer there). Added `EnrollmentCreatedPayload`
  (`enrollmentId`, `userId`, `courseId`, `enrolledAt`) and the `ENROLLMENT_CREATED` routing key alongside
  the other two, for consistency, rather than leaving this one event's constant un-centralized while its
  two siblings are. Published by extending 004's existing `PaymentSucceededListener`: after inserting the
  `Enrollment` row, the same transaction now also writes an `enrollment.created` outbox row (R7) —
  additive to that listener's existing behavior, not a replacement of it.
- **Rationale**: the brief's own request pattern elsewhere in this spec (one outbox row per
  state-changing fact, written in the same transaction as the fact itself) applies identically here; the
  omission reads as an oversight in the brief's shared-events bullet, not a deliberate choice to leave
  this one event un-centralized.

## R3. course-service's internal lookup grows two fields; its path and existing contract are unchanged

- **Decision**: `CourseSummaryView` (and the `CourseLookupService` that builds it) gains `title` (the
  course's current title) and `lessonIds` (the course's lesson ids in position order, capped at 200 —
  the brief's own stated bound). The endpoint, path, and every existing field are unchanged, so
  `payment-service`'s already-shipped `CourseLookupClient` (004) keeps working against the same shape it
  already tolerates (it ignores fields it doesn't map, same as any Feign/Jackson consumer that doesn't
  declare every field).
- **Rationale**: the brief explicitly wants `enrollment-service` to validate a lesson id against the
  course it claims to belong to, and to compute progress against an authoritative lesson count — both
  already-fetched facts course-service's internal lookup is the natural place to extend, consistent with
  R6's original "one internal endpoint, several consumers" design from 003/004.
- **Alternatives considered**: a second, dedicated endpoint (`GET
  /api/v1/internal/courses/{id}/lessons`). Rejected: the existing endpoint is already fetched once per
  mark-complete call; a second round trip for data that fits trivially in the same response is
  unjustified complexity (Principle X).

## R4. Auth-service's new internal lookup returns only id and email

- **Decision**: `GET /api/v1/internal/accounts/{id}` — no gateway route, reached only by
  `notification-service` via `lb://auth-service`. Returns `{id, email}` only: no password hash, no role,
  no timestamps. Backed entirely by `AccountRepository.findById`, already inherited from
  `JpaRepository<Account, UUID>` — no new repository method needed.
- **Rationale**: this is the first internal, service-to-service surface auth-service exposes; keeping its
  response to the two fields notification-service actually needs is the minimum necessary disclosure
  (constitution Principle VII's security-by-minimality spirit), consistent with how course-service's own
  internal lookup never exposed more than each consumer needed either.

## R5. Two new Feign clients, both fail-closed, no graceful degradation

- **Decision**: `certificate-service`'s `CourseLookupClient` (to course-service, for the title) and
  `notification-service`'s `AccountLookupClient` (to auth-service, for the email) and `CourseLookupClient`
  (to course-service, for the title) each use a `fallbackFactory`/`fallback` that **throws** rather than
  returning a degraded result. For `certificate-service`, a thrown exception from the listener causes the
  message to retry (R6) rather than issuing a titleless certificate — the brief's explicit "failing the
  message (so it retries) if unreachable." For `notification-service`, the same: a failed lookup or a
  failed send both throw, so the message retries rather than sending a broken email or silently dropping
  it. `enrollment-service`'s own `CourseLookupClient` (R1) throws a `503` directly to the caller instead
  (it backs a synchronous HTTP request, not a message consumer) — the brief's explicit "fail closed with
  503 if unreachable."
- **Rationale**: every one of these three clients follows the same `spring.cloud.openfeign.circuitbreaker.
  group.enabled=true` + `resilience4j.timelimiter.*.cancel-running-future=true` configuration 004's own
  `checklists/success-criteria.md` recorded as the two real gotchas found there — both are set from the
  start this time, not rediscovered.
- **Implementation finding — test-only wiring for all three clients**: tests originally overrode
  `spring.cloud.openfeign.client.config.<name>.url` via `@DynamicPropertySource`, the pattern already
  working reliably for `payment-service`'s and `enrollment-service`'s own Feign clients (both called
  synchronously from an HTTP request thread). For `certificate-service`'s and `notification-service`'s
  listener-triggered calls, the identical property intermittently resolved to a dead `lb://` lookup
  (`No servers available for service: course-service`) instead of the stubbed URL. Fixed by giving each
  client's `@FeignClient` annotation its own `url = "${course-service.url:}"` (empty in production, so
  load-balancing is unchanged there) and having tests override that plain property directly instead —
  `FeignClientFactoryBean` then builds a non-load-balanced target outright, never touching Spring Cloud
  LoadBalancer. Every test class also needs this property non-empty even when it never calls the client
  (the Feign proxy is still constructed eagerly at context startup), so each service's test
  `application.yml` sets a harmless unreachable default (`http://localhost:1`) that real tests override.
- **Implementation finding — JVM fork reuse corrupts LoadBalancer/Resilience4j state across IT classes**:
  even with the fix above, certificate-service's and notification-service's `*ConsumerIT`/`*DownIT`/
  `DeadLetterIT` classes still failed intermittently — but only when run as part of the full module suite,
  never alone. Root-caused to Failsafe's default `reuseForks=true`: five-plus IT classes, each booting a
  full Spring context with its own Feign/LoadBalancer/Resilience4j beans, share one JVM process, and
  whatever global/static state those libraries keep (observed via `Observation.observe()` in every
  failing stack trace) degrades across contexts until a later class's very first listener-triggered Feign
  call either hangs past its `TimeLimiter` budget or never resolves a server instance at all — 100%
  reproducible with `reuseForks=true`, 100% gone with `reuseForks=false` (confirmed by toggling only that
  flag, nothing else, across three otherwise-identical runs). Fixed by setting
  `<reuseForks>false</reuseForks>` on `maven-failsafe-plugin` in both modules' own `pom.xml` — a fresh JVM
  per IT class costs wall-clock time but is the only mechanism that actually isolates it. Left
  `enrollment-service` on the shared-fork default since its own Feign calls are HTTP-request-triggered,
  not listener-triggered, and its suite passed repeatedly without this change.
- **Implementation finding — a fresh JVM still pays a one-time "cold start" tax**: separately from both
  fixes above, a service's very first real Feign call in a freshly-booted context (JIT warm-up, HTTP
  connection pool, Resilience4j/LoadBalancer bean initialization) measured ~5s locally even when
  everything else was healthy, against production's 3s `resilience4j.timelimiter` budget. Test profiles
  for `certificate-service`, `enrollment-service` and `notification-service` raise this to 15s (the
  production value is untouched); the WireMock "times out" contract-test fixtures that used a 10s fixed
  delay were bumped to 20s so they still exceed the new budget; and the new consumer/outage ITs' own
  `Awaitility` windows were widened to 20–30s so a slow-but-successful first call still has room to finish
  and be observed.

## R6. Three consumers, the same idempotent, three-retry-then-dead-letter discipline as 004

- **Decision**: `certificate-service` consumes `course.completed` on queue `certificate.q` (the brief's
  literal name), dead-lettering to `certificate.q.dlq`; `notification-service` consumes both
  `enrollment.created` and `certificate.issued` on queue `notification.q`, dead-lettering to
  `notification.q.dlq`. Both reuse the existing `learning.events.dlx` dead-letter exchange 004 already
  created (research.md R6 there) rather than each declaring their own — one shared "holding area"
  exchange, each consumer's own named DLQ bound to it under its own routing key, is simpler than three
  near-identical exchanges (Principle X). Both get the identical retry interceptor shape 004 built:
  three attempts, short fixed backoff, `RepublishMessageRecoverer`, `defaultRequeueRejected(false)`,
  `concurrentConsumers(3)` so one stuck message can never block the others (FR-024/FR-027).
  `certificate-service` deduplicates via its own `processed_events` table (the same mechanism
  `enrollment-service` already uses); `notification-service` deduplicates through the `notifications`
  table itself (R7's "two-phase" design) rather than a separate ledger, per the brief's explicit schema.
- **Rationale**: this is Principle V's consumer rule, already proven correct and tested in 004; nothing
  about a second and third consumer changes what correctness requires.

## R7. Two new transactional outboxes, one two-phase notification write

- **Decision**: `enrollment-service` and `certificate-service` each gain an `outbox_events` table and a
  `ScheduledExecutorService`-based publisher — **not** `@Scheduled`/`@EnableScheduling`, which
  `infra-tests`'s `NoWiringCodeTest` forbids outside `platform-common` (the exact mistake caught and
  fixed mid-build in 004; avoided from the start here), started in `@PostConstruct`/stopped in
  `@PreDestroy`, otherwise identical to `payment-service`'s own `OutboxPublisher`. `notification-service`
  writes its `notifications` row in two phases across two separate transactions, not one: phase one
  finds-or-inserts a `PENDING` row for the event's id (committed immediately, so it survives a crash
  before sending); phase two — outside any open transaction — resolves the email and course title and
  sends; phase three, a second small transaction, marks the row `SENT`. A redelivery that finds an
  existing `SENT` row acknowledges without resending (FR-022); one that finds an existing `PENDING` row
  (a prior attempt was interrupted after phase one but before phase three) retries phases two and three
  without re-inserting.
- **Rationale**: the brief's own literal step order — "insert the row (unique event_id), look up the
  email..., and the course title..., send..., then mark SENT" — is exactly a three-phase write, and only
  works as an idempotency guard if phase one survives a crash in phase two or three; a single
  `@Transactional` wrapping all three phases would roll the insert back right along with a failed send,
  defeating the purpose of recording the attempt at all.
- **Implementation finding (tasks.md T060)**: tasks.md describes `EnrollmentCreatedListener` and
  `CertificateIssuedListener` as each carrying their own `@RabbitListener(queues =
  RabbitTopologyConfig.QUEUE)` method. Registering both as independent listeners on the same queue would
  be a real bug, not a style choice: RabbitMQ hands each message on a queue to exactly one of its
  subscribed consumers, chosen without regard to routing key or payload, so roughly half of
  `certificate.issued`'s deliveries would land on `EnrollmentCreatedListener`'s consumer (and vice versa)
  and be permanently lost rather than ever reaching the listener that could actually process them.
  Implemented instead as a single `NotificationDispatchListener` carrying the one `@RabbitListener` on
  `notification.q`, which reads the envelope's own `eventType` and calls `handle(envelope)` on whichever
  of the two listeners (now plain, non-listener `@Component`s) owns that type — preserving "one handling
  method per routing key" while keeping the queue single-consumer. Mirrors R9's gateway route-predicate
  correction: implement what the queue topology actually requires, document the deviation from the
  brief's literal phrasing.

## R8. The verification endpoint's stricter rate limit is the gateway's first per-route limit

- **Decision**: `GET /api/v1/certificates/verify/{code}` keeps the existing global per-client-address
  limit **and** gets a second, stricter `TokenBucketRateLimiter` instance scoped to it alone, selected by
  a `metadata.rate-limit: strict` flag on its route (the same metadata-driven mechanism `roles` and
  `public` already use for other per-route behavior). `RateLimitGlobalFilter` is extended to read the
  matched route's metadata and consume from the stricter bucket when present, falling back to the
  existing single global bucket for every other route — additive to the filter, not a rewrite.
  `platform.gateway.rate-limit-strict.*` configures the stricter bucket's own capacity and refill rate,
  independently of the existing `platform.gateway.rate-limit.*`.
- **Rationale**: a public, unauthenticated, enumerable-by-guessing endpoint (a 16-character code from a
  fixed alphabet is a large space, but still guessable at scale without a tighter limit than the
  platform's general burst allowance) is exactly the kind of endpoint that needs its own, lower ceiling;
  reusing the existing `TokenBucketRateLimiter` class a second time, rather than introducing Redis-backed
  rate limiting or any new library, keeps this inside Principle X.
- **Alternatives considered**: Spring Cloud Gateway's built-in `RequestRateLimiter` filter factory.
  Rejected: it requires a Redis-backed `RateLimiter` implementation out of the box, and Redis is not in
  the constitution's approved stack; building a second instance of the project's own, already-approved,
  in-memory limiter needs no new technology at all.

## R9. Gateway routes: progress falls under the existing catch-all; two new routes for certificates

- **Decision**: the lesson-progress and progress-view endpoints need **no new gateway route** — they live
  under `/api/v1/enrollments/**`. **Implementation finding**: the existing `enrollments` route from 004
  was actually declared with an *exact*-path predicate (`Path=/api/v1/enrollments`, matching only the
  list endpoint), not a catch-all — it would not have matched
  `/api/v1/enrollments/{courseId}/lessons/{lessonId}/complete` or `.../progress` at all, 404ing every
  progress request before it ever reached enrollment-service. Discovered while wiring the route during
  implementation (T036) and fixed by widening the existing route's predicate to `Path=/api/v1/
  enrollments/**` — AntPathMatcher's `**` also matches the bare prefix, so the original `GET
  /api/v1/enrollments` list endpoint keeps working unchanged. Two new routes are added:
  `certificates-mine` (`GET /api/v1/certificates/mine`, protected, ownership enforced inside
  certificate-service) and `certificates-verify` (`GET /api/v1/certificates/verify/**`, public, the
  stricter rate limit from R8). Neither course-service's extended internal endpoint nor the new
  auth-service internal endpoint gets a gateway route — the same `/api/v1/internal/**`-is-never-routed
  precedent 003's R6 established, reused unchanged. `RealGatewayConfigTest` is extended to assert both new
  routes' shapes **and** that a request for either internal path still resolves to a plain 404 ("no route
  matches"), the exact test the brief asks for.
- **Rationale**: identical mechanism to every prior feature's gateway additions; no new routing concept
  is needed beyond R8's rate-limit metadata.
