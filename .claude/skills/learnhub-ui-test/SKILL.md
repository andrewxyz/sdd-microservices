---
name: learnhub-ui-test
description: Run the LearnHub student web app through a real browser with playwright-cli and report what passes or fails - login form, account registration, catalog ratings, and the full course review flow (not-enrolled refusal, enroll, create, edit, delete). Use whenever the user asks to test, smoke-test, click through, verify or demo the LearnHub frontend at localhost:9100, to check a UI change in the browser, or to confirm the reviews feature end to end, even if they do not mention Playwright. Prefer this over ad-hoc browser poking so the known pitfalls below are avoided.
---

# LearnHub UI test with playwright-cli

Drives `http://localhost:9100` (nginx frontend, which proxies `/api/` to the gateway) with the global `playwright-cli` (see the `playwright-cli` skill for the command reference). It covers what Vitest cannot: the real built bundle, real backend, real session storage.

## Before you start

1. The stack must be up. Check `curl -s -o /dev/null -w "%{http_code}" http://localhost:9100/` returns 200. If not, bring it up with `wsl -e bash -lc "cd <repo> && bash scripts/up.sh"` (Docker lives in WSL). Build images one at a time if WSL has crashed before; parallel builds exhaust its ~7.5 GB.
2. Check a course exists: `curl -s "http://localhost:9080/api/v1/courses?size=5"`. If the catalog is empty, run `scripts/seed-demo.sh`.
3. Ask or infer whether the user wants to watch. If yes, open with `playwright-cli open <url> --headed --browser=msedge` and leave the browser open at the end unless told to close it.
4. Use a throwaway account (for example `playwright.student@test.com`, a made-up password). Never read `.env` for admin credentials and never write real passwords into reports.

## How to drive the page cheaply and reliably

- Target elements with locators, not refs: `click "getByRole('button', { name: 'Sign in' })"`, `fill "getByLabel('Email')" "..."`. Refs such as `e17` change on every navigation (the prefix becomes `f2e...`), so refs from an earlier snapshot break.
- Snapshots cost tokens. Prefer `playwright-cli --raw snapshot | grep -E "heading|button|alert"`, `find "text"`, or `--raw eval "() => ..."`.
- `eval` needs the function form: `eval "() => document.title"`. A bare statement such as `window.x = 1` runs but silently does nothing useful.
- `playwright-cli --raw requests` lists API calls with status codes. Use it to assert on behaviour (for example "one `/reviews/summaries` request, not one per card") rather than guessing from the screen.
- Wait after navigation or login (`sleep 3`) before submitting forms; a submit fired while the page is still loading is silently lost.

## Pitfalls we hit (check these before calling something a bug)

- **Stale bundle.** After rebuilding the frontend container the browser may keep the old JS. Verify with `eval "() => [...document.scripts].map(s => s.src).join(' ')"` and compare to `curl -s localhost:9100/ | grep -o 'assets/[^"]*\.js'`. `playwright-cli reload` fixes it. nginx now sends `Cache-Control: no-cache` for `index.html`, so this should only happen on older builds.
- **Cold start.** In the first minutes after the stack comes up, the first calls can return 503 or 504 (registry not warm). Retry once; if it then passes, report it as a transient warm-up issue, not a failure. Confirm by calling the API directly with curl.
- **Native dialogs are auto-accepted** by playwright-cli, so `dialog-dismiss` errors with "no modal state". To test a cancelled confirm, replace it in the page: `eval "() => { window.__asked = 0; window.confirm = (m) => { window.__asked++; window.__msg = m; return false; }; }"`, click, then read `window.__asked` and `window.__msg` and confirm no DELETE went out.
- A 401 on login logs one console error ("Failed to load resource"). That is the browser reporting the expected response, not an app error.
- URLs containing `&` need escaping on Windows (`^&` in cmd, `--%` in PowerShell).

## Scenarios and expected results

Run the ones the user asked for; the full set is A to E.

**A. Login form** (`/login`)
- Empty submit -> "Enter your email and password." and no request to `/auth/login`.
- Wrong email or password -> "Wrong email or password." (same message for an unknown email), stay on `/login`, `POST /auth/login` is 401.
- Valid credentials -> lands on `/`, header shows My courses, Account and Sign out.

**B. Registration** (`/register`): create account -> `POST /auth/register` 201, then automatic login 200, then `GET /auth/me` 200, ends signed in on `/`.

**C. Session storage** (constitution XII): `sessionstorage-list` has `learnhub.refreshToken`; `localstorage-list` empty; `cookie-list` empty.

**D. Catalog ratings:** every card shows a rating with its count, or "No reviews yet". The page makes exactly one `GET /reviews/summaries?courseIds=...` request for all cards; the catalog still renders if that call fails (can be checked with `route "**/reviews/summaries**" --status=500`).

**E. Review flow on a free course** (`/courses/<id>`)
1. Signed in but not enrolled: submit a review -> 403, the page shows "You must be enrolled in this course to review it." with a Reference code. (A 503 on the very first try is the cold start above.)
2. Click "Enroll for free" -> "You're enrolled!", and the old refusal message disappears.
3. Submit rating 4 with a comment containing `<b>bold</b>` -> 201, summary shows 4.0, the comment is displayed as literal text (not bold), the form switches to "Your review".
4. Edit to rating 2 -> summary 2.0, the catalog card now shows 2.0.
5. Delete with confirmation: confirm cancelled -> review stays and no DELETE is sent; confirm accepted -> 204 and "No reviews yet" returns.
6. Course detail fetch failing with 5xx or 504 (use `route`) -> a retryable error with a Try again button, not "This course is no longer available" (that wording is only for a real 404).

## Report

Finish with a table of step, expected, actual and PASS/FAIL, then a short list of anything surprising (with the request status codes that explain it). Mention test data left behind (the test account, soft-deleted reviews) and whether the browser was left open.
