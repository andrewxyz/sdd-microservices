# Implementation Plan: Student Web App

**Branch**: `006-student-web-app` | **Date**: 2026-10-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-student-web-app/spec.md`

## Summary

Build the student-facing web app in `frontend/` as a React 19 single-page app that reaches the
platform only through the gateway at the same-origin path `/api/`. It delivers the six journeys in
the spec — browse, register/sign in, enroll, learn, certificates, account — against the backend
exactly as it already is, with one exception approved during planning: the lesson-content endpoint
gains a lesson `id` field, because without it no gateway-reachable endpoint tells a student which
lesson to mark complete (see **Complexity Tracking**).

Technical approach: one `request()` wrapper owns every call — bearer header, problem-details
parsing into an `ApiError` carrying the `correlationId` shown to users as a reference code, and a
single-flight refresh on 401 that retries the original request once. The access token lives in
memory and the refresh token in `sessionStorage`, per constitution XII. API types are generated
from the five existing backend contracts into a committed `src/api/schema.ts`, so a contract change
surfaces as a type error. Production is a multi-stage image: Node builds, `nginx:alpine` serves the
bundle with a SPA fallback and proxies `/api/` to the gateway, so no CORS is ever involved.

## Technical Context

**Language/Version**: TypeScript in `strict` mode, built and tested on Node 24 LTS. The shipped
artifact is static files; Node is a build-and-test dependency only.

**Primary Dependencies**: Runtime — React 19 and `react-router` only. Dev — `vite`, `vitest`,
`@testing-library/react`, `jsdom`, `openapi-typescript`, `typescript`. No UI component library and
no state-management library (constitution XI). Exact versions are pinned by the committed lockfile;
this plan deliberately does not name version numbers it cannot verify at install time.

**Storage**: None of its own. The only client-held state is the session: the access token in a
module-level variable and the refresh token in `sessionStorage` (constitution XII). Everything else
is fetched per view.

**Testing**: Vitest with Testing Library and `jsdom`, `fetch` mocked, fake timers for polling. Tests
cover logic — API client, session, checkout, guards, lesson rendering — not snapshots
(constitution XI). The backend smoke test remains the integration gate, extended with two frontend
checks.

**Target Platform**: Evergreen browsers on phone and desktop; served by `nginx:alpine` in the
platform's Docker Compose stack, and by the Vite dev server during development. Both on port 9100.

**Project Type**: Web frontend (single-page app) added to an existing monorepo whose backend is a
Spring Cloud microservices platform.

**Performance Goals**: Catalog usable on first load without a visible blank period — a loading state
appears immediately and results replace it in one round trip. Filter and page changes issue one
request. The whole first-visit-to-enrolled journey fits inside the spec's two-minute budget
(SC-001).

**Constraints**: Same-origin `/api/` only — no absolute service URLs, no CORS reliance
(constitution XIII). Lesson text is never interpreted as markup (constitution XII, FR-027). Every
user-visible error carries a reference code (FR-040). Keyboard-operable and readable at 360 px
(SC-008). At most one payment per enrollment attempt (FR-023).

**Scale/Scope**: Nine routes (`/`, `/courses/:id`, `/login`, `/register`, `/learn`,
`/learn/:courseId`, `/account`, `/verify`, `/verify/:code`) plus a not-found page; one student per
browser tab; a demo catalog of a handful of courses.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Checked against constitution **v1.2.0**.

| Principle | Gate | Status |
|-----------|------|--------|
| I. Monorepo layout | `frontend/` is its own app root with its own `package.json`, lockfile and build; not a Maven module; backend and frontend build independently | **Pass** — `frontend/` gains exactly that, and nothing is added to `backend/pom.xml` |
| II. Service ownership and data isolation | No service reads another's database; no domain logic in shared libraries | **Pass** — the one backend change adds a field to course-service's own view of its own data |
| III. Discovery and configuration | Backend registration and config rules | **Pass** — not touched |
| IV. Gateway as the only entry point | The frontend must never call a service directly, and relies on OpenAPI as the contract | **Pass** — every call goes to same-origin `/api/`, proxied to the gateway; types are generated from the published contracts |
| V. Synchronous and asynchronous communication | Feign/RabbitMQ rules | **Pass** — not touched; the frontend consumes no events |
| VI. Test-First Quality (NON-NEGOTIABLE) | Tests written first, seen to fail, then implemented | **Pass** — every logic task in `tasks.md` is sequenced test-first, including the one backend change |
| VII. Security and secrets | No committed secrets; `.env.example` documents variables | **Pass** — the frontend holds no secret; `scripts/seed-demo.sh` reads the bootstrap admin from `.env`, which is already git-ignored |
| VIII. Errors and observability | RFC 7807 with `correlationId` everywhere a client can reach | **Pass** — the client parses exactly that shape and surfaces `correlationId` as the user's reference code |
| IX. Conventions | `/api/v1` paths; all host ports follow the port map | **Pass** — the app calls `/api/v1/**`; port 9100 is added to `scripts/up.sh`'s check as the constitution's port-map note requires |
| X. Simplicity | No extra technology without an amendment | **Pass, with one judgement recorded** — see the note below |
| XI. Frontend stack and conventions | React 19, TS strict, Vite, React Router, plain CSS, no UI framework, no state library, dependency justification, Vitest + Testing Library on logic, backend smoke tests still the gate | **Pass** — see Technical Context and research R2 |
| XII. Frontend session and security | Access token in memory, refresh in `sessionStorage`, no HTML rendering of server text, single-flight refresh, correlation ID in errors | **Pass** — research R4, R5, R6, R9 implement each rule directly |
| XIII. Frontend-backend boundary | Same-origin `/api/` through the gateway, proxied in dev and production, types generated from contract OpenAPI | **Pass** — research R1 and R3 |

**Note on Principle X** — the constitution names Vitest and Testing Library as the frontend test
tools and says adding another "requires an amendment". Two dev packages are not on that list, and
the judgement is recorded here rather than left implicit:

- **`jsdom`** is the DOM implementation Vitest renders into; Testing Library cannot run without
  one. It is read as part of the named pair rather than a third test tool.
- **`openapi-typescript`** is build-time codegen, not a test tool — and constitution XIII itself
  *requires* that API types be generated from the contract OpenAPI files, so some generator is
  mandated by the constitution. Nothing it produces ships at runtime.

If a reviewer disagrees with either reading, the fix is a one-line PATCH amendment naming them, not
a change to this design.

**Result: all gates pass.** One deviation exists, but it is from the feature's own scope statement
rather than from any constitution principle — recorded under Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/006-student-web-app/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output — client-side state only
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output — which backend contracts this app consumes
├── checklists/
│   └── requirements.md  # From /speckit-specify
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
frontend/                           # New. Its own app root (constitution I, XI)
├── Dockerfile                      # Multi-stage: Node build → nginx:alpine
├── nginx/
│   └── default.conf.template       # SPA fallback, /api/ proxy to ${GATEWAY_URL}, security headers
├── index.html
├── package.json                    # Scripts: dev, build, preview, test, typecheck, gen:api
├── package-lock.json               # Exact pinned versions, committed
├── tsconfig.json                   # strict: true
├── vite.config.ts                  # Dev server on 9100, /api proxy, Vitest config
├── README.md                       # Frontend-specific docs, incl. the demo-seed walkthrough
└── src/
    ├── main.tsx
    ├── App.tsx                     # Routes
    ├── styles.css                  # The single stylesheet (constitution XI)
    ├── api/
    │   ├── schema.ts               # GENERATED by gen:api, committed
    │   ├── client.ts               # request(), bearer header, 401 single-flight refresh + retry
    │   ├── problem.ts              # problem+json → ApiError, friendly message mapping
    │   └── endpoints.ts            # Thin typed helpers per endpoint
    ├── auth/
    │   ├── session.ts              # Access token in memory, refresh token in sessionStorage
    │   ├── AuthProvider.tsx        # Restore-on-load, login, register, logout
    │   ├── useAuth.ts
    │   └── RequireAuth.tsx         # Redirects to /login with returnTo
    ├── hooks/
    │   └── useCheckout.ts          # Idempotency key lifecycle + post-payment polling
    ├── pages/
    │   ├── CatalogPage.tsx         # /            filters, sort, paging in the URL query
    │   ├── CourseDetailPage.tsx    # /courses/:id  outline + enroll panel
    │   ├── LoginPage.tsx           # /login
    │   ├── RegisterPage.tsx        # /register
    │   ├── MyCoursesPage.tsx       # /learn
    │   ├── LearnPage.tsx           # /learn/:courseId
    │   ├── AccountPage.tsx         # /account      profile, payments, certificates
    │   ├── VerifyPage.tsx          # /verify and /verify/:code
    │   └── NotFoundPage.tsx
    └── components/
        ├── ErrorNotice.tsx         # Message + reference code (FR-040)
        ├── Loading.tsx
        ├── EmptyState.tsx
        ├── ProgressBar.tsx
        ├── CourseCard.tsx
        ├── EnrollPanel.tsx
        └── Field.tsx               # Label + input + field-level message (FR-016)

backend/course-service/             # The one approved backend change (Complexity Tracking)
└── src/main/java/.../api/CourseContentView.java   # LessonView gains `UUID id`

docker-compose.yml                  # New `frontend` service: 9100→80, depends_on api-gateway healthy
scripts/up.sh                       # Port check gains 9100
scripts/smoke-test.sh               # Two new checks through the frontend container
scripts/seed-demo.sh                # New: categories, instructor, four published courses
README.md                           # Port table row, frontend pointer
.github/workflows/ci.yml            # New frontend job: npm ci, typecheck, test, build
```

**Structure Decision**: `frontend/` is a standalone application root as constitution I and XI
require — its own `package.json`, lockfile, `tsconfig.json` and build, with no coupling to the Maven
reactor. Inside `src/`, the four directories named in the brief (`api/`, `auth/`, `pages/`,
`components/`) are joined by one small addition, `hooks/`, holding `useCheckout.ts`: the checkout
state machine is logic rather than UI or session, it is the single most heavily tested unit in the
app, and burying it in a page component would make it hard to test in isolation. Tests sit beside
the code they cover (`client.test.ts` next to `client.ts`), with one shared `src/test/setup.ts`.

## Complexity Tracking

> One deviation, approved during planning.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| The backend **is** changed, contrary to the spec's "the backend is not changed" assumption: `CourseContentView.LessonView` in course-service gains a `UUID id` field (plus its mapping, its contract in `specs/004-checkout-enrollment/contracts/course-service-content.openapi.yaml`, and a test assertion) | Marking a lesson complete requires `PUT /api/v1/enrollments/{courseId}/lessons/{lessonId}/complete`, which takes a lesson **UUID**. No gateway-reachable endpoint gives a student that id: the public detail returns `LessonOutline(title, position, durationMinutes)`, the enrolled content endpoint returns `LessonView(title, position, durationMinutes, content)`, and the only view carrying `id` — `/courses/{id}/full` — is owner/administrator only (`CourseFullController`). Without this field, FR-028 and SC-002 are unbuildable and the certificate journey can never be reached through the app | **Exposing `id` on the public outline** was rejected as a wider public surface for no gain, since the learning page already fetches the content endpoint. **Using `position` instead of `id`** was rejected because the endpoint's contract requires a UUID and validates it against the course's lesson list. **Dropping mark-complete** was rejected because it guts User Story 4, makes SC-002 unachievable and leaves progress permanently at zero. The chosen change is additive and backward-compatible: existing consumers ignore the new field, and the field stays behind both authentication and the endpoint's existing enrollment gate |

Note on scope wording: the spec's Assumptions section has been corrected to record this change, so
the two documents do not contradict each other. No constitution principle is affected — course-
service is adding a field to its own view of its own data (Principle II is intact).
