# Implementation Plan: Course Catalog

**Branch**: `003-course-catalog` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-course-catalog/spec.md`. No additional technical brief
was given to `/speckit-plan`; every technical decision below was made here and is recorded, with
rationale, in [research.md](research.md).

**Scope**: Backend only. Adds `backend/course-service` (new), `backend/config-repo/course-service.yml`
(new), `backend/config-repo/api-gateway.yml` (five new routes), `backend/pom.xml` (one module entry, no
new dependency versions — everything course-service needs is already managed), `docker-compose.yml`
(new service entry on port 9002), and `.env.example` (nothing new — course-service's database password,
`COURSE_DB_PASSWORD`, already exists from platform-foundation). `frontend/` and every other business
service are untouched.

## Summary

Adds `course-service` (port 9002) owning courses, lessons and categories in its own `course` database.
The public catalog (list, search, single-course detail) is anonymous and gateway-public; everything
else — creating and managing a course and its lessons, publishing, archiving, and category
administration — requires a valid token, with ownership and role nuance enforced inside the service
(the gateway only distinguishes "public" from "needs a token," plus a flat ADMINISTRATOR-only rule for
category mutations, the same two mechanisms 002 already built and proved). Concurrent edits are guarded
by a standard HTTP `ETag`/`If-Match` optimistic-lock, and a dedicated internal lookup lets later specs
(payment, enrollment) ask course-service about a course without ever being reachable by a client.

## Technical Context

**Language/Version**: Java 21 (unchanged)

**Primary Dependencies**: Spring Boot 3.5.15 / Spring Cloud 2025.0.3 (unchanged versions, same BOM);
Spring Data JPA + Flyway (already managed, used the same way as 002's auth-service); no new library at
all — course-service needs nothing 002 didn't already introduce to `backend/pom.xml`, and it needs no
JWT/security library of its own since it only reads gateway-forwarded identity headers.

**Storage**: PostgreSQL 16, database `course` (already created, empty, by platform-foundation's
`db-init`).

**Testing**: JUnit 5 and Testcontainers (PostgreSQL), the same tools already approved; no new test
library.

**Target Platform**: Linux containers via the existing `docker-compose.yml`, registered with the
existing Eureka server, loading config from the existing config server — no new infrastructure.

**Project Type**: Backend microservice addition (new module) plus a small, additive extension of
`api-gateway`'s route configuration only (no gateway code changes — this feature needs no new filter,
unlike 002).

**Performance Goals**: SC-004 — a filtered, sorted, paginated catalog list over 10,000 published courses
returns within 2 seconds (research.md R4).

**Constraints**: Every mutating course/lesson/category request MUST carry a valid `If-Match` matching
the resource's current version, or be refused with 409 (FR-025, research.md R1); the service-to-service
lookup MUST have no gateway route at all (FR-023, research.md R6).

**Scale/Scope**: Single course-service instance for this feature (no scaled copy, matching
auth-service's own precedent — nothing in this spec needs more than one).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design below.*

| Principle | Check | Result |
|---|---|---|
| I. Monorepo layout | New module at `backend/course-service`; nothing added at the repo root beyond the already-permitted set | Pass |
| II. Service ownership & data isolation | course-service owns its own `course` database; no cross-service joins; the internal lookup (FR-023) is the only way another service learns anything about a course, and it returns a projection, not raw access | Pass |
| III. Discovery & configuration | Registers with Eureka as `course-service`; loads config via non-optional `spring.config.import=configserver:` with fail-fast + retry, same as every other service | Pass |
| IV. Gateway as only entry point | Public routes listed explicitly (FR-001, FR-006); everything else protected by default; the one path with no route at all (FR-023) is correctly *not* reachable by any client, which is the requirement, not a violation of "gateway is the only entry point" (it's still true — clients reach nothing of course-service's except through the gateway; other *services* reaching each other directly is Principle V's domain, not IV's) | Pass |
| V. Sync/async communication | The internal lookup (FR-023) is this feature's one synchronous inter-service surface; it is a plain REST endpoint resolved via `lb://course-service`, to be consumed by a future spec's own Feign client with its own circuit breaker — this feature does not add a Feign *client* itself, since nothing in 003 calls another service. No event is published or consumed. | Pass (nothing to violate yet; the contract this feature publishes sets up Principle V compliance for the specs that will consume it) |
| VI. Test-first | tasks.md (next command) sequences tests before implementation per story, as in 002 | Pass (enforced at tasks/implement time) |
| VII. Security & secrets | No new secret: course-service has no credentials of its own beyond the existing `COURSE_DB_PASSWORD` (already in `.env.example` since platform-foundation) | Pass |
| VIII. Errors & observability | Reuses `platform-common`'s `ProblemDetailsAdvice` and correlation filter, identical to auth-service; the one new status this feature needs (409, for both FR-013/FR-018's state conflicts and FR-025's concurrency conflicts, and 422 for FR-014's publish-readiness failures) are both already in the shared `problem-details.schema.json` enum — no further widening needed | Pass |
| IX. Conventions | Package `com.example.learnhub.course` with `api`/`application`/`domain`/`infrastructure`; `/api/v1` paths; Flyway migrations; port 9002 per the existing table | Pass |
| X. Simplicity | No new library, no new service beyond what the constitution already names (`course-service`, Principle II); the `ETag`/`If-Match` concurrency mechanism is a standard HTTP feature, not a new technology | Pass |

**Complexity Tracking**: None. Unlike 002 (which needed a constitution-adjacent note for two new
libraries), this feature introduces no library the constitution doesn't already cover.

## Project Structure

### Documentation (this feature)

```text
specs/003-course-catalog/
├── plan.md               # This file
├── research.md           # Phase 0 output
├── data-model.md          # Phase 1 output
├── quickstart.md          # Phase 1 output
├── contracts/
│   └── course-service.openapi.yaml
└── tasks.md               # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                   # + course-service module entry (no new dependency versions)
├── config-repo/
│   └── course-service.yml                    # new: refreshable settings (none required beyond defaults yet)
├── course-service/                           # new module
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/example/learnhub/course/
│       │   ├── CourseServiceApplication.java
│       │   ├── api/            # CourseController (public+owner routes), LessonController,
│       │   │                   # CategoryController, MyCoursesController, InternalCourseController
│       │   ├── application/    # CreateCourseService, EditCourseService, PublishCourseService,
│       │   │                   # ArchiveCourseService, DeleteCourseService, LessonService,
│       │   │                   # ReorderLessonsService, CategoryService, CourseLookupService
│       │   ├── domain/         # Course, Lesson, Category, CourseStatus, Level,
│       │   │                   # PublishReadiness (the FR-014/FR-018 completeness check)
│       │   └── infrastructure/ # CourseRepository, LessonRepository, CategoryRepository
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/
│       │       ├── V1__create_categories.sql
│       │       ├── V2__create_courses.sql
│       │       └── V3__create_lessons.sql
│       └── test/java/com/example/learnhub/course/
│           ├── support/AbstractCourseServiceIT.java   # Testcontainers Postgres, singleton pattern (research.md, 002 lesson)
│           ├── CatalogListIT.java            # list/sort/filter/search, FR-001 to FR-005
│           ├── CourseDetailIT.java           # public detail + identical-404, FR-006 to FR-007
│           ├── CourseManagementIT.java       # create/edit/delete/ownership, FR-008 to FR-013
│           ├── PublishArchiveIT.java         # FR-014 to FR-018
│           ├── CategoryManagementIT.java     # FR-019 to FR-022
│           ├── LessonContentAccessIT.java    # FR-024
│           ├── InternalLookupIT.java         # FR-023
│           ├── ConcurrencyIT.java            # FR-025
│           └── CatalogPerformanceIT.java     # SC-004, 10,000-row seed
│
└── config-repo/api-gateway.yml  # + courses-public-list, courses-public-detail, courses-manage,
                                  #   categories-public-list, categories-admin routes

docker-compose.yml       # + course-service entry (port 9002), depends_on discovery+config+db-init
```

## Constitution Check (post-design)

Re-checked after Phase 1 design above: no new conflicts. The gateway route additions follow the exact,
now-twice-proven pattern (narrow public routes ordered before a protected catch-all); the one new
subtlety (a single-segment `*` wildcard for the public course-detail route, research.md R2) is a
correct, documented use of an existing Spring Cloud Gateway predicate capability, not a new mechanism.

## Phase 0 and Phase 1 outputs

- [research.md](research.md) — 9 decisions (R1–R9), no `NEEDS CLARIFICATION` remaining.
- [data-model.md](data-model.md) — `Course` (aggregate root), `Lesson` (child), `Category`,
  `CourseSummary` (internal-lookup projection, not persisted).
- [contracts/course-service.openapi.yaml](contracts/course-service.openapi.yaml) — full endpoint
  contract, reusing `specs/001-platform-foundation/contracts/problem-details.schema.json` for every
  error shape (no widening needed this time).
- [quickstart.md](quickstart.md) — curl walkthrough of all 6 user stories plus a no-regression check
  against `scripts/smoke-test.sh`.
