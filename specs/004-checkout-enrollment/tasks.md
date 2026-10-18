# Tasks: Checkout and Enrollment

**Input**: Design documents from `specs/004-checkout-enrollment/` (spec.md, plan.md, research.md,
data-model.md, contracts/, quickstart.md)

**Tests**: Included and sequenced before their implementation tasks per story — constitution Principle
VI ("Test-First Quality") is NON-NEGOTIABLE for this project, not optional.

**Organization**: Tasks are grouped by user story from spec.md (US1–US6, in priority order) so each
story can be implemented, tested and demoed independently, exactly as 002 and 003 were.

## Format: `- [ ] [TaskID] [P?] [Story?] Description with file path`

- **[P]**: Can run in parallel with other [P] tasks in the same phase (different files, no ordering
  dependency between them)
- **[Story]**: Which user story the task belongs to (US1–US6); Setup, Foundational and Polish tasks
  carry no story label

## Path conventions

- `backend/payment-service/src/main/java/com/example/learnhub/payment/...`
- `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/...`
- `backend/course-service/src/main/java/com/example/learnhub/course/...` (additive change only)
- Test files mirror the same package under `src/test/java/...`

---

## Phase 1: Setup (project initialization)

- [X] T001 Add `<module>payment-service</module>` and `<module>enrollment-service</module>` entries to
      `backend/pom.xml`, in build order after `course-service` (research.md R8; no new dependency
      *version* needed — `spring-boot-starter-amqp` is managed by `spring-boot-starter-parent`,
      `spring-cloud-starter-openfeign` and `spring-cloud-starter-circuitbreaker-resilience4j` are
      managed by the already-imported `spring-cloud-dependencies` BOM).
- [X] T002 [P] Create `backend/payment-service/pom.xml`, mirroring `course-service/pom.xml`'s starter
      set exactly (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
      `spring-boot-starter-validation`, `flyway-core` + `flyway-database-postgresql`,
      `org.postgresql:postgresql`, `spring-cloud-starter-netflix-eureka-client`,
      `spring-cloud-starter-config`, `spring-retry` + `spring-boot-starter-aop`,
      `spring-boot-starter-actuator`, `platform-common`), plus `spring-boot-starter-amqp`,
      `spring-cloud-starter-openfeign`, `spring-cloud-starter-circuitbreaker-resilience4j` (research.md
      R8). No version on any dependency (constitution Principle IX).
- [X] T003 [P] Create `backend/enrollment-service/pom.xml` with the identical starter set from T002
      (research.md R8).
- [X] T004 [P] Create `backend/config-repo/payment-service.yml`, mirroring
      `backend/config-repo/course-service.yml`'s placeholder-comment pattern (no refreshable setting
      yet, exists so payment-service participates in the non-optional config-server import).
- [X] T005 [P] Create `backend/config-repo/enrollment-service.yml`, same pattern as T004.
- [X] T006 [P] Create `backend/payment-service/src/main/java/com/example/learnhub/payment/PaymentServiceApplication.java`
      (`@SpringBootApplication`, `@EnableFeignClients`) and
      `backend/payment-service/src/main/resources/application.yml` (port 9004; Eureka registration;
      non-optional `spring.config.import=configserver:` with fail-fast + retry; `spring.rabbitmq.*`
      connection properties sourced from environment variables; `spring.rabbitmq.publisher-confirm-type:
      correlated` (research.md R1); `feign.circuitbreaker.enabled: true`; a 2-second connect/read
      timeout for the default Feign client config (research.md R4)).
- [X] T007 [P] Create `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/EnrollmentServiceApplication.java`
      (`@SpringBootApplication`) and
      `backend/enrollment-service/src/main/resources/application.yml` (port 9003; same Eureka/config
      pattern as T006; `spring.rabbitmq.*` connection properties).
- [X] T008 Add a `payment-service` entry to `docker-compose.yml` (port `9004:9004`), mirroring the
      `course-service` block (build context, `SPRING_DATASOURCE_*` using `PAYMENT_DB_PASSWORD`,
      `depends_on` discovery-server/config-server `service_healthy` and db-init
      `service_completed_successfully`), plus `depends_on: rabbitmq: condition: service_healthy` and
      `SPRING_RABBITMQ_*` environment variables sourced from `RABBITMQ_DEFAULT_USER`/`RABBITMQ_DEFAULT_PASS`.
- [X] T009 Add an `enrollment-service` entry to `docker-compose.yml` (port `9003:9003`), same pattern as
      T008 with `ENROLLMENT_DB_PASSWORD`.
- [X] T010 [P] Add `"9003:enrollment-service"` and `"9004:payment-service"` to the `PORTS` array in
      `scripts/up.sh`; update the "reserved" comment to reflect only `9005-9007` remain unused.
- [X] T011 [P] Extend `reservedPortsAreNotUsed()` in
      `backend/infra-tests/src/test/java/com/example/learnhub/infra/ComposeFileTest.java` to exempt
      ports 9003 and 9004 (mirroring the existing 9001/9002 exemptions).

**Checkpoint**: Both new modules compile and register with Eureka; `docker compose up` brings up empty
shells of both services alongside everything else.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: Schema, domain entities, repositories, test infrastructure and messaging topology every
user story below depends on.

**⚠️ CRITICAL**: No user story's tests can be written meaningfully until this phase is complete.

- [X] T012 [P] `backend/payment-service/src/main/resources/db/migration/V1__create_payments.sql`:
      `payments` table — `id UUID PRIMARY KEY`, `user_id UUID NOT NULL`, `course_id UUID NOT NULL`,
      `amount_cents INTEGER NOT NULL CHECK (amount_cents >= 0)`, `currency VARCHAR(3) NOT NULL DEFAULT
      'USD'`, `status VARCHAR(20) NOT NULL`, `idempotency_key VARCHAR(255) NOT NULL`,
      `payment_token VARCHAR(255)` (nullable — null for a free checkout, data-model.md), `failure_reason
      TEXT` (nullable), `created_at TIMESTAMPTZ NOT NULL`, `updated_at TIMESTAMPTZ NOT NULL`; `UNIQUE
      (user_id, idempotency_key)` (research.md R9); a **partial** unique index, `CREATE UNIQUE INDEX
      payments_one_succeeded_per_user_course ON payments (user_id, course_id) WHERE status =
      'SUCCEEDED'` (research.md R3, FR-004, FR-015).
- [X] T013 [P] `backend/payment-service/src/main/resources/db/migration/V2__create_outbox_events.sql`:
      `outbox_events` table — `id UUID PRIMARY KEY`, `event_id UUID NOT NULL`, `payment_id UUID NOT NULL
      REFERENCES payments(id)`, `payload_json TEXT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL`,
      `published_at TIMESTAMPTZ` (nullable until published, data-model.md).
- [X] T014 [P] `backend/payment-service/src/main/java/com/example/learnhub/payment/domain/PaymentStatus.java`
      (enum `PENDING, SUCCEEDED, DECLINED, FAILED`, data-model.md) and
      `backend/payment-service/src/main/java/com/example/learnhub/payment/domain/Payment.java` (JPA
      entity matching T012's columns exactly, immutable `userId`/`courseId`/`idempotencyKey` after
      construction).
- [X] T015 [P] `backend/payment-service/src/main/java/com/example/learnhub/payment/domain/OutboxEvent.java`
      (JPA entity matching T013's columns).
- [X] T016 [P] `backend/payment-service/src/main/java/com/example/learnhub/payment/infrastructure/PaymentRepository.java`
      (`findByUserIdAndIdempotencyKey`, `existsByUserIdAndCourseIdAndStatus`, paginated
      `findByUserId`) and `.../infrastructure/OutboxEventRepository.java` (`findByPublishedAtIsNull`).
- [X] T017 [P] `backend/payment-service/src/test/java/com/example/learnhub/payment/support/AbstractPaymentServiceIT.java`:
      Testcontainers PostgreSQL **and** RabbitMQ, singleton-container pattern (plain static fields
      started once, never explicitly stopped — mirrors `course-service`'s `AbstractCourseServiceIT`,
      required so Spring's cached `ApplicationContext` is never invalidated by a container restart
      mid-suite).
- [X] T018 [P] `backend/enrollment-service/src/main/resources/db/migration/V1__create_enrollments.sql`:
      `enrollments` table — `id UUID PRIMARY KEY`, `user_id UUID NOT NULL`, `course_id UUID NOT NULL`,
      `enrolled_at TIMESTAMPTZ NOT NULL`, `source_payment_id UUID NOT NULL`; `UNIQUE (user_id,
      course_id)` (data-model.md, FR-015).
- [X] T019 [P] `backend/enrollment-service/src/main/resources/db/migration/V2__create_processed_events.sql`:
      `processed_events` table — `event_id UUID PRIMARY KEY`, `processed_at TIMESTAMPTZ NOT NULL`
      (data-model.md, research.md R6).
- [X] T020 [P] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/domain/Enrollment.java`
      (JPA entity matching T018's columns).
- [X] T021 [P] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/domain/ProcessedEvent.java`
      (JPA entity matching T019's columns).
- [X] T022 [P] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/infrastructure/EnrollmentRepository.java`
      (`existsByUserIdAndCourseId`, paginated `findByUserId`) and
      `.../infrastructure/ProcessedEventRepository.java` (`existsById`).
- [X] T023 [P] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/support/AbstractEnrollmentServiceIT.java`,
      same singleton Testcontainers Postgres+RabbitMQ pattern as T017.
- [X] T024 `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/infrastructure/RabbitTopologyConfig.java`:
      declare the durable queue `enrollment-service.payment-succeeded` bound to the existing
      `learning.events` topic exchange (`LearningEvents.EXCHANGE`) on routing key `payment.succeeded`;
      declare the dead-letter exchange `learning.events.dlx` and the durable DLQ
      `enrollment-service.payment-succeeded.dlq` bound to it (research.md R6). (Retry-interceptor and
      `RepublishMessageRecoverer` wiring is US6's job, T058 — this task only declares the topology beans
      so a basic listener can bind to them starting in US1.)
- [X] T025 Add four new routes to `backend/config-repo/api-gateway.yml`, after the existing
      `courses-manage` route, all protected (no `public: true`, no role metadata — research.md R7):
      `checkout` (`Path=/api/v1/checkouts` + `Method=POST` → `lb://payment-service`), `payments`
      (`Path=/api/v1/payments/**` → `lb://payment-service`), `enrollments` (`Path=/api/v1/enrollments`
      → `lb://enrollment-service`), and `course-content` (`Path=/api/v1/courses/*/content` →
      `lb://course-service`, given an `order` lower than `courses-manage`'s so the narrower,
      two-segment shape is considered first).
- [X] T026 [P] Extend `backend/api-gateway/src/test/java/com/example/learnhub/gateway/RealGatewayConfigTest.java`
      with assertions loading the real `api-gateway.yml` and checking: all four new routes require a
      token (no `public: true`), `course-content`'s order is lower than `courses-manage`'s, the
      `checkout` route's predicates include `Method=POST`, and no new route carries `roles` metadata
      (research.md R7).

**Checkpoint**: Both new services have a working database, domain model, and test harness; the
messaging topology exists; the gateway knows about every new path. No business behavior yet — ready for
US1.

---

## Phase 3: User Story 1 - Checkout and automatic enrollment (Priority: P1) 🎯 MVP

**Goal**: A logged-in user checks out a free or paid (simulated-success) course and ends up enrolled,
automatically, without further action, and can see that enrollment afterward.

**Independent Test**: Register a user, check out a free course, confirm immediate access; check out a
paid course with the always-succeeds token, confirm enrollment follows within 5 seconds and appears in
the user's own enrollment list.

### Tests for User Story 1

- [X] T027 [P] [US1] `backend/payment-service/src/test/java/com/example/learnhub/payment/CourseLookupClientContractTest.java`:
      WireMock-stubbed contract test for `CourseLookupClient` — a 200 with a `CourseSummaryView` body
      maps correctly; a 404 maps to "course not found"; a 5xx or an artificially slow (timeout-tripping)
      response falls back to a clear "course details unavailable" outcome rather than an unhandled
      exception (research.md R4; satisfies constitution Principle VI's Feign-contract-test rule).
- [X] T028 [P] [US1] `backend/payment-service/src/test/java/com/example/learnhub/payment/CheckoutIT.java`:
      a free course (`priceCents = 0`) checks out immediately as `SUCCEEDED` with `amountCents = 0` and
      no `paymentToken` required (FR-005); a paid course with `paymentToken = "sim-success"` checks out
      as `SUCCEEDED` with `amountCents` equal to the course's current catalog price (FR-006); the
      course's own instructor checking out their own course is refused with 403 (FR-003, US1 scenario
      4); a user with an existing `SUCCEEDED` payment for the course checking out again is refused with
      409 (FR-004, US1 scenario 5).
- [X] T029 [P] [US1] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/PaymentSucceededConsumerIT.java`:
      publishing a well-formed `payment.succeeded` event (built with the shared `EventEnvelope`) to
      `learning.events` results in exactly one `Enrollment` row for that `userId`/`courseId` appearing
      within a few seconds.
- [X] T030 [P] [US1] `backend/payment-service/src/test/java/com/example/learnhub/payment/OutboxPublisherIT.java`:
      after a checkout resolves to `SUCCEEDED`, its `outbox_events` row has `published_at` set within a
      few polling cycles, and the published message on `learning.events` deserializes into an
      `EventEnvelope` whose `payload` matches `payment-succeeded.event.schema.json`.
- [X] T031 [P] [US1] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/MyEnrollmentsIT.java`:
      `GET /api/v1/enrollments` returns only the caller's own enrollments, paginated (FR-018); a second
      user's call to the same endpoint never includes the first user's rows (US1 scenario 6).
- [X] T032 [P] [US1] `backend/payment-service/src/test/java/com/example/learnhub/payment/PaymentSucceededPayloadSerializationTest.java`:
      a fast unit test (no Testcontainers) round-tripping `PaymentSucceededPayload` to and from JSON and
      validating it against `contracts/payment-succeeded.event.schema.json`, mirroring `shared-events`'
      own `EventEnvelopeSerializationTest` pattern (constitution Principle VI's "every event MUST have a
      serialization test").

### Implementation for User Story 1

- [X] T033 [US1] `backend/payment-service/src/main/java/com/example/learnhub/payment/application/SimulatedPaymentProvider.java`:
      maps payment token `"sim-success"` to a `SUCCEEDED` outcome (research.md R2; decline/unavailable
      tokens are US2's job, T042).
- [X] T034 [US1] `backend/payment-service/src/main/java/com/example/learnhub/payment/infrastructure/CourseLookupClient.java`
      (`@FeignClient(name = "course-service", fallback = CourseLookupClientFallback.class)`, `GET
      /api/v1/internal/courses/{courseId}` returning the existing `CourseSummaryView` shape from
      specs/003-course-catalog) and `.../infrastructure/CourseLookupClientFallback.java` (research.md
      R4: an open circuit or timeout becomes a typed "unavailable" result, not a raw exception).
- [X] T035 [US1] `backend/payment-service/src/main/java/com/example/learnhub/payment/application/CheckoutService.java`
      (`@Transactional`): look up the course via `CourseLookupClient` (404 if missing, 503 if
      unavailable); refuse if `status != PUBLISHED` (409) or `instructorId == callerId` (403, FR-003);
      refuse if `PaymentRepository.existsByUserIdAndCourseIdAndStatus(userId, courseId, SUCCEEDED)`
      (409, FR-004); branch on `priceCents == 0` (free path, FR-005) vs. greater than zero (resolve via
      `SimulatedPaymentProvider`, FR-006); persist the `Payment` row and, only when it resolves to
      `SUCCEEDED`, an `OutboxEvent` row in the same transaction (research.md R1); catch a unique-
      constraint violation on commit and treat it as "already enrolled" (research.md R3's race case).
- [X] T036 [US1] `backend/payment-service/src/main/java/com/example/learnhub/payment/api/CheckoutController.java`
      (`POST /api/v1/checkouts`, reads `X-User-Id`/`X-User-Role` via `IdentityHeaders`) and
      `.../api/PaymentView.java` (record per contracts/payment-service.openapi.yaml, `message` set to
      "enrollment will follow shortly" only when `status == SUCCEEDED`, FR-016).
- [X] T037 [US1] `backend/payment-service/src/main/java/com/example/learnhub/payment/application/OutboxPublisher.java`:
      a `ScheduledExecutorService`-based poller started in `@PostConstruct`/stopped in `@PreDestroy`
      (not `@Scheduled`/`@EnableScheduling`, which `NoWiringCodeTest` forbids outside platform-common —
      research.md R1), selecting `OutboxEventRepository.findByPublishedAtIsNull`, publishing each as a
      `payment.succeeded` `EventEnvelope` via `RabbitTemplate` with publisher confirms, setting
      `publishedAt` only once the broker acknowledges.
- [X] T038 [US1] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/application/PaymentSucceededListener.java`
      (`@RabbitListener(queues = "enrollment-service.payment-succeeded")`, `@Transactional`):
      deserialize the `EventEnvelope`; if `ProcessedEventRepository.existsById(eventId)`, acknowledge
      and return (idempotent replay, research.md R6); otherwise insert the `Enrollment` row (from the
      payload's `userId`/`courseId`/`paymentId`) and the `ProcessedEvent` row together.
- [X] T039 [US1] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/api/EnrollmentController.java`
      (`GET /api/v1/enrollments`, scoped to `X-User-Id` via `IdentityHeaders`, default page size 20 max
      100 matching 003's pagination default, FR-018) and `.../api/EnrollmentView.java` (record per
      contracts/enrollment-service.openapi.yaml).
- [X] T040 [US1] Wire `spring.rabbitmq.*` connection properties and `RabbitTemplate`/`Jackson2JsonMessageConverter`
      beans in both services' `application.yml`/configuration classes so T037 and T038 have a working
      broker connection end to end.

**Checkpoint**: A free or simulated-success paid checkout results in an enrollment within 5 seconds,
visible in the user's own enrollment list, end to end through the gateway. This alone is a demoable MVP.

---

## Phase 4: User Story 2 - A declined or failed payment never grants access (Priority: P1)

**Goal**: The decline and provider-unavailable simulated outcomes behave correctly: clear reason, no
enrollment, ever.

**Independent Test**: Check out a paid course with the always-declines token and the
simulates-unavailable token; confirm no enrollment exists after either, with a clear reason in the
response.

### Tests for User Story 2

- [X] T041 [P] [US2] Extend `backend/payment-service/src/test/java/com/example/learnhub/payment/CheckoutIT.java`:
      `paymentToken = "sim-decline"` resolves to `DECLINED` with a non-null `failureReason` and creates
      no `outbox_events` row (FR-008); `paymentToken = "sim-unavailable"` resolves to `FAILED` with a
      different, clearly distinct `failureReason` and likewise creates no outbox row; a later checkout
      of the same course under a new idempotency key is still allowed after a decline (US2 scenario 3).

### Implementation for User Story 2

- [X] T042 [US2] Extend `SimulatedPaymentProvider.java` with `"sim-decline"` → `DECLINED` (failure
      reason: "the payment was declined") and `"sim-unavailable"` → `FAILED` (failure reason: "the
      payment provider was temporarily unavailable") (research.md R2); a token matching none of the
      three known values is a `422` validation error ("unrecognized payment token"), not a silent
      default.
- [X] T043 [US2] Extend `CheckoutService.java` so only a `SUCCEEDED` resolution writes an `OutboxEvent`
      row (FR-008 — a declined or failed payment never results in an enrollment).

**Checkpoint**: All three simulated-provider outcomes are correctly distinguished; US1 and US2 together
fully cover the checkout half of the feature.

---

## Phase 5: User Story 3 - Lesson content is gated by enrollment (Priority: P1)

**Goal**: `course-service`'s new reading endpoint shows full lesson content only to an enrolled student,
the owning instructor, or an administrator — and never on an unconfirmed basis.

**Independent Test**: With a directly-seeded enrollment, confirm the enrolled user can read content; a
different, non-enrolled user cannot; the owner and an admin always can.

### Tests for User Story 3

- [X] T044 [P] [US3] `backend/course-service/src/test/java/com/example/learnhub/course/EnrollmentLookupClientContractTest.java`:
      WireMock-stubbed contract test for the new `EnrollmentLookupClient` — `enrolled: true` and
      `enrolled: false` responses map correctly; a 5xx or timeout falls back to an "unconfirmed" outcome
      (research.md R4; constitution Principle VI).
- [X] T045 [P] [US3] Extend `backend/course-service/src/test/java/com/example/learnhub/course/LessonContentAccessIT.java`:
      `GET /api/v1/courses/{id}/content` returns 200 with lesson text for an enrolled student (FR-021);
      403 for a non-enrolled, non-owner, non-admin caller (FR-022); 200 for the owning instructor and
      for an administrator regardless of enrollment (FR-023); 503 when the enrollment check cannot be
      confirmed (stub `EnrollmentLookupClientFallback` behavior via WireMock fault injection, FR-024);
      404 for an unpublished course when the caller is not its owner/admin.
- [X] T046 [P] [US3] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/InternalLookupIT.java`:
      `GET /api/v1/internal/enrollments/{courseId}/{userId}` returns `enrolled: true` with a non-null
      `enrolledAt` for an existing enrollment, and `enrolled: false` with a null `enrolledAt` otherwise,
      for both a never-existing course id and a never-existing user id (no special-casing needed).

### Implementation for User Story 3

- [X] T047 [US3] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/api/InternalEnrollmentController.java`
      (`GET /api/v1/internal/enrollments/{courseId}/{userId}`, no gateway route targets this path,
      research.md R4).
- [X] T048 [US3] `backend/course-service/src/main/java/com/example/learnhub/course/infrastructure/EnrollmentLookupClient.java`
      (`@FeignClient(name = "enrollment-service", fallback = EnrollmentLookupClientFallback.class)`) and
      `.../infrastructure/EnrollmentLookupClientFallback.java` (an open circuit or timeout becomes a
      typed "unconfirmed" outcome, translated to 503 at the controller, FR-024).
- [X] T049 [US3] `backend/course-service/src/main/java/com/example/learnhub/course/application/CourseContentService.java`
      (`@Transactional(readOnly = true)`, mapping to a DTO inside the transaction per the established
      lazy-loading-avoidance pattern): 404 if the course is unknown, or unpublished and the caller is
      neither owner nor admin; 200 unconditionally for owner/admin (reusing `OwnershipGuard`); otherwise
      call `EnrollmentLookupClient` — 200 if enrolled, 403 if confirmed not enrolled, 503 if unconfirmed
      (research.md R5).
- [X] T050 [US3] `backend/course-service/src/main/java/com/example/learnhub/course/api/CourseContentController.java`
      (`GET /api/v1/courses/{id}/content`) and `.../api/CourseContentView.java` (record per
      contracts/course-service-content.openapi.yaml — no `status`/`version` fields, data-model.md).

**Checkpoint**: The three P1 stories (US1, US2, US3) together deliver the entire "login → pay (or not)
→ read content (or not)" journey from SC-001, SC-002 and SC-005.

---

## Phase 6: User Story 4 - Safe retries (Priority: P2)

**Goal**: Resending an identical checkout under the same idempotency key is always safe; reusing a key
with different content is refused; 20 simultaneous identical requests still produce exactly one result.

**Independent Test**: Send the same checkout body and idempotency key twice; confirm identical payment
ids. Reuse the key with a different course; confirm 409. Fire 20 identical concurrent requests; confirm
exactly one payment exists afterward.

### Tests for User Story 4

- [X] T051 [P] [US4] `backend/payment-service/src/test/java/com/example/learnhub/payment/IdempotencyIT.java`:
      resending the identical `{courseId, idempotencyKey, paymentToken}` body returns the same
      `PaymentView.id` both times with no second row created (FR-011); reusing the same
      `idempotencyKey` with a different `courseId` is refused with 409 and processes nothing (FR-012);
      firing 20 threads with the identical request and idempotency key concurrently results in exactly
      one `Payment` row and (after allowing time for the outbox/consumer) exactly one `Enrollment` row
      (SC-003).

### Implementation for User Story 4

- [X] T052 [US4] Extend `CheckoutService.java`: before any course lookup, query
      `PaymentRepository.findByUserIdAndIdempotencyKey`; an exact match on `courseId` and
      `paymentToken` short-circuits by returning the stored row unchanged (FR-011, no second provider or
      course-lookup call); a mismatch on either field is refused with 409 before any further processing
      (FR-012); a unique-constraint violation caught at commit time (the T035 race case) is resolved by
      re-reading the row that won, rather than surfacing the raw database error (research.md R9).

**Checkpoint**: Checkout is now safe to retry under any network condition, and concurrency is proven,
not assumed.

---

## Phase 7: User Story 5 - Payment history (Priority: P2)

**Goal**: A user can list their own payments and check one by id; never anyone else's.

**Independent Test**: As a user with payments, list history and confirm it is exactly their own; a
second user's history never includes the first user's payments; a payment id belonging to someone else
returns 404.

### Tests for User Story 5

- [X] T053 [P] [US5] `backend/payment-service/src/test/java/com/example/learnhub/payment/PaymentHistoryIT.java`:
      `GET /api/v1/payments` returns only the caller's own payments, paginated (FR-019); a second user's
      call to the same endpoint never includes the first user's rows (FR-020); `GET
      /api/v1/payments/{id}` returns 200 for the owner and an identical 404 for a non-owner or an
      unknown id (FR-017, FR-020).

### Implementation for User Story 5

- [X] T054 [US5] `backend/payment-service/src/main/java/com/example/learnhub/payment/api/PaymentController.java`
      (`GET /api/v1/payments` using `PaymentRepository.findByUserId` scoped to `X-User-Id`, default
      page size 20 max 100 matching 003's established pagination default; `GET /api/v1/payments/{id}`
      returning 404 — not 403 — when the id exists but belongs to someone else, the same
      identical-response pattern 003 uses for draft/archived/unknown courses).

**Checkpoint**: All P1/P2 stories are complete; the feature is fully usable end to end.

---

## Phase 8: User Story 6 - Reliable enrollment under failure (Priority: P3)

**Goal**: A payment is never lost to a broker outage or an enrollment-service outage; a consumer failure
is retried three times with backoff and then dead-lettered, without blocking other messages.

**Independent Test**: Succeed a payment while enrollment-service (or the broker) is unreachable; confirm
exactly one enrollment appears once it recovers. Send a malformed message; confirm it lands in the DLQ
without delaying a subsequent valid message.

### Tests for User Story 6

- [X] T055 [P] [US6] Extend `backend/payment-service/src/test/java/com/example/learnhub/payment/OutboxPublisherIT.java`
      for the broker-outage case: with the RabbitMQ Testcontainer paused/stopped at the moment a
      checkout succeeds, the `outbox_events` row stays unpublished; once the broker is available again,
      the next poll cycle publishes it and sets `publishedAt` (FR-025).
- [X] T056 [P] [US6] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/EnrollmentServiceDownIT.java`
      for the consumer-outage case (SC-004 specifically): with `PaymentSucceededListener`'s container
      stopped via `RabbitListenerEndpointRegistry.stop()`, a `payment.succeeded` event published during
      the outage sits durably queued and is not lost; starting the container again results in exactly
      one `Enrollment` for that payment.
- [X] T057 [P] [US6] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/DeadLetterIT.java`:
      a message that is not valid JSON (malformed) is retried and then lands on
      `enrollment-service.payment-succeeded.dlq` after exactly three attempts (FR-026); a well-formed
      event published immediately after a malformed one is still processed into an `Enrollment` without
      waiting for the malformed message's retries to exhaust (FR-027, no head-of-line blocking).

### Implementation for User Story 6

- [X] T058 [US6] Configure a stateless retry interceptor (three attempts, short backoff) on the
      `SimpleRabbitListenerContainerFactory` backing `PaymentSucceededListener`, with
      `defaultRequeueRejected(false)` and a `RepublishMessageRecoverer` targeting
      `learning.events.dlx`/`enrollment-service.payment-succeeded.dlq` once attempts are exhausted
      (research.md R6), added to `RabbitTopologyConfig.java` (T024).

**Checkpoint**: All six user stories are complete and independently verified.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T059 [P] Update `README.md`: port table rows for `enrollment-service` (9003) and `payment-service`
      (9004) from "reserved, not yet built" to built; a new "## Checkout and enrollment" section.
- [X] T060 [P] Update `HOWTORUN.md`: startup output sample, component table, new walkthrough sections
      mirroring quickstart.md's eight steps, test-count update, troubleshooting rows for a 409
      idempotency-key conflict and a 503 "enrollment status unavailable" response, a "See also" entry
      for 004.
- [X] T061 Write `specs/004-checkout-enrollment/checklists/success-criteria.md`: measured status of
      SC-001 through SC-007, following the same format 002 and 003 used. SC-006 (≥95%
      enrollment-within-5-seconds) is proven only via single-trial tests (T029, T031), not a volume run
      — record it as Outstanding rather than Met unless a dedicated volume run is added, the same
      honest-accounting precedent 002 and 003 used for their own unmeasured success criteria.
- [X] T062 Full platform verification: `docker compose down -v`, `scripts/up.sh`, run every step of
      quickstart.md by hand against the live stack (including the enrollment-service-stop/restart
      step), then `scripts/smoke-test.sh` to confirm SC-007 (no regression to platform-foundation,
      auth, or course-catalog behavior).
- [X] T063 `mvn -f backend/pom.xml verify` for the full reactor (from WSL, Docker available for
      Testcontainers; use `set -o pipefail` and check `${PIPESTATUS[0]}` rather than piping to `tail`,
      per this project's own prior mistake) — confirm every new and existing test is green.

---

## Dependencies and execution order

- **Setup (T001–T011)**: No dependencies; T001–T007 can run in parallel with each other; T008/T009
  depend on T002/T003/T006/T007 existing conceptually (same files are fine as sequential edits);
  T010/T011 are independent of everything else in this phase.
- **Foundational (T012–T026)**: Depends on Setup. Blocks every user story. T012–T017 (payment-service
  schema/domain/repos/test-support) and T018–T023 (enrollment-service equivalents) are two independent
  parallel tracks; T024 depends on T018–T023 existing (needs the module to compile); T025 is independent
  of T012–T024; T026 depends on T025.
- **User Story 1 (T027–T040)**: Depends on Foundational. T027–T032 (tests) can be written in parallel
  with each other; T033–T036 depend on T027/T028 existing (TDD: see them fail first) and on each other
  in sequence (provider → Feign client → service → controller); T037 depends on T030; T038 depends on
  T024 and T029; T039 depends on T031; T040 can run alongside T033–T039.
- **User Story 2 (T041–T043)**: Depends on User Story 1 (extends the same `CheckoutIT` and
  `SimulatedPaymentProvider`/`CheckoutService` files).
- **User Story 3 (T044–T050)**: Depends on Foundational only (not on US1/US2) for its own new files, but
  its IT (T045) exercises the same `course-service` test file `LessonContentAccessIT` that already
  exists from 003 — safe to implement in parallel with US1/US2 since the files touched are disjoint
  (`course-service` vs. `payment-service`/`enrollment-service`).
- **User Story 4 (T051–T052)**: Depends on User Story 1 (extends `CheckoutService.java` and
  `CheckoutIT.java`).
- **User Story 5 (T053–T054)**: Depends on User Story 1 (`Payment` rows must exist to list); otherwise
  independent of US2–US4.
- **User Story 6 (T055–T058)**: Depends on User Story 1 (`OutboxPublisher`, `PaymentSucceededListener`,
  `EnrollmentController`, `RabbitTopologyConfig` must already exist).
- **Polish (T059–T063)**: Depends on every user story being complete.

## Parallel execution examples

```text
# Foundational phase, two independent service tracks at once:
T012, T013, T014, T015, T016, T017   (payment-service schema/domain/repos/test-support)
T018, T019, T020, T021, T022, T023   (enrollment-service equivalents, fully parallel with the above)

# User Story 1 tests, all in parallel before any implementation task starts:
T027, T028, T029, T030, T031, T032

# User Story 3 can proceed in parallel with User Story 1/2 (disjoint files):
T044, T045, T046  ->  T047, T048, T049, T050
```

## Implementation strategy

**MVP = User Story 1 only**: free and simulated-success paid checkout resulting in automatic
enrollment, visible in the user's own enrollment list, end to end through the gateway. Demoable and
independently valuable on its own, exactly as 002's and 003's MVPs were.

**Incremental delivery after that**: US2 (financial safety) and US3 (access control) are the other two
P1s — do these next, in either order, since they touch different files. Then US4 (retries) and US5
(history), independent of each other. US6 (reliability hardening) last, since it is a non-functional
property layered on top of behavior US1 already established.
