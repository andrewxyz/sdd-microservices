# LearnHub student web app

The React/TypeScript frontend for the LearnHub platform. Browse the course catalog, enroll, track
your progress, earn certificates, and manage your account — all served as a single-page application
behind an nginx proxy.

## Running the dev server

Requires Node 24+. The backend platform must be up (`scripts/up.sh` from the repo root).

```bash
npm install          # first time only
npm run dev          # starts on http://localhost:9100
```

All API calls go to `/api/…`, which the dev server proxies to `http://localhost:9080` (the gateway).
No CORS configuration is needed.

## Generating API types

TypeScript types for every service API are generated from the OpenAPI contracts in `specs/*/contracts/`:

```bash
npm run gen:api      # regenerates src/api/generated/*.ts and src/api/schema.ts
```

Re-run whenever a contract file changes. The generated files are committed so CI does not need a
generation step; generating locally and then committing the diff is the intended workflow.

## Seeding demo data

With the platform running:

```bash
scripts/seed-demo.sh    # run from the repo root
```

Creates two categories (Programming, Design), one instructor account, and four published courses
(one free, three paid, each with three lessons). The script is idempotent: it exits without changes
if any course already exists.

## Manual walkthrough checklist

Follow these steps to verify every user story end to end after starting the platform and seeding data:

### US1 — Public course catalog

- [ ] Open `http://localhost:9100` — the catalog page loads with at least four courses.
- [ ] Search by keyword (e.g. "TypeScript") — only matching courses appear.
- [ ] Filter by level (Beginner) — only beginner courses appear.
- [ ] Filter to free only — only the free course appears.
- [ ] Clear filters — all four courses reappear.
- [ ] Click a course card — the course detail page loads with its lessons listed.
- [ ] Reload the filtered page — the filters survive a browser refresh (URL-driven state).

### US2 — Account sign-up and sign-in

- [ ] Click "Create account" — the register form appears.
- [ ] Register a new student account — you are signed in immediately after.
- [ ] Sign out — the header reverts to "Sign in / Create account".
- [ ] Sign in with the same credentials — you are signed in again.
- [ ] Close the tab and reopen `http://localhost:9100` — you remain signed in (session restored from
      refresh token in sessionStorage).
- [ ] Try an incorrect password — a form-level error appears (no reference code, because 401 carries no
      correlation ID from the gateway).

### US3 — Course enrollment

- [ ] Open a paid course detail page while signed in — the enroll panel with radio choices appears.
- [ ] Select "Test card: success" and click Enroll — the panel transitions to an enrolled confirmation.
- [ ] Open a free course and click Enroll — enrollment succeeds immediately.
- [ ] Open a course you are already enrolled in — the panel shows the enrolled state.
- [ ] Select "Test card: decline" and click Enroll — the panel shows the declined state and lets you
      retry.
- [ ] Sign out and open a course — a "Sign in to enroll" link appears in place of the enroll panel.

### US4 — Learning and progress tracking

- [ ] Click "My courses" in the header — your enrolled courses appear.
- [ ] Click a course to open its learning page — lessons are listed on the left, the first lesson
      selected.
- [ ] Click "Mark complete" on a lesson — the progress bar updates immediately (no page reload).
- [ ] Click "Mark incomplete" — the bar reverts.
- [ ] Complete all lessons — the bar reaches 100%.
- [ ] Open `/learn/nonexistent` — a "not found" message appears.
- [ ] Open the learning page for a course you are not enrolled in — a "not enrolled" prompt appears.

### US5 — Certificates and verification

- [ ] After completing a course, open "Account" — a certificate appears within a minute.
- [ ] Click "Copy code" — the verification code is in the clipboard.
- [ ] Sign out and open `http://localhost:9100/verify` — the verification form appears.
- [ ] Paste the code and click Verify — the course title and issue date appear.
- [ ] Enter a made-up code — "This certificate code is not valid." appears (no reference code, no
      error styling).

### US6 — Account and payment history

- [ ] Open "Account" while signed in — your email and role appear under "Profile".
- [ ] The payment history table shows your payments, newest first.
- [ ] Amounts appear formatted (e.g. $49.99, not 4999).
- [ ] Click "Sign out" on the account page — you are redirected to the catalog.
- [ ] If you have no payment history, an empty-state message appears instead of a blank table.
