# Tasks: Course Catalog

**Input**: Design documents from `specs/003-course-catalog/` (plan.md, spec.md, research.md,
data-model.md, contracts/, quickstart.md)

**Tests**: Included and mandatory — constitution Principle VI ("Test-First Quality") is NON-NEGOTIABLE:
tests are written, seen to fail, then made to pass.

**Organization**: Grouped by user story (spec.md priorities): US1 (P1, browse/view the public catalog),
US2 (P1, instructor create/manage including lessons), US3 (P2, publish/archive), US4 (P2, categories),
US5 (P3, lesson-content privacy — a regression-only story over US1+US2's existing code, per spec.md's
own "doesn't unlock new capability on its own"), US6 (P3, service-to-service lookup).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no unmet dependency)
- **[Story]**: US1–US6, per spec.md
- Every task names its exact file path

## Path Conventions

Backend-only feature (plan.md Scope). New module `backend/course-service/`, following the same
`api`/`application`/`domain`/`infrastructure` layering as `auth-service` (constitution Principle IX,
research.md R8). Touches `backend/api-gateway/` (config only, no code), `backend/pom.xml`,
`backend/config-repo/`, `docker-compose.yml`, `scripts/up.sh`, `backend/infra-tests/`, `README.md` and
`HOWTORUN.md`. `frontend/` is untouched.

---

## Phase 1: Setup

- [X] T001 Add `course-service` to the `<modules>` list in `backend/pom.xml`.
- [X] T002 Create `backend/course-service/pom.xml`: parent `backend/pom.xml`, artifact
      `course-service`, depending on `spring-boot-starter-web`, `spring-boot-starter-data-jpa`,
      `spring-boot-starter-validation`, `spring-boot-starter-actuator`,
      `spring-cloud-starter-netflix-eureka-client`, `spring-cloud-starter-config`, `spring-retry` +
      `spring-boot-starter-aop`, `platform-common`, `flyway-core`, `flyway-database-postgresql`,
      `org.postgresql:postgresql`, plus `spring-boot-starter-test`, `testcontainers-junit-jupiter` and
      `testcontainers-postgresql` in test scope — no security library (research.md R8: course-service
      only reads gateway-forwarded identity headers, it never validates a token itself).
- [X] T003 [P] Create `backend/course-service/src/main/resources/application.yml`: `spring.application.name:
      course-service`, `server.port: 9002` (constitution port table), non-optional
      `spring.config.import=configserver:${CONFIG_SERVER_URL}` with fail-fast and retry, Eureka client
      pointed at `${EUREKA_SERVER_URL}`, `spring.jpa.hibernate.ddl-auto: validate` — mirrors
      `backend/auth-service/src/main/resources/application.yml`.
- [X] T004 [P] Create `backend/config-repo/course-service.yml` with a comment placeholder, same pattern
      as `backend/config-repo/auth-service.yml` (no refreshable setting required by this feature yet).
- [X] T005 [P] Add a `course-service` entry to `docker-compose.yml`: build arg `MODULE=course-service`,
      port `9002:9002`, `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/course`,
      `SPRING_DATASOURCE_USERNAME=course`,
      `SPRING_DATASOURCE_PASSWORD=${COURSE_DB_PASSWORD:?COURSE_DB_PASSWORD is required}` (the existing
      `.env.example` variable from platform-foundation, not a new one), the same
      `CONFIG_SERVER_URL`/`CONFIG_USER`/`CONFIG_PASSWORD`/`EUREKA_URL` pattern every service uses;
      `depends_on` discovery-server, config-server and db-init (`condition: service_completed_successfully`);
      healthcheck against `/actuator/health/readiness` — mirrors `auth-service`'s entry exactly
      (analyze-equivalent finding from 002: do not copy a dependency-free service's entry by mistake).
- [X] T006 Create `backend/course-service/src/main/java/com/example/learnhub/course/CourseServiceApplication.java`:
      a plain `@SpringBootApplication` main class, matching `AuthServiceApplication`.
- [X] T007 [P] Add `"9002:course-service"` to the `PORTS` array in `scripts/up.sh`, next to the existing
      `"9001:auth-service"` entry.
- [X] T008 [P] Update `reservedPortsAreNotUsed` in
      `backend/infra-tests/src/test/java/com/example/learnhub/infra/ComposeFileTest.java` to also
      exempt port 9002 for service `course-service`, the same way 9001/`auth-service` is already
      exempted.

**Checkpoint**: `mvn -f backend/pom.xml -pl course-service -am compile` succeeds; nothing is testable yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T009 Create `backend/course-service/src/main/resources/db/migration/V1__create_categories.sql`:
      `categories` — `id UUID PRIMARY KEY`, `name VARCHAR(100) NOT NULL`, a unique index on
      `lower(name)` (data-model.md: "unique case-insensitively"), `version BIGINT NOT NULL DEFAULT 0`
      (analyze finding G1: FR-025 explicitly includes category changes in its no-silent-overwrite rule).
- [X] T010 Create `backend/course-service/src/main/resources/db/migration/V2__create_courses.sql`:
      `courses` — `id UUID PRIMARY KEY`, `title VARCHAR(200)`, `description TEXT`,
      `category_id UUID REFERENCES categories(id)`, `level VARCHAR(20) CHECK (level IN
      ('BEGINNER','INTERMEDIATE','ADVANCED'))` nullable, `language VARCHAR(20)` nullable,
      `price_cents INTEGER CHECK (price_cents >= 0)` nullable, `currency VARCHAR(3) NOT NULL DEFAULT
      'USD'`, `instructor_id UUID NOT NULL`, `status VARCHAR(20) NOT NULL CHECK (status IN
      ('DRAFT','PUBLISHED','ARCHIVED'))`, `published_at TIMESTAMPTZ NULL`, `created_at TIMESTAMPTZ NOT
      NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `version BIGINT NOT NULL
      DEFAULT 0` (research.md R1, JPA `@Version`); indexes on `status`, `category_id`, `published_at`
      (research.md R4, SC-004).
- [X] T011 Create `backend/course-service/src/main/resources/db/migration/V3__create_lessons.sql`:
      `lessons` — `id UUID PRIMARY KEY`, `course_id UUID NOT NULL REFERENCES courses(id)`,
      `title VARCHAR(200) NOT NULL`, `position INTEGER NOT NULL`, `duration_minutes INTEGER NOT NULL
      CHECK (duration_minutes >= 0)`, `content TEXT NOT NULL DEFAULT ''`; unique index on
      `(course_id, position)` (data-model.md: "unique per course").
- [X] T012 [P] Create `backend/course-service/src/main/java/com/example/learnhub/course/domain/CourseStatus.java`:
      enum `DRAFT`, `PUBLISHED`, `ARCHIVED`.
- [X] T013 [P] Create `backend/course-service/src/main/java/com/example/learnhub/course/domain/Level.java`:
      enum `BEGINNER`, `INTERMEDIATE`, `ADVANCED` (research.md R3).
- [X] T014 [P] Create `backend/course-service/src/main/java/com/example/learnhub/course/domain/Category.java`:
      JPA entity mapped to `categories` (T009): `id`, `name`, `@Version version` (analyze finding G1).
- [X] T015 Create `backend/course-service/src/main/java/com/example/learnhub/course/domain/Course.java`:
      JPA entity mapped to `courses` (T010), `@Version` on the `version` field (research.md R1); fields
      per data-model.md Course; a method or domain check expressing "`instructorId` is immutable after
      creation" (no setter for it).
- [X] T016 Create `backend/course-service/src/main/java/com/example/learnhub/course/domain/Lesson.java`:
      JPA entity mapped to `lessons` (T011), `@ManyToOne` to `Course`; fields per data-model.md Lesson.
- [X] T017 [P] Create `backend/course-service/src/main/java/com/example/learnhub/course/infrastructure/CategoryRepository.java`:
      Spring Data JPA repository on `Category` with `existsByNameIgnoreCase`,
      `findByNameIgnoreCase`.
- [X] T018 [P] Create `backend/course-service/src/main/java/com/example/learnhub/course/infrastructure/CourseRepository.java`:
      Spring Data JPA repository on `Course`, extending `JpaSpecificationExecutor<Course>` for the
      catalog's combination of filters/sort/pagination (research.md R4), plus `countByCategoryId` (for
      FR-021's category-deletion guard, research.md R7).
- [X] T019 [P] Create `backend/course-service/src/main/java/com/example/learnhub/course/infrastructure/LessonRepository.java`:
      Spring Data JPA repository on `Lesson` with `findByCourseIdOrderByPosition`,
      `countByCourseId`.
- [X] T020 Create `backend/course-service/src/main/java/com/example/learnhub/course/domain/PublishReadiness.java`:
      `violations(Course course)` returns the specific missing parts among title (non-blank),
      description (non-blank), categoryId (set), priceCents (set — zero counts as set, FR-015), and at
      least one lesson (data-model.md Course "Publish-readiness"); used both when publishing (US3) and
      when editing an already-published course (FR-018, US2).
- [X] T021 Add five routes to `backend/config-repo/api-gateway.yml` (research.md R2, ordered as
      written — public routes first, protected catch-alls last):
      1. `courses-public-list`: `uri: lb://course-service`, `predicates: ["Path=/api/v1/courses",
         "Method=GET"]`, `metadata.public: true`.
      2. `courses-public-detail`: `uri: lb://course-service`, `predicates: ["Path=/api/v1/courses/*",
         "Method=GET"]` (single-segment `*`, not `**` — research.md R2), `metadata.public: true`,
         ordered after #1.
      3. `categories-public-list`: `uri: lb://course-service`, `predicates: ["Path=/api/v1/categories",
         "Method=GET"]`, `metadata.public: true`.
      4. `categories-admin`: `uri: lb://course-service`, `predicates: ["Path=/api/v1/categories/**"]`,
         `metadata.roles: [ADMINISTRATOR]`, ordered after #3.
      5. `courses-manage`: `uri: lb://course-service`, `predicates: ["Path=/api/v1/courses/**"]`
         (double-star catch-all), no metadata (protected, any authenticated caller — ownership/role
         nuance enforced in course-service, research.md R9), ordered after #1 and #2.
      Do **not** add any route for `/api/v1/internal/**` or `/api/v1/my/**-as-course-detail` — the
      instructor's own listing lives at `/api/v1/my/courses` (its own top-level prefix, no route
      collision risk by construction) and is covered by its own route in this same edit:
      6. `my-courses`: `uri: lb://course-service`, `predicates: ["Path=/api/v1/my/courses"]`, no
         metadata (protected).
- [X] T022 Extend `backend/api-gateway/src/test/java/com/example/learnhub/gateway/RealGatewayConfigTest.java`:
      parse the real `api-gateway.yml` (T021) and assert `courses-public-list` and
      `courses-public-detail` have `metadata.public: true`, `categories-admin` has
      `metadata.roles: [ADMINISTRATOR]`, and `courses-manage`/`my-courses` have none of those, each
      ordered after the public routes that share a path prefix with it.
- [X] T023 Create `backend/api-gateway/src/test/java/com/example/learnhub/gateway/course/CourseRoutingIT.java`
      (mirrors `EchoSecureAccessIT`'s real-context, WireMock-stubbed-upstream pattern — the pattern that
      caught 002's real routing bug, research.md R2's stated verification plan): with a WireMock
      upstream standing in for `course-service`, assert `GET /api/v1/courses` and
      `GET /api/v1/courses/{id}` succeed with no token; assert `GET /api/v1/my/courses` with no token is
      refused (401) — proving it was never accidentally swallowed by the public single-course-detail
      route; assert `POST /api/v1/courses` with no token is refused (401); assert
      `POST /api/v1/categories` with a non-administrator token is refused (403).
- [X] T024 [P] Create `backend/course-service/src/main/java/com/example/learnhub/course/infrastructure/ConcurrencyControl.java`:
      reads the `If-Match` request header and the target `Course`'s `version`; throws a 409
      (`ResponseStatusException`) naming the conflict if the header is missing or does not match
      (FR-025, research.md R1); on success, provides the value to set as the response `ETag`.

**Checkpoint**: Foundation compiles and migrates against a Testcontainers Postgres; the real gateway
routing test (T023) passes; no story-level behavior exists yet.

---

## Phase 3: User Story 1 - Browse and view the public catalog (Priority: P1) 🎯 MVP

**Goal**: Anyone, without logging in, can list, sort, filter, search and open published courses; drafts
and archived courses never appear, and a draft/archived/unknown id all look identically "not found."

**Independent Test**: Seed a mix of draft, published and archived courses directly via the repository in
test setup (no write API needed yet), then call the public list and detail endpoints with no
credentials and confirm only published ones are ever visible.

### Tests for User Story 1 ⚠️ write first, confirm they fail

- [X] T025 [P] [US1] Write `backend/course-service/src/test/java/com/example/learnhub/course/CatalogListIT.java`
      (Testcontainers): seeded mix of draft/published/archived courses; list returns only published
      (FR-005); sort by newest (`publishedAt` descending, research.md R5), title, price ascending and
      price descending each produce the expected order; filtering by category, by level, and by
      free-vs-paid (`priceCents = 0` vs `> 0`) each narrow correctly; a keyword search matching no
      course's title or description returns an empty `items` array with 200, not an error (FR-004);
      pagination (`page`/`size`) returns every published course exactly once across pages.
- [X] T026 [P] [US1] Write `backend/course-service/src/test/java/com/example/learnhub/course/CourseDetailIT.java`
      (Testcontainers): a published course's public detail includes title, description, categoryId,
      level, language, priceCents, instructorId and a lesson outline (title, position, durationMinutes)
      with no `content` field anywhere (FR-006); a draft, an archived course, and a random unknown id
      each produce byte-for-byte the same 404 problem-details shape except `instance` (FR-007).

### Implementation for User Story 1

- [X] T027 [US1] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/CatalogQueryService.java`:
      builds a `CourseRepository` `Specification` from page/size/sort/category/level/free/q parameters
      (research.md R4), always anding in `status = PUBLISHED`.
- [X] T028 [US1] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/CourseDetailService.java`:
      `viewPublic(courseId)` returns the public view only when `status = PUBLISHED`, else throws the
      same 404 regardless of whether the course is a draft, archived, or doesn't exist (FR-007).
- [X] T029 [US1] Create `backend/course-service/src/main/java/com/example/learnhub/course/api/CourseController.java`
      with `GET /api/v1/courses` (delegates to `CatalogQueryService`) and
      `GET /api/v1/courses/{courseId}` (delegates to `CourseDetailService`); map results through a
      `CoursePublicView` response record (id, title, description, categoryId, level, language,
      priceCents, currency, instructorId, publishedAt, lessons-outline-only).
- [X] T030 [US1] Run `CatalogListIT` and `CourseDetailIT` (T025–T026) and confirm they now pass.

**Checkpoint**: The public catalog works end to end, independent of every other story (seeded directly,
not through a write API yet).

---

## Phase 4: User Story 2 - Instructors create and manage their courses (Priority: P1)

**Goal**: A logged-in instructor (or administrator) creates a draft, edits it, manages its lessons, and
sees their own courses in every status; nobody else can touch a course they don't own.

**Independent Test**: One instructor account creates a draft, adds and reorders lessons; a second
instructor account cannot read or change it; a student account cannot create one at all.

### Tests for User Story 2 ⚠️ write first, confirm they fail

- [X] T031 [P] [US2] Write `backend/course-service/src/test/java/com/example/learnhub/course/CourseManagementIT.java`
      (Testcontainers): an instructor creates a course and it is a draft owned by them (FR-008); a
      student's create attempt is refused (FR-009); the owner edits their course's details and the
      change is saved; a different instructor's edit attempt on it is refused (FR-011); an
      administrator's edit on it succeeds the same as the owner's (US2 acceptance scenario 7);
      `GET /api/v1/my/courses` for the owner includes the draft and never includes another
      instructor's courses (FR-012); the owner deletes their draft and it is gone (FR-013); a published
      course's delete attempt (seeded directly) is refused regardless of caller (FR-013).
- [X] T032 [P] [US2] Write `backend/course-service/src/test/java/com/example/learnhub/course/LessonManagementIT.java`
      (Testcontainers): the owner adds a lesson (title, durationMinutes, content) and it appears at the
      end of the order; the owner edits a lesson's title/duration/content; the owner removes a lesson
      and remaining lessons keep contiguous positions; the owner reorders lessons by submitting the
      full id list in a new order, and it takes effect; a reorder submitting a list that omits one of
      the course's lessons or duplicates one is refused (422, spec.md Edge Cases); a different
      instructor's attempt to add/edit/remove/reorder lessons on this course is refused (FR-011).

### Implementation for User Story 2

- [X] T033 [US2] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/OwnershipGuard.java`:
      `requireOwnerOrAdmin(Course course, UUID callerId, String callerRole)` throws 403 unless
      `callerRole == "ADMINISTRATOR"` or `course.getInstructorId().equals(callerId)` (FR-011).
- [X] T034 [US2] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/CreateCourseService.java`:
      refuses (403) a caller whose role is `STUDENT` (FR-009); otherwise creates a `Course` with
      `status = DRAFT` and `instructorId = callerId` (FR-008, spec.md Clarifications 2026-10-18: an
      administrator creating a course also becomes its owning instructor of record).
- [X] T035 [US2] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/EditCourseService.java`:
      uses `OwnershipGuard` (T033) and `ConcurrencyControl` (T024); if the course's current status is
      `PUBLISHED`, after applying the edit in memory it re-runs `PublishReadiness.violations()` (T020)
      against the edited state and refuses (409, naming the violations) rather than saving, if the edit
      would have broken it (FR-018).
- [X] T036 [US2] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/DeleteCourseService.java`:
      uses `OwnershipGuard`; refuses (409) unless `status == DRAFT` (FR-013).
- [X] T037 [US2] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/LessonService.java`:
      `add`, `edit`, `remove` — each uses `OwnershipGuard` and `ConcurrencyControl` on the parent
      course; `remove` renumbers remaining positions to stay contiguous (data-model.md Lesson); `add`
      appends at `max(position) + 1`.
- [X] T038 [US2] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/ReorderLessonsService.java`:
      accepts the full ordered list of the course's lesson ids; refuses (422) unless it contains
      exactly the course's current lesson ids, each exactly once (spec.md Edge Cases); otherwise applies
      the new `position` values.
- [X] T039 [US2] Extend `CourseController` (T029) with `POST /api/v1/courses` (T034),
      `PUT /api/v1/courses/{courseId}` (T035) and `DELETE /api/v1/courses/{courseId}` (T036); every
      mutating method reads `If-Match` via `ConcurrencyControl` and sets the response `ETag`; map to a
      `CourseFullView` response record (public fields plus `status`, `version`, and lessons with
      `content` included — only ever returned to the owner/administrator, since this controller method
      requires a valid token and the ownership check happens before the mapping).
- [X] T040 [US2] Create `backend/course-service/src/main/java/com/example/learnhub/course/api/LessonController.java`:
      `POST /api/v1/courses/{courseId}/lessons`, `PUT /api/v1/courses/{courseId}/lessons/{lessonId}`,
      `DELETE /api/v1/courses/{courseId}/lessons/{lessonId}`,
      `PATCH /api/v1/courses/{courseId}/lessons/reorder`.
- [X] T041 [US2] Create `backend/course-service/src/main/java/com/example/learnhub/course/api/MyCoursesController.java`:
      `GET /api/v1/my/courses` returns every course where `instructorId == caller's X-User-Id`, any
      status, mapped through `CourseFullView`.
- [X] T042 [US2] Run `CourseManagementIT` and `LessonManagementIT` (T031–T032) and confirm they now pass.

**Checkpoint**: Instructors (and administrators) can fully author courses and lessons, independent of
publish/archive/categories.

---

## Phase 5: User Story 3 - Publish and archive (Priority: P2)

**Goal**: A complete draft can be published into the catalog; archiving pulls it back out without
deleting it; it can be republished.

**Independent Test**: Publish a complete draft (seeded with a category and a lesson via US2's
machinery) and confirm it now appears in the public catalog (US1); archive it and confirm it disappears
again without being deleted; publish it a second time.

### Tests for User Story 3 ⚠️ write first, confirm they fail

- [X] T043 [P] [US3] Write `backend/course-service/src/test/java/com/example/learnhub/course/PublishArchiveIT.java`
      (Testcontainers): a draft with a title, description, category, price and one lesson publishes
      successfully and then appears in `GET /api/v1/courses` (FR-014); a draft missing any of those is
      refused with a 422 naming every missing part (FR-014); publishing sets `publishedAt`
      (research.md R5); archiving a published course removes it from the public list while
      `GET /api/v1/courses/{id}/full` (owner) still returns it fully intact (FR-016); publishing an
      archived course again succeeds and refreshes `publishedAt` (FR-017, research.md R5); a
      non-owner/non-administrator's publish or archive attempt is refused (FR-011 applied to these
      actions).

### Implementation for User Story 3

- [X] T044 [US3] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/PublishCourseService.java`:
      uses `OwnershipGuard` and `ConcurrencyControl`; refuses (422, naming violations) unless
      `PublishReadiness.violations()` (T020) is empty; otherwise sets `status = PUBLISHED` and
      `publishedAt = now()` (every publish, including a republish, refreshes it — research.md R5).
- [X] T045 [US3] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/ArchiveCourseService.java`:
      uses `OwnershipGuard` and `ConcurrencyControl`; sets `status = ARCHIVED`; `publishedAt` is left
      unchanged (it is only touched by a publish, research.md R5).
- [X] T046 [US3] Add `PATCH /api/v1/courses/{courseId}/publish` and
      `PATCH /api/v1/courses/{courseId}/archive` to `CourseController` (T029/T039).
- [X] T047 [US3] Create `backend/course-service/src/main/java/com/example/learnhub/course/api/CourseFullController.java`
      (or extend `CourseController`): `GET /api/v1/courses/{courseId}/full` — uses `OwnershipGuard`,
      returns `CourseFullView` including every lesson's `content` (FR-024 is fully exercised once this
      exists; see US5).
- [X] T048 [US3] Run `PublishArchiveIT` (T043) and confirm it now passes.

**Checkpoint**: Publish/archive work end to end against the real catalog (US1) and real authoring (US2).

---

## Phase 6: User Story 4 - Categories (Priority: P2)

**Goal**: Administrators create, rename and delete categories; non-administrators cannot; a category
still referenced by a course cannot be deleted.

**Independent Test**: An administrator creates a category and renames it; a non-administrator's attempt
at either is refused; deleting a category a seeded course still uses is refused.

### Tests for User Story 4 ⚠️ write first, confirm they fail

- [X] T049 [P] [US4] Write `backend/course-service/src/test/java/com/example/learnhub/course/CategoryManagementIT.java`
      (Testcontainers): an administrator creates a category with a unique name (FR-019); creating one
      with a name colliding case-insensitively with an existing category is refused (FR-020); an
      administrator renames a category and a course already in it keeps the (renamed) category
      (US4 acceptance scenario 3); deleting a category with zero courses succeeds (FR-019); deleting a
      category at least one seeded course still references is refused (FR-021); a non-administrator
      (including an instructor) attempting create, rename or delete is refused (FR-022) — note: the
      gateway's `categories-admin` route (T021) already blocks a non-administrator before the request
      reaches this service; this test calls the service's own layer directly to prove the invariant
      holds independent of the gateway too (defense in depth, matching 002's pattern for
      `ChangeRoleService`/`auth-admin`); a rename or delete submitted with a missing or stale `If-Match`
      is refused with 409 (FR-025, analyze finding G1).

### Implementation for User Story 4

- [X] T050 [US4] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/CategoryService.java`:
      `create`/`rename` refuse (409) a name colliding case-insensitively with any other category's
      name — excluding the category's own current name on rename (FR-020); `rename`/`delete` use
      `ConcurrencyControl` (T024) against the category's `version`, refusing (409) a missing or stale
      `If-Match` (FR-025, analyze finding G1); `delete` refuses (409, naming how many courses still
      reference it) when `CourseRepository.countByCategoryId(id) > 0` (FR-021, research.md R7).
- [X] T051 [US4] Create `backend/course-service/src/main/java/com/example/learnhub/course/api/CategoryController.java`:
      `GET /api/v1/categories` (public), `POST /api/v1/categories`,
      `PATCH /api/v1/categories/{categoryId}` (requires `If-Match`),
      `DELETE /api/v1/categories/{categoryId}` (requires `If-Match`).
- [X] T052 [US4] Run `CategoryManagementIT` (T049) and confirm it now passes.

**Checkpoint**: Categories are fully manageable and the filter in US1 has real data to filter against.

---

## Phase 7: User Story 5 - Lesson content stays private until enrollment (Priority: P3)

**Goal**: Only the owning instructor and administrators can ever read a lesson's actual content.

**Independent Test**: The owning instructor and an administrator can read a lesson's full content via
`GET /api/v1/courses/{id}/full` (US3/T047); a different instructor, a student, and an anonymous caller
cannot reach that endpoint at all, and the public detail view (US1) never includes it.

### Tests for User Story 5 ⚠️ write first, confirm it fails

- [X] T053 [P] [US5] Write `backend/course-service/src/test/java/com/example/learnhub/course/LessonContentAccessIT.java`
      (Testcontainers): the owning instructor's `GET /api/v1/courses/{id}/full` includes every lesson's
      `content` (FR-024); an administrator's call to the same endpoint for someone else's course also
      includes it; a different instructor's and a student's call to it are refused (403); an anonymous
      call to it is refused (401, enforced at the gateway in production, simulated here by the absence
      of identity headers); `GET /api/v1/courses/{id}` (the public view, US1) never contains a
      `content` field anywhere in its body, confirmed by a raw string search of the response.

### Implementation for User Story 5

No new implementation: this story is fully satisfied by `OwnershipGuard` (T033) and the
`CoursePublicView`/`CourseFullView` split (T029, T039/T047) already built for US1–US3. This phase exists
to prove that cross-cutting property explicitly, as spec.md itself notes ("doesn't unlock new capability
on its own").

- [X] T054 [US5] Run `LessonContentAccessIT` (T053) and confirm it passes against the existing
      implementation. If it fails, the gap is in `OwnershipGuard` or the view split from an earlier
      story, not new code to write here — fix it there.

**Checkpoint**: The lesson-content privacy boundary is explicitly proven, not just assumed from US1–US3.

---

## Phase 8: User Story 6 - Other services look up a course (Priority: P3)

**Goal**: Another backend service can retrieve a course's status, price, currency, instructor and
lesson count by id, regardless of status — and no client can reach this through the entry point.

**Independent Test**: Call `GET /api/v1/internal/courses/{id}` directly on course-service (not through
the gateway) for a known and an unknown id; separately, confirm the gateway has no route that reaches
this path at all (T023 already covers the gateway side; this phase covers the service side).

### Tests for User Story 6 ⚠️ write first, confirm it fails

- [X] T055 [P] [US6] Write `backend/course-service/src/test/java/com/example/learnhub/course/InternalLookupIT.java`
      (Testcontainers): looking up an existing course (seeded in any status — draft, published or
      archived) returns its `status`, `priceCents`, `currency`, `instructorId` and `lessonCount`
      (FR-023); looking up an unknown id returns a clear 404.

### Implementation for User Story 6

- [X] T056 [US6] Create `backend/course-service/src/main/java/com/example/learnhub/course/application/CourseLookupService.java`:
      `summarize(courseId)` returns the `CourseSummary` projection (data-model.md) for any status, or
      throws 404 if the id doesn't exist.
- [X] T057 [US6] Create `backend/course-service/src/main/java/com/example/learnhub/course/api/InternalCourseController.java`:
      `GET /api/v1/internal/courses/{courseId}`. No gateway route targets this path (T021 deliberately
      omits one, research.md R6); it is reachable only by another service calling
      `lb://course-service` directly.
- [X] T058 [US6] Run `InternalLookupIT` (T055) and confirm it now passes.

**Checkpoint**: All six user stories are independently functional.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T059 [P] Create `backend/course-service/src/test/java/com/example/learnhub/course/ConcurrencyIT.java`
      (Testcontainers): two concurrent edit requests to the same course, the second submitted with the
      `ETag` the course had *before* the first one's edit lands, result in exactly one success and one
      409 naming the conflict, across at least 10 repeated trials, with zero silent overwrites (FR-025,
      SC-006).
- [X] T060 [P] Create `backend/course-service/src/test/java/com/example/learnhub/course/CatalogPerformanceIT.java`
      (Testcontainers): seed 10,000 published courses across varied categories/levels/prices; a single
      filtered, sorted, paginated `GET /api/v1/courses` call returns within 2 seconds (SC-004).
- [X] T061 [P] Update `README.md`: course-service's port-table row changes from "reserved, not yet
      built" to 9002 (built); add a short "Course catalog" section mirroring the existing "Accounts and
      login" section, pointing at `specs/003-course-catalog/contracts/course-service.openapi.yaml` and
      `quickstart.md`.
- [X] T062 [P] Update `HOWTORUN.md`: add course-service to the component table and startup output;
      replace any now-stale "none of their code exists yet" wording for course-service; add a curl
      walkthrough section (category create, draft create, lesson add, publish-too-early 422, publish,
      public catalog visibility, archive, the internal-lookup 404-at-the-gateway check) — every command
      re-run against the live platform before being written down, per this file's own established
      discipline (see its git history from 002).
- [X] T063 Run `scripts/smoke-test.sh` against the full platform with `course-service` included and
      confirm the existing platform-foundation and auth checks (SC-008) still pass unchanged.
- [X] T064 Walk through `specs/003-course-catalog/quickstart.md` section by section against the running
      platform; fix any command whose real output does not match what is documented.
- [X] T065 Create `specs/003-course-catalog/checklists/success-criteria.md` (mirroring
      `specs/002-user-auth/checklists/success-criteria.md`'s format) recording, for each of SC-001 to
      SC-008, which automated test or quickstart step verifies it, and the measured result.
- [X] T066 [P] Run shellcheck (the existing Docker-image approach) on `scripts/up.sh`, since this
      feature edited it (T007).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup. BLOCKS every user story.
- **US1 (Phase 3)**: Depends on Foundational only — seeds its own test data directly, no write API
  needed yet.
- **US2 (Phase 4)**: Depends on Foundational only.
- **US3 (Phase 5)**: Depends on Foundational and US2 (needs a draft with lessons to publish) and, to be
  demonstrated end to end, US1 (to observe the published course in the catalog) and US4 (a real category
  — though its own tests may seed a category row directly without the Category API).
- **US4 (Phase 6)**: Depends on Foundational only.
- **US5 (Phase 7)**: Depends on US1 and US2/US3 (the full-detail endpoint and ownership guard it tests
  are built there); adds no new code of its own.
- **US6 (Phase 8)**: Depends on Foundational only.
- **Polish (Phase 9)**: Depends on all six stories.

### Within Each User Story

- Tests are written and confirmed failing before implementation (constitution Principle VI).
- Domain/infrastructure before application services; application services before controllers.

### Parallel Opportunities

- Setup tasks marked `[P]` (T003–T005, T007–T008).
- Foundational tasks marked `[P]` (T012–T014, T017–T019, T024).
- US1's two test files (T025–T026); US2's two test files (T031–T032); US3/US4/US5/US6 each have one
  test file, markable `[P]` against any other story's own test file.
- Once Foundational (Phase 2) is done, US1, US2, US4 and US6 have no dependency on each other and could
  be staffed in parallel; US3 and US5 each need at least one other story's output to be meaningful end
  to end, so sequence them after.

---

## Parallel Example: User Story 1

```bash
Task: "CatalogListIT in backend/course-service/src/test/java/com/example/learnhub/course/CatalogListIT.java"
Task: "CourseDetailIT in backend/course-service/src/test/java/com/example/learnhub/course/CourseDetailIT.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1: Setup
2. Phase 2: Foundational (blocks everything)
3. Phase 3: User Story 1
4. **STOP and VALIDATE**: catalog list/detail work against seeded data, independently
5. Demo if ready — proves the new module, its database, and the gateway's new public routes all work

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. US1 → validate → demo (MVP, against seeded data)
3. US2 → validate → demo (real authoring, including lessons)
4. US4 → validate → demo (categories — can run before or after US3)
5. US3 → validate → demo (publish/archive, now with real authoring and real categories available)
6. US5 → validate → demo (privacy boundary proven)
7. US6 → validate → demo (internal lookup)
8. Polish

## Notes

- `[P]` tasks touch different files with no unmet dependency.
- Every data-model.md field constraint is quoted verbatim in the task that creates it (T009–T011,
  T015–T016), so it is not left to implementation-time discretion.
- Commit after each task or logical group; stop at any checkpoint to validate a story independently.
- Avoid: skipping the "write test, watch it fail" step — Principle VI is non-negotiable, not a default.
