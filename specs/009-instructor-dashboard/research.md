# Research: Instructor Dashboard

All unknowns are resolved; no NEEDS CLARIFICATION remain. Names below were checked in source.

## R1. Resolve course ownership at query time (not stored)

**Decision**: analytics-service stores counters by `course_id` only. Who owns a course is asked of
course-service on each request (Feign): the new list endpoint for the overview, and the existing
internal detail (`instructorId`) for a single course.
**Rationale**: constitution XIV forbids Feign during event handling, and ownership can change; a
stored owner would go stale and could show numbers to the wrong person (spec US5). Failing closed on
lookup failure is then simple: no ownership answer, no numbers.
**Alternatives**: copy `instructorId` into stats rows from events (rejected: not in any event payload,
stale on change); sync a local ownership table (rejected: new service-to-service coupling and a
consistency problem for a 200-row lookup).

## R2. UTC bucketing from the envelope

**Decision**: the day of a counter is `envelope.occurredAt` converted to UTC date. Payment events also
carry an inner `occurredAt`; the envelope's is used for all four types so one rule applies.
**Rationale**: spec FR-018; a single clock source avoids double-bucketing and keeps replays on the same
day. The series is generated as `today(UTC) - (days-1) .. today(UTC)` with missing days zero-filled.

## R3. No personal data

**Decision**: payloads are read for `courseId`, `amountCents` and `occurredAt` only. `userId`,
`paymentId`, `enrollmentId` and `certificateId` are never persisted; `processed_events` stores only
`event_id` and `processed_at`. Logs never print payload bodies.
**Rationale**: FR-011, SC-005, constitution XIV. Duplicate detection needs only the envelope `eventId`.

## R4. Idempotent, commutative ingest

**Decision**: one transaction per message: `INSERT INTO processed_events ... ON CONFLICT DO NOTHING`;
zero rows affected means a duplicate, so acknowledge and skip; otherwise
`INSERT INTO course_daily_stats ... ON CONFLICT (course_id, day) DO UPDATE SET col = col + :delta`
(revenue adds `amountCents`). Increments are commutative and atomic in SQL, so ordering and concurrency
(20 concurrent events) give exact sums without locking.
**Alternatives**: read-modify-write in JPA (rejected: lost updates under concurrency).

## R5. Messaging topology (mirrors notification-service)

**Decision**: queue `analytics.q` bound to `payment.succeeded`, `enrollment.created`,
`course.completed`, `certificate.issued` on `learning.events`; DLQ `analytics.q.dlq` on
`learning.events.dlx`, bound with the same four keys. One `@RabbitListener` dispatches on envelope
`type` (notification-service learned that two listeners on one queue lose messages). Retry: three
attempts with the fixed 1 s backoff and a `RepublishMessageRecoverer` with empty routing-key prefix,
exactly as `RabbitTopologyConfig` in notification-service. Malformed messages (unreadable envelope or
payload, missing `courseId`) throw `AmqpRejectAndDontRequeueException`, which the retry policy is
configured not to retry (the same bytes would fail the same way); the same recoverer then republishes
them to the dead-letter exchange, so they reach `analytics.q.dlq` straight away instead of after three
attempts. `analytics.q` is also declared with `x-dead-letter-exchange=learning.events.dlx` as a backstop for
any rejection that does not pass through the recoverer. Three consumer threads, so a retrying message never blocks others.
`payment.succeeded` has no constant in `LearningEvents`, so analytics defines its own.
**Verified in implementation**: `MalformedMessageIT` proves unreadable, incomplete and mismatched messages all reach the DLQ and never block valid ones.

## R6. Query layer and caller identity

**Decision**: the gateway enforces roles `INSTRUCTOR` and `ADMINISTRATOR` and injects
`X-User-Id` / `X-User-Role` (`IdentityHeaders`). The service additionally rejects any other role with
403, because services trust headers and published ports can bypass the gateway (run-1 lead 2).
Instructor summary = sum of stats rows whose `course_id` is in the course-service list (all statuses,
up to 200, newest first). `ADMINISTRATOR` summary and list use SQL aggregates with no ownership lookup;
the admin list is ordered by revenue descending, paged, size capped at 200. A single-course request
checks `instructorId` via the existing internal detail; non-owner non-admin and unknown course both
return an identical 404 problem body.
**Limit**: an instructor owning more than 200 courses sees the newest 200. Because course-service returns at most 200, a list of exactly 200 may be complete or cut off, so `truncated` is set when 200 items come back and the UI shows a notice (FR-023).

## R7. Failure behaviour

**Decision**: the Feign client uses a `fallbackFactory` that throws `OwnershipUnavailableException`
(a 404 on the single-course lookup becomes `CourseNotFoundException`, the one non-failure answer); a
handler maps it (and any timeout, connection error or open circuit) to 503 problem details with
`correlationId` and no figures. The circuit breaker (window 10, open at 50% failures after 5 calls,
10 s open, 3 trial calls) and the 3 s time limit are set in `CourseServiceResilienceConfig` as the
factory default, because `application.yml` instance names such as `course-service` do not match the
ids Feign gives each method, so the yaml alone would silently leave a 1 s limit and a window of 100 calls
(`OwnershipUnavailableIT` showed the circuit never opening before this was set). Each Feign method has
its own circuit. The correlation interceptor is `platform-common`'s `CorrelationFeignInterceptor`.

## R8. Course-service addition

**Decision**: `GET /api/v1/internal/courses?instructorId={uuid}` returns up to 200
`{courseId, status, title}` (all statuses, `createdAt` descending) via a new
`CourseRepository` method. Missing or malformed `instructorId` returns 400. No gateway route exists;
the existing `noRouteEverTargetsInternalPaths` test already fails on any predicate containing
`/internal/`, and gets an explicit assertion for the list path. The contract is a new addendum file
under this spec, and a provider-side test checks the real response against it.

## R9. Frontend

**Decision**: `RequireRole` wraps `RequireAuth` and reads `useAuth().user.role` (UX only; backend is
the authority). Overview merges `/api/v1/my/courses` (titles, status) with the stats list, zero-fills,
sorts client-side. For administrators it shows platform totals and top courses by revenue, resolving
titles with the public `GET /api/v1/courses/{id}` and a short id on 404. Charts are an inline SVG bar
chart plus a `sr-only` / toggleable table; the funnel is CSS-width bars; money via
`Intl.NumberFormat("en-US", {style:"currency", currency:"USD"})` from whole cents. "Last updated" is
the response's `generatedAt` time, refreshed by the Refresh button. Server text is rendered as text.

## R10. Provisioning and wiring

**Decision**: add `analytics` to `SERVICES` in `db-init/provision.sh`; `ANALYTICS_DB_PASSWORD` to
`.env.example` and `REQUIRED_SETTINGS` in `scripts/up.sh`; `9008:analytics-service` to `PORTS`; module
to `backend/pom.xml`; `COPY analytics-service/pom.xml` to the Dockerfile; compose service after
`db-init`, `rabbitmq`, `config-server` (and discovery) healthy, with the same environment shape as
notification-service; README port table row.
