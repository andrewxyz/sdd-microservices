# Data Model: Student Web App (client-side state only)

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-19

This app owns **no persistent data**. It has no database, no cache layer and no offline store. The
only thing that outlives a single render is the session, and the only thing that outlives a page
reload is one `sessionStorage` entry. Everything else below is transient view state, held for as
long as a page is mounted.

Server-side shapes are **not** redefined here: they are generated into `src/api/schema.ts` from the
backend contracts (see [contracts/README.md](contracts/README.md)). This document covers only what
the client itself holds.

## Persisted state

Exactly one key, per constitution XII.

| Where | Key | Value | Lifetime | Why there |
|-------|-----|-------|----------|-----------|
| `sessionStorage` | `learnhub.refreshToken` | The current refresh token (opaque string) | Until the tab closes, sign-out, or a failed refresh | A reload must keep the user signed in (FR-013); a new tab must not (spec assumption). `localStorage` is forbidden by constitution XII |

Nothing else is written to any browser storage. No cookie is set by the app.

## Session state (`src/auth/session.ts` + `AuthProvider`)

| Field | Type | Where it lives | Notes |
|-------|------|----------------|-------|
| `accessToken` | `string \| null` | Module-level variable, memory only | Never persisted (constitution XII). Lost on reload and rebuilt by R4's restore-on-load refresh |
| `refreshToken` | `string \| null` | `sessionStorage`, mirrored in memory | The only persisted value |
| `user` | `{ id, email, role } \| null` | React context | Populated from `GET /api/v1/auth/me` after a successful sign-in or restore |
| `status` | `'restoring' \| 'authenticated' \| 'anonymous'` | React context | `restoring` is the first-paint state while R4's refresh runs; guarded routes render a loading state rather than flashing `/login` |

**Transitions**

| From | Event | To |
|------|-------|-----|
| `restoring` | No refresh token present | `anonymous` |
| `restoring` | Refresh succeeds | `authenticated` |
| `restoring` | Refresh fails | `anonymous` (storage cleared) |
| `anonymous` | Sign-in, or register-then-login (R0.1), succeeds | `authenticated` |
| `authenticated` | Single-flight refresh fails on a 401 (R6) | `anonymous`, then redirect to `/login?returnTo=…` |
| `authenticated` | Sign-out | `anonymous` (logout called, then storage cleared regardless of its result) |

## Error state (`ApiError`, `src/api/problem.ts`)

The single shape every failed call produces. One per failure, held by whichever page or hook made
the call.

| Field | Type | Source |
|-------|------|--------|
| `status` | `number` | HTTP status; `0` for a network failure |
| `title` | `string` | problem+json `title` |
| `detail` | `string \| undefined` | problem+json `detail` |
| `correlationId` | `string \| undefined` | problem+json `correlationId` — rendered as the user's reference code (FR-040) |
| `retryAfterSeconds` | `number \| undefined` | Parsed from the `Retry-After` header on a 429 |
| `fieldErrors` | `Record<string, string>` | **Derived by the client**, not sent by the server — see R5. Empty for most errors |
| `message` | `string` | The friendly sentence shown to the user, chosen by status |

`fieldErrors` is populated only where the status is unambiguous for a given form: register `409` →
`email`, register `422` → `password`. Sign-in `401` stays a form-level message because the backend
deliberately cannot distinguish a wrong email from a wrong password.

## Catalog state (`/`, `CatalogPage`)

Held **in the URL**, not in React state (R11, FR-009), so a result set is shareable and survives a
reload with no second copy to drift.

| Query parameter | Type | Default | Passed to the backend as |
|-----------------|------|---------|--------------------------|
| `q` | `string` | absent | `q` |
| `category` | UUID | absent | `category` |
| `level` | `BEGINNER \| INTERMEDIATE \| ADVANCED` | absent | `level` |
| `free` | `true` when the toggle is on | absent | `free` |
| `sort` | `newest \| title \| price_asc \| price_desc` | `newest` | `sort` |
| `page` | integer ≥ 0 | `0` | `page` |
| `size` | integer 1–100 | `20` | `size` |

Fetched alongside it and held in memory for the session: the category list
(`GET /api/v1/categories`, a bare array), used to turn the `categoryId` on each course card into a
name, and to populate the filter control.

## Checkout state (`src/hooks/useCheckout.ts`)

The most carefully specified state in the app, because it is the one that can cost money (R7).

| Field | Type | Notes |
|-------|------|-------|
| `choice` | `'sim-success' \| 'sim-decline' \| 'sim-unavailable' \| null` | `null` for a free course, where no choice is shown (FR-019) |
| `idempotencyKey` | `string \| null` | `crypto.randomUUID()`, created on first submit for the current (course, choice) pair |
| `phase` | see below | Drives the whole enroll panel |
| `payment` | `PaymentView \| null` | The result returned by the backend |
| `error` | `ApiError \| null` | Populated for the non-success outcomes |
| `pollElapsedMs` | `number` | Counts toward the 15-second ceiling |

**Phases**

| Phase | Meaning | Submit control |
|-------|---------|----------------|
| `idle` | Nothing attempted yet, or the choice just changed | Enabled |
| `submitting` | A checkout request is in flight | **Disabled** (FR-024) |
| `confirming` | Payment `SUCCEEDED`; polling progress for the enrollment (R8) | Disabled |
| `enrolled` | Poll returned 200 — the course is now enrolled | Replaced by a link into the course |
| `pending` | 15 seconds elapsed without confirmation | Shows the check-back-shortly message (FR-022) |
| `declined` | Payment `DECLINED` | Enabled again, shows `failureReason` |
| `failed` | Payment `FAILED`, or a terminal error | Enabled again; retrying regenerates the key |
| `blocked` | 403 own course, 409 already enrolled, 404, 422 | Shows the specific message; no retry offered |

**Idempotency key rules** (FR-023, SC-006)

| Event | Key |
|-------|-----|
| First submit for a (course, choice) pair | Generated |
| Double click while `submitting` | Not reached — the control is disabled |
| Automatic retry after a network failure | **Reused**, so the backend replays the original result |
| Payment choice changed | **Regenerated** — reusing it with different content would be a 409 (R0.2) |
| User retries after a terminal `FAILED` | **Regenerated** — this is a genuinely new attempt |

## Learning state (`/learn/:courseId`, `LearnPage`)

| Field | Type | Source |
|-------|------|--------|
| `content` | `CourseContentView \| null` | `GET /api/v1/courses/{id}/content` — lessons carry `id` after the change in R0.3 |
| `selectedLessonId` | `UUID \| null` | Defaults to the first lesson by `position` |
| `progress` | `ProgressView \| null` | Seeded by `GET …/progress`, then **replaced by the response of every mark/unmark**, which returns the resulting progress (R9) |
| `completedLessonIds` | `Set<UUID>` | Derived from the marks the user makes this session, reconciled against `completedLessons` count |
| `pageState` | `'loading' \| 'ready' \| 'not-enrolled' \| 'not-found' \| 'unavailable'` | Mapped from 200 / 403 / 404 / 503 respectively (R9) |

`unavailable` is kept distinct from `not-enrolled` deliberately: a 503 means enrollment could not be
*confirmed*, and showing "you are not enrolled" to a paying student during a transient outage would
be wrong.

## My Courses state (`/learn`, `MyCoursesPage`)

Assembled from three endpoints because no aggregate exists (R12).

| Field | Type | Source |
|-------|------|--------|
| `enrollments` | `EnrollmentView[]` | `GET /api/v1/enrollments` — `{id, courseId, enrolledAt}` only |
| `titles` | `Map<UUID, string>` | One `GET /api/v1/courses/{id}` per enrollment, in parallel |
| `progressByCourse` | `Map<UUID, ProgressView>` | One `GET …/{id}/progress` per enrollment, in parallel |

Each card renders as soon as its own three pieces arrive; a slow course does not block the rest.

## Account state (`/account`)

| Field | Source |
|-------|--------|
| `user` | Already in session context, from `GET /api/v1/auth/me` |
| `payments` | `GET /api/v1/payments` — `PaymentView[]`, newest first |
| `certificates` | `GET /api/v1/certificates/mine` — `CertificateView[]` |

## Verification state (`/verify`, `/verify/:code`)

| Field | Type | Notes |
|-------|------|-------|
| `code` | `string` | Trimmed and upper-cased before sending (FR-036) |
| `result` | `'valid' \| 'not-valid' \| null` | A **404 is a result, not an error** (R10) — rendered without a reference code |
| `detail` | `{ courseTitle, issuedAt } \| null` | Only on a valid result; never contains anything about the holder (FR-034) |

## What is deliberately not modelled

- **No client-side cache.** Each page fetches what it needs when it mounts. The data volumes are
  small and a stale-cache bug is worse than a second request.
- **No global store.** Session is the only cross-page state and React context covers it, which is
  why constitution XI's "no state library" rule costs nothing here.
- **No optimistic writes.** Marking a lesson complete waits for the server's `ProgressView` rather
  than guessing, so the progress bar can never disagree with the backend.
- **No derived copy of catalog filters in React state.** The URL is the single source of truth.
