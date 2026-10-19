# Data Model: Lesson Progress, Certificates, and Email Notifications

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

Persisted data for this feature spans three databases: `enrollment-service`'s existing `enrollment`
database gains new tables and a new column; `certificate` and `notification` (already provisioned, empty,
by platform-foundation's `db-init`) get their first tables. No service reads another's database; every
cross-service fact is obtained via the internal lookups in research.md R3/R4, or via the events in R2/R6.

## Enrollment (`enrollment-service`, database `enrollment`, extended from 004)

| Field | Rule |
|-------|------|
| *(existing fields unchanged — see [specs/004-checkout-enrollment/data-model.md](../004-checkout-enrollment/data-model.md))* | |
| `completedAt` | **New.** Null until every lesson of the course is complete; set at most once, permanently (FR-008, FR-009). Never cleared by later unmarking (research.md R1). |

## LessonProgress (`enrollment-service`, database `enrollment`)

Whether a specific enrollment has completed a specific lesson (FR-001 to FR-006).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key |
| `enrollmentId` | Foreign key to `Enrollment.id`; immutable |
| `lessonId` | The course-service lesson id, validated against the course's current lesson list at write time (research.md R3); immutable |
| `completedAt` | Set once, when the lesson was marked complete |

Constraints:

- Unique on `(enrollment_id, lesson_id)` — a lesson is either complete or not; there is no "complete
  again" state (FR-004). Absence of a row *is* "not complete" (FR-005's unmark deletes the row rather
  than flagging it).

## OutboxEvent (`enrollment-service`, database `enrollment`, not exposed via any API)

Durable record that `course.completed` or `enrollment.created` still needs publishing (research.md R7).
Identical shape to `payment-service`'s own `OutboxEvent`
([specs/004-checkout-enrollment/data-model.md](../004-checkout-enrollment/data-model.md)): `id`,
`eventId`, an aggregate reference (`enrollmentId` here, in place of `paymentId`), `payloadJson`,
`createdAt`, `publishedAt` (nullable until published).

## Certificate (`certificate-service`, database `certificate`)

Proof a student completed a course (FR-010 to FR-018).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key |
| `userId` | The certificate holder; immutable |
| `courseId` | The completed course; immutable |
| `verificationCode` | 16 characters from a fixed, unambiguous alphabet (excludes `0/O`, `1/I/L` and similar look-alikes); globally unique; immutable |
| `courseTitle` | Captured from course-service at issuance time (research.md R3); immutable — a later course rename never changes an already-issued certificate |
| `issuedAt` | Set once, at creation |

Constraints:

- Unique on `(user_id, course_id)` — at most one certificate per student per course (FR-010).
- Unique on `verification_code` — collisions are vanishingly unlikely (16 characters from a ~32-symbol
  alphabet) but handled defensively by regenerating and retrying the insert on violation.

No update or delete operation exists on `Certificate` in this feature (no revocation, per spec.md
Assumptions).

## OutboxEvent (`certificate-service`, database `certificate`, not exposed via any API)

Identical shape and purpose to enrollment-service's own (above); the aggregate reference is
`certificateId`. Carries `certificate.issued` until published (research.md R7).

## ProcessedEvent (`certificate-service`, database `certificate`, not exposed via any API)

Idempotency ledger for the `course.completed` consumer (research.md R6). Identical shape to
enrollment-service's own from 004: `eventId` (primary key), `processedAt`.

## Notification (`notification-service`, database `notification`)

One attempt to turn a consumed event into an email (research.md R7's two-phase write).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key |
| `eventId` | The consumed `EventEnvelope.eventId`; unique — this *is* notification-service's idempotency guard (FR-022), not a separate ledger table |
| `userId` | The intended recipient's account id |
| `type` | `ENROLLMENT` or `CERTIFICATE` — which of the two consumed event types produced this row |
| `status` | `PENDING` (row written, send not yet confirmed) or `SENT` (email handed to Mailpit successfully) |
| `createdAt` | Set once, when the row is first written (phase one of research.md R7) |

No email address or body is ever persisted here, logged, or otherwise recorded anywhere (FR-021's "never
log email bodies or addresses") — the address is resolved fresh from auth-service (research.md R4) each
time it's needed and never written to disk by notification-service itself.

## Event payloads (`shared-events`, research.md R1/R2)

### CourseCompletedPayload (`type = "course.completed"`, version 1)

| Field | Source |
|-------|--------|
| `enrollmentId` | `Enrollment.id` |
| `userId` | `Enrollment.userId` |
| `courseId` | `Enrollment.courseId` |
| `completedAt` | The moment `Enrollment.completedAt` was set |

Schema: [contracts/course-completed.event.schema.json](contracts/course-completed.event.schema.json).

### CertificateIssuedPayload (`type = "certificate.issued"`, version 1)

| Field | Source |
|-------|--------|
| `certificateId` | `Certificate.id` |
| `userId` | `Certificate.userId` |
| `courseId` | `Certificate.courseId` |
| `verificationCode` | `Certificate.verificationCode` |
| `issuedAt` | `Certificate.issuedAt` |

No course title here — notification-service fetches it fresh from course-service itself (research.md
R3/R5), the same way certificate-service did when it first issued the certificate.

Schema: [contracts/certificate-issued.event.schema.json](contracts/certificate-issued.event.schema.json).

### EnrollmentCreatedPayload (`type = "enrollment.created"`, version 1, research.md R2)

| Field | Source |
|-------|--------|
| `enrollmentId` | `Enrollment.id` |
| `userId` | `Enrollment.userId` |
| `courseId` | `Enrollment.courseId` |
| `enrolledAt` | `Enrollment.enrolledAt` (specs/004) |

Schema: [contracts/enrollment-created.event.schema.json](contracts/enrollment-created.event.schema.json).

## course-service and auth-service: extended/new projections (no new persisted entity)

`course-service`'s existing `CourseSummaryView` (research.md R3) gains:

| Field | Source |
|-------|--------|
| `title` | `Course.title` |
| `lessonIds` | `Lesson.id` for every lesson of the course, in position order, capped at 200 |

`auth-service`'s new internal lookup (research.md R4) introduces `AccountSummaryView`:

| Field | Source |
|-------|--------|
| `id` | `Account.id` |
| `email` | `Account.email` |
