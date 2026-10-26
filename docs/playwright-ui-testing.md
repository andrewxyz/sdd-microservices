# Browser testing the student web app with playwright-cli

`playwright-cli` drives a real browser from the command line. It was used to click through the LearnHub
student web app against the running Docker stack, and the flow is captured as a Claude skill
(`.claude/skills/learnhub-ui-test/SKILL.md`) so it can be repeated. This page explains what it is, how to
run it, what it found, and the traps to avoid.

It is a manual and agent-driven check, not part of the automated test suite. The constitution names
Vitest and Testing Library as the only frontend test tools, so adding a permanent Playwright test suite
would need a constitution amendment first. The CLI is installed globally on a developer machine; it is
not a project dependency.

## Setup

```bash
playwright-cli --version              # 0.1.22 was used here
npm install -g @playwright/cli@latest # only if it is missing
```

Output goes to `.playwright-cli/` (snapshots, console logs). It can contain credentials, so it is in
`.gitignore`.

The stack must be running. From the repo root, through WSL (Docker lives there):

```bash
wsl -e bash -lc "cd '<repo path in /mnt/c/...>' && bash scripts/up.sh"
```

If WSL has been unstable, build the images one at a time (`docker compose build <service>`) before
`up.sh`; building all of them in parallel exhausted the VM's memory and crashed it. Then check
`http://localhost:9100/` returns 200 and the catalog has courses (`scripts/seed-demo.sh` if not).

## Running it from Claude Code (skills)

Two project skills live in `.claude/skills/`, and Claude Code picks up skills from that folder:

| Skill | What it is |
|---|---|
| `playwright-cli` | The command reference for the CLI (open, click, fill, snapshot, requests, tracing, video, mocking). Claude uses it whenever it drives a browser. |
| `learnhub-ui-test` | The LearnHub-specific test plan: scenarios A to E, expected results, the traps below, and the report format. |

Start Claude Code in the repo root, then either type the skill name or just describe the job:

```text
/learnhub-ui-test
/learnhub-ui-test run only the login scenarios, headed so I can watch
Smoke-test the reviews feature in the browser and leave the window open
Check in the browser that the catalog makes one ratings request
/playwright-cli open http://localhost:9100 and take a screenshot
```

The skill description is written to trigger on phrases like "test", "click through", "smoke-test" or
"verify the UI at localhost:9100", so naming it is optional.

What happens when you run it:
1. Claude checks the stack answers on `http://localhost:9100/` and that the catalog has courses. If the
   stack is down it uses the WSL commands above, and asks before doing anything heavy.
2. It opens the browser (headed if you said you want to watch, otherwise headless) and runs the scenarios
   you asked for, using role and label locators and the network log to check results.
3. It ends with a pass/fail table, anything surprising with the status codes behind it, and the test data
   it left behind. It leaves a headed browser open unless you ask it to close.

Practical notes:
- The `playwright-cli` skill pre-approves `Bash(playwright-cli:*)` and `npx playwright` commands. Other
  commands (curl, `wsl ...`, `docker compose`) follow your normal permission prompts, so expect a few.
- Skills are read from the folder when a session starts. If a newly added skill is not listed, start a
  new session (or `/clear`), then run `/learnhub-ui-test` again.
- To change what is tested, edit `.claude/skills/learnhub-ui-test/SKILL.md`: add a scenario under "Scenarios
  and expected results" and any new trap under "Pitfalls". Keep expected results observable (a status code,
  a message, a stored value) so the check is repeatable.
- Running it costs tokens, mostly from page snapshots. Ask for a single scenario or group, not "everything",
  when you only changed one screen.
- To try the skill on a feature that is not covered yet, ask Claude to extend it after the walkthrough
  ("add what we just tested to the skill").

## Running it by hand

If you are not using Claude Code, the CLI works on its own. For a visible window:

```bash
playwright-cli open http://localhost:9100/login --headed --browser=msedge
```

By hand, the loop is: act, read the result cheaply, check the network.

```bash
playwright-cli fill "getByLabel('Email')" "playwright.student@test.com"
playwright-cli fill "getByLabel('Password')" "<throwaway password>"
playwright-cli click "getByRole('button', { name: 'Sign in' })"
playwright-cli --raw snapshot | grep -E "heading|button|alert"   # cheap view of the page
playwright-cli --raw requests                                    # API calls with status codes
playwright-cli --raw sessionstorage-list                         # where the refresh token lives
```

## What the run covered and found (2026-10-26)

| Area | Result |
|---|---|
| Login: empty submit | Showed "Wrong email or password." with no hint. **Fixed**: now "Enter your email and password." and no request is sent |
| Login: wrong credentials | Generic message, 401, same text for an unknown email |
| Registration | 201, automatic login 200, profile 200, lands on the home page |
| Session storage | Refresh token only in `sessionStorage`; `localStorage` and cookies empty (matches constitution XII) |
| Catalog ratings | Each card shows a rating or "No reviews yet" from a single `/reviews/summaries` request |
| Review while not enrolled | 403 "You must be enrolled in this course to review it." with a reference code |
| Enroll, create, edit, delete a review | 201 / updated summary / 204; a comment containing `<b>` is shown as literal text |
| Course page on a 504 | Showed "no longer available". **Fixed**: only a 404 says that; other failures show the error with a Try again button |
| Stale error after enrolling | The old refusal stayed on screen. **Fixed**: it clears when the student enrolls |
| Delete a review | Had no confirmation. **Fixed**: asks "Delete your review? This cannot be undone." |
| Deploys | nginx sent no cache header for `index.html`, so a browser kept the old build. **Fixed**: `Cache-Control: no-cache` |

The fixes are on branch `fix/review-ui-polish` with unit tests (87 frontend tests pass).

Not fixed, noted: after logging back in, the course page still offers "Enroll for free" for a course the
account is already enrolled in.

## Traps to know about

- **Stale bundle in the test browser.** After rebuilding the frontend container the browser can keep the
  old JavaScript, which makes a fixed bug look unfixed. Check with
  `playwright-cli --raw eval "() => [...document.scripts].map(s => s.src).join(' ')"` against
  `curl -s http://localhost:9100/ | grep -o 'assets/[^"]*\.js'`, and `playwright-cli reload`.
- **Cold start.** Right after the stack comes up, the first requests can return 503 or 504 while services
  register with the registry. Retry once before calling it a bug; confirm with curl against the gateway.
- **Refs change.** Element refs like `e17` are renumbered on every navigation. Use role and label
  locators instead.
- **`eval` needs a function.** Write `"() => ..."`; a bare statement can run without doing what you meant.
- **Dialogs are auto-accepted.** `dialog-dismiss` fails with "no modal state". To test cancelling a
  confirm, replace `window.confirm` in the page with a function that returns false and check no DELETE
  request was sent.
- **Expected console error.** A failed login logs one "Failed to load resource" 401 message; the browser
  is reporting the response, not the app erroring.
- **Windows URLs with `&`** need `^&` in cmd or `--%` in PowerShell.
- **Token cost.** Snapshots add text to a Claude conversation. Use `--raw`, `find`, `grep`, and
  `--mobile` for smaller pages.

## Test data left behind

A local account `playwright.student@test.com` and soft-deleted reviews in the `review` database. Nothing
is committed. `docker compose down -v` wipes it along with the rest of the data.
