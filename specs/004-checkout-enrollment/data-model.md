# Data Model: Checkout and Enrollment

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

Persisted data for this feature lives in two separate, already-provisioned databases: `payment-service`
owns `payment`, `enrollment-service` owns `enrollment`. Neither reads the other's database or
`course-service`'s database; the only cross-service data access is the two Feign calls in research.md R4.

## Payment (`payment-service`, database `payment`)

One attempt to pay for one course by one user (FR-006 to FR-012, FR-017, FR-019, FR-020, FR-028).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key, generated at creation |
| `userId` | The caller's account id (from `X-User-Id`); immutable |
| `courseId` | The course being checked out; immutable |
| `amountCents` | Whole, non-negative integer; zero for a free course (FR-028) |
| `currency` | Fixed `USD` (FR-028) |
| `status` | One of `PENDING`, `SUCCEEDED`, `DECLINED`, `FAILED` (see State transitions below) |
| `idempotencyKey` | The caller-supplied key (FR-010); immutable |
| `paymentToken` | The simulated-provider test token used; null for a free checkout (research.md R2) |
| `failureReason` | Set when `status` is `DECLINED` or `FAILED`; null otherwise (FR-008) |
| `createdAt` | Set once at creation |
| `updatedAt` | Set whenever `status` changes |

Constraints:

- Unique on `(user_id, idempotency_key)` — research.md R9; the mechanism behind safe retries.
- Partial unique on `(user_id, course_id) WHERE status = 'SUCCEEDED'` — research.md R3; the mechanism
  behind "cannot pay twice" and "never more than one enrollment" (FR-004, FR-015), enforced at the
  payment layer since enrollment is only ever created from a `SUCCEEDED` payment.

State transitions:

```text
(created) --> PENDING --(free course, or provider token = success)--> SUCCEEDED
PENDING --(provider token = decline)--> DECLINED
PENDING --(provider token = unavailable)--> FAILED
```

There is no transition out of `SUCCEEDED`, `DECLINED` or `FAILED` — a `Payment` row, once resolved, is
immutable (refunds and retries explicitly out of scope, spec.md). A new attempt at the same course is a
new `Payment` row under a new idempotency key.

## OutboxEvent (`payment-service`, database `payment`, not exposed via any API)

The durable record that a `payment.succeeded` event still needs to be published (research.md R1).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key |
| `eventId` | UUID; becomes the published `EventEnvelope.eventId`; generated once, at the same time as the row |
| `paymentId` | Foreign key to `Payment.id` |
| `payloadJson` | The serialized `PaymentSucceededPayload` (see below) |
| `createdAt` | Set once, in the same transaction as the `Payment` row's transition to `SUCCEEDED` |
| `publishedAt` | Null until the scheduled publisher receives a broker acknowledgment; then set once, never cleared |

Written in the same database transaction as the owning `Payment` row's update to `SUCCEEDED` — the two
either both commit or both roll back, which is the entire point of R1.

### PaymentSucceededPayload (the `EventEnvelope.payload` for `type = "payment.succeeded"`, version 1)

| Field | Source |
|-------|--------|
| `paymentId` | `Payment.id` |
| `userId` | `Payment.userId` |
| `courseId` | `Payment.courseId` |
| `amountCents` | `Payment.amountCents` |
| `currency` | `Payment.currency` |
| `occurredAt` | The moment `Payment.status` became `SUCCEEDED` |

Schema: [contracts/payment-succeeded.event.schema.json](contracts/payment-succeeded.event.schema.json).

## Enrollment (`enrollment-service`, database `enrollment`)

Evidence that a user has access to a course (FR-013 to FR-015, FR-018, FR-021 to FR-024).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key |
| `userId` | The enrolled user's account id |
| `courseId` | The course the user is enrolled in |
| `enrolledAt` | Set once, when the enrollment is created |
| `sourcePaymentId` | The `Payment.id` (from the consumed event) that caused this enrollment |

Constraints:

- Unique on `(user_id, course_id)` — belt-and-suspenders alongside `payment-service`'s own partial
  unique index (research.md R3): even if the same `payment.succeeded` event were ever somehow delivered
  for two different payments on the same user+course (which R3 is designed to prevent from ever
  existing), this table still refuses to hold two enrollments for the same pair (FR-015).

No update or delete operation exists on `Enrollment` in this feature (unenrolling is out of scope,
spec.md).

## ProcessedEvent (`enrollment-service`, database `enrollment`, not exposed via any API)

Idempotency ledger for the `payment.succeeded` consumer (research.md R6, constitution Principle V).

| Field | Rule |
|-------|------|
| `eventId` | Primary key; the consumed `EventEnvelope.eventId` |
| `processedAt` | Set once, when the event was first (and only) acted on |

A listener invocation that finds its `eventId` already present here acknowledges the message and takes
no further action — this is what makes redelivery (after a crash, a retry, or a broker-level at-least-
once redelivery) safe.

## Course-service additions (no new persisted entity)

`course-service`'s new `GET /api/v1/courses/{id}/content` endpoint (research.md R5) introduces no new
table. It projects the existing `Course` and `Lesson` entities (both already defined in
[specs/003-course-catalog/data-model.md](../003-course-catalog/data-model.md)) into a new response
shape:

| Field | Source |
|-------|--------|
| `courseId` | `Course.id` |
| `title` | `Course.title` |
| `lessons[].title` | `Lesson.title` |
| `lessons[].position` | `Lesson.position` |
| `lessons[].durationMinutes` | `Lesson.durationMinutes` |
| `lessons[].content` | `Lesson.content` |

Unlike 003's `/full` view, this shape carries no `status` or `version` — it is a reading view for an
enrolled student, the owning instructor, or an administrator, not an editing view.
