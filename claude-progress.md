# Progress Log

Repository-local session log, read at session start and updated before handoff (see `CLAUDE.md`).
The filename is a course convention; any coding agent can use it. Nothing updates it automatically.

## Current Verified State

- Repository root: this directory (`sdd-microservices`)
- Standard startup path: `wsl -e bash -lc "cd <repo> && scripts/up.sh"`, web app at http://localhost:9100
- Standard verification path: `./init.sh` (frontend typecheck + tests); `VERIFY_BACKEND=1 ./init.sh` adds backend tests in WSL
- Current highest-priority unfinished feature: `sec-001` (validate the admin role-change route path-normalisation lead from run-1)
- Current blocker: none; needs a throwaway local stack and the user's go-ahead

## Session Log

### Session 001 (harness adoption)

- Date: 2026-10-27
- Goal: add the Learn Harness Engineering templates to the project
- Completed: `CLAUDE.md`, `init.sh`, `claude-progress.md`, `feature_list.json`, `session-handoff.md`, `docs/harness/` (clean-state checklist, evaluator rubric, quality document)
- Verification run: `./init.sh` (frontend typecheck + vitest)
- Evidence captured: typecheck clean; 22 test files, 89 tests passed (about 104 s). Backend tests not run.
- Commits: none yet
- Files or artifacts updated: see Completed
- Known risk or unresolved issue: `feature_list.json` marks specs 001-008 `passing` on the strength of their merge to master, not a fresh run; the first `./init.sh` run should confirm the baseline. Security leads from `docs/security-audit/run-1` are unvalidated.
- Next best step: wait for the user to describe spec 009 (`spec-009` in feature_list.json, blocked on that), then run /speckit-specify; otherwise `sec-001`

### Session 002

- Date:
- Goal:
- Completed:
- Verification run:
- Evidence captured:
- Commits:
- Files or artifacts updated:
- Known risk or unresolved issue:
- Next best step:

### Session 003 (spec 009: specify)

- Date: 2026-10-27
- Goal: write the spec for the instructor dashboard (spec 009), following the harness
- Completed: constitution amended 1.2.1 -> 1.3.0 (principle XIV read-model services, analytics-service port 9008, uncommitted); branch `009-instructor-dashboard`; `specs/009-instructor-dashboard/spec.md` and `checklists/requirements.md`; `feature_list.json` spec-009 set to in_progress
- Verification run: spec quality checklist (all items pass, no clarification markers). `./init.sh` not re-run this session (no code changed since the last pass)
- Evidence captured: checklist at specs/009-instructor-dashboard/checklists/requirements.md
- Commits: none yet (constitution, spec and harness updates are uncommitted on the branch)
- Files or artifacts updated: constitution, spec, checklist, `.specify/feature.json`, `feature_list.json`, this log
- Known risk or unresolved issue: two points flagged for clarify/plan (attribution of completions without a recorded enrollment; meaning of "last updated"); Principle II still says "seven business services"
- Next best step: `/speckit-plan`, then `/speckit-tasks`, `/speckit-analyze`, `/speckit-implement`
- Observation log (for the harness/graphify question): the harness files were read at session start; graphify was not used in this phase because the spec phase needed only a few targeted greps

### Session 003 addendum (spec 009: plan)

- Goal: plan phase for spec 009 from the user's detailed brief
- Completed: plan.md, research.md, data-model.md, contracts/ (analytics API, course-service internal list addendum), quickstart.md
- Verification run: constitution check (all principles pass; Principle II count and XI "plain CSS" noted); names verified in source
- Corrections to the brief, recorded in plan.md: internal path is /api/v1/internal/courses; role is ADMINISTRATOR; frontend list is /api/v1/my/courses; db and role are named `analytics` (not analytics_db/analytics_user, constitution table wording to fix); PaymentSucceededPayload is a private copy; styling is Tailwind; Dockerfile and parent POM list modules
- Observation log: one graphify query (~1.5k tokens) returned file pointers but was noisy; the notification-service topology and listener pattern needed direct file reads anyway. Harness: spec-009 entry and progress log used as the state record
- Next best step: /speckit-tasks, then /speckit-analyze, then /speckit-implement

### Session 003 addendum 2 (spec 009: tasks)

- Completed: constitution wording fixed (1.3.1: database and login `analytics`); `specs/009-instructor-dashboard/tasks.md` generated (69 tasks, all in checklist format)
- Verification run: format check by grep (every task has checkbox, ID, optional [P] and [US] label, file path)
- Next best step: /speckit-analyze (optional consistency check), then /speckit-implement on branch 009-instructor-dashboard; verification needs WSL Docker for backend ITs

### Session 004 (spec 009: implement)

- Date: 2026-10-27
- Goal: implement spec 009 (instructor dashboard) with the harness, and see whether the harness and graphify help
- Completed: tasks T001-T068 (T069 waits for the user's go-ahead). New `backend/analytics-service`, course-service internal list endpoint, gateway route, db/compose/up.sh wiring, frontend pages (overview, course detail, RequireRole, not-allowed), smoke-test section, docs (README, ARCHITECTURE, HOWTORUN)
- Verification run: analytics 52 unit + 13 IT classes; course-service, api-gateway and infra-tests suites; frontend typecheck, 155 tests, build; live stack smoke test (earlier 28 checks + 6 new); headed-browser walkthrough
- Evidence captured: see `feature_list.json` spec-009
- Commits: none (all changes uncommitted on branch 009-instructor-dashboard, including constitution 1.3.1)
- Files or artifacts updated: many; `.env` got one local line `ANALYTICS_DB_PASSWORD=change-me-analytics` (the preflight requires it; the value is the .env.example placeholder)
- Known risk or unresolved issue: unchanged modules' own suites were not re-run; T069 (constitution Principle II still says seven business services); the "no gateway route to /internal/**" guarantee is a gateway-config test, not a runtime probe; stale JSON-schema ids in problem-details (additionalProperties allowed) - fieldErrors is a new, undocumented extra property
- Next best step: ask the user to review, then commit/merge; then T069 via /speckit-constitution
- Harness observations (honest): (1) I did not run `./init.sh` at the start of this implementation session; the baseline was last proven earlier the same day, so a regression between would not have been caught by the harness. (2) The feature list and progress log did help me keep one feature in view across a very long session and a context reset. (3) Most real defects came from checking names in source, not from the harness: wrong internal path, role names, `/my/courses`, `sim-success` token, db naming, Dockerfile pom list, ProvisionScriptIT service list, Spring Cloud circuit breaker ids. (4) Nothing enforced the rules automatically; they held because I followed CLAUDE.md. A pre-commit hook or a CI check would enforce them.
- Graphify observations (honest): used once, in the plan phase (one query, about 1.5k tokens of output, noisy file pointers); I still read the actual files. Not used during implement. No evidence it saved tokens on this feature; spec 007 found the same. The real cost drivers here were long WSL Maven and Docker runs and repeated test reruns, which graphify cannot reduce.
