# Data Model: Instructor Dashboard

No personal data is stored anywhere in this model (research R3).

## Tables (analytics-service, database `analytics`, Flyway `V1__create_analytics.sql`)

### course_daily_stats

| Column | Type | Notes |
|---|---|---|
| course_id | uuid | part of primary key |
| day | date | UTC day of the envelope's `occurredAt`; part of primary key |
| enrollments | bigint NOT NULL DEFAULT 0 | +1 per `enrollment.created` |
| revenue_cents | bigint NOT NULL DEFAULT 0 | + `amountCents` per `payment.succeeded` |
| completions | bigint NOT NULL DEFAULT 0 | +1 per `course.completed` |
| certificates | bigint NOT NULL DEFAULT 0 | +1 per `certificate.issued` |

Primary key `(course_id, day)`. All writes are `INSERT ... ON CONFLICT (course_id, day) DO UPDATE SET
<col> = course_daily_stats.<col> + :delta`; counters only ever increase.

### processed_events

| Column | Type | Notes |
|---|---|---|
| event_id | uuid | primary key; the envelope `eventId` |
| processed_at | timestamptz NOT NULL | when it was handled |

Inserted in the same transaction as the counter update; a conflict means "already processed".

## Event inputs (read-only, from `learning.events`)

| Routing key | Payload fields read | Effect |
|---|---|---|
| `payment.succeeded` | `courseId`, `amountCents` | revenue += amountCents |
| `enrollment.created` | `courseId` | enrollments += 1 |
| `course.completed` | `courseId` | completions += 1 |
| `certificate.issued` | `courseId` | certificates += 1 |

Envelope fields used: `eventId` (idempotency), `type`, `occurredAt` (UTC day), `correlationId` (MDC).
Other payload fields (user, payment, enrollment and certificate ids) are ignored and never stored.

## Derived read shapes (see contracts/analytics-service.openapi.yaml)

- **Totals**: `{enrollments, revenueCents, completions, certificates}` (all integers).
- **CourseStats**: `{courseId} + Totals`. Completion rate is computed by the client as
  `completions / enrollments`, `0` when enrollments is 0, capped at 100%.
- **Summary**: `{scope: "OWN" | "PLATFORM", totals, truncated, generatedAt}`; `truncated` is true when the ownership list has 200 entries.
- **CourseList**: `{items: CourseStats[], page, size, totalItems, truncated, generatedAt}`; instructors get one
  unpaged page of owned courses that have activity; administrators get all, ordered by revenue
  descending, size at most 200.
- **CourseDetail**: `{courseId, days, totals, funnel: {enrolled, completed, certificateIssued},
  series: [{day, enrollments}], generatedAt}` with `series` having exactly `days` entries, oldest
  first, ending today (UTC), zero-filled.

## Validation rules

- `days` must be 7, 30 or 90 (default 30); anything else returns 400 with a field error on `days`.
- `courseId` must be a UUID, else 400.
- Roles other than `INSTRUCTOR` and `ADMINISTRATOR` get 403; non-owner non-admin and unknown course get
  an identical 404; ownership lookup failure gets 503 with no figures.

## Upstream shape added to course-service

`GET /api/v1/internal/courses?instructorId={uuid}` -> `[{courseId, status, title}]`, at most 200,
newest first, all statuses (see contracts/course-service-internal-list.openapi.yaml).
