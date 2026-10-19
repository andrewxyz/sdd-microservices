# Tasks: Lesson Progress, Certificates, and Email Notifications

**Input**: Design documents from `specs/005-progress-certificates-notifications/` (spec.md, plan.md,
research.md, data-model.md, contracts/, quickstart.md)

**Tests**: Included and sequenced before their implementation tasks per story — constitution Principle
VI ("Test-First Quality") is NON-NEGOTIABLE for this project, not optional.

**Organization**: Tasks are grouped by user story from spec.md (US1–US7, in priority order), the same
discipline 002/003/004 used, so each story can be implemented, tested and demoed independently.

## Format: `- [ ] [TaskID] [P?] [Story?] Description with file path`

- **[P]**: Can run in parallel with other [P] tasks in the same phase (different files, no ordering
  dependency between them)
- **[Story]**: Which user story the task belongs to (US1–US7); Setup, Foundational and Polish tasks
  carry no story label

## Path conventions

- `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/...` (existing module,
  additive)
- `backend/certificate-service/src/main/java/com/example/learnhub/certificate/...` (new module)
- `backend/notification-service/src/main/java/com/example/learnhub/notification/...` (new module)
- `backend/course-service/...` and `backend/auth-service/...` (existing modules, one additive change each)
- `backend/shared-events/src/main/java/com/example/learnhub/events/...` (existing module, additive)
- Test files mirror the same package under `src/test/java/...`

---

## Phase 1: Setup (project initialization)

- [X] T001 Add `<module>certificate-service</module>` and `<module>notification-service</module>` entries
      to `backend/pom.xml`, after `enrollment-service` (research.md; no new dependency *version* needed —
      every starter either is already managed by `spring-boot-starter-parent`/the imported Spring Cloud
      BOM, or was already added for `payment-service`/`course-service` in 004).
- [X] T002 [P] Create `backend/certificate-service/pom.xml`, mirroring `payment-service/pom.xml`'s
      starter set exactly (web, data-jpa, validation, amqp, flyway-core + flyway-database-postgresql,
      postgresql, eureka-client, config, openfeign, circuitbreaker-resilience4j, spring-retry +
      aop, actuator, platform-common, shared-events; test: spring-boot-starter-test, spring-rabbit-test,
      testcontainers junit-jupiter/postgresql/rabbitmq, wiremock-standalone).
- [X] T003 [P] Create `backend/notification-service/pom.xml`, the same starter set as T002 plus
      `spring-boot-starter-mail` (new to the platform, for its one outbound SMTP connection to Mailpit,
      research.md Technical Context). No `GenericContainer`-specific dependency needed — it ships inside
      the already-declared `org.testcontainers:postgresql`/`rabbitmq` test artifacts (research.md
      Testing: a Testcontainers `GenericContainer` running the same, already-approved `axllent/mailpit`
      image is reused for IT tests, not a new technology).
- [X] T004 [P] Add `spring-cloud-starter-openfeign`, `spring-cloud-starter-circuitbreaker-resilience4j`
      and `org.wiremock:wiremock-standalone` (test scope) to `backend/enrollment-service/pom.xml` —
      its first outbound Feign client (research.md R1, R3).
- [X] T005 [P] Create `backend/config-repo/certificate-service.yml`, mirroring
      `backend/config-repo/payment-service.yml`'s placeholder-comment pattern.
- [X] T006 [P] Create `backend/config-repo/notification-service.yml`: the placeholder-comment pattern,
      plus the non-secret SMTP settings (`spring.mail.host: mailpit`, `spring.mail.port: 1025`, no
      auth/TLS — Mailpit needs neither) as the one refreshable setting this service actually has.
- [X] T007 [P] Create `backend/certificate-service/src/main/java/com/example/learnhub/certificate/CertificateServiceApplication.java`
      (`@SpringBootApplication`, `@EnableFeignClients`) and
      `backend/certificate-service/src/main/resources/application.yml` (port 9005; Eureka/config
      bootstrap identical to `payment-service`'s; `spring.cloud.openfeign.circuitbreaker.enabled: true`
      **and** `.group.enabled: true`; `resilience4j.timelimiter.instances.course-service.cancel-running-future:
      true` — both set from the start this time, the two gotchas 004's `checklists/success-criteria.md`
      recorded, research.md R5).
- [X] T008 [P] Create `backend/notification-service/src/main/java/com/example/learnhub/notification/NotificationServiceApplication.java`
      (`@SpringBootApplication`, `@EnableFeignClients`) and
      `backend/notification-service/src/main/resources/application.yml` (port 9007; same Eureka/config
      pattern; two Feign circuit-breaker instance configs — `auth-service` and `course-service` — both
      with `group.enabled: true` and `cancel-running-future: true` from the start, research.md R5).
- [X] T009 Add `certificate-service` (port `9005:9005`) and `notification-service` (port `9007:9007`)
      entries to `docker-compose.yml`, mirroring `payment-service`'s block (build args, `SPRING_DATASOURCE_*`
      using `CERTIFICATE_DB_PASSWORD`/`NOTIFICATION_DB_PASSWORD`, `SPRING_RABBITMQ_*`, `depends_on`
      discovery-server/config-server `service_healthy`, db-init `service_completed_successfully`,
      rabbitmq `service_healthy`).
- [X] T010 [P] Add `backend/certificate-service/pom.xml` and `backend/notification-service/pom.xml` to
      the dependency-cache-warming `COPY` list in `backend/docker/Dockerfile`.
- [X] T011 [P] Add `"9005:certificate-service"` and `"9007:notification-service"` to the `PORTS` array in
      `scripts/up.sh`; update the "reserved" comment to reflect only `9006` (review-service) remains
      unused.
- [X] T012 [P] Extend `reservedPortsAreNotUsed()` in
      `backend/infra-tests/src/test/java/com/example/learnhub/infra/ComposeFileTest.java` to exempt
      ports 9005 and 9007 for `certificate-service`/`notification-service`.

**Checkpoint**: Both new modules compile and register with Eureka; `docker compose up` brings up empty
shells of both services alongside everything else.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: Shared event contracts, the two extended internal lookups, schema/domain/repositories for
all three touched-or-new services, messaging topology, and gateway routing — everything every user story
below depends on.

**⚠️ CRITICAL**: No user story's tests can be written meaningfully until this phase is complete.

- [X] T013 [P] Add `COURSE_COMPLETED = "course.completed"`, `CERTIFICATE_ISSUED = "certificate.issued"`
      and `ENROLLMENT_CREATED = "enrollment.created"` constants to
      `backend/shared-events/src/main/java/com/example/learnhub/events/LearningEvents.java`
      (research.md R1/R2; the deliberate, brief-instructed exception to Principle II recorded in
      plan.md's Constitution Check — a data contract, not domain logic).
- [X] T014 [P] `backend/shared-events/src/main/java/com/example/learnhub/events/CourseCompletedPayload.java`:
      record with `enrollmentId`, `userId`, `courseId`, `completedAt` (all `UUID`/`Instant` as
      appropriate, data-model.md).
- [X] T015 [P] `backend/shared-events/src/main/java/com/example/learnhub/events/CertificateIssuedPayload.java`:
      record with `certificateId`, `userId`, `courseId`, `verificationCode` (String), `issuedAt`
      (data-model.md — deliberately no course title, research.md R3/R5).
- [X] T016 [P] `backend/shared-events/src/main/java/com/example/learnhub/events/EnrollmentCreatedPayload.java`:
      record with `enrollmentId`, `userId`, `courseId`, `enrolledAt` (research.md R2, data-model.md).
- [X] T017 [P] `backend/shared-events/src/test/java/com/example/learnhub/events/NewPayloadsSerializationTest.java`:
      a fast unit test (no Testcontainers) round-tripping each of the three new payloads to/from JSON via
      `EventMapper.create()` and validating each against its own
      `contracts/*.event.schema.json`, mirroring 004's `PaymentSucceededPayloadSerializationTest`
      pattern (constitution Principle VI).
- [X] T018 `backend/auth-service/src/main/java/com/example/learnhub/auth/api/AccountSummaryView.java`
      (record: `id`, `email` only, data-model.md — no password hash, role, or timestamp),
      `.../application/AccountLookupService.java` (a thin service wrapping the existing
      `AccountRepository.findById`, already inherited from `JpaRepository` — no new repository method —
      matching the `api`/`application`/`domain`/`infrastructure` layering every other internal lookup in
      this codebase follows, e.g. course-service's `CourseLookupService`), and
      `.../api/InternalAccountController.java` (`GET /api/v1/internal/accounts/{accountId}`, no gateway
      route targets this path, research.md R4, R9), calling `AccountLookupService`, not the repository
      directly.
- [X] T019 [P] `backend/auth-service/src/test/java/com/example/learnhub/auth/InternalAccountLookupContractTest.java`:
      a provider-side MockMvc test (extends the existing `AbstractAuthServiceIT`) asserting the real
      endpoint's JSON response for a seeded account has exactly `id` and `email`, matching
      `contracts/auth-service-internal.openapi.yaml` (plan.md Testing: closes the gap a purely
      consumer-side WireMock stub can never catch).
- [X] T020 `backend/course-service/src/main/java/com/example/learnhub/course/api/CourseSummaryView.java`:
      add `title` (`String`, from `Course.title`) and `lessonIds` (`List<UUID>`, every lesson id of the
      course in position order, **capped at 200**, data-model.md/research.md R3) to the existing record
      and its `of(...)` factory; update
      `.../application/CourseLookupService.java`'s `summarize(...)` to populate both from the already-
      loaded `Course`/`LessonRepository` data — the endpoint path and every existing field are unchanged
      (research.md R0, R3).
- [X] T021 [P] Extend `backend/course-service/src/test/java/com/example/learnhub/course/InternalLookupIT.java`
      (the existing 003 test): assert the real response for a seeded course with lessons now also
      includes `title` matching the seeded value and `lessonIds` matching the seeded lessons' ids in
      position order (plan.md Testing: provider-side contract check).
- [X] T022 [P] `backend/enrollment-service/src/main/resources/db/migration/V3__add_completion_and_progress.sql`
      (not `V2` — already taken by 004, research.md R0): `ALTER TABLE enrollments ADD COLUMN
      completed_at TIMESTAMPTZ`; `CREATE TABLE lesson_progress (id UUID PRIMARY KEY, enrollment_id UUID
      NOT NULL REFERENCES enrollments(id), lesson_id UUID NOT NULL, completed_at TIMESTAMPTZ NOT NULL)`;
      `CREATE UNIQUE INDEX lesson_progress_enrollment_lesson_unique ON lesson_progress (enrollment_id,
      lesson_id)` (data-model.md, FR-004).
- [X] T023 [P] `backend/enrollment-service/src/main/resources/db/migration/V4__create_outbox_events.sql`:
      identical shape to `payment-service`'s own `V2__create_outbox_events.sql` (004), with
      `enrollment_id` in place of `payment_id` (data-model.md, research.md R7).
- [X] T024 [P] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/domain/Enrollment.java`:
      add `completedAt` (`Instant`, nullable) field and getter — no setter; it is set only via the
      conditional-update repository method (T044, research.md R1) — matching V3's new column.
- [X] T025 [P] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/domain/LessonProgress.java`
      (JPA entity matching T022's `lesson_progress` columns) and
      `.../infrastructure/LessonProgressRepository.java` (`existsByEnrollmentIdAndLessonId`,
      `countByEnrollmentId`, `deleteByEnrollmentIdAndLessonId`, a method returning the enrollment row
      with a pessimistic write lock — `@Lock(LockModeType.PESSIMISTIC_WRITE)` on
      `EnrollmentRepository.findById`, research.md R1 — add this to `EnrollmentRepository` instead).
- [X] T026 [P] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/domain/OutboxEvent.java`
      (identical shape to `payment-service`'s own, 004) and
      `.../infrastructure/OutboxEventRepository.java` (`findByPublishedAtIsNull`).
- [X] T027 `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/infrastructure/CourseLookupClient.java`
      (`@FeignClient(name = "course-service", fallbackFactory = ...)`, `GET
      /api/v1/internal/courses/{courseId}` returning a local `CourseSummaryResponse` DTO with `status`,
      `lessonCount`, `title`, `lessonIds`) and `.../infrastructure/CourseLookupClientFallbackFactory.java`
      — **fail-closed**: every failure (404 included — a course that no longer resolves is itself
      unusual here, since an enrollment implies the course existed) throws a `503` directly to the
      caller, the brief's explicit instruction (research.md R5) — no distinguishing 404-vs-503 branch
      like `payment-service`'s own client has, since enrollment-service calls this only for a course it
      already knows exists.
- [X] T028 [P] `backend/certificate-service/src/main/java/com/example/learnhub/certificate/domain/Certificate.java`,
      `.../domain/OutboxEvent.java`, `.../domain/ProcessedEvent.java` (identical shapes to their
      `payment-service`/`enrollment-service` counterparts, data-model.md) plus
      `.../infrastructure/CertificateRepository.java` (`existsByUserIdAndCourseId`, paginated
      `findByUserId`, `existsByVerificationCode`, `findByVerificationCode`),
      `.../infrastructure/OutboxEventRepository.java`, `.../infrastructure/ProcessedEventRepository.java`,
      and `backend/certificate-service/src/main/resources/db/migration/V1__create_certificates.sql`
      (`UNIQUE (user_id, course_id)`, `UNIQUE (verification_code)`, data-model.md),
      `V2__create_outbox_events.sql`, `V3__create_processed_events.sql`.
- [X] T029 `backend/certificate-service/src/main/java/com/example/learnhub/certificate/infrastructure/RabbitTopologyConfig.java`:
      declare the durable queue `certificate.q` (the brief's literal name) bound to `learning.events` on
      routing key `course.completed`; declare the durable DLQ `certificate.q.dlq` bound to the existing
      `learning.events.dlx` (reused from 004, research.md R6 — not a new exchange per consumer); declare
      the `Jackson2JsonMessageConverter`/`TopicExchange` beans the same way `payment-service`'s
      `RabbitMessagingConfig` does. Topology only — the retry-then-dead-letter *behavior* is wired in
      US7 (T0XX), the same phase split 004 used.
- [X] T030 `backend/certificate-service/src/main/java/com/example/learnhub/certificate/infrastructure/CourseLookupClient.java`
      (`@FeignClient(name = "course-service", fallbackFactory = ...)`, same internal endpoint as T027)
      and its fallback factory — **throws** on every failure (no degraded result), so a
      `@RabbitListener` method calling it propagates the exception and the message retries rather than
      issuing a titleless certificate (research.md R5, the brief's explicit instruction).
- [X] T031 [P] `backend/notification-service/src/main/java/com/example/learnhub/notification/domain/NotificationType.java`
      (enum `ENROLLMENT, CERTIFICATE`) and `.../domain/Notification.java` (`id`, `eventId` unique,
      `userId`, `type`, `status` enum `PENDING`/`SENT`, `createdAt`, data-model.md) plus
      `.../infrastructure/NotificationRepository.java` (`findByEventId`) and
      `backend/notification-service/src/main/resources/db/migration/V1__create_notifications.sql`
      (`UNIQUE (event_id)`, data-model.md).
- [X] T032 `backend/notification-service/src/main/java/com/example/learnhub/notification/infrastructure/RabbitTopologyConfig.java`:
      declare the durable queue `notification.q` bound to `learning.events` on **both** routing keys
      `enrollment.created` and `certificate.issued` (two bindings, one queue — the brief's literal
      design); declare `notification.q.dlq` bound to `learning.events.dlx`; declare the message
      converter/exchange beans. Topology only (as T029).
- [X] T033 `backend/notification-service/src/main/java/com/example/learnhub/notification/infrastructure/AccountLookupClient.java`
      (`@FeignClient(name = "auth-service", fallbackFactory = ...)`, `GET
      /api/v1/internal/accounts/{accountId}` returning a local `AccountSummaryResponse` DTO) and its
      fallback factory — throws on every failure (research.md R5).
- [X] T034 [P] `backend/notification-service/src/main/java/com/example/learnhub/notification/infrastructure/CourseLookupClient.java`
      (same shape as T030, notification-service's own copy per the one-Feign-client-per-consumer
      convention already established in 004) and its fallback factory.
- [X] T035 `backend/notification-service/src/main/java/com/example/learnhub/notification/infrastructure/EmailSender.java`:
      wraps `JavaMailSender` to send a plain-text message; logs only the notification id and type on
      success or failure, **never** the recipient address or the message body (FR-021, the brief's
      explicit instruction).
- [X] T036 Add two routes to `backend/config-repo/api-gateway.yml`, after `enrollments` (progress/
      completion need no new route — they already fall under it, research.md R9): `certificates-mine`
      (`Path=/api/v1/certificates/mine` + `Method=GET` → `lb://certificate-service`, protected) and
      `certificates-verify` (`Path=/api/v1/certificates/verify/**` + `Method=GET` →
      `lb://certificate-service`, `metadata.public: true`, `metadata.rate-limit: strict` — research.md
      R8).
- [X] T037 `backend/api-gateway/src/main/java/com/example/learnhub/gateway/ratelimit/RateLimiterProperties.java`:
      add a second, nested properties group (or a sibling `@ConfigurationProperties
      ("platform.gateway.rate-limit-strict")` class) for the stricter bucket's own capacity/refill;
      extend `RateLimitGlobalFilter` to read the matched route's `metadata.rate-limit` value (via the
      exchange's `GATEWAY_ROUTE_ATTR`) and consume from the stricter `TokenBucketRateLimiter` instance
      when it equals `"strict"`, falling back to the existing single global bucket otherwise (research.md
      R8); wire the second limiter bean in `GatewayConfiguration.java`.
- [X] T038 [P] Extend `backend/api-gateway/src/test/java/com/example/learnhub/gateway/RealGatewayConfigTest.java`:
      assert `certificates-mine` and `certificates-verify` route shapes/metadata (the latter public with
      `rate-limit: strict`); assert loading the real YAML never produces a route for
      `/api/v1/internal/**` (any service) — the brief's explicit "a test asserts it still returns 404,
      including the new auth internal endpoint."

**Checkpoint**: All five touched services have their schema, domain model, messaging topology and
internal lookups in place; the gateway knows about the new paths. No business behavior yet — ready for
US1.

---

## Phase 3: User Story 1 - Track lesson progress (Priority: P1) 🎯 MVP

**Goal**: An enrolled student marks a lesson complete or undoes that, and sees completed/total/percentage
for their course.

**Independent Test**: As an enrolled student, mark one lesson complete, confirm the progress view
reflects it; unmark it, confirm the view reflects that too; confirm a non-enrolled caller and an
unknown/foreign lesson id are both refused.

### Tests for User Story 1

- [X] T039 [P] [US1] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/CourseLookupClientContractTest.java`:
      WireMock-stubbed contract test for enrollment-service's `CourseLookupClient` — a 200 with
      `title`/`lessonIds`/`lessonCount` maps correctly; a 5xx or timeout throws a 503 (research.md R5;
      constitution Principle VI).
- [X] T040 [P] [US1] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/LessonProgressIT.java`:
      marking a real lesson of an enrolled course's course returns `completedLessons: 1` (FR-001);
      marking it again changes nothing (FR-004); unmarking it, then unmarking again, changes nothing
      either time (FR-002, FR-005); a non-enrolled caller is refused with 403 (FR-003); a lesson id not
      in the course's `lessonIds` is refused with 404 (FR-006); viewing progress returns
      `completedLessons`/`totalLessons`/`percentComplete` correctly (FR-007).

### Implementation for User Story 1

- [X] T041 [US1] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/application/LessonProgressService.java`
      (`@Transactional`): `mark(userId, courseId, lessonId)` — look up the caller's enrollment for
      `courseId` (403 if none, FR-003); call `CourseLookupClient` (fail-closed 503, T027); refuse with
      404 if `lessonId` is not in the course's `lessonIds` (FR-006); insert a `LessonProgress` row only
      if `existsByEnrollmentIdAndLessonId` is false (FR-004); `unmark(...)` — delete the row if present,
      no-op otherwise (FR-005); `view(userId, courseId)` — compute `completedLessons` =
      `countByEnrollmentId`, `totalLessons` = the course's `lessonCount`, `percentComplete` accordingly
      (FR-007). The completion side effect (research.md R1's lock and conditional update) is added in
      US2 (T044) — this task only inserts/deletes/counts.
- [X] T042 [US1] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/api/LessonProgressController.java`
      (`PUT`/`DELETE /api/v1/enrollments/{courseId}/lessons/{lessonId}/complete`, `GET
      /api/v1/enrollments/{courseId}/progress`, reading `X-User-Id` via `IdentityHeaders`) and
      `.../api/ProgressView.java` (record per `contracts/enrollment-service-progress.openapi.yaml`).

**Checkpoint**: A student can mark/unmark lessons and see accurate progress, end to end through the
gateway. This alone is a demoable MVP.

---

## Phase 4: User Story 2 - Automatic, one-time course completion (Priority: P1)

**Goal**: Completing the last lesson flips the enrollment to completed exactly once, permanently, even
under concurrent completions of the last few lessons.

**Independent Test**: Mark every lesson of a course complete and confirm the enrollment becomes
completed; fire several concurrent completions of the course's last few lessons and confirm exactly one
`course.completed` outbox row results; unmark-then-remark after completion and confirm nothing resets.

### Tests for User Story 2

- [X] T043 [P] [US2] `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/CourseCompletionIT.java`:
      completing a course's last lesson sets `Enrollment.completedAt` (FR-008); unmarking and re-marking
      a lesson afterward never changes that timestamp (FR-009); **ten concurrent requests** completing
      the course's remaining ten lessons at once (the brief's explicit volume) result in exactly one
      `outbox_events` row for `course.completed` (research.md R1's pessimistic-lock correctness claim,
      not just a single-trial assertion).

### Implementation for User Story 2

- [X] T044 [US2] Extend `LessonProgressService.mark(...)`: after inserting the `LessonProgress` row,
      load the enrollment with `EnrollmentRepository`'s pessimistic-write-lock method (T025, research.md
      R1) and, while holding that lock, recompute `countByEnrollmentId` against the course's
      `lessonCount`; if equal and `completedAt` is null, run the conditional update (`UPDATE enrollments
      SET completed_at = :now WHERE id = :id AND completed_at IS NULL`, T024) and, only if it affected
      one row, insert an `OutboxEvent` for `course.completed` (`CourseCompletedPayload`) in the same
      transaction (research.md R1, R7).
- [X] T045 [US2] `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/application/OutboxPublisher.java`
      and `.../infrastructure/RabbitMessagingConfig.java` (`Jackson2JsonMessageConverter` +
      `learning.events` `TopicExchange` beans) — identical `ScheduledExecutorService`-in-
      `@PostConstruct`/`@PreDestroy` pattern to `payment-service`'s own (research.md R7; **not**
      `@Scheduled`/`@EnableScheduling`, which `NoWiringCodeTest` forbids outside `platform-common`).

**Checkpoint**: Course completion is correct even under real concurrency — the foundation every
downstream story (certificates, emails) depends on.

---

## Phase 5: User Story 3 - Automatic certificate issuance (Priority: P1)

**Goal**: The moment a course completes, the student gets exactly one certificate for it, with a
verification code, the course title, and the completion date.

**Independent Test**: Publish a `course.completed` event and confirm exactly one certificate results,
with the right fields; publish the identical event again (simulating redelivery) and confirm no second
certificate is ever created.

### Tests for User Story 3

- [X] T046 [P] [US3] `backend/certificate-service/src/test/java/com/example/learnhub/certificate/CourseLookupClientContractTest.java`:
      WireMock-stubbed contract test for certificate-service's `CourseLookupClient` — a 200 with `title`
      maps correctly; a 5xx or timeout throws (so the caller can let the message retry, research.md R5).
- [X] T047 [P] [US3] `backend/certificate-service/src/test/java/com/example/learnhub/certificate/CourseCompletedConsumerIT.java`:
      publishing a well-formed `course.completed` event results in exactly one `Certificate` for that
      `userId`/`courseId`, with a 16-character verification code, the stubbed course title, and an
      `issuedAt` (FR-010, FR-011); publishing the **identical event again** (same `eventId`) creates no
      second certificate (FR-010 scenario 2) — the brief's explicit "one certificate under duplicate
      delivery."

### Implementation for User Story 3

- [X] T048 [US3] `backend/certificate-service/src/main/java/com/example/learnhub/certificate/application/CertificateCodeGenerator.java`:
      produces a 16-character code from a fixed, unambiguous alphabet (excludes `0/O`, `1/I/L` and
      similar look-alikes, data-model.md) using `SecureRandom`; the caller retries generation on a
      unique-constraint violation (vanishingly unlikely, handled defensively).
- [X] T049 [US3] `backend/certificate-service/src/main/java/com/example/learnhub/certificate/application/CourseCompletedListener.java`
      (`@RabbitListener(queues = RabbitTopologyConfig.QUEUE)`, `@Transactional`): deserialize the
      `EventEnvelope`; if `ProcessedEventRepository.existsById(eventId)`, acknowledge and return
      (idempotent replay); otherwise call `CourseLookupClient` for the course title (T030 — throws,
      letting the message retry, on failure); insert the `Certificate` row (using
      `CertificateCodeGenerator`) and the `ProcessedEvent` row; insert an `OutboxEvent` for
      `certificate.issued` (`CertificateIssuedPayload`) — all in the same transaction.
- [X] T050 [US3] `backend/certificate-service/src/main/java/com/example/learnhub/certificate/application/OutboxPublisher.java`
      (identical pattern to T045).

**Checkpoint**: The three P1 stories (US1, US2, US3) together deliver the entire "track progress →
complete → get a certificate" journey from SC-001 and SC-005.

---

## Phase 6: User Story 4 - Public certificate verification (Priority: P2)

**Goal**: Anyone can check a certificate by its code and see it is valid, the course title, and the
completion date — nothing about who holds it.

**Independent Test**: Take a real certificate's code and confirm an anonymous check reports it valid with
the right course title and date; try a made-up code and confirm a clear "not valid" result.

### Tests for User Story 4

- [X] T051 [P] [US4] `backend/certificate-service/src/test/java/com/example/learnhub/certificate/CertificateVerificationIT.java`:
      `GET /api/v1/certificates/verify/{code}` for a real, seeded certificate's code returns 200 with
      `valid: true`, the course title, and the issued date, and the response body contains neither
      `userId` nor any other holder-identifying field (FR-016, FR-017); an unknown code returns 404
      (FR-018).

### Implementation for User Story 4

- [X] T052 [US4] `backend/certificate-service/src/main/java/com/example/learnhub/certificate/api/CertificateVerificationController.java`
      (`GET /api/v1/certificates/verify/{code}`, no identity header read — this is the public path) and
      `.../api/VerificationView.java` (record per `contracts/certificate-service.openapi.yaml` —
      deliberately excludes `userId`, FR-017).

---

## Phase 7: User Story 5 - A student's own certificate list (Priority: P2)

**Goal**: A student sees all of their own certificates; never anyone else's.

**Independent Test**: As a student with certificates, list them and confirm only your own appear; confirm
a different student's list never includes them.

### Tests for User Story 5

- [X] T053 [P] [US5] `backend/certificate-service/src/test/java/com/example/learnhub/certificate/CertificateHistoryIT.java`:
      `GET /api/v1/certificates/mine` returns only the caller's own certificates, paginated (FR-013); a
      second user's call to the same endpoint never includes the first user's rows (FR-014).

### Implementation for User Story 5

- [X] T054 [US5] `backend/certificate-service/src/main/java/com/example/learnhub/certificate/api/CertificateController.java`
      (`GET /api/v1/certificates/mine`, reading `X-User-Id`, default page size 20 max 100 matching
      003/004's established pagination default) and `.../api/CertificateView.java` (record per
      `contracts/certificate-service.openapi.yaml`).

---

## Phase 8: User Story 6 - Enrollment and certificate emails (Priority: P2)

**Goal**: A student gets exactly one email for becoming enrolled and exactly one for earning a
certificate, captured locally, never delivered anywhere; a redelivered event never sends a second email.

**Independent Test**: Enroll in a course and confirm an enrollment email appears in the local catcher;
complete the course and confirm a certificate email appears too; replay either underlying event and
confirm no extra email appears.

### Tests for User Story 6

- [X] T055 [P] [US6] `backend/notification-service/src/test/java/com/example/learnhub/notification/AccountLookupClientContractTest.java`:
      WireMock-stubbed contract test for `AccountLookupClient` — a 200 with `email` maps correctly; a
      5xx or timeout throws.
- [X] T056 [P] [US6] `backend/notification-service/src/test/java/com/example/learnhub/notification/support/AbstractNotificationServiceIT.java`:
      Testcontainers PostgreSQL, RabbitMQ, **and** a `GenericContainer` running `axllent/mailpit`
      (exposing 1025 and 8025), singleton-container pattern identical to every other `Abstract*ServiceIT`
      in this codebase (research.md Testing — reuses the already-approved Mailpit image via
      Testcontainers' generic support, not a new technology).
- [X] T057 [P] [US6] `backend/notification-service/src/test/java/com/example/learnhub/notification/EnrollmentCreatedConsumerIT.java`:
      publishing a well-formed `enrollment.created` event results in exactly one email captured by the
      Mailpit container (checked via its HTTP API) and one `Notification` row with `status: SENT`
      (FR-019); publishing the identical event again sends no second email, verified against Mailpit's
      message count (FR-022).
- [X] T058 [P] [US6] `backend/notification-service/src/test/java/com/example/learnhub/notification/CertificateIssuedConsumerIT.java`:
      the same shape as T057, for `certificate.issued` (FR-020).

### Implementation for User Story 6

- [X] T059 [US6] Extend `backend/enrollment-service/src/main/java/com/example/learnhub/enrollment/application/PaymentSucceededListener.java`
      (004): in the same transaction that inserts the `Enrollment` row, also insert an `OutboxEvent` for
      `enrollment.created` (`EnrollmentCreatedPayload`, research.md R2) — additive to its existing
      behavior, not a replacement.
- [X] T060 [US6] `backend/notification-service/src/main/java/com/example/learnhub/notification/application/EnrollmentCreatedListener.java`
      and `.../application/CertificateIssuedListener.java` (`@RabbitListener(queues =
      RabbitTopologyConfig.QUEUE)`, one method per routing key): research.md R7's two-phase write —
      phase one, in its own transaction, finds-or-inserts a `PENDING` `Notification` row keyed by
      `eventId` (if found already `SENT`, acknowledge and return, FR-022); phase two, outside any
      transaction, resolves the email via `AccountLookupClient` and the course title via
      `CourseLookupClient`, then sends via `EmailSender` (T035) — any failure here throws, leaving the
      `PENDING` row in place for the next retry attempt to resume from; phase three, a second small
      transaction, marks the row `SENT`.

**Checkpoint**: All P1/P2 stories are complete; the feature is fully usable end to end, including email.

---

## Phase 9: User Story 7 - Reliability of completion, certification, and notification (Priority: P3)

**Goal**: A course completion is never lost on its way to a certificate even if certificate-service is
briefly unavailable; a notification that cannot be processed, or that is malformed, is held for
inspection without blocking the messages behind it.

**Independent Test**: Complete a course while certificate-service is stopped; confirm no certificate
exists yet; restart it and confirm exactly one certificate appears. Send a malformed notification and
confirm a valid one sent right after it is still processed promptly.

### Tests for User Story 7

- [X] T061 [P] [US7] `backend/certificate-service/src/test/java/com/example/learnhub/certificate/DeadLetterIT.java`:
      a malformed message (invalid JSON) is retried and lands on `certificate.q.dlq` after exactly three
      attempts (FR — mirrors 004's own `DeadLetterIT`); a well-formed `course.completed` event published
      immediately after it is still processed into a certificate without waiting for the malformed
      message's retries to exhaust (no head-of-line blocking).
- [X] T062 [P] [US7] `backend/certificate-service/src/test/java/com/example/learnhub/certificate/CertificateServiceDownIT.java`:
      with `CourseCompletedListener`'s container stopped via `RabbitListenerEndpointRegistry.stop()`, a
      `course.completed` event published during the outage sits durably queued; starting the container
      again results in exactly one `Certificate` (SC-004, mirrors 004's `EnrollmentServiceDownIT`).
- [X] T063 [P] [US7] `backend/notification-service/src/test/java/com/example/learnhub/notification/DeadLetterIT.java`:
      the same shape as T061, for `notification.q`/`notification.q.dlq` (FR-023, FR-024).
- [X] T064 [P] [US7] Extend `backend/enrollment-service/src/test/java/com/example/learnhub/enrollment/CourseCompletionIT.java`
      (or a new `OutboxPublisherIT`) for the broker-outage case: with the RabbitMQ Testcontainer
      paused at the moment a course completes, the `outbox_events` row for `course.completed` stays
      unpublished; once the broker is available again, the next poll cycle publishes it (FR-025, mirrors
      004's own `OutboxPublisherIT` broker-outage test).

### Implementation for User Story 7

- [X] T065 [US7] Add the stateless retry interceptor (three attempts, short backoff) +
      `RepublishMessageRecoverer` (targeting `learning.events.dlx`/`certificate.q.dlq`) +
      `defaultRequeueRejected(false)` + `concurrentConsumers(3)` to a dedicated
      `SimpleRabbitListenerContainerFactory` bean in `certificate-service`'s `RabbitTopologyConfig.java`
      (T029), referenced from `CourseCompletedListener` (T049) — identical mechanism to 004's own
      `RabbitTopologyConfig` (research.md R6).
- [X] T066 [US7] The same extension (retry interceptor, recoverer, `concurrentConsumers(3)`) in
      `notification-service`'s `RabbitTopologyConfig.java` (T032), referenced from both
      `EnrollmentCreatedListener` and `CertificateIssuedListener` (T060).

**Checkpoint**: All seven user stories are complete and independently verified.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [X] T067 [P] Update `README.md`: port table rows for `certificate-service` (9005) and
      `notification-service` (9007) from "reserved, not yet built" to built; a new "## Progress,
      certificates, and notifications" section.
- [X] T068 [P] Update `HOWTORUN.md`: component table, a curl walkthrough covering all seven user stories
      (mirroring `quickstart.md`), test-count update, troubleshooting rows for a 404 lesson-not-in-course
      response and a 429 from the stricter verification rate limit, a "See also" entry for 005.
- [ ] T069 Write `specs/005-progress-certificates-notifications/checklists/success-criteria.md`:
      measured status of SC-001 through SC-007, following 002/003/004's own format — including an honest
      accounting of any success criterion proven once rather than at full stated volume.
- [ ] T070 Full platform verification: `docker compose down -v`, `scripts/up.sh`, run every step of
      `quickstart.md` by hand against the live stack (including the certificate-service-stop/restart
      step and the Mailpit API check for two distinct emails), then `scripts/smoke-test.sh` to confirm
      SC-007 (no regression to platform-foundation, auth, course-catalog, or checkout-and-enrollment
      behavior).
- [ ] T071 `mvn -f backend/pom.xml verify` for the full reactor (from WSL; `set -o pipefail` and check
      `${PIPESTATUS[0]}` rather than piping to `tail`) — confirm every new and existing test is green.

---

## Dependencies and execution order

- **Setup (T001–T012)**: No dependencies; most tasks are `[P]` since they touch independent files.
- **Foundational (T013–T038)**: Depends on Setup. Blocks every user story. T013–T017 (shared-events),
  T018–T019 (auth-service), T020–T021 (course-service), T022–T027 (enrollment-service schema/domain/Feign),
  T028–T030 (certificate-service schema/domain/topology/Feign) and T031–T035 (notification-service
  equivalents) are five largely independent parallel tracks; T036–T038 (gateway) depend on nothing above
  but logically follow once the services they route to are known to exist.
- **User Story 1 (T039–T042)**: Depends on Foundational (needs T020/T021's extended course lookup and
  T022–T027's enrollment-service schema/Feign client).
- **User Story 2 (T043–T045)**: Depends on User Story 1 (extends `LessonProgressService` and its IT).
- **User Story 3 (T046–T050)**: Depends on Foundational only (certificate-service's own files are
  independent of enrollment-service's), but its consumer test needs a real `course.completed` event
  shape, which only exists once User Story 2 can actually produce one — safe to implement in parallel,
  verify end-to-end only once US2 is done.
- **User Story 4 (T051–T052)** and **User Story 5 (T053–T054)**: Both depend on User Story 3 (certificates
  must exist to verify or list) but are independent of each other.
- **User Story 6 (T055–T060)**: Depends on User Story 3 (needs `certificate.issued` to exist) and on
  Foundational's auth-service lookup (T018); independent of US4/US5.
- **User Story 7 (T061–T066)**: Depends on User Story 3 (certificate-service) and User Story 6
  (notification-service) already having working consumers to add retry/recovery behavior to.
- **Polish (T067–T071)**: Depends on every user story being complete.

## Parallel execution examples

```text
# Foundational phase, five independent tracks at once:
T013, T014, T015, T016, T017                  (shared-events)
T018, T019                                     (auth-service)
T020, T021                                     (course-service)
T022, T023, T024, T025, T026, T027             (enrollment-service)
T028, T029, T030                               (certificate-service)
T031, T032, T033, T034, T035                   (notification-service)

# User Story 1 tests, in parallel before implementation starts:
T039, T040

# User Stories 4 and 5 can proceed in parallel once US3 lands (disjoint endpoints, same module):
T051 -> T052   ||   T053 -> T054
```

## Implementation strategy

**MVP = User Story 1 only**: a student can mark/unmark lessons and see accurate progress, end to end
through the gateway. Demoable and independently valuable on its own, exactly as every prior spec's MVP
was.

**Incremental delivery after that**: US2 (one-time completion) unlocks everything downstream and should
come next. US3 (certificates) follows directly — it is the whole point of completion existing. US4 and
US5 (verification, own-list) are independent of each other and can be done in either order. US6 (email)
depends only on US3 and the Foundational auth lookup. US7 (reliability hardening) comes last, since it is
a non-functional property layered on top of behavior US3 and US6 already established — identical
philosophy to 004's own US6.
