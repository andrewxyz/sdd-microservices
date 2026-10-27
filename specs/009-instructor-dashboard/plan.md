# Implementation Plan: Instructor Dashboard

**Branch**: `009-instructor-dashboard` | **Date**: 2026-10-27 | **Spec**: [spec.md](./spec.md)

## Summary

Add a read-model service, `analytics-service` (host port 9008), that consumes `payment.succeeded`,
`enrollment.created`, `course.completed` and `certificate.issued` from `learning.events` and keeps
per-course, per-UTC-day counters with no personal data. It serves three read endpoints under
`/api/v1/instructor`. Ownership is resolved at query time through course-service (Feign), failing
closed with 503. course-service gains one internal list endpoint. The gateway gets one protected,
role-restricted route. The student web app gains `/instructor` and `/instructor/courses/:id` behind a
`RequireRole` guard. Covers **both** `backend/` and `frontend/`.

## Corrections to the brief (names verified in source)

| Brief said | Actual in the repo | Plan uses |
|---|---|---|
| `GET /internal/v1/courses/{id}` | `GET /api/v1/internal/courses/{courseId}` (`InternalCourseController`, contract `specs/005-.../course-service-internal.openapi.yaml`) | `/api/v1/internal/courses` for the new list and the existing detail |
| role `ADMIN` | role `ADMINISTRATOR` (gateway `roles: [ADMINISTRATOR]`, `MeView.role`) | `INSTRUCTOR` and `ADMINISTRATOR` |
| `GET /api/v1/courses/mine` | `GET /api/v1/my/courses` (`MyCoursesController`, gateway route `my-courses`) | `/api/v1/my/courses` (no frontend helper exists yet; add `listMyCourses`) |
| `analytics_db` / `analytics_user` | db-init creates database and role named after the service: `analytics` / `analytics` (`SERVICES="auth course ... notification"`) | `analytics` / `analytics`, env var `ANALYTICS_DB_PASSWORD`; constitution table wording to be corrected (PATCH) |
| payment payload in shared-events | `PaymentSucceededPayload` is a private copy in enrollment-service; no `payment.succeeded` constant in `LearningEvents` | analytics-service keeps its own payload copy and routing-key constant (Principle II) |
| "reuse the existing CSS file" (plain CSS) | `src/styles.css` is only `@import "tailwindcss"`; pages use Tailwind utility classes | Tailwind classes, `sr-only` for the hidden table |
| smoke test pays with `tok_success` | payment-service accepts `sim-success` (simulator token, `SimulatedPaymentProvider`) | `sim-success` |
| Dockerfile only needs MODULE arg | Dockerfile also `COPY`s each module's `pom.xml` for the dependency-cache layer; parent POM lists modules | add `analytics-service` to both |

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5.15, Spring Cloud 2025.0.3); TypeScript strict, React 19
**Primary Dependencies**: existing only. analytics-service mirrors notification-service's POM (web, data-jpa, validation, amqp, flyway, openfeign, circuitbreaker-resilience4j, eureka client, config, actuator, platform-common, shared-events). No new frontend dependency; no chart library.
**Storage**: PostgreSQL database `analytics` (Flyway V1: `course_daily_stats`, `processed_events`)
**Testing**: JUnit 5, Testcontainers (PostgreSQL + RabbitMQ), WireMock for Feign contract tests; Vitest + Testing Library; extended `scripts/smoke-test.sh`
**Target Platform**: Docker Compose stack; SPA behind nginx
**Project Type**: web application (backend + frontend)
**Performance Goals**: figures visible within 10 s of the source event (single consumer path, one transaction per message)
**Constraints**: constitution III, V, VI, VII, VIII, IX, XIV; no personal data stored; fail closed on ownership lookup
**Scale/Scope**: 1 new service (3 endpoints, 1 consumer, 2 tables), 1 internal endpoint, 1 gateway route, 2 pages, 1 guard

## Constitution Check

| Principle | Status |
|---|---|
| I Layout: new module under `backend/`, frontend stays independent; spec names both | PASS |
| II Data isolation: own database; reads no other DB; payload copy kept locally | PASS (see note) |
| III Discovery/config: Eureka registration, non-optional config import with fail-fast | PASS |
| IV Gateway only entry: one protected route, frontend uses `/api/` | PASS |
| V Communication: Feign with timeouts, circuit breaker, fallback; one queue + DLQ, idempotent on `eventId`, 3 retries | PASS |
| VI Test-first: Testcontainers ITs, Feign contract tests, Vitest, smoke test, written before code | PASS |
| VII Security: gateway role gate plus service-side role check; no secrets committed (`ANALYTICS_DB_PASSWORD` placeholder) | PASS |
| VIII Errors/observability: problem details with `correlationId`, health probes, MDC correlation | PASS |
| IX Conventions: `com.example.learnhub.analytics`, `/api/v1`, Flyway, versions in parent POM, port 9008 | PASS |
| X Simplicity: new service is the brief's explicit scope; no new library | PASS |
| XI-XIII Frontend: no new deps, server text rendered as text, generated types, same-origin `/api/` | PASS |
| XIV Read-model: events only, no personal data, Feign at query time never in event handling | PASS |

**Notes**: Principle II still says "seven business services"; the amendment record (1.3.0) flags the
count for reconciliation. XI says "plain CSS" while the existing app uses Tailwind (since spec 006);
this plan follows existing practice and does not widen the deviation. No violations needing
Complexity Tracking.

## Project Structure

```text
specs/009-instructor-dashboard/
  spec.md plan.md research.md data-model.md quickstart.md tasks.md
  contracts/analytics-service.openapi.yaml
  contracts/course-service-internal-list.openapi.yaml   # addendum to the 005 internal contract

backend/analytics-service/                 # NEW module
  pom.xml
  src/main/java/com/example/learnhub/analytics/
    AnalyticsServiceApplication.java
    api/        InstructorStatsController, response records, role guard
    application/ StatsQueryService, StatsIngestService, EventDispatchListener
    domain/     CourseDailyStats, completion-rate and series zero-fill helpers
    infrastructure/ StatsRepository (native upsert), ProcessedEventRepository,
                CourseOwnershipClient (+FallbackFactory), RabbitTopologyConfig
  src/main/resources/{application.yml, db/migration/V1__create_analytics.sql}
  src/test/java/.../   unit tests, *IT (Testcontainers), contract tests (WireMock)

backend/course-service/  InternalCourseController (+ list), CourseLookupService, CourseRepository query
backend/api-gateway/     tests extended (RealGatewayConfigTest, role tests)
backend/config-repo/     analytics-service.yml (new), api-gateway.yml (new route)
backend/docker/          Dockerfile (+pom COPY), db-init/provision.sh (+analytics)
backend/pom.xml          + <module>analytics-service</module>
docker-compose.yml, .env.example, scripts/{up.sh,smoke-test.sh}, README.md

frontend/
  scripts/gen-api.mjs                      # + ["analytics", ".../contracts/analytics-service.openapi.yaml"]
  src/api/{endpoints.ts, schema.ts, generated/analytics.ts}
  src/auth/RequireRole.tsx                 # built on RequireAuth
  src/pages/{InstructorOverviewPage, InstructorCoursePage, NotAllowedPage}.tsx
  src/components/{StatTile, FunnelBars, DailyBarChart, RefreshBar}.tsx
  src/lib/{money.ts, stats.ts}             # cents -> USD, merge/sort/zero-fill helpers
  src/App.tsx                              # routes and role-gated nav link
```

**Structure Decision**: web application; the new service follows notification-service's layout and
listener-dispatch pattern (one `@RabbitListener` per queue dispatching on envelope type).

## Phase 0 / Phase 1 outputs

research.md (decisions), data-model.md (tables, response shapes), contracts/ (analytics API,
course-service list addendum), quickstart.md (validation run).
