# Phase 0 Research: Student Web App

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-19

Every decision below was taken against the **published contracts and the actual backend code**, not
against assumptions about what the backend probably does. R0 records the three places where reading
them changed the plan.

## R0. Three corrections found by reading the contracts

The planning brief made three reasonable-sounding assumptions that the contracts contradict. All
three are settled here before any code is written.

### R0.1 Registration does not sign the user in

- **Found**: `POST /api/v1/auth/register` returns **201** with `{id, email, role, createdAt}` and
  **no tokens** (`specs/002-user-auth/contracts/auth-service.openapi.yaml`). Only `/login` and
  `/refresh` return a `TokenPair`.
- **Decision**: `register()` performs register-then-login: on a 201 it immediately calls `/login`
  with the same credentials and stores the resulting pair. The user sees one action, as FR-010
  requires.
- **Consequence**: the rare case where register succeeds but the follow-up login fails is handled
  explicitly — the account exists, so the user is sent to `/login` with a message saying the account
  was created and to sign in. Silently reporting "registration failed" would be a lie.

### R0.2 "Own course" is 403, and 409 means three different things

- **Found**: `specs/004-checkout-enrollment/contracts/payment-service.openapi.yaml` maps checkout
  failures as **403** = the caller is the course's own instructor; **404** = no such course;
  **409** = *either* the course is not published, *or* the caller already has a succeeded payment,
  *or* the idempotency key was reused with different content; **422** = paid course with no
  `paymentToken`; **503** = course-service unreachable. The brief assumed own-course was 409.
- **Decision**: map 403 to "you cannot enroll in your own course" and disambiguate 409 from the
  problem-details `detail` text, falling back to the already-enrolled wording (the overwhelmingly
  likely case from this app, which never submits a draft course and always generates its own key).
- **Also found**: replaying the same key with identical content returns **200** rather than 201,
  with the original `PaymentView`. The client therefore treats 200 and 201 identically — which is
  exactly what makes a retry after a network error safe (R7).

### R0.3 No reachable endpoint exposes lesson identifiers — the one backend change

- **Found**: marking a lesson complete needs a lesson **UUID**, but
  `CourseContentView.LessonView` is `(title, position, durationMinutes, content)` and
  `CoursePublicView.LessonOutline` is `(title, position, durationMinutes)`. The only view carrying
  `id` is `CourseFullView`, served by `CourseFullController` to the **owner or an administrator
  only**. Verified in both the contracts and the Java records.
- **Decision** (approved during planning): add `UUID id` to `CourseContentView.LessonView`, update
  its mapping, its contract file and a test assertion. Additive, backward-compatible, and the id
  stays behind both authentication and the endpoint's existing enrollment gate.
- **Alternatives considered**: putting `id` on the public outline (wider public surface for no
  gain, since the learning page already calls the content endpoint); using `position` instead (the
  endpoint requires a UUID and validates it against the course's lessons); dropping mark-complete
  (guts User Story 4 and makes SC-002 unreachable). Full justification: plan.md Complexity Tracking.

## R1. Same-origin `/api/`, proxied in both environments

- **Decision**: every request targets a relative `/api/...` path. The Vite dev server proxies
  `/api` to `http://localhost:9080`; the production `nginx:alpine` image proxies `/api/` to
  `${GATEWAY_URL}`, defaulting to `http://api-gateway:9080`, rendered from a template by nginx's
  built-in `envsubst` entrypoint.
- **Rationale**: constitution XIII requires it, and it removes CORS from the picture entirely —
  the browser never makes a cross-origin request, so the gateway's CORS configuration is irrelevant
  to this app and no origin needs adding to it.
- **Alternatives considered**: calling `http://localhost:9080` directly (cross-origin, needs CORS,
  and bakes a host into the bundle — forbidden by constitution XIII); a build-time base URL
  (same problem, plus a rebuild per environment).

## R2. Dependency budget, and why each one is here

Constitution XI requires a one-line justification per runtime dependency and forbids a UI framework
or state library.

| Dependency | Kind | Justification |
|------------|------|---------------|
| `react` + `react-dom` | runtime | The framework the constitution names |
| `react-router` | runtime | Nine routes, URL-driven catalog state and the `returnTo` redirect all need a real router; hand-rolling history handling would be more code and worse |
| `vite` | dev | The build tool the constitution names |
| `typescript` | dev | `strict` mode is mandated by constitution XI |
| `vitest` | dev | The test runner the constitution names |
| `@testing-library/react` | dev | The component-testing library the constitution names |
| `jsdom` | dev | The DOM Vitest renders into; Testing Library needs one |
| `openapi-typescript` | dev | Generates `schema.ts` from the backend contracts (constitution XIII); pure build-time codegen, nothing ships |

Nothing else is added. State is React's own `useState`/`useContext` plus the router's URL; styling
is one hand-written `styles.css`; dates and currency use the platform `Intl` APIs. Versions are
pinned exactly by the committed lockfile, so CI and a developer's machine build the same tree.

**Implementation finding**: `typescript` is pinned to **5.9.3**, not the newest `7.x` line, because
`openapi-typescript@7.13.0` declares a peer dependency of `typescript@^5.x` — installing TS 7
produced an unresolvable `npm install` conflict. `strict` mode (constitution XI) is identical in
meaning across both lines; this is a compatibility constraint from a declared dependency, not a
design choice.

## R3. Generated API types

- **Decision**: an npm script `gen:api` runs `openapi-typescript` over the five contracts this app
  consumes — auth (002), course catalog (003), course content (004), payment (004), enrollment
  (004), progress (005) and certificate (005) — emitting a single committed `src/api/schema.ts`.
  `endpoints.ts` derives its request and response types from it.
- **Rationale**: constitution XIII mandates generation over hand-writing so a contract change
  surfaces as a type error. Committing the output keeps `npm ci && npm run build` working with no
  codegen step in CI and makes contract drift visible in review as a diff.
- **Consequence**: regenerating is a deliberate act. `gen:api` is run whenever a consumed contract
  changes — including for R0.3's new lesson `id` — and the resulting diff is committed.
- **Implementation finding**: `openapi-typescript`'s CLI takes exactly one `[input]` document per
  run — confirmed via `openapi-typescript --help`, which shows no multi-file or glob merge option.
  The seven contracts are independent OpenAPI documents (separate `info`, separate `servers`), and
  two of them even reuse the schema name `ProblemDetail` for the same shared shape, so bundling them
  into one document first would need a bundler this project does not otherwise need. `gen:api`
  (`frontend/scripts/gen-api.mjs`) therefore runs `openapi-typescript` once per contract into
  `src/api/generated/<name>.ts`, and a short hand-written `src/api/schema.ts` barrel re-exports each
  as a namespace (`auth`, `course`, `courseContent`, `payment`, `enrollment`, `progress`,
  `certificate`). The rest of the app still imports from one file; only the generation step is
  per-contract. All eight files (seven generated plus the barrel) are committed.

## R4. Session: memory plus `sessionStorage`, restored by refreshing

- **Decision**: the access token lives in a module-level variable in `session.ts` and never touches
  storage. The refresh token lives in `sessionStorage`. On application load, if a refresh token is
  present, the app calls `/refresh` once to obtain a fresh pair before rendering guarded content;
  if that fails it clears everything and treats the user as signed out. `logout()` calls
  `POST /api/v1/auth/logout` with the refresh token, then clears both regardless of the response.
- **Rationale**: exactly constitution XII. A page reload keeps the user signed in (FR-013) because
  the refresh token survives it; a new tab starts signed out because `sessionStorage` is per-tab,
  which the spec states as intended rather than a defect.
- **Consequence**: there is a short "restoring session" state on first paint. Guarded routes render
  a loading state during it rather than flashing the login page — a flash would otherwise be the
  most visible bug in the whole app.

## R5. Problem details → `ApiError`, and the missing field errors

- **Found**: the shared `problem-details.schema.json` defines `{type, title, status, detail,
  instance, correlationId}` and **no field-error property**. `ProblemDetailsAdvice` in
  `platform-common` funnels framework exceptions — including bean-validation failures — through one
  `handleExceptionInternal` that emits exactly those fields. **The backend never returns per-field
  validation errors.**
- **Decision**: `ApiError` carries `status`, `title`, `detail`, `correlationId` and a
  `fieldErrors` map that the client populates itself, by mapping the status codes whose meaning is
  unambiguous for a given form:
  - register → **409** attaches to `email` ("that email is already registered"), **422** attaches
    to `password` ("choose a longer password")
  - login → **401** is a form-level message, not a field one (the backend deliberately returns the
    same error for a wrong email and a wrong password, so blaming one field would mislead)
  - verification → **404** is a result, not an error (R10)
- **Rationale**: FR-016 asks for messages beside the right field. Since the server cannot say which
  field, the only honest implementation is a small, explicit, per-form mapping of the status codes
  whose meaning *is* unambiguous — and leaving everything else as a form-level message.
- **Alternatives considered**: parsing `detail` prose to guess a field (brittle and would break
  silently when wording changes); changing the backend to emit field errors (a second backend
  change, not justified by the value).

Every `ApiError` also produces a friendly message by status: a network failure ("we could not reach
LearnHub — check your connection"), **429** (uses the `Retry-After` header to say when to try
again), **503** ("temporarily unavailable, try again shortly"), and an unknown-error fallback. The
`correlationId` is always rendered as the reference code FR-040 requires.

## R6. Single-flight refresh

- **Decision**: `client.ts` holds one module-level `Promise<void> | null`. On a 401 *when a token
  was sent*, the first caller starts `/refresh` and stores the promise; concurrent callers await the
  same promise instead of starting their own. When it resolves, each caller retries its original
  request **once**. If it rejects, the session is cleared and the app navigates to
  `/login?returnTo=<current path>`. The promise is cleared in a `finally` either way.
- **Rationale**: constitution XII requires single-flight, and the reason is concrete — the backend
  rotates refresh tokens and treats reuse of a spent one as theft by revoking the whole session, so
  two parallel refreshes would log the user out. A 401 with *no* token is not a refresh trigger; it
  simply means sign-in is required.
- **Consequence**: the retry is capped at one attempt, so a genuinely expired session cannot loop.
  The "five parallel 401s cause exactly one refresh call" test guards this directly.

## R7. Checkout: idempotency key lifecycle

- **Decision**: `useCheckout` generates a key with `crypto.randomUUID()` on the **first submit** for
  a given (course, payment choice) pair and holds it. The same key is reused for a double click and
  for an automatic retry after a network failure. It is regenerated when the payment choice changes,
  and when the user retries after a terminal `FAILED` result. The submit control is disabled while a
  request is in flight.
- **Rationale**: this is what makes FR-023 and SC-006 true, and it lines up with the backend's own
  semantics from R0.2 — an identical replay returns 200 with the original payment, while the same
  key with *different* content is a 409 the user should never be able to trigger.
- **Consequence**: changing the choice from "succeeds" to "is declined" must produce a new key,
  otherwise the backend would reject it as a key reused with different content.

## R8. Post-payment polling uses the progress endpoint

- **Found**: the brief left this conditional on what the contract supports.
  `GET /api/v1/enrollments/{courseId}/progress` returns **200** with a `ProgressView` when the
  caller is enrolled and **403** when they are not — unambiguous — plus **503** when course-service
  cannot be reached to compute the lesson total.
- **Decision**: poll that endpoint once per second for up to 15 seconds after a `SUCCEEDED`
  payment. **200** stops the poll and shows the course as enrolled; **403** means *not yet* and the
  poll continues (the enrollment is created asynchronously from the payment event); **503** is
  transient and the poll also continues; anything else stops with an error. On timeout the app shows
  the check-back-shortly message from FR-022.
- **Rationale**: it is a single call that answers exactly the question being asked, and it returns
  the progress the UI wants to display anyway. The enrollments list would need paging through
  results to find one course.
- **Consequence**: treating 403 as "keep waiting" is correct *only* in this polling window. Outside
  it — on the learning page — the same 403 means "not enrolled", and R9 relies on that.

The spec's user-facing wait is "about ten seconds" (FR-022); the poll runs to 15 to leave headroom
above the platform's own ten-second target before telling the user to check back.

## R9. Learning page states

- **Decision**: `GET /api/v1/courses/{courseId}/content` drives the page. **200** renders the
  lessons; **403** renders the enroll invitation (FR-029); **404** renders not-found; **503**
  renders an explanation with a retry button (FR-030) and never the "not enrolled" wording.
- **Rationale**: the contract distinguishes these precisely — 403 is "confirmed not enrolled", 503
  is "could not confirm", and the backend deliberately refuses to serve content on an unconfirmed
  basis. Collapsing them would tell an enrolled user they are not enrolled during a transient
  outage.
- **Marking complete**: `PUT`/`DELETE .../lessons/{lessonId}/complete` both return **200 with the
  resulting `ProgressView`**, so the progress bar updates from the response. No refetch is needed,
  which also removes a class of stale-progress bugs.

## R10. Lesson text and the public verification result

- **Decision**: lesson content renders into a plain text node styled `white-space: pre-wrap`.
  `dangerouslySetInnerHTML` appears nowhere in the codebase.
- **Rationale**: constitution XII and FR-027. React escapes text children by default, so the
  guarantee comes from *not* opting out; the test that pastes a `<script>` tag into lesson content
  and asserts it is visible as text rather than executed is what keeps it true.
- **Verification page**: `GET /api/v1/certificates/verify/{code}` returns **200** with
  `{valid, courseTitle, issuedAt}` and **404** for an unknown code. A 404 here is a *result* — "not
  valid" — not an error, so it is rendered as an ordinary outcome without a reference code. The code
  is trimmed and upper-cased before sending (FR-036), which is safe because the backend generates
  codes from a fixed uppercase alphabet. The endpoint also carries a stricter rate limit, so a
  **429** is surfaced with its `Retry-After` wait.

## R11. Catalog state lives in the URL

- **Decision**: `q`, `category`, `level`, `free`, `sort`, `page` and `size` are held in the query
  string via the router's search-params API and passed straight through to
  `GET /api/v1/courses`, whose parameters have the same names and whose `sort` accepts exactly
  `newest`, `title`, `price_asc`, `price_desc`.
- **Rationale**: FR-009 wants a shareable, reload-surviving result set, and the backend's parameter
  names already match — so the URL is the state, with no duplicate copy in React state to drift.
- **Consequence**: the category filter needs names, but the list returns `categoryId`. The app
  fetches `GET /api/v1/categories` (a bare array, public) once and maps id → name for both the
  filter control and the course cards.

## R12. My Courses assembles from existing endpoints

- **Found**: `EnrollmentView` is `{id, courseId, enrolledAt}` — no title and no completion flag.
- **Decision**: `/learn` fetches `GET /api/v1/enrollments`, then for each enrollment fetches the
  public course detail (title) and the progress (counts, `courseCompletedAt`) **in parallel**, and
  renders each card as its data arrives.
- **Rationale**: the spec explicitly assumes no new backend aggregate. For the demo-sized catalogs
  this app targets the cost is a handful of parallel requests, which is acceptable; inventing a
  backend aggregate would be a second, larger backend change for a page that lists a few rows.
- **Alternatives considered**: a new `/api/v1/enrollments/summary` endpoint — rejected as scope
  creep given R0.3 already costs one backend change.

## R13. Production image and security headers

- **Decision**: a multi-stage `Dockerfile` — a Node stage runs `npm ci && npm run build`, then
  `nginx:alpine` serves `/usr/share/nginx/html` with `try_files $uri /index.html` for the SPA
  fallback and proxies `/api/` to `${GATEWAY_URL}`. Headers: `Content-Security-Policy:
  default-src 'self'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`,
  `X-Frame-Options: DENY`. The Compose service maps host **9100** to container **80**, depends on
  `api-gateway` being healthy, and has a `wget`-based healthcheck against `/`.
- **Rationale**: `default-src 'self'` is enforceable precisely *because* the app has no CDN, no
  inline third-party script and no cross-origin call — the same-origin design from R1 is what makes
  the strict policy free.
- **Consequence**: Vite must not emit inline scripts that would need `'unsafe-inline'`; the build
  is checked against the policy as part of the quickstart, and a violation is a build problem to
  fix rather than a reason to weaken the header.

## R14. Testing approach

- **Decision**: Vitest with `jsdom`, `fetch` replaced by a mock, and fake timers for anything
  time-based. Tests are written before the code they cover (constitution VI). The set that must
  exist: single-flight refresh (five parallel 401s → exactly one `/refresh`), refresh failure clears
  the session and redirects, problem-details parsing including `correlationId`, idempotency-key
  reuse and regeneration, checkout polling under fake timers (success, timeout, 403-then-200),
  `RequireAuth` redirecting with `returnTo`, the learning page's 403 and 503 states, and lesson
  content containing a `<script>` tag rendering as visible text.
- **Rationale**: these are exactly the places where a quiet bug is expensive — money, sessions and
  script injection. Rendered snapshots are excluded by constitution XI and would not catch any of
  them.
- **Integration gate**: unchanged — `scripts/smoke-test.sh` remains the platform gate, extended with
  two checks that prove the container serves the app and proxies the API
  (`GET http://localhost:9100/` returns HTML; `GET http://localhost:9100/api/v1/courses` returns
  JSON). No browser end-to-end tests are added.

## R15. Demo data

- **Decision**: `scripts/seed-demo.sh` uses `curl` against `http://localhost:9080`, signs in as the
  bootstrap administrator from `.env`, and creates two categories, one instructor (register →
  promote → sign in again, because a role change only takes effect on the next login) and four
  published courses with three lessons each, one of them free. It exits without changes if
  `GET /api/v1/courses` already reports any course.
- **Rationale**: every journey in the spec needs a populated catalog, and a paid course is required
  to exercise the three simulated payment outcomes. Making it idempotent means it is safe to re-run
  and safe to call from a walkthrough.
- **Note**: the register-promote-relogin sequence is not incidental — the auth contract states a
  role change "takes effect for the target account on its next login", so skipping the second login
  would leave the instructor unable to create courses.
