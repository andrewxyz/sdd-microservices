# Implementation Plan: Review Service

**Branch**: `007-review-service` | **Date**: 2026-10-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-review-service/spec.md`

## Summary

Add `review-service`, the backend business service reserved at port 9006 and deferred since the
constitution's last amendment. Enrolled students rate (1-5) and optionally comment on courses they are
enrolled in; one review per (student, course); students can edit or delete their own review; anyone can
browse a course's reviews and its aggregate rating with no authentication. Enrollment is verified by
reusing enrollment-service's existing internal lookup endpoint (no change to enrollment-service). The
aggregate rating is computed directly from review-service's own data on every read — no RabbitMQ, outbox,
or new event type is introduced, because nothing outside review-service consumes a review event in this
spec's scope (research.md R1). This spec covers **backend only**; it touches `backend/` (new
`review-service` module, `backend/config-repo/review-service.yml`, `backend/pom.xml`,
`docker-compose.yml`) and does not touch `frontend/`.

## Technical Context

**Language/Version**: Java 21 (matches every other backend module)

**Primary Dependencies**: Spring Boot 3.5.15, Spring Cloud 2025.0.3 (Eureka client, Config client,
OpenFeign, LoadBalancer, Resilience4j circuit breaker), Spring Data JPA, Flyway, PostgreSQL driver,
Spring Validation. No `spring-boot-starter-amqp` (research.md R1 — no messaging needed).

**Storage**: PostgreSQL 16, database `review`, owned exclusively by review-service (Principle II).

**Testing**: JUnit 5 (unit, `*Test`), Testcontainers PostgreSQL + WireMock (integration, `*IT`, run by
`mvn verify`) — no Testcontainers RabbitMQ module needed. Feign contract test for the reused enrollment
lookup.

**Target Platform**: Linux container (Docker), orchestrated by the repo-root `docker-compose.yml`;
registers with Eureka, loads config from config-server, reached only through api-gateway.

**Project Type**: Backend microservice within the existing Spring Cloud monorepo (Option 2 shape already
established by the repo).

**Performance Goals**: Consistent with existing business services — no new performance target; standard
web-service latency (reads/writes well under 1s under normal load) per constitution defaults.

**Constraints**: Must not introduce a new technology/library beyond what's already approved (Principle X);
must not create a direct DB dependency on course-service or enrollment-service (Principle II); enrollment
check must degrade safely (reject the write, not silently approve it) when enrollment-service is
unavailable (R2).

**Scale/Scope**: One new backend module, 5 REST endpoints, 1 outbound Feign client, 1 database table, no
new shared-events routing keys, 2 new gateway routes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design below.*

| Principle | Check | Status |
|-----------|-------|--------|
| I. Monorepo layout | New module lives entirely under `backend/review-service/`; no frontend changes; nothing added at repo root beyond the existing `docker-compose.yml`/`pom.xml`/config-repo edits every service needs. | PASS |
| II. Service ownership & data isolation | review-service owns its own `review` database and the `Review` entity exclusively; no cross-service DB access; enrollment and course identity are UUIDs only, not foreign keys; enrollment data obtained via Feign, never a DB join. | PASS |
| III. Discovery & configuration | Registers with Eureka under `review-service`; loads config via non-optional `configserver:` import with fail-fast + retry, identical to every other business service. | PASS |
| IV. Gateway as only entry point | All client traffic goes through api-gateway; two new routes, one public (reads) one protected-by-default (writes), matching the existing public/protected split pattern; review-service is never reachable directly by the frontend. | PASS |
| V. Sync/async communication | The one synchronous call (enrollment lookup) is OpenFeign with a timeout, Resilience4j circuit breaker and a fallback (R2). No asynchronous event is published — justified in research.md R1 as the simplest design meeting the spec, since there is no consumer. This is a deliberate *absence* of the async pattern, not a deviation from it: Principle V governs how async communication must be built *when used*, and doesn't mandate a service publish events it has no consumer for. | PASS |
| VI. Test-first | Tests (contract test for the Feign client, Testcontainers-backed integration tests for the REST endpoints and the unique-constraint upsert behavior, unit tests for the profanity/length validator) are written before implementation, per `/speckit-tasks` ordering. | PASS (enforced at /speckit-tasks and /speckit-implement) |
| VII. Security & secrets | No passwords/tokens handled directly by review-service (identity arrives pre-verified from the gateway as `X-User-Id`); no new secret beyond the standard `REVIEW_DB_PASSWORD` env var, added to `.env.example`. | PASS |
| VIII. Errors & observability | All error responses use RFC 7807 problem-details with `correlationId`; Actuator health (liveness/readiness) exposed like every other business service. | PASS |
| IX. Conventions | Base package `com.example.learnhub.review` with `api`/`application`/`domain`/`infrastructure` layers; REST paths under `/api/v1`; schema changes via Flyway; versions inherited from the parent POM; port 9006 per the constitution's existing port map. | PASS |
| X. Simplicity | No RabbitMQ, no outbox, no new library — the smallest design that satisfies every functional requirement (research.md R1, R4). | PASS |
| XI–XIII. Frontend | Not applicable — this spec is backend-only. A future frontend spec would consume the OpenAPI contract in `contracts/review-service.openapi.yaml`. | N/A |

No violations requiring the Complexity Tracking table.

## Project Structure

### Documentation (this feature)

```text
specs/007-review-service/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   └── review-service.openapi.yaml
└── tasks.md              # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                  # add <module>review-service</module>
├── config-repo/
│   └── review-service.yml                   # new — minimal, config-import-only (research.md R6)
└── review-service/                          # new module
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/com/example/learnhub/review/
        │   │   ├── ReviewServiceApplication.java
        │   │   ├── api/
        │   │   │   ├── ReviewController.java          # POST/PUT/DELETE/GET .../reviews/...
        │   │   │   ├── ReviewView.java
        │   │   │   └── CourseRatingSummaryView.java
        │   │   ├── application/
        │   │   │   ├── ReviewService.java              # upsert, edit, delete, validation orchestration
        │   │   │   └── ReviewValidator.java            # profanity/length checks (R4)
        │   │   ├── domain/
        │   │   │   └── Review.java
        │   │   └── infrastructure/
        │   │       ├── ReviewRepository.java
        │   │       ├── EnrollmentLookupClient.java      # Feign, mirrors course-service's own
        │   │       ├── EnrollmentLookupClientFallback.java
        │   │       └── EnrollmentLookupUnavailableException.java
        │   └── resources/
        │       ├── application.yml
        │       └── db/migration/
        │           └── V1__create_reviews.sql
        └── test/
            └── java/com/example/learnhub/review/
                ├── ReviewSubmissionIT.java
                ├── ReviewOwnershipIT.java
                ├── ReviewValidationTest.java
                ├── EnrollmentLookupClientContractTest.java
                └── support/
                    ├── AbstractReviewServiceIT.java
                    └── EnrollmentServiceStub.java

docker-compose.yml        # add a review-service block (own Postgres DB "review", port 9006:9006)
.env.example               # add REVIEW_DB_PASSWORD
```

**Structure Decision**: Follows the established backend microservice layout (Option 2's backend half,
already in force across every existing service) — one new sibling module under `backend/`, registered in
the parent POM, with the standard `api`/`application`/`domain`/`infrastructure` package split
(Principle IX). No frontend directories are touched by this spec.

## Complexity Tracking

*No entries — Constitution Check passed with no violations.*
