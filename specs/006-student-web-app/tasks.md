---

description: "Task list for the Student Web App (006-student-web-app)"
---

# Tasks: Student Web App

**Input**: Design documents from `/specs/006-student-web-app/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/)

**Tests**: Included and sequenced **before** the code they cover — constitution Principle VI
("Test-First Quality") is NON-NEGOTIABLE for this project, and the planning brief asked for it
explicitly.

**Organization**: Grouped by user story so each can be implemented and tested independently. The
phase order follows the requested sequence — scaffold → session → catalog → checkout → learning,
certificates and account → packaging — so that **every phase ends with an app that runs**.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: Which user story the task serves (US1–US6); Setup, Foundational and Polish carry none

## Path Conventions

- `frontend/` is its own application root (constitution I, XI) — not a Maven module
- `frontend/src/{api,auth,hooks,pages,components}/`, tests colocated as `*.test.ts(x)`
- Repo-root files touched: `docker-compose.yml`, `scripts/`, `README.md`, `.github/workflows/ci.yml`
- One backend file is touched, in Phase 2 — the approved deviation from plan.md Complexity Tracking

---

## Phase 1: Setup (scaffold and proxy)

**Purpose**: A React app that starts, builds, tests and reaches the gateway through `/api/`.

- [X] T001 Create `frontend/package.json` as the app root: `"type": "module"`, an `engines.node` of
      `>=24`, and scripts `dev`, `build`, `preview`, `test`, `typecheck` (`tsc --noEmit`) and
      `gen:api`. Nothing is added to `backend/pom.xml` (constitution I).
- [X] T002 Install and **pin exact versions** in `frontend/package-lock.json`. Runtime: `react`,
      `react-dom`, `react-router`. Dev: `typescript`, `vite`, `@vitejs/plugin-react`, `vitest`,
      `@testing-library/react`, `jsdom`, `openapi-typescript`, `@types/react`, `@types/react-dom`.
      No UI component library and no state library (constitution XI). `@vitejs/plugin-react` and
      the `@types/*` packages are toolchain requirements of the named stack, not additional
      technology choices — see plan.md's note on Principle X.
- [X] T003 [P] Create `frontend/tsconfig.json` with `"strict": true` (constitution XI), `jsx:
      react-jsx`, `moduleResolution: bundler`, and `noUncheckedIndexedAccess` for safety.
- [X] T004 Create `frontend/vite.config.ts`: dev server on **port 9100** with
      `proxy: { '/api': 'http://localhost:9080' }` (research R1), plus the Vitest block
      (`environment: 'jsdom'`, `setupFiles: ['./src/test/setup.ts']`, `globals: true`).
- [X] T005 [P] Create `frontend/src/test/setup.ts` installing a `fetch` mock helper and resetting it
      between tests. Tests never hit a real network (research R14).
- [X] T006 Create the app shell: `frontend/index.html`, `frontend/src/main.tsx`,
      `frontend/src/App.tsx` with the router and a placeholder home route, and
      `frontend/src/styles.css` as the single stylesheet (constitution XI).
- [X] T007 [P] Create `frontend/.gitignore` for `node_modules/` and `dist/`.
- [X] T008 Verify the scaffold: `npm run dev` serves on 9100, `npm run typecheck` and `npm test`
      pass on the empty suite, and `npm run build` produces `dist/`.

**Checkpoint**: The app runs on 9100 and proxies `/api` to the gateway. Nothing is styled or
functional yet, but it is deployable.

---

## Phase 2: Foundational (lesson ids, generated types, API client)

**Purpose**: The one backend change, the generated contract types, and the single `request()`
wrapper every later phase depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### The approved backend change (test first)

> plan.md Complexity Tracking / research R0.3. Sequenced here, before `gen:api`, so the contract
> types are generated once from their final shape rather than regenerated later.

- [X] T009 Extend `backend/course-service/src/test/java/com/example/learnhub/course/LessonContentAccessIT.java`
      to assert the enrolled-reader response's `lessons[0].id` equals the seeded lesson's id. **Run
      it and watch it fail** before T010 (constitution VI).
- [X] T010 Add `UUID id` as the first component of `LessonView` in
      `backend/course-service/src/main/java/com/example/learnhub/course/api/CourseContentView.java`,
      and pass `lesson.getId()` where that record is constructed in
      `backend/course-service/src/main/java/com/example/learnhub/course/application/CourseContentService.java`.
- [X] T011 [P] Add `id: { type: string, format: uuid }` to the `CourseContentView.lessons[]` schema
      in `specs/004-checkout-enrollment/contracts/course-service-content.openapi.yaml`.
- [X] T012 Run `mvn -f backend/pom.xml -pl course-service -am verify` from WSL and confirm the whole
      module is green, including T009.

### Generated API types

- [X] T013 Add the `gen:api` script to `frontend/package.json` running `frontend/scripts/gen-api.mjs`,
      which invokes `openapi-typescript` once per contract listed in
      [contracts/README.md](contracts/README.md) — auth (002), course catalog (003), course content
      (004, now with `id`), payment (004), enrollment (004), progress (005) and certificate (005) —
      each into its own `frontend/src/api/generated/<name>.ts`, then writes the barrel
      `frontend/src/api/schema.ts` re-exporting all seven as namespaces (research R3's implementation
      finding: `openapi-typescript`'s CLI takes one input per run, so one file cannot hold all seven).
- [X] T014 Run `npm run gen:api` and **commit** `frontend/src/api/generated/*.ts` and
      `frontend/src/api/schema.ts` (constitution XIII; research R3 — committing keeps
      `npm ci && npm run build` codegen-free in CI).

### API client (tests first)

> Write T015–T017 and watch them fail before T018–T020.

- [X] T015 [P] Write `frontend/src/api/problem.test.ts`: an `application/problem+json` body parses
      into `ApiError` with `status`, `title`, `detail` and `correlationId`; a **429** also carries
      `retryAfterSeconds` from the `Retry-After` header; a thrown `fetch` yields `status: 0` with
      the network message; each status maps to its friendly sentence (research R5).
- [X] T016 [P] Write `frontend/src/api/client.singleflight.test.ts`: **five parallel requests that
      all receive 401 cause exactly one `POST /api/v1/auth/refresh`**, every caller awaits the same
      promise, and each original request is retried exactly once (research R6, constitution XII).
- [X] T017 [P] Write `frontend/src/api/client.refreshfail.test.ts`: when the refresh returns 401 the
      session is cleared and the app navigates to `/login?returnTo=<current path>`; the retry is
      capped at one attempt so no loop is possible.
- [X] T018 Implement `frontend/src/api/problem.ts`: the `ApiError` class, problem+json parsing, the
      `Retry-After` reader, and the status → friendly-message map (network, 429, 503, fallback).
- [X] T019 Implement `frontend/src/api/client.ts`: one `request()` that targets relative `/api/...`
      paths only (constitution XIII), attaches `Authorization: Bearer` when a token exists, parses
      failures through `problem.ts`, and performs the module-level single-flight refresh-and-retry
      from T016/T017.
- [X] T020 Implement `frontend/src/api/endpoints.ts`: thin typed helpers for every operation in
      [contracts/README.md](contracts/README.md), with request and response types derived from
      `schema.ts`.

### Shared presentation pieces

- [X] T021 [P] Create `frontend/src/components/ErrorNotice.tsx` rendering an `ApiError`'s friendly
      message plus its `correlationId` as the user-facing **reference code** (FR-040).
- [X] T022 [P] Create `frontend/src/components/Loading.tsx` and
      `frontend/src/components/EmptyState.tsx` (FR-043, FR-007).
- [X] T023 [P] Establish the base layer of `frontend/src/styles.css`: layout, typography, a visible
      `:focus-visible` outline, and the mobile-first rules that keep pages usable at 360 px
      (FR-041, FR-042, SC-008).

**Checkpoint**: Contract types are generated and committed; every future call goes through one
tested client that refreshes safely and surfaces reference codes.

---

## Phase 3: User Story 2 - Register, sign in, stay signed in, sign out (Priority: P1)

**Goal**: A visitor can create an account and sign in, stays signed in across reloads in the tab,
has their session renewed invisibly, and is returned to where they were when renewal fails.

**Independent Test**: Register a new account, reload (still signed in), expire the access token and
keep using the app (no prompt), then sign out and confirm guarded routes are unreachable.

### Tests for User Story 2 (write first, watch fail) ⚠️

- [X] T024 [P] [US2] Write `frontend/src/auth/session.test.ts`: the access token is held in memory
      and **never** written to storage; the refresh token is written to `sessionStorage` under
      `learnhub.refreshToken`; **nothing is ever written to `localStorage`** (constitution XII,
      data-model.md).
- [X] T025 [P] [US2] Write `frontend/src/auth/AuthProvider.test.tsx` for restore-on-load: no refresh
      token → `anonymous`; refresh succeeds → `authenticated` with the profile from
      `GET /api/v1/auth/me`; refresh fails → `anonymous` with storage cleared (research R4).
- [X] T026 [P] [US2] Write `frontend/src/auth/RequireAuth.test.tsx`: a signed-out visitor is
      redirected to `/login` carrying `returnTo`, and while status is `restoring` a loading state
      renders rather than a flash of the login page (FR-017, data-model.md).
- [X] T027 [P] [US2] Write `frontend/src/pages/RegisterPage.test.tsx`: registration is
      **register-then-login** because `POST /api/v1/auth/register` returns no tokens (research
      R0.1); and if the follow-up login fails, the user is sent to `/login` told the account was
      created — never told registration failed.
- [X] T028 [P] [US2] Write `frontend/src/pages/LoginPage.test.tsx` for field-level messages
      (research R5): register **409** attaches to the email field, register **422** attaches to the
      password field, and login **401** renders as a form-level message because the backend
      deliberately cannot distinguish a wrong email from a wrong password (FR-016).

### Implementation for User Story 2

- [X] T029 [US2] Implement `frontend/src/auth/session.ts`: access token in a module variable,
      refresh token in `sessionStorage` under `learnhub.refreshToken`, plus `clear()`.
- [X] T030 [US2] Implement `frontend/src/auth/AuthProvider.tsx` and
      `frontend/src/auth/useAuth.ts` with the `restoring | authenticated | anonymous` state machine
      and transitions from data-model.md, including `logout()` calling
      `POST /api/v1/auth/logout` and then clearing regardless of the response.
- [X] T031 [US2] Implement `frontend/src/auth/RequireAuth.tsx` redirecting to
      `/login?returnTo=…` and rendering `Loading` while restoring.
- [X] T032 [P] [US2] Create `frontend/src/components/Field.tsx`: label, input, and the slot where a
      field-level message appears (FR-016).
- [X] T033 [US2] Implement `frontend/src/pages/LoginPage.tsx`, honouring `returnTo` on success and
      showing the `Retry-After` wait on a 429.
- [X] T034 [US2] Implement `frontend/src/pages/RegisterPage.tsx` with the register-then-login flow
      from T027.
- [X] T035 [US2] Wire `/login` and `/register` into `frontend/src/App.tsx`, wrap the app in
      `AuthProvider`, and add a header showing the signed-in email with a sign-out control.

**Checkpoint**: Sign-up, sign-in, reload-persistence, silent renewal and sign-out all work. The app
still has no catalog — that is the next phase.

---

## Phase 4: User Story 1 - Browse the catalog without an account (Priority: P1) 🎯 MVP

**Goal**: Anyone can search, filter, sort and page the catalog and open a course's outline, with no
account and no lesson content anywhere.

**Independent Test**: With no session, search and filter the catalog and open a course page.
Nothing asks for a login; no lesson text appears.

### Tests for User Story 1 (write first, watch fail) ⚠️

- [X] T036 [P] [US1] Write `frontend/src/pages/CatalogPage.test.tsx` for URL-driven state: `q`,
      `category`, `level`, `free`, `sort`, `page` and `size` round-trip through the query string and
      are passed through unchanged to `GET /api/v1/courses`; defaults are `sort=newest`, `page=0`,
      `size=20`; `sort` accepts only `newest`, `title`, `price_asc`, `price_desc` (research R11,
      data-model.md, FR-009).
- [X] T037 [P] [US1] Extend `frontend/src/pages/CatalogPage.test.tsx`: an empty `items` array renders
      the friendly empty state with a clear-filters action, **not** an error (FR-007).
- [X] T038 [P] [US1] Write `frontend/src/pages/CourseDetailPage.test.tsx`: a 200 renders details and
      the lesson outline ordered by `position` with **no `content` field anywhere**, and a 404
      renders "no longer available" (FR-008, spec edge case).

### Implementation for User Story 1

- [X] T039 [P] [US1] Create `frontend/src/components/CourseCard.tsx` showing title, level, category
      name and either the price or "Free" (FR-001).
- [X] T040 [US1] Implement `frontend/src/pages/CatalogPage.tsx`: filters, sort and paging held in
      the URL via the router's search params, one `GET /api/v1/courses` per change, and a single
      `GET /api/v1/categories` whose bare array maps `categoryId` → name for the filter control and
      the cards (research R11).
- [X] T041 [US1] Implement `frontend/src/pages/CourseDetailPage.tsx`: course details plus the
      ordered outline from `CoursePublicView.lessons`, which carries no lesson text.
- [X] T042 [P] [US1] Create `frontend/src/pages/NotFoundPage.tsx` and register the catch-all route
      in `frontend/src/App.tsx`.
- [X] T043 [US1] Extend `frontend/src/styles.css` for the catalog grid, filter bar and course page,
      verified readable and operable at 360 px with keyboard only.

**Checkpoint**: A visitor can browse and inspect courses with no account. Combined with Phase 3 this
is the demoable MVP.

---

## Phase 5: User Story 3 - Enroll in a course (Priority: P1)

**Goal**: A signed-in student enrolls — one click when free, a labelled simulated outcome when paid
— with every result explained and never more than one payment.

**Independent Test**: Enroll in the free course in one click; run each of the three paid outcomes;
double-click enroll and confirm exactly one payment in history.

### Tests for User Story 3 (write first, watch fail) ⚠️

- [X] T044 [P] [US3] Write `frontend/src/hooks/useCheckout.idempotency.test.ts`: the key is created
      by `crypto.randomUUID()` on the **first** submit for a (course, choice) pair; **reused** for an
      automatic retry after a network failure; **regenerated** when the payment choice changes; and
      **regenerated** when the user retries after a terminal `FAILED` (research R7, data-model.md,
      FR-023, SC-006).
- [X] T045 [P] [US3] Write `frontend/src/hooks/useCheckout.polling.test.ts` with fake timers: after a
      `SUCCEEDED` payment the hook polls `GET /api/v1/enrollments/{courseId}/progress` once a second
      for up to 15 s; **200 → `enrolled`**, **403 → keep waiting** (the enrollment is created
      asynchronously), **503 → keep waiting** (transient), and a timeout → `pending` with the
      check-back-shortly message (research R8, FR-022).
- [X] T046 [P] [US3] Write `frontend/src/hooks/useCheckout.outcomes.test.ts` for status mapping
      (research R0.2): **201 and 200 are treated identically** (200 is the idempotent replay);
      **403** is "your own course"; **409** is already-enrolled (disambiguated from `detail`);
      **404**, **422** and **503** each get their own message; `DECLINED` surfaces `failureReason`
      (FR-021).

### Implementation for User Story 3

- [X] T047 [US3] Implement `frontend/src/hooks/useCheckout.ts` with the phase machine from
      data-model.md (`idle → submitting → confirming → enrolled | pending | declined | failed |
      blocked`) and the idempotency-key rules from T044.
- [X] T048 [US3] Create `frontend/src/components/EnrollPanel.tsx`: a single confirm for a free
      course; for a paid course exactly three clearly **labelled as test** outcomes mapping to
      `sim-success`, `sim-decline` and `sim-unavailable`, and **no field asking for a card or any
      payment credential** (FR-019, FR-020); the submit control is disabled while pending (FR-024).
- [X] T049 [US3] Wire `EnrollPanel` into `frontend/src/pages/CourseDetailPage.tsx`, sending a
      signed-out visitor to `/login` with `returnTo` set to the course page.
- [X] T050 [US3] Render the post-payment messaging: "being confirmed", then enrolled with a link into
      the course, or the check-back-shortly message on timeout (FR-022).

**Checkpoint**: The money path works end to end and cannot double-charge.

---

## Phase 6: User Story 4 - Learn and track progress (Priority: P2)

**Goal**: A student sees their courses with progress, reads lessons as plain text, and marks lessons
complete with the bar updating.

**Independent Test**: Open My Courses, enter a course, read a lesson, mark it complete, see progress
move, unmark it and see it move back.

### Tests for User Story 4 (write first, watch fail) ⚠️

- [X] T051 [P] [US4] Write `frontend/src/pages/LearnPage.states.test.tsx`: `GET
      /api/v1/courses/{id}/content` returning **403** renders the enroll invitation (FR-029);
      **503** renders an explanation with a retry button and **never** the "not enrolled" wording
      (FR-030, research R9); **404** renders not-found.
- [X] T052 [P] [US4] Write `frontend/src/pages/LearnPage.xss.test.tsx`: a lesson whose `content` is
      `<script>alert(1)</script>` renders that string as **visible text**, nothing executes, and
      `dangerouslySetInnerHTML` appears nowhere (FR-027, constitution XII).
- [X] T053 [P] [US4] Write `frontend/src/pages/LearnPage.progress.test.tsx`: marking a lesson
      complete updates the bar **from the `ProgressView` in the PUT response** with no refetch, and
      unmarking does the same via DELETE (research R9, FR-028).

### Implementation for User Story 4

- [X] T054 [P] [US4] Create `frontend/src/components/ProgressBar.tsx` showing the bar plus
      `completedLessons`/`totalLessons` and the percentage (FR-025, FR-028).
- [X] T055 [US4] Implement `frontend/src/pages/MyCoursesPage.tsx` at `/learn`: `GET
      /api/v1/enrollments`, then **in parallel** per enrollment the public course detail for its
      title and `GET …/progress` for its counts, each card rendering as its own data arrives
      (research R12); an empty state points at the catalog (US4 scenario 2).
- [X] T056 [US4] Implement `frontend/src/pages/LearnPage.tsx` at `/learn/:courseId`: the lesson list,
      the selected lesson's `content` in a text node styled `white-space: pre-wrap`, the
      complete/undo toggle keyed by the lesson `id` added in T010, and the progress bar; guarded by
      `RequireAuth`.
- [X] T057 [US4] Register `/learn` and `/learn/:courseId` in `frontend/src/App.tsx` and extend
      `frontend/src/styles.css` for the lesson layout at 360 px.

**Checkpoint**: A student can work through a course and reach 100%.

---

## Phase 7: User Story 5 - Certificates and public verification (Priority: P2)

**Goal**: A student finds their certificate and code; anyone can verify a code without an account
and learn nothing about the holder.

**Independent Test**: As a student with a certificate, copy the code; signed out, verify it
successfully; then verify a made-up code and get a clear "not valid".

### Tests for User Story 5 (write first, watch fail) ⚠️

- [X] T058 [P] [US5] Write `frontend/src/pages/VerifyPage.test.tsx`: the entered code is **trimmed
      and upper-cased** before being sent (FR-036); a **200** shows valid with `courseTitle` and
      `issuedAt` and nothing about the holder (FR-034); a **404 is rendered as the "not valid"
      result, not an error, and carries no reference code** (research R10, FR-035); a **429** shows
      its `Retry-After` wait, since this route has a stricter limit.

### Implementation for User Story 5

- [X] T059 [US5] Implement `frontend/src/pages/VerifyPage.tsx` serving both `/verify` (with an entry
      field) and `/verify/:code` (verifying immediately), public — not wrapped in `RequireAuth` —
      and register both routes in `frontend/src/App.tsx`.
- [X] T060 [US5] Create `frontend/src/pages/AccountPage.tsx` with its certificates section:
      `GET /api/v1/certificates/mine` rendering course title, issue date and verification code, each
      with a copy control (FR-031, FR-032). Register `/account` behind `RequireAuth`.
- [X] T061 [US5] Add the "a certificate is on its way" state shown when the list is empty, rather
      than an empty area (US5 scenario 3).

**Checkpoint**: The payoff journey works, including for someone with no account.

---

## Phase 8: User Story 6 - Account and payment history (Priority: P3)

**Goal**: A signed-in user sees their email, role and payments, and can sign out from the page.

**Independent Test**: As a student who has paid at least once, open `/account` and confirm email,
role and payment list, then sign out from there.

> Depends on T060, which created `AccountPage` for the certificates section. This phase extends that
> page rather than creating a second one.

### Tests for User Story 6 (write first, watch fail) ⚠️

- [ ] T062 [P] [US6] Write `frontend/src/pages/AccountPage.test.tsx`: the profile shows the email and
      role from `GET /api/v1/auth/me` (FR-037); `GET /api/v1/payments` renders course, amount,
      status and date **newest first** (FR-038); an empty history renders an empty state rather than
      a blank area (US6 scenario 3).

### Implementation for User Story 6

- [ ] T063 [US6] Extend `frontend/src/pages/AccountPage.tsx` with the profile block (email, role) and
      the payment history table sorted newest first, formatting amounts from `amountCents` and
      `currency` with `Intl` (no date or money library — constitution X).
- [ ] T064 [US6] Add the sign-out control to `frontend/src/pages/AccountPage.tsx`, reusing
      `useAuth().logout()` and sending the user to the catalog afterwards (FR-039).

**Checkpoint**: All six user stories are complete and independently demonstrable.

---

## Phase 9: Polish — packaging, platform integration and documentation

**Purpose**: Ship the app as part of the platform, and prove nothing regressed.

- [ ] T065 [P] Create `frontend/Dockerfile`: a Node 24 build stage running `npm ci && npm run build`,
      then an `nginx:alpine` stage serving `dist/` from `/usr/share/nginx/html` (research R13).
- [ ] T066 [P] Create `frontend/nginx/default.conf.template`: `try_files $uri /index.html` for the
      SPA fallback, `location /api/ { proxy_pass ${GATEWAY_URL}; }` rendered by nginx's `envsubst`
      entrypoint with the default `http://api-gateway:9080`, and the headers
      `Content-Security-Policy: default-src 'self'`, `X-Content-Type-Options: nosniff`,
      `Referrer-Policy: no-referrer` and `X-Frame-Options: DENY`.
- [ ] T067 Add the `frontend` service to `docker-compose.yml`: built from `./frontend`, host **9100**
      to container **80**, `GATEWAY_URL` defaulting to `http://api-gateway:9080`,
      `depends_on: api-gateway: condition: service_healthy`, and a `wget`-based healthcheck against
      `/` matching the interval/retries style of the existing services.
- [ ] T068 [P] Add `"9100:frontend"` to the `PORTS` array in `scripts/up.sh`, as the constitution's
      port-map note requires.
- [ ] T069 [P] Create `scripts/seed-demo.sh`: `curl` against `http://localhost:9080`, signing in as
      the bootstrap administrator from `.env`; create two categories and one instructor
      (**register → promote → sign in again**, because the auth contract states a role change takes
      effect only on the target account's next login — research R15); then four published courses
      with three lessons each, **one of them free**. Exit without changes if
      `GET /api/v1/courses` already reports any course. Keep it `shellcheck`-clean like its
      siblings.
- [ ] T070 Extend `scripts/smoke-test.sh` with two checks: `GET http://localhost:9100/` returns HTML,
      and `GET http://localhost:9100/api/v1/courses` returns JSON through the container's `/api/`
      proxy (SC-009 support).
- [ ] T071 [P] Add a `frontend` job to `.github/workflows/ci.yml`: Node 24, `npm ci`,
      `npm run typecheck`, `npm test`, `npm run build`, running independently of the backend job.
- [ ] T072 [P] Create `frontend/README.md`: how to run the dev server on 9100, what `gen:api` does
      and when to re-run it, how to seed demo data, and the **manual walkthrough checklist** mirroring
      [quickstart.md](quickstart.md) and the success criteria. This README lives in `frontend/`, not
      at the repo root.
- [ ] T073 [P] Update the root `README.md`: change the port table's frontend row to **9100 → 80
      (nginx)**, and add a short "Student web app" section pointing at `frontend/README.md`.
- [ ] T074 Check whether `docker-compose.yml`'s `GATEWAY_URL` needs an entry in `.env.example`; add
      one with its non-secret default if the compose file reads it from the environment, and leave
      `.env.example` untouched if the default is inlined.
- [ ] T075 Confirm the production build satisfies `default-src 'self'`: serve the container, load
      every route, and verify the browser console reports **no CSP violation**. A violation is a
      build problem to fix, never a reason to weaken the header (research R13).
- [ ] T076 Full verification: `npm run typecheck && npm test && npm run build` in `frontend/`;
      `mvn -f backend/pom.xml verify` from WSL for the T010 backend change; `docker compose down -v`
      then `scripts/up.sh`, `scripts/seed-demo.sh` and `scripts/smoke-test.sh`; then walk every step
      of [quickstart.md](quickstart.md) by hand.
- [ ] T077 Write `specs/006-student-web-app/checklists/success-criteria.md` recording the measured
      status of SC-001 through SC-009, in the same honest format 002–005 used — including any
      criterion proven once rather than repeatedly.

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)**: no dependencies.
- **Phase 2 (Foundational)**: needs Phase 1. **Blocks every user story.** T009–T012 (the backend
  change) must precede T013–T014 so types are generated once from the final contract.
- **Phase 3 (US2 session)**: needs Phase 2.
- **Phase 4 (US1 catalog)**: needs Phase 2. Independent of US2 — the catalog is public — though the
  shared header from T035 is nicer with it in place.
- **Phase 5 (US3 checkout)**: needs Phase 2, US2 (a session) and US1 (a course page to enroll from).
- **Phase 6 (US4 learning)**: needs Phase 2, US2, and **T010** for lesson ids. Easiest to demo after
  US3, but can be tested against a manually seeded enrollment.
- **Phase 7 (US5 certificates)**: needs Phase 2 and US2. `/verify` alone needs neither.
- **Phase 8 (US6 account)**: needs Phase 2, US2 and **T060** (which creates `AccountPage`).
- **Phase 9 (Polish)**: needs every story you intend to ship.

### Requested ordering note

The brief asked for session before catalog. Both are P1 in spec.md, so their relative order is a
free choice; this list follows the request. If you would rather demo sooner, Phase 4 can be done
before Phase 3 — the catalog needs no session.

### Within each story

- Tests are written and **seen to fail** before the code they cover (constitution VI).
- Shared components before the pages that use them.
- Pages before their route registration and styling.

## Parallel Opportunities

- **Phase 1**: T003, T005 and T007 are independent files.
- **Phase 2**: T011 (contract) runs alongside T009/T010; the three client tests T015–T017 are
  independent files; T021, T022 and T023 are independent.
- **Every story phase**: all its test tasks are separate files and can be written together.
- **Phase 9**: T065, T066, T068, T069, T071, T072 and T073 touch different files entirely.

### Parallel example: Phase 2 client tests

```bash
Task: "Write frontend/src/api/problem.test.ts"            # T015
Task: "Write frontend/src/api/client.singleflight.test.ts" # T016
Task: "Write frontend/src/api/client.refreshfail.test.ts"  # T017
```

### Parallel example: User Story 4 tests

```bash
Task: "Write frontend/src/pages/LearnPage.states.test.tsx"   # T051
Task: "Write frontend/src/pages/LearnPage.xss.test.tsx"      # T052
Task: "Write frontend/src/pages/LearnPage.progress.test.tsx" # T053
```

## Implementation Strategy

### MVP

Phases 1, 2, 3 and 4 — scaffold, API client, session, catalog. That is a deployable app a visitor
can browse and sign up to. **Stop and validate there** before taking on money or content.

### Incremental delivery

1. Phases 1–2 → the app runs, calls the gateway, and the client is tested.
2. Phase 3 → accounts work.
3. Phase 4 → **MVP**: browse plus accounts.
4. Phase 5 → enrolling works, and cannot double-charge.
5. Phase 6 → the core value: learning with progress.
6. Phases 7–8 → the payoff and the supporting account page.
7. Phase 9 → shipped with the platform, documented and verified.

Every phase above ends with an app that runs, which is what the requested ordering was for.

## Notes

- **[P]** means a different file with no incomplete dependency.
- Tests come first everywhere; a test that passes before its implementation exists is a broken test.
- Exactly one task touches `backend/` — T010, the approved deviation. If it is skipped, T053 and
  Phase 6's mark-complete cannot work at all.
- Commit after each task or logical group; the phase checkpoints are good places to stop.
