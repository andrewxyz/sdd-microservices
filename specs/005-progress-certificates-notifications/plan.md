# Implementation Plan: Lesson Progress, Certificates, and Email Notifications

**Branch**: `005-progress-certificates-notifications` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/005-progress-certificates-notifications/spec.md`, plus a
detailed technical brief supplied with `/speckit-plan` (unlike 003 and 004, where no brief was given).
Every technical decision below either restates that brief directly, or — where the brief left a gap or
used a path/name that no longer matches the already-shipped codebase — records the judgment call made to
reconcile it, with rationale, in [research.md](research.md).

**Scope**: Backend only. Adds `backend/certificate-service` (new, port 9005) and
`backend/notification-service` (new, port 9007); extends `backend/enrollment-service` (lesson progress,
one-time course completion, its first outbox and its first outbound Feign client), `backend/course-service`
and `backend/auth-service` (one additive internal-endpoint change each); extends `backend/shared-events`
(three new event payloads and routing keys) and `backend/api-gateway` (three new routes, one of them with
a stricter, route-scoped rate limit — the gateway's first per-route limit). `docker-compose.yml`,
`backend/config-repo/`, `scripts/up.sh` and `README.md` get the same additive treatment every prior
service addition has. `.env.example` needs no new variable: `CERTIFICATE_DB_PASSWORD` and
`NOTIFICATION_DB_PASSWORD` already exist from platform-foundation, and both databases are already
provisioned, empty, by `db-init`. `frontend/` and every other business service are untouched.

## Summary

A student's progress through a course's lessons is now tracked in `enrollment-service` itself (the
service that already owns the enrollment relationship). Completing the last lesson flips the enrollment
to completed exactly once — guarded by a pessimistic lock plus a conditional update, so ten concurrent
completions of the last few lessons still produce exactly one `course.completed` event — written to a
transactional outbox in the same transaction, the same reliability pattern `payment-service` established
in 004. `certificate-service` consumes that event, looks up the course's title directly from
`course-service` (failing the message, not the certificate, if that call cannot be made — so the event is
retried rather than silently producing a titleless certificate), and issues exactly one certificate per
student per course, itself durably outboxed as `certificate.issued`. `notification-service` consumes both
that event and a new `enrollment.created` event (extending 004's existing payment-succeeded-to-enrollment
flow to also announce itself), resolves the recipient's email from a new auth-service internal lookup and
the course's title from the same course-service lookup certificate-service uses, and sends a plain-text
email through the platform's existing local Mailpit catcher — never logging the address or body. Every
consumer in this feature follows the same idempotent, three-retry-then-dead-letter discipline 004
established, extended now to a second and third consumer.

## Technical Context

**Language/Version**: Java 21 (unchanged)

**Primary Dependencies**: Spring Boot 3.5.15 / Spring Cloud 2025.0.3 (unchanged versions, same BOM).
`enrollment-service` gains `spring-cloud-starter-openfeign` and
`spring-cloud-starter-circuitbreaker-resilience4j` (its first outbound call, to course-service) plus
`spring-boot-starter-amqp` is already present from 004 (now also used to *publish*, not just consume).
`certificate-service` needs the same set `payment-service` already has (Feign, circuit breaker, AMQP,
Flyway, JPA). `notification-service` needs AMQP, Flyway, JPA, and — new to the platform —
`spring-boot-starter-mail` for its one outbound SMTP connection to Mailpit. `auth-service` needs nothing
new (its internal endpoint reuses `AccountRepository.findById`, already inherited from `JpaRepository`).
No library outside what the constitution already names or what 004 already introduced.

**Storage**: PostgreSQL 16, databases `certificate` and `notification` (already created, empty, by
platform-foundation's `db-init`); `enrollment-service`'s existing `enrollment` database gains two tables
and one column via new Flyway migrations. RabbitMQ 4.3, the existing `learning.events` exchange and
`learning.events.dlx` dead-letter exchange (both already running since 004; no new exchange).

**Testing**: JUnit 5 and Testcontainers (PostgreSQL and RabbitMQ), WireMock for the new Feign clients'
consumer-side contract tests (the established pattern from 004), plus a lightweight **provider-side**
contract check in `course-service` and `auth-service` themselves (a plain MockMvc assertion that the real
internal endpoint's JSON actually has the shape its contract promises) — new to this feature, closing the
one gap a purely consumer-side WireMock stub can never catch: the real provider silently drifting from
what the stub assumes. No new test library.

**Target Platform**: Linux containers via the existing `docker-compose.yml`, registered with the existing
Eureka server, loading config from the existing config server.

**Project Type**: Two new backend microservice modules, additive changes to three existing modules
(`enrollment-service`, `course-service`, `auth-service`), and a small, additive extension of
`api-gateway`'s routing *and* its rate-limit filter (the one genuine gateway code change this feature
needs, research.md R8).

**Performance Goals**: SC-006 — certificate issuance normally completes within 10 seconds of course
completion (mirrors 004's own 5-second enrollment target, doubled for the extra hop: completion →
certificate → — notification is not on this critical path).

**Constraints**: A course-completion check MUST fail closed (503) if course-service cannot be reached to
validate a lesson or compute progress (the brief's explicit instruction, consistent with 004's R4/R5
"refuse rather than guess" philosophy for anything the caller cannot itself confirm); a certificate
lookup failure MUST fail the *message* (so it retries), never silently issue a certificate with a missing
title; an email send or lookup failure MUST fail the *message* the same way; no email address or body may
ever be logged (FR-021/FR-022, the brief's explicit instruction).

**Scale/Scope**: Single instance of each new service for this feature, matching every prior service's own
precedent.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design below.*

| Principle | Check | Result |
|---|---|---|
| I. Monorepo layout | Two new modules at `backend/certificate-service`, `backend/notification-service`; nothing added at the repo root beyond the already-permitted set | Pass |
| II. Service ownership & data isolation | Each new service owns its own database (`certificate`, `notification`); `enrollment-service`'s progress/completion data lives in its own existing database; no cross-service joins anywhere | Pass — with one explicit, documented tension (research.md R1): the two new event *payload* shapes and their routing-key constants are centralized in `shared-events` at the brief's explicit instruction, where 004 deliberately kept `PaymentSucceededPayload` local to each service. Justified as a data-contract (no behavior), the same category `EventEnvelope` and `IdentityHeaders` already occupy there — not the business/domain *logic* Principle II actually forbids centralizing. |
| III. Discovery & configuration | Both new services register with Eureka under their own names and load config via non-optional `spring.config.import=configserver:` with fail-fast + retry, same as every other service | Pass |
| IV. Gateway as only entry point | All three new client-facing endpoints are listed as explicit gateway routes (research.md R9); the three internal lookups (course-service's extended endpoint, the new auth-service one, and no new endpoint needed for notification-service since it has none) get no gateway route at all, same R6/R4 precedent from 003/004, and a test proves it (research.md R9) | Pass |
| V. Sync/async communication | Three new Feign clients (enrollment→course, certificate→course, notification→auth and notification→course), each with a timeout, a Resilience4j circuit breaker and a fallback (research.md R3, R5); two new events (`course.completed`, `certificate.issued`) plus one not named in the brief's own shared-events bullet but required by its notification-service bullet (`enrollment.created`, research.md R2), all on the existing `learning.events` exchange using the shared `EventEnvelope`; every consumer has its own queue and DLQ, is idempotent by `eventId`, and retries three times with backoff before dead-lettering (research.md R6, R7) | Pass |
| VI. Test-first | tasks.md (next command) sequences tests before implementation per story; every new Feign client gets a consumer-side WireMock contract test *and* the provider gets its own shape-matching test (research.md, Testing above) | Pass (enforced at tasks/implement time) |
| VII. Security & secrets | No new secret: both new services use already-provisioned database passwords; Mailpit needs no credential (local catcher, no auth); the new auth-service internal endpoint returns only `id` and `email`, nothing else sensitive | Pass |
| VIII. Errors & observability | Reuses `platform-common`'s `ProblemDetailsAdvice` and correlation filter unchanged; a fail-closed 503 from an unreachable internal dependency is a normal `ResponseStatusException`, not a raw failure; email addresses and bodies are explicitly never logged (FR-021/FR-022) | Pass |
| IX. Conventions | Packages `com.example.learnhub.certificate` and `com.example.learnhub.notification`, each with `api`/`application`/`domain`/`infrastructure`; `/api/v1` paths (research.md R0 corrects the brief's own internal-path shorthand to match this); Flyway migrations numbered to not collide with 004's already-shipped ones (research.md R0); ports 9005 and 9007 per the existing table | Pass |
| X. Simplicity | No new library beyond `spring-boot-starter-mail` (already named in the constitution's Technology Stack as Mailpit's obvious client, not a new *technology* — Mailpit itself has run since platform-foundation); the stricter verification rate limit reuses the existing `TokenBucketRateLimiter` class a second time rather than introducing a new mechanism (research.md R8) | Pass |

**Complexity Tracking**: One explicit, justified deviation recorded above (Principle II, event-payload
centralization) — a deliberate choice in the brief, not an oversight; no other deviation.

## Project Structure

### Documentation (this feature)

```text
specs/005-progress-certificates-notifications/
├── plan.md               # This file
├── research.md           # Phase 0 output
├── data-model.md          # Phase 1 output
├── quickstart.md          # Phase 1 output
└── contracts/
    ├── enrollment-service-progress.openapi.yaml   # Addendum to progress/completion endpoints
    ├── certificate-service.openapi.yaml
    ├── course-service-internal.openapi.yaml       # Addendum: title + lessonIds on the existing lookup
    ├── auth-service-internal.openapi.yaml         # New internal lookup
    ├── course-completed.event.schema.json
    ├── certificate-issued.event.schema.json
    └── enrollment-created.event.schema.json
```

(`tasks.md` is Phase 2 output — `/speckit-tasks`, not created by this command.)

### Source Code (repository root)

```text
backend/
├── pom.xml                                   # + certificate-service, notification-service module entries
├── shared-events/src/main/java/com/example/learnhub/events/
│   ├── LearningEvents.java                   # + COURSE_COMPLETED, CERTIFICATE_ISSUED, ENROLLMENT_CREATED
│   ├── CourseCompletedPayload.java           # new
│   ├── CertificateIssuedPayload.java         # new
│   └── EnrollmentCreatedPayload.java         # new (research.md R2)
│
├── config-repo/
│   ├── certificate-service.yml               # new: refreshable settings placeholder
│   └── notification-service.yml              # new: SMTP host/port (not secrets) live here
│
├── auth-service/                             # existing module, additive change only
│   └── src/main/java/com/example/learnhub/auth/
│       ├── api/InternalAccountController.java        # new: GET /api/v1/internal/accounts/{id}
│       ├── api/AccountSummaryView.java                # new: {id, email} only
│       └── application/AccountLookupService.java      # new
│
├── course-service/                           # existing module, additive change only
│   └── src/main/java/com/example/learnhub/course/
│       ├── api/CourseSummaryView.java                 # + title, lessonIds (max 200)
│       └── application/CourseLookupService.java       # + title/lessonIds in the projection
│
├── enrollment-service/                       # existing module, additive change only
│   └── src/main/java/com/example/learnhub/enrollment/
│       ├── api/LessonProgressController.java          # new: PUT/DELETE .../lessons/{lessonId}/complete, GET .../progress
│       ├── api/ProgressView.java                       # new
│       ├── application/LessonProgressService.java      # new (pessimistic lock + conditional update, research.md R1)
│       ├── application/PaymentSucceededListener.java   # extended: also outboxes enrollment.created
│       ├── application/OutboxPublisher.java            # new (mirrors payment-service's, research.md R7)
│       ├── domain/Enrollment.java                      # + completedAt
│       ├── domain/LessonProgress.java                  # new
│       ├── domain/OutboxEvent.java                     # new
│       ├── infrastructure/CourseLookupClient.java       # new Feign client + fallback (fail-closed 503)
│       ├── infrastructure/LessonProgressRepository.java # new
│       ├── infrastructure/OutboxEventRepository.java    # new
│       └── resources/db/migration/
│           ├── V3__add_completion_and_progress.sql     # research.md R0: not V2, already taken by 004
│           └── V4__create_outbox_events.sql
│
├── certificate-service/                      # new module
│   └── src/main/java/com/example/learnhub/certificate/
│       ├── CertificateServiceApplication.java
│       ├── api/                               # CertificateController (mine), CertificateVerificationController (public)
│       ├── application/                       # CourseCompletedListener, CertificateCodeGenerator, OutboxPublisher
│       ├── domain/                            # Certificate, OutboxEvent, ProcessedEvent
│       ├── infrastructure/                    # CertificateRepository, *Repository, CourseLookupClient + fallback
│       └── resources/db/migration/
│           ├── V1__create_certificates.sql
│           ├── V2__create_outbox_events.sql
│           └── V3__create_processed_events.sql
│
├── notification-service/                     # new module
│   └── src/main/java/com/example/learnhub/notification/
│       ├── NotificationServiceApplication.java
│       ├── application/                       # EnrollmentCreatedListener, CertificateIssuedListener, EmailSender
│       ├── domain/                             # Notification (PENDING/SENT), NotificationType
│       ├── infrastructure/                    # NotificationRepository, AccountLookupClient, CourseLookupClient (+ fallbacks)
│       └── resources/db/migration/
│           └── V1__create_notifications.sql
│
├── api-gateway/
│   └── src/main/java/com/example/learnhub/gateway/ratelimit/
│       └── RateLimitGlobalFilter.java         # extended: route-metadata-driven stricter limiter (research.md R8)
└── config-repo/api-gateway.yml                # + enrollments progress (falls under existing catch-all),
                                                #   certificates-mine, certificates-verify (strict rate limit)

docker-compose.yml       # + certificate-service (9005), notification-service (9007) entries
```

## Constitution Check (post-design)

Re-checked after Phase 1 design above: no new conflicts beyond the one already recorded (Principle II,
event-payload centralization — a deliberate brief instruction, justified above). The new per-route rate
limit is an extension of an existing, approved mechanism (`TokenBucketRateLimiter`), not a new one.

## Phase 0 and Phase 1 outputs

- [research.md](research.md) — 9 decisions (R0–R8: R0 is a transparency note on two brief-vs-shipped-code
  corrections, not a technical choice), no `NEEDS CLARIFICATION` remaining.
- [data-model.md](data-model.md) — `LessonProgress`, `Enrollment` (extended), `OutboxEvent`
  (enrollment-service); `Certificate`, `OutboxEvent`, `ProcessedEvent` (certificate-service);
  `Notification` (notification-service); three event payloads.
- [contracts/](contracts/) — endpoint contracts for the new/extended endpoints on all five touched
  services, plus the three event payload schemas.
- [quickstart.md](quickstart.md) — curl walkthrough of all seven user stories plus a no-regression check
  against `scripts/smoke-test.sh`.
