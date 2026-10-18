# Implementation Plan: Checkout and Enrollment

**Branch**: `004-checkout-enrollment` | **Date**: 2026-10-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-checkout-enrollment/spec.md`. No additional technical
brief was given to `/speckit-plan`; every technical decision below was made here and is recorded, with
rationale, in [research.md](research.md).

**Scope**: Backend only. Adds `backend/payment-service` (new, port 9004) and
`backend/enrollment-service` (new, port 9003); modifies `backend/course-service` (adds one new,
additive endpoint and one outbound Feign client — its existing contract from 003 is unchanged);
modifies `backend/api-gateway`'s route config only (five new routes, no gateway code change); modifies
`backend/pom.xml` (two module entries, plus the first use of `spring-cloud-starter-openfeign` and
`spring-cloud-starter-circuitbreaker-resilience4j`, both already managed by the imported Spring Cloud
BOM, and the first use of `spring-boot-starter-amqp`, already managed by `spring-boot-starter-parent`
— no new dependency *versions* to pin); `docker-compose.yml` (two new service entries). `.env.example`
needs no new variable: `ENROLLMENT_DB_PASSWORD`, `PAYMENT_DB_PASSWORD`, `RABBITMQ_DEFAULT_USER` and
`RABBITMQ_DEFAULT_PASS` already exist from platform-foundation, and the `enrollment`/`payment` databases
are already provisioned by `db-init`. `frontend/` and every other business service are untouched.

## Summary

Adds the platform's first synchronous (Feign) and asynchronous (RabbitMQ) inter-service calls, both
already mandated by the constitution but not yet exercised by any shipped feature. `payment-service`
processes checkout against a published course's catalog price (looked up from `course-service` via its
existing internal endpoint, over Feign with a circuit breaker and fallback), runs it through a
deterministic, in-process simulated payment provider keyed by a caller-supplied test token, and —on a
successful payment— writes the fact durably to its own outbox table in the same transaction as the
payment row. A scheduled publisher drains that outbox onto the `learning.events` exchange as a
`payment.succeeded` event, so a payment is never lost even if the broker is briefly unreachable at the
moment it succeeds. `enrollment-service` consumes that event idempotently (deduplicated by `eventId`,
retried three times with backoff, dead-lettered on exhaustion or malformed input, exactly as the
constitution already requires of every consumer) and creates the matching enrollment. `course-service`
gains one new, additive endpoint for reading a course's full lesson content that checks the caller's
enrollment via a new Feign call to `enrollment-service`, refusing with a temporary error rather than
showing content when that call cannot be confirmed.

## Technical Context

**Language/Version**: Java 21 (unchanged)

**Primary Dependencies**: Spring Boot 3.5.15 / Spring Cloud 2025.0.3 (unchanged versions, same BOM).
First real use of `spring-cloud-starter-openfeign` and `spring-cloud-starter-circuitbreaker-resilience4j`
(both managed by the already-imported `spring-cloud-dependencies` BOM — no version to add) and
`spring-boot-starter-amqp` (managed by `spring-boot-starter-parent` — no version to add). No library
outside what the constitution already names (Principle X, Technology Stack section).

**Storage**: PostgreSQL 16, databases `payment` and `enrollment` (already created, empty, by
platform-foundation's `db-init`). RabbitMQ 4.3, the existing single `learning.events` topic exchange
(already running since platform-foundation; no new exchange).

**Testing**: JUnit 5 and Testcontainers (PostgreSQL *and*, for the first time, RabbitMQ — both already
constitution-approved), plus WireMock for the new Feign clients' contract tests (same tool `003`
already uses for the gateway). No new test library.

**Target Platform**: Linux containers via the existing `docker-compose.yml`, registered with the
existing Eureka server, loading config from the existing config server — no new infrastructure beyond
the two new application containers (RabbitMQ and both databases already run).

**Project Type**: Two new backend microservice modules, one additive change to an existing module
(`course-service`), plus a small, additive extension of `api-gateway`'s route configuration only.

**Performance Goals**: SC-003 — 20 simultaneous, identical checkout requests under one idempotency key
produce exactly one payment and one enrollment. SC-006 — at least 95% of successful payments are
enrolled within 5 seconds under normal operation.

**Constraints**: A payment MUST NOT be lost even if the broker or enrollment-service is briefly
unavailable at the moment it succeeds (FR-025, research.md R1); an enrollment consumer MUST be
idempotent by `eventId`, retry three times with backoff, and dead-letter on exhaustion or malformed
input without blocking other messages (constitution Principle V, research.md R6); lesson content MUST
never be shown when enrollment cannot be confirmed (FR-024, research.md R5).

**Scale/Scope**: Single instance of each new service for this feature (no scaled copy), matching every
prior service's own precedent.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design below.*

| Principle | Check | Result |
|---|---|---|
| I. Monorepo layout | Two new modules at `backend/payment-service`, `backend/enrollment-service`; nothing added at the repo root beyond the already-permitted set | Pass |
| II. Service ownership & data isolation | Each new service owns its own database (`payment`, `enrollment`); no cross-service joins; `course-service` is read only through its existing internal lookup, never its database | Pass |
| III. Discovery & configuration | Both new services register with Eureka under their own names and load config via non-optional `spring.config.import=configserver:` with fail-fast + retry, same as every other service | Pass |
| IV. Gateway as only entry point | All five new client-facing endpoints are listed as explicit gateway routes (research.md R7); the two new internal lookups (payment→course already existed from 003; course→enrollment is new, FR hidden by design) get no gateway route at all, the same `003` precedent (R6 there) | Pass |
| V. Sync/async communication | First real exercise of this principle: two Feign clients, each with a configured timeout, a Resilience4j circuit breaker and a fallback (research.md R4); one event (`payment.succeeded`) on the existing `learning.events` exchange, using the shared `EventEnvelope`; the one consumer has its own queue and DLQ, is idempotent by `eventId`, and retries three times with backoff before dead-lettering (research.md R6) | Pass |
| VI. Test-first | tasks.md (next command) sequences tests before implementation per story; both new Feign clients get a WireMock contract test, and the `payment.succeeded` payload gets its own serialization test, per Principle VI's explicit requirements | Pass (enforced at tasks/implement time) |
| VII. Security & secrets | No new secret: both new services use already-provisioned database passwords and the already-provisioned RabbitMQ credentials, all already in `.env.example` | Pass |
| VIII. Errors & observability | Reuses `platform-common`'s `ProblemDetailsAdvice` and correlation filter, identical to every prior service; a circuit-open Feign call is translated to a 503 problem-details response through the fallback, not a raw exception (research.md R4) | Pass |
| IX. Conventions | Packages `com.example.learnhub.payment` and `com.example.learnhub.enrollment`, each with `api`/`application`/`domain`/`infrastructure`; `/api/v1` paths; Flyway migrations; ports 9004 and 9003 per the existing table | Pass |
| X. Simplicity | No new library beyond what the constitution already names; the simulated payment provider is a pure in-process function of its input token, not a fake external HTTP dependency (research.md R2); the "already enrolled" guard is enforced from `payment-service`'s own data rather than an extra cross-service call (research.md R3) | Pass |

**Complexity Tracking**: None. The transactional outbox table in `payment-service` (research.md R1) is
not a new technology — it is a plain table and a background poller (the same `ScheduledExecutorService`
pattern `platform-common`'s `ConfigWatcher` already uses, not `@Scheduled`), the standard way to make "write
to the database" and "publish an event" atomic without a distributed transaction, and it is the only
way to honestly satisfy FR-025's "never lost, even if the broker is briefly unreachable" against a
message broker that (unlike a database write) cannot be rolled back into the same transaction.

## Project Structure

### Documentation (this feature)

```text
specs/004-checkout-enrollment/
├── plan.md               # This file
├── research.md           # Phase 0 output
├── data-model.md          # Phase 1 output
├── quickstart.md          # Phase 1 output
├── contracts/
│   ├── payment-service.openapi.yaml
│   ├── enrollment-service.openapi.yaml
│   ├── course-service-content.openapi.yaml   # Addendum to 003's contract: one new endpoint
│   └── payment-succeeded.event.schema.json   # The payload shape inside EventEnvelope.payload
└── tasks.md               # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                      # + payment-service, enrollment-service module entries
├── config-repo/
│   ├── payment-service.yml                      # new: refreshable settings (none required beyond defaults yet)
│   └── enrollment-service.yml                   # new: same
├── payment-service/                              # new module
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/example/learnhub/payment/
│       │   ├── PaymentServiceApplication.java
│       │   ├── api/            # CheckoutController, PaymentController
│       │   ├── application/    # CheckoutService, SimulatedPaymentProvider, OutboxPublisher
│       │   ├── domain/         # Payment, PaymentStatus, OutboxEvent
│       │   └── infrastructure/ # PaymentRepository, OutboxEventRepository,
│       │                       # CourseLookupClient (Feign) + fallback
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/
│       │       ├── V1__create_payments.sql
│       │       └── V2__create_outbox_events.sql
│       └── test/java/com/example/learnhub/payment/
│           ├── support/AbstractPaymentServiceIT.java   # Testcontainers Postgres + RabbitMQ, singleton pattern
│           ├── CheckoutIT.java                 # US1, US2: free/paid success, decline, provider-unavailable
│           ├── IdempotencyIT.java              # US4: repeats, conflicting reuse, 20-concurrent
│           ├── PaymentHistoryIT.java           # US5: own-only visibility
│           ├── OutboxPublisherIT.java          # R1: broker-down-then-recovers delivery
│           └── CourseLookupClientContractTest.java   # WireMock contract test for the Feign client
│
├── enrollment-service/                           # new module
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/example/learnhub/enrollment/
│       │   ├── EnrollmentServiceApplication.java
│       │   ├── api/            # EnrollmentController (my enrollments), InternalEnrollmentController
│       │   ├── application/    # PaymentSucceededListener, EnrollmentLookupService
│       │   ├── domain/         # Enrollment, ProcessedEvent
│       │   └── infrastructure/ # EnrollmentRepository, ProcessedEventRepository, RabbitTopologyConfig
│       ├── main/resources/
│       │   ├── application.yml
│       │   └── db/migration/
│       │       ├── V1__create_enrollments.sql
│       │       └── V2__create_processed_events.sql
│       └── test/java/com/example/learnhub/enrollment/
│           ├── support/AbstractEnrollmentServiceIT.java   # Testcontainers Postgres + RabbitMQ
│           ├── MyEnrollmentsIT.java             # FR-018
│           ├── PaymentSucceededConsumerIT.java  # US6: idempotent-by-eventId, exactly-once
│           ├── DeadLetterIT.java                # US6: malformed + repeatedly-failing → DLQ, no head-of-line block
│           └── InternalLookupIT.java            # consumed by course-service's new Feign client
│
├── course-service/                               # existing module, additive change only
│   └── src/
│       ├── main/java/com/example/learnhub/course/
│       │   ├── api/CourseContentController.java         # new: GET /api/v1/courses/{id}/content
│       │   ├── application/CourseContentService.java    # new
│       │   └── infrastructure/EnrollmentLookupClient.java   # new Feign client + fallback
│       └── test/java/com/example/learnhub/course/
│           ├── LessonContentAccessIT.java (extended)     # enrolled/non-enrolled/unavailable cases
│           └── EnrollmentLookupClientContractTest.java   # new WireMock contract test
│
└── config-repo/api-gateway.yml   # + checkout, payments, enrollments, course-content routes
```

```text
docker-compose.yml       # + payment-service entry (port 9004), enrollment-service entry (port 9003),
                          #   both depends_on discovery+config+db-init+rabbitmq
```

## Constitution Check (post-design)

Re-checked after Phase 1 design above: no new conflicts. The two Feign clients and the one consumer are
the first of their kind in this codebase, but every shape they take (timeouts + circuit breaker +
fallback for Feign; per-consumer queue + DLQ + eventId idempotency + three retries with backoff for the
listener) is dictated by the constitution itself, not invented here — research.md records the concrete
configuration values chosen within those constraints.

## Phase 0 and Phase 1 outputs

- [research.md](research.md) — 9 decisions (R1–R9), no `NEEDS CLARIFICATION` remaining.
- [data-model.md](data-model.md) — `Payment`, `OutboxEvent` (payment-service); `Enrollment`,
  `ProcessedEvent` (enrollment-service); the `payment.succeeded` event payload shape.
- [contracts/](contracts/) — `payment-service.openapi.yaml`, `enrollment-service.openapi.yaml`,
  `course-service-content.openapi.yaml` (additive to 003's contract), and
  `payment-succeeded.event.schema.json`.
- [quickstart.md](quickstart.md) — curl walkthrough of all six user stories plus a no-regression check
  against `scripts/smoke-test.sh`.
