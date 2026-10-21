# Research: Review Service

Patterns below were confirmed by reading the certificate-service and course-service/enrollment-service
source (the closest existing analogues: certificate-service for the per-service Spring Boot skeleton,
course-service/enrollment-service for the Feign lookup-and-fallback pattern) rather than re-derived from
scratch.

## R1 — Does review-service need RabbitMQ / an outbox at all?

**Decision**: No. review-service has no RabbitMQ listener and no outbox publisher.

**Rationale**: Every existing async publisher in the platform exists because a *different* service
consumes the event (certificate-service listens to `course.completed`; notification-service listens to
several). This spec's own aggregate rating (FR-009, FR-010) is consumed only by review-service's own
public read endpoint — no other bounded context needs to react to a review being created, edited or
deleted. Constitution Principle X ("the simplest design that meets the spec MUST be chosen... no extra
service, library or technology may be introduced without amending this constitution") rules out adding a
RabbitMQ topology, outbox table and poller purely to mirror certificate-service's shape when nothing
downstream reads the event. The aggregate is computed directly from review-service's own `reviews` table
with a single indexed `AVG`/`COUNT` query, which is both simpler and has zero eventual-consistency window
(SC-003 — zero drift — is trivially satisfied because the aggregate query and the review table are the
same source of truth in the same transaction boundary).

**Alternatives considered**:
- Outbox + `review.created`/`review.updated`/`review.deleted` events, mirroring certificate-service,
  for a future consumer (e.g. course-service caching a rating on its own Course row for catalog
  sorting). Rejected for *this* spec: no current requirement needs it, and Principle V's event-envelope
  rule plus Principle II's "no shared library carries domain logic" would still require a real consumer
  to justify adding `review.*` routing-key constants. If a later spec needs a cached rating on
  course-service, that spec can add the outbox then, following the exact certificate-service template.

## R2 — How does review-service verify enrollment?

**Decision**: Reuse enrollment-service's existing internal endpoint, unchanged:
`GET /api/v1/internal/enrollments/{courseId}/{userId}` → `EnrollmentLookupResponse(boolean enrolled,
Instant enrolledAt)`, called via a new Feign client `EnrollmentLookupClient` in review-service,
byte-for-byte the same shape as course-service's own `EnrollmentLookupClient`
(`backend/course-service/.../infrastructure/EnrollmentLookupClient.java`): `@FeignClient(name =
"enrollment-service", fallback = ...)`, called through `lb://enrollment-service`, never through the
gateway.

**Rationale**: The endpoint already returns exactly what's needed (a boolean, not a status enum) and is
already proven safe to call from another service. No change to enrollment-service is needed. The spec's
"active or completed enrollment" language collapses to that single `enrolled` boolean — enrollment-service
doesn't expose a finer-grained status via this endpoint, and there's no requirement in this spec that
needs one.

**Alternatives considered**: Adding a new, review-specific enrollment-status endpoint. Rejected — the
existing boolean already satisfies every acceptance scenario; adding a second internal contract merely to
distinguish "active" vs "completed" would be unused complexity (Principle X).

**Fallback behavior**: Per Principle V, the Feign client MUST have a timeout, a Resilience4j circuit
breaker and a fallback — identical `resilience4j.circuitbreaker.instances.enrollment-service` /
`resilience4j.timelimiter.instances.enrollment-service` block as certificate-service's
`course-service` instance config, renamed to the `enrollment-service` target. When the circuit is open or
the call times out, the fallback MUST NOT silently treat the student as enrolled (that would let an
unenrolled student post a review); it throws a 503-mapped `EnrollmentLookupUnavailableException` so the
write is rejected rather than silently approved, matching certificate-service's
`CourseLookupClientFallbackFactory` pattern of surfacing lookup failure rather than hiding it.

## R3 — How is "one review per (student, course)" enforced?

**Decision**: A unique constraint on `(user_id, course_id)` in the `reviews` table (Flyway migration),
backed by a repository `findByUserIdAndCourseId` lookup in the service layer: if found, update that row;
if not, insert a new one. This is the same upsert-by-natural-key shape already used by
enrollment-service's own `findByUserIdAndCourseId` (`EnrollmentRepository`), just applied to reviews.

**Alternatives considered**: Application-level-only uniqueness check (SELECT-then-INSERT with no DB
constraint). Rejected — a race between two concurrent submissions from the same student could create two
rows; the DB constraint is the only mechanism that is correct under concurrency, and it's one line in a
migration.

## R4 — Basic profanity/length validation

**Decision**: A small, static, in-process blocked-word list plus a maximum comment length (500
characters), enforced in the application service before persisting — a plain Java validator, not a new
dependency. Rejected submissions return a 400 problem-details response naming which rule failed (too
long, or contains a blocked term) per constitution Principle VIII.

**Rationale**: The spec explicitly scopes this to "simple profanity/length validation only (no ML
moderation)" and explicitly excludes any moderation workflow. A hardcoded list reviewable in code review
is the simplest thing that satisfies FR-007 without adding a library (Principle X: "no extra service,
library or technology... without amending this constitution").

**Alternatives considered**: A third-party profanity-filter library. Rejected — not on the constitution's
approved dependency list, and the spec only asks for "simple" validation.

## R5 — REST surface and gateway routes

**Decision**:
- `POST /api/v1/reviews/courses/{courseId}` — create-or-update the caller's review for that course
  (idempotent upsert per R3). Protected (JWT + `X-User-Id`).
- `PUT /api/v1/reviews/{reviewId}` — edit the caller's own review (404 if the review doesn't belong to
  the caller, same "identical 404 for someone else's resource" convention as payment-service). Protected.
- `DELETE /api/v1/reviews/{reviewId}` — delete the caller's own review. Protected.
- `GET /api/v1/reviews/courses/{courseId}` — paginated list of a course's active reviews. Public.
- `GET /api/v1/reviews/courses/{courseId}/summary` — average rating + review count for a course. Public.

Gateway routes (`backend/config-repo/api-gateway.yml`), following the existing
`certificates-mine`/`certificates-verify` split of a protected vs. public route pair:

```yaml
- id: reviews-write
  uri: lb://review-service
  order: 21
  predicates:
    - Path=/api/v1/reviews/**
    - Method=POST,PUT,DELETE
  # protected by default (no metadata.public) — JWT required

- id: reviews-read
  uri: lb://review-service
  order: 22
  predicates:
    - Path=/api/v1/reviews/**
    - Method=GET
  metadata:
    public: true
```

**Rationale**: Matches constitution Principle IV (public routes listed explicitly, everything else
protected by default) and reuses the existing method-based route-splitting idiom instead of inventing a
new one.

## R6 — Module wiring

**Decision**: `review-service` is added to `backend/pom.xml`'s `<modules>` (already named, port 9006
already reserved in the constitution's port map) with the identical dependency set as
certificate-service's `pom.xml` minus `spring-boot-starter-amqp` (R1: no RabbitMQ) and its `spring-rabbit-test`
test dependency; `backend/config-repo/review-service.yml` is a minimal config-import-only file like
`certificate-service.yml`; `docker-compose.yml` gets a `review-service` block identical in shape to the
`certificate-service` block (own Postgres database `review`, own `REVIEW_DB_PASSWORD` secret, port
`9006:9006`, health check on `/actuator/health/readiness`).
