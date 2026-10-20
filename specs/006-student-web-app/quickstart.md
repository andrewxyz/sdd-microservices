# Quickstart: Student Web App

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-19

How to run this feature and prove it works. Every step maps to a success criterion from the spec.
Run it after `scripts/up.sh` has the platform healthy.

## Prerequisites

- The platform is up and healthy: `scripts/up.sh` (from WSL, where Docker lives).
- Node 24 LTS available for the dev-server path.
- A populated catalog: `scripts/seed-demo.sh`. It is idempotent — it exits without changes if the
  catalog already has courses — and it creates two categories, an instructor, and four published
  courses with three lessons each, one of them free.

```sh
scripts/up.sh
scripts/seed-demo.sh
```

## Running it

Two supported ways. Both serve the app on **port 9100** and both reach the backend through the
same-origin path `/api/`, so neither involves CORS.

```sh
# Production-shaped: the container in the Compose stack
docker compose up -d frontend
# → http://localhost:9100

# Development: Vite dev server, proxying /api to http://localhost:9080
cd frontend && npm ci && npm run dev
# → http://localhost:9100
```

## Automated checks

```sh
cd frontend
npm run typecheck     # tsc --noEmit, strict
npm test              # Vitest: API client, session, checkout, guards, lesson rendering
npm run build         # production bundle

# From the repo root, with the stack up:
scripts/smoke-test.sh # platform gate, now including the two frontend checks
```

The two smoke checks added by this feature:

1. `GET http://localhost:9100/` returns HTML — the container serves the app.
2. `GET http://localhost:9100/api/v1/courses` returns JSON — the `/api/` proxy reaches the gateway.

## Manual walkthrough

Each step names the criterion it proves. Do them in order with a fresh browser tab.

### 1. Browse without an account (SC-001 start, US1)

1. Open <http://localhost:9100>. The catalog lists the seeded courses; each card shows title,
   level, category name and either a price or "Free".
2. Type a keyword — the list narrows.
3. Pick a category, pick a level, turn on free-only — the list narrows further and the active
   filters are visible.
4. Set filters that match nothing — a friendly empty state appears with a way to clear them, not an
   error.
5. Copy the address, open it in a new tab — the same filtered result set appears. *(FR-009)*
6. Open a course — details and the ordered lesson outline appear, **with no lesson text anywhere**.

### 2. Register and stay signed in (US2)

1. Register with a new email and a strong password. You end up signed in with no second sign-in
   step — the app registers then signs in for you, because the backend's register returns no
   tokens. *(R0.1)*
2. Try registering the same email again — the message appears **next to the email field**.
   *(FR-016)*
3. Reload the page — you are still signed in. *(FR-013)*
4. Open the app in a second tab — that tab starts signed out, by design.

### 3. Enroll in the free course (SC-001, SC-002 start, US3)

1. Open the free course and enroll. No payment choice is offered; it takes one confirmation.
2. The app says the enrollment is being confirmed, then shows the course as enrolled within about
   ten seconds. *(FR-022, SC-007)*
3. Time steps 1–3 from first opening the app: under two minutes. *(SC-001)*

### 4. Learn and complete it (SC-002, US4)

1. Open **My Courses** — the course appears with its progress.
2. Open it. The lesson list is there and the selected lesson's text renders as plain text.
3. Mark each lesson complete — the progress bar moves each time, fed by the server's response.
4. Unmark one and re-mark it — progress moves back and forward; completion does not reset.
5. Complete every lesson — the course shows as completed.

### 5. Certificate and public verification (SC-002, US5)

1. Open **Account** — the certificate appears with its course title, date and verification code.
   (If you are quick, it may say one is on its way; wait a few seconds and reload.)
2. Copy the code.
3. Sign out, then open `/verify` and paste the code — **with extra spaces and in lower case**. It is
   still recognised, reports valid, and shows the course title and date. *(FR-036)*
4. Confirm nothing on the page says who holds it. *(FR-034)*
5. Enter a made-up code — a clear "not valid" result, not an error page. *(FR-035)*

### 6. The paid course, all three outcomes (SC-003, SC-004)

Sign in again, then on a paid course:

1. **Succeeds** — the payment resolves, enrollment confirms, the course appears in My Courses.
   *(SC-003)*
2. **Is declined** — the stated reason is shown and you are **not** enrolled. Check Account →
   payment history: the payment is there with status `DECLINED`. *(SC-004)*
3. **Provider unavailable** — the app says it is temporarily unavailable and invites a retry.

### 7. No double charge (SC-006)

1. On a paid course, double-click the enroll button as fast as you can. The control disables on the
   first click.
2. Check Account → payment history: **exactly one** payment for that attempt. *(FR-023)*

### 8. Silent session renewal (SC-005)

1. Stay signed in and idle until the access token expires (its lifetime is 15 minutes by default).
2. Click into My Courses or mark a lesson complete. It just works — no sign-in prompt, no lost
   action. *(FR-014)*
3. To force the failure path instead: clear `learnhub.refreshToken` from the tab's `sessionStorage`
   and the in-memory token by reloading, then visit `/account`. You land on `/login` with a
   `returnTo`, and after signing in you arrive back at `/account`. *(FR-015)*

### 9. Error reference codes (FR-040)

Stop a dependency and confirm an error still reads well:

```sh
docker compose stop course-service
```

Open a course page. The error explains the situation **and shows a reference code**; that code is
the `correlationId` from the backend's problem-details response, so it can be grepped in the
service logs. Restart it afterwards:

```sh
docker compose start course-service
```

### 10. Accessibility and small screens (SC-008)

1. Navigate the catalog, the enroll panel and the learning page using **Tab and Enter only** —
   every control is reachable and the focused one is visibly marked.
2. Narrow the window to 360 px — every page stays readable and usable with no horizontal scrolling.

### 11. Lesson text is never markup (FR-027)

With an instructor account, add a lesson whose content includes `<script>alert(1)</script>`, publish
it, enrol and open it. The tag appears **as visible text**. Nothing executes. The automated test
covers the same case, so this is a spot-check rather than the primary guard.

### 12. Nothing regressed (SC-009)

```sh
mvn -f backend/pom.xml verify   # from WSL
scripts/smoke-test.sh
```

Both pass. The only backend change in this feature is the additive lesson `id` field on the content
endpoint, covered by its own test.

## Success criteria covered

| Criterion | Proven by |
|-----------|-----------|
| SC-001 under two minutes to enrolled | Steps 1–3 |
| SC-002 full journey to a verified certificate | Steps 2–5 |
| SC-003 the same for a paid course | Step 6.1 |
| SC-004 declined shows its reason, no enrollment | Step 6.2 |
| SC-005 silent renewal | Step 8 |
| SC-006 no double charge | Step 7 |
| SC-007 confirmation within ten seconds | Step 3.2 |
| SC-008 keyboard and 360 px | Step 10 |
| SC-009 backend and smoke tests unchanged | Step 12 |
