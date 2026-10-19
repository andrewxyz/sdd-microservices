# Contracts consumed by the Student Web App

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Date**: 2026-10-19

This feature **publishes no contract of its own**. It is a browser client: its only external
interface is the set of backend endpoints it calls, every one of them already specified by an
earlier feature and reached through the gateway at the same-origin prefix `/api/` (constitution
IV, XIII).

This file therefore records *which* existing contracts the app depends on, and exactly which
operations and status codes it relies on — so that a future change to any of them can be traced to
the screens it would break.

## Source files fed to `gen:api`

`npm run gen:api` runs `openapi-typescript` over these files and writes one committed
`frontend/src/api/schema.ts`.

| # | Contract file | Provides |
|---|---------------|----------|
| 1 | `specs/002-user-auth/contracts/auth-service.openapi.yaml` | register, sign in, refresh, sign out, profile |
| 2 | `specs/003-course-catalog/contracts/course-service.openapi.yaml` | catalog list and filters, public course detail, category list |
| 3 | `specs/004-checkout-enrollment/contracts/course-service-content.openapi.yaml` | lesson content for an enrolled student |
| 4 | `specs/004-checkout-enrollment/contracts/payment-service.openapi.yaml` | checkout, payment history |
| 5 | `specs/004-checkout-enrollment/contracts/enrollment-service.openapi.yaml` | the caller's enrollments |
| 6 | `specs/005-progress-certificates-notifications/contracts/enrollment-service-progress.openapi.yaml` | progress, mark/unmark a lesson |
| 7 | `specs/005-progress-certificates-notifications/contracts/certificate-service.openapi.yaml` | the caller's certificates, public verification |

## Operations used, by screen

### Catalog — `/` and `/courses/:id`

| Operation | Status codes handled | Screen behaviour |
|-----------|---------------------|------------------|
| `GET /api/v1/courses` (`q`, `category`, `level`, `free`, `sort`, `page`, `size`) | 200 | Course cards; an empty `items` array renders the friendly empty state (FR-007) |
| `GET /api/v1/courses/{courseId}` | 200, 404 | Public detail with the lesson outline; 404 renders "no longer available" |
| `GET /api/v1/categories` | 200 | A bare array; drives the filter control and maps `categoryId` → name on cards |

### Session — `/login`, `/register`, everywhere

| Operation | Status codes handled | Screen behaviour |
|-----------|---------------------|------------------|
| `POST /api/v1/auth/register` | 201, 409, 422 | **Returns no tokens** — followed immediately by sign-in (R0.1). 409 → message on the email field; 422 → message on the password field (R5) |
| `POST /api/v1/auth/login` | 200, 401, 429 | 401 is a form-level message, deliberately not blamed on one field; 429 shows the `Retry-After` wait |
| `POST /api/v1/auth/refresh` | 200, 401 | Restore-on-load and the single-flight 401 retry (R4, R6). 401 clears the session |
| `POST /api/v1/auth/logout` | 204, 401 | Session cleared locally regardless of the response |
| `GET /api/v1/auth/me` | 200, 401 | Email and role for the account page and the header |

### Enrolling — `/courses/:id`

| Operation | Status codes handled | Screen behaviour |
|-----------|---------------------|------------------|
| `POST /api/v1/checkouts` | **201** new, **200** idempotent replay, **403** own course, **404** no such course, **409** already enrolled / not published / key reused with different content, **422** paid course with no token, **503** course-service unreachable | 200 and 201 are treated identically, which is what makes a retry safe (R0.2). 403 is **own course** — not 409, as the brief assumed |
| `GET /api/v1/enrollments/{courseId}/progress` | 200 enrolled, 403 not yet, 503 transient | Post-payment polling, once a second for up to 15 s (R8) |

### Learning — `/learn` and `/learn/:courseId`

| Operation | Status codes handled | Screen behaviour |
|-----------|---------------------|------------------|
| `GET /api/v1/enrollments` | 200 | `{id, courseId, enrolledAt}` only — titles and progress are fetched per course (R12) |
| `GET /api/v1/courses/{courseId}/content` | 200, 403, 404, 503 | 403 → enroll invitation; 503 → retry button, never "not enrolled" (R9) |
| `PUT /api/v1/enrollments/{courseId}/lessons/{lessonId}/complete` | 200, 403, 404, 503 | Returns the resulting progress, which replaces the bar's state directly |
| `DELETE` same path | 200, 403 | As above |
| `GET /api/v1/enrollments/{courseId}/progress` | 200, 403, 503 | Seeds the progress bar on load |

### Certificates — `/account`, `/verify`, `/verify/:code`

| Operation | Status codes handled | Screen behaviour |
|-----------|---------------------|------------------|
| `GET /api/v1/certificates/mine` | 200 | Course title, issue date, verification code |
| `GET /api/v1/certificates/verify/{code}` | 200, 404, 429 | Public. **404 is a result** ("not valid"), not an error (R10); 429 reflects this route's stricter rate limit |

### Account — `/account`

| Operation | Status codes handled | Screen behaviour |
|-----------|---------------------|------------------|
| `GET /api/v1/payments` | 200 | Course, amount, status, date, newest first |

## The one contract change this feature makes

`specs/004-checkout-enrollment/contracts/course-service-content.openapi.yaml` —
`CourseContentView.lessons[]` gains `id: { type: string, format: uuid }`.

Marking a lesson complete requires a lesson UUID, and before this change no endpoint a student
could reach returned one (the public outline and the content view both omitted it; only the
owner/admin-only `/courses/{id}/full` had it). The field is additive and backward-compatible, and
stays behind both authentication and the endpoint's existing enrollment gate. Full justification:
[plan.md](../plan.md) Complexity Tracking, and [research.md](../research.md) R0.3.

Because the contract changes, `gen:api` is re-run and the resulting `schema.ts` diff is committed
alongside it.

## Error shape

Every failure across every endpoint above uses the shared
`specs/001-platform-foundation/contracts/problem-details.schema.json`:
`{type, title, status, detail, instance, correlationId}`.

The schema defines **no field-error property**, and `ProblemDetailsAdvice` in `platform-common`
does not add one — so per-field messages are derived client-side from unambiguous status codes
only (R5). `correlationId` is always surfaced to the user as the reference code FR-040 requires.
