---

description: "Task list for review-service implementation"
---

# Tasks: Review Service

**Input**: Design documents from `/specs/007-review-service/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/review-service.openapi.yaml, quickstart.md

**Tests**: Included — constitution Principle VI ("Test-First Quality (NON-NEGOTIABLE)") requires tests written before implementation for every feature.

**Organization**: Tasks are grouped by user story (spec.md priorities: US1 P1, US3 P1, US2 P2) so each can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1 / US2 / US3, matching spec.md

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 Create the `backend/review-service` module skeleton (`pom.xml`, `src/main/java/.../review/{api,application,domain,infrastructure}`, `src/test/java/.../review/support`) per plan.md's Project Structure, with the same dependency set as `backend/certificate-service/pom.xml` minus `spring-boot-starter-amqp` and its `spring-rabbit-test` test dependency (research.md R6).
- [x] T002 Add `<module>review-service</module>` to `backend/pom.xml`'s `<modules>` list.
- [x] T003 [P] Create `backend/config-repo/review-service.yml` — minimal, config-import-only, same shape as `backend/config-repo/certificate-service.yml` (research.md R6).
- [x] T004 [P] Add a `review-service` block to `docker-compose.yml`: own Postgres database `review`, `REVIEW_DB_PASSWORD` secret, host port `9006:9006`, health check on `/actuator/health/readiness`, `depends_on` discovery-server/config-server/db-init/rabbitmq-free (no RabbitMQ dependency needed) — copy the shape of the existing `certificate-service` block and drop the RabbitMQ env vars (research.md R1, R6).
- [x] T005 [P] Add `REVIEW_DB_PASSWORD` to `.env.example` with a non-secret placeholder value, alongside the other `*_DB_PASSWORD` entries.
- [x] T006 Create `backend/review-service/src/main/resources/application.yml`: `spring.application.name: review-service`, non-optional `configserver:` import with fail-fast + retry, `jpa.open-in-view: false`, `hibernate.ddl-auto: validate`, `flyway.enabled: true`, Feign default timeouts (`connect-timeout`/`read-timeout: 2000`), `server.port: 9006` — same shape as `backend/certificate-service/src/main/resources/application.yml` minus the `spring.rabbitmq.publisher-confirm-type` line (no messaging, research.md R1).

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story task may start until this phase is complete.

- [x] T007 Create `ReviewServiceApplication` in `backend/review-service/src/main/java/com/example/learnhub/review/ReviewServiceApplication.java` (`@SpringBootApplication`, registers with Eureka), mirroring `CertificateServiceApplication`.
- [x] T008 [P] Create the `Review` JPA entity in `backend/review-service/src/main/java/com/example/learnhub/review/domain/Review.java` with fields `id` (UUID PK), `userId` (UUID, not null), `courseId` (UUID, not null), `rating` (short, not null), `comment` (String, nullable, max length 500), `status` (enum `ACTIVE`/`DELETED`, not null, default `ACTIVE`), `createdAt`/`updatedAt` (Instant, not null) — per data-model.md's Review table.
- [x] T009 Create Flyway migration `backend/review-service/src/main/resources/db/migration/V1__create_reviews.sql`: table `reviews` matching T008's fields, `CHECK (rating BETWEEN 1 AND 5)`, `UNIQUE (user_id, course_id)`, an index on `course_id` — per data-model.md's constraints.
- [x] T010 [P] Create `ReviewRepository` in `backend/review-service/src/main/java/com/example/learnhub/review/infrastructure/ReviewRepository.java` extending `JpaRepository<Review, UUID>` with `findByUserIdAndCourseId(UUID userId, UUID courseId)` (used for the upsert/ownership checks in US1 and US2).

**Checkpoint**: Module builds, Flyway migration runs cleanly against a fresh `review` database, foundation ready for user story work.

---

## Phase 3: User Story 1 - Enrolled student reviews a course (Priority: P1) 🎯 MVP

**Goal**: An enrolled student can submit a rating (1-5) + optional comment for a course; a second submission updates rather than duplicates; non-enrolled submissions and invalid content are rejected (FR-001, FR-002, FR-003, FR-007, FR-012).

**Independent Test**: `POST /api/v1/reviews/courses/{courseId}` as an enrolled student returns 201 with the review; repeating it returns 200 updating the same row; the same call from a non-enrolled student returns 403; an out-of-range rating or an over-length/blocked-word comment returns 400 (quickstart.md Scenarios 1–3).

### Tests for User Story 1 (write first, confirm they fail)

- [x] T011 [P] [US1] Contract test `EnrollmentLookupClientContractTest` in `backend/review-service/src/test/java/com/example/learnhub/review/EnrollmentLookupClientContractTest.java` (WireMock stub of `GET /api/v1/internal/enrollments/{courseId}/{userId}`), mirroring `course-service`'s own `EnrollmentLookupClientContractTest.java`.
- [x] T012 [P] [US1] Unit test `ReviewValidationTest` in `backend/review-service/src/test/java/com/example/learnhub/review/ReviewValidationTest.java` covering: rating 0 and 6 rejected, ratings 1–5 accepted (FR-012's "rating MUST be 1-5 inclusive"); a 501-character comment rejected, a 500-character comment accepted (data-model.md's `VARCHAR(500)` / research.md R4's "maximum comment length 500 characters"); a comment containing a blocked-list word rejected (FR-007).
- [x] T013 [P] [US1] Integration test `ReviewSubmissionIT` (Testcontainers PostgreSQL + WireMock enrollment-service stub, no RabbitMQ container) in `backend/review-service/src/test/java/com/example/learnhub/review/ReviewSubmissionIT.java` covering: enrolled student → 201 with persisted row; repeat submission by the same student/course → 200, same review id, updated rating/comment, still exactly one row (FR-003's "unique constraint on `(user_id, course_id)`"); non-enrolled student → 403, no row created (FR-002); enrollment-service lookup failing/circuit open → 503, no row created (research.md R2's "fallback MUST NOT silently treat the student as enrolled").

### Implementation for User Story 1

- [x] T014 [US1] Create `EnrollmentLookupClient` Feign interface in `backend/review-service/src/main/java/com/example/learnhub/review/infrastructure/EnrollmentLookupClient.java`: `@FeignClient(name = "enrollment-service", fallback = EnrollmentLookupClientFallback.class)`, `GET /api/v1/internal/enrollments/{courseId}/{userId}` returning `EnrollmentLookupResponse(boolean enrolled, Instant enrolledAt)` — byte-for-byte the shape of `course-service`'s `EnrollmentLookupClient` (research.md R2).
- [x] T015 [US1] Create `EnrollmentLookupClientFallback` and `EnrollmentLookupUnavailableException` in `backend/review-service/src/main/java/com/example/learnhub/review/infrastructure/`: the fallback throws `EnrollmentLookupUnavailableException` rather than returning a default "not enrolled"/"enrolled" value, so a lookup failure surfaces as 503 and never silently approves or rejects a real student (research.md R2).
- [x] T016 [US1] Add a `resilience4j.circuitbreaker.instances.enrollment-service` / `resilience4j.timelimiter.instances.enrollment-service` block to `backend/review-service/src/main/resources/application.yml` (sliding-window-size 10, minimum-number-of-calls 5, failure-rate-threshold 50, wait-duration-in-open-state 10s, permitted-number-of-calls-in-half-open-state 3; timelimiter timeout-duration 3s, cancel-running-future true) — same values as `certificate-service`'s `course-service` instance config, renamed to `enrollment-service` (research.md R2).
- [x] T017 [US1] Create `ReviewValidator` in `backend/review-service/src/main/java/com/example/learnhub/review/application/ReviewValidator.java`: reject rating outside 1–5 (FR-012), reject a comment longer than 500 characters or containing a word from a small static blocked-word list (FR-007, research.md R4), throwing a dedicated validation exception naming which rule failed.
- [x] T018 [US1] Create `ReviewService.submit(UUID userId, UUID courseId, int rating, String comment)` in `backend/review-service/src/main/java/com/example/learnhub/review/application/ReviewService.java`: call `EnrollmentLookupClient`; if not enrolled, throw a "not enrolled" exception (→ 403); validate via `ReviewValidator` (T017); look up an existing row via `ReviewRepository.findByUserIdAndCourseId`; if present (ACTIVE or DELETED), update its rating/comment/status=ACTIVE/updatedAt in place; if absent, insert a new ACTIVE row (FR-001, FR-002, FR-003, data-model.md's DELETED→ACTIVE reactivation transition).
- [x] T019 [US1] Create `ReviewView` record in `backend/review-service/src/main/java/com/example/learnhub/review/api/ReviewView.java` (`id`, `courseId`, `userId`, `rating`, `comment`, `createdAt`, `updatedAt`) and `ReviewController` with `POST /api/v1/reviews/courses/{courseId}` in `backend/review-service/src/main/java/com/example/learnhub/review/api/ReviewController.java`, reading the caller from `@RequestHeader(IdentityHeaders.USER_ID)`, returning 201 on create / 200 on update per `contracts/review-service.openapi.yaml`.
- [x] T020 [US1] Map `ReviewService`'s validation / not-enrolled / lookup-unavailable exceptions to RFC 7807 problem-details responses (400 / 403 / 503) carrying `correlationId`, consistent with the platform-common error-handling convention used by every other business service (constitution Principle VIII).

**Checkpoint**: User Story 1 is independently testable — `mvn -pl backend/review-service verify` passes and quickstart.md Scenarios 1–3 succeed against the running stack.

---

## Phase 4: User Story 3 - Prospective student browses reviews and rating (Priority: P1)

**Goal**: Anyone, authenticated or not, can list a course's active reviews and see its aggregate rating (FR-008, FR-009).

**Independent Test**: `GET /api/v1/reviews/courses/{courseId}` and `GET /api/v1/reviews/courses/{courseId}/summary` succeed with no `Authorization` header; the summary's count/average match the active reviews returned by the list; a course with zero active reviews returns `reviewCount: 0` with no `averageRating` (quickstart.md Scenario 5).

### Tests for User Story 3 (write first, confirm they fail)

- [x] T021 [P] [US3] Integration test `ReviewBrowsingIT` (Testcontainers PostgreSQL, requests sent with no `Authorization` header) in `backend/review-service/src/test/java/com/example/learnhub/review/ReviewBrowsingIT.java` covering: listing returns only `ACTIVE` reviews, paginated; the summary's `averageRating`/`reviewCount` match a direct `AVG`/`COUNT` over the same active rows (SC-003); a course with zero active reviews returns `reviewCount: 0` and no `averageRating` (Edge Cases, FR-009).

### Implementation for User Story 3

- [x] T022 [US3] Add to `backend/review-service/src/main/java/com/example/learnhub/review/infrastructure/ReviewRepository.java`: `findByCourseIdAndStatus(UUID courseId, ReviewStatus status, Pageable pageable)` and an aggregate query (`@Query` with `AVG(rating)`/`COUNT(*)` filtered to `course_id` + `status = ACTIVE`) per data-model.md's Course Rating Summary (computed on read, no stored/materialized copy — research.md R1).
- [x] T023 [US3] Create `CourseRatingSummaryView` record (`courseId`, `averageRating` nullable, `reviewCount`) in `backend/review-service/src/main/java/com/example/learnhub/review/api/CourseRatingSummaryView.java`, and add `GET /api/v1/reviews/courses/{courseId}` (paginated list) and `GET /api/v1/reviews/courses/{courseId}/summary` to `backend/review-service/src/main/java/com/example/learnhub/review/api/ReviewController.java`, both reachable with no authentication, per `contracts/review-service.openapi.yaml`.

**Checkpoint**: User Stories 1 and 3 both work independently — public browsing shows exactly what Story 1 created.

---

## Phase 5: User Story 2 - Student updates or removes their own review (Priority: P2)

**Goal**: A student can edit or soft-delete their own review; a non-owner's attempt is rejected identically to a not-found review (FR-004, FR-005, FR-006).

**Independent Test**: `PUT`/`DELETE /api/v1/reviews/{reviewId}` succeeds for the owning student and returns 404 for anyone else or a missing review (quickstart.md Scenario 4).

### Tests for User Story 2 (write first, confirm they fail)

- [x] T024 [P] [US2] Integration test `ReviewOwnershipIT` (Testcontainers PostgreSQL) in `backend/review-service/src/test/java/com/example/learnhub/review/ReviewOwnershipIT.java` covering: the owner edits their review → 200 with updated fields (FR-004); the owner deletes their review → 204, row's `status` becomes `DELETED`, and it disappears from both the list and the summary aggregate (FR-005, FR-009); a different student's edit/delete attempt → 404, not 403, and the review is unchanged (FR-006); editing/deleting a non-existent review id → 404.

### Implementation for User Story 2

- [x] T025 [US2] Add `ReviewService.edit(UUID reviewId, UUID callerId, int rating, String comment)` and `ReviewService.delete(UUID reviewId, UUID callerId)` to `backend/review-service/src/main/java/com/example/learnhub/review/application/ReviewService.java`: look up by id, throw a not-found exception (→ 404) if missing or if `review.getUserId()` does not equal `callerId` (FR-006, "identical 404 for someone else's resource" convention); `edit` re-validates via `ReviewValidator` (T017) and updates `rating`/`comment`/`updatedAt`; `delete` sets `status = DELETED` and `updatedAt` (soft delete, data-model.md).
- [x] T026 [US2] Add `PUT /api/v1/reviews/{reviewId}` and `DELETE /api/v1/reviews/{reviewId}` to `backend/review-service/src/main/java/com/example/learnhub/review/api/ReviewController.java`, reading the caller from `@RequestHeader(IdentityHeaders.USER_ID)`, returning 200/204 per `contracts/review-service.openapi.yaml`; map the not-found/not-owner exception to a 404 problem-details response (T020's exception-mapping extended).

**Checkpoint**: All three user stories work independently and together.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T027 [P] Add two routes to `backend/config-repo/api-gateway.yml`: `reviews-write` (`Path=/api/v1/reviews/**`, `Method=POST,PUT,DELETE`, protected by default, order 21) and `reviews-read` (`Path=/api/v1/reviews/**`, `Method=GET`, `metadata.public: true`, order 22) — per research.md R5.
- [x] T028 [P] Update the constitution's port-map row for `review-service` (currently "9006 | Deferred, not started") to reflect that it is implemented, and record this spec in the Development Workflow section's completed-specs list — a PATCH-level clarification, not a new principle (constitution Governance section).
- [x] T029 Run `mvn verify` for `backend/review-service` (and the full backend reactor) via `wsl -e bash -lc "cd backend && mvn verify"` to confirm every unit and integration test passes.
- [ ] T030 Bring up the full stack (`docker compose up -d` via WSL) including the new `review-service` container, and run quickstart.md's five scenarios end-to-end against `http://localhost:9080`.
- [x] T031 Refresh the graphify knowledge graph (`graphify-out/`) now that `backend/` has changed. Done as a code-only AST update scoped to the 25 changed `backend/` files (the new `review-service` module + `backend/pom.xml` + `ComposeFileTest.java`): 2578→2706 nodes, 8428→8905 edges, re-clustered to 117 communities, zero LLM tokens spent. A separate incremental scan of the whole repo also surfaced 94 previously-unindexed doc files (mostly `specs/*.md`) that would need semantic-extraction subagents to index — deliberately deferred (not part of this feature's code change, and expensive) rather than run now; a future `/graphify . --update` will pick them up if/when the graph's scope is intentionally widened.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS every user story.
- **User Story 1 (Phase 3, P1)**: Depends on Foundational only.
- **User Story 3 (Phase 4, P1)**: Depends on Foundational only; independently testable without US1 (it can list "no reviews yet" on its own), but is more meaningfully verified once US1 exists to create data.
- **User Story 2 (Phase 5, P2)**: Depends on Foundational and on a review existing to edit/delete, so it is verified after US1 but does not require US1's *code* — only test data.
- **Polish (Phase 6)**: Depends on all three user stories being complete.

### Parallel Opportunities

- T003, T004, T005 (Phase 1) touch different files and can run in parallel.
- T008 and T010 (Phase 2) touch different files and can run in parallel; T009 (the migration) can be written in parallel with both but should be validated against T008's field list.
- T011, T012, T013 (US1 tests) touch different files and can run in parallel.
- T021 (US3 test) can run in parallel with any US1 task once Foundational is done, since it only reads.
- T024 (US2 test) can run in parallel with US3 tasks.
- T027 and T028 (Phase 6) touch different files and can run in parallel.

Tasks against the same file (`ReviewRepository.java`: T010/T022; `ReviewController.java`: T019/T023/T026; `ReviewService.java`: T018/T025; `application.yml`: T006/T016) are intentionally left unmarked for parallelism and should be done in the listed order.

## Implementation Strategy

### MVP First

1. Phase 1 (Setup) → Phase 2 (Foundational) → Phase 3 (User Story 1).
2. **STOP and VALIDATE**: quickstart.md Scenarios 1–3 against a locally built `review-service` module (an enrolled student can submit a review; non-enrolled and invalid submissions are rejected).
3. This alone is a demonstrable MVP slice even before browsing or editing exist.

### Incremental Delivery

1. Setup + Foundational → foundation ready.
2. User Story 1 → validate → (optional checkpoint).
3. User Story 3 → validate (now reviews are publicly visible with an accurate aggregate).
4. User Story 2 → validate (students can correct or retract their reviews).
5. Polish: gateway routes, constitution bookkeeping, full verification, graphify refresh.
