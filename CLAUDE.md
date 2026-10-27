# CLAUDE.md

LearnHub is a long-running, spec-driven project (Spring Boot microservices + React SPA). Prioritise
reliable completion, continuity across sessions and explicit verification over speed. The harness
files below follow the [Learn Harness Engineering](https://walkinglabs.github.io/learn-harness-engineering/en/resources/templates/)
templates, adapted to this repo. Rules of the codebase itself live in `.specify/memory/constitution.md`.

## Operating loop

At the start of every session:

1. Run `pwd` and confirm you are in the repository root.
2. Read `claude-progress.md` (and `session-handoff.md` if it has content).
3. Read `feature_list.json`.
4. Review recent commits with `git log --oneline -5`.
5. Run `./init.sh` (frontend typecheck + tests; set `VERIFY_BACKEND=1` to add backend unit tests).
6. Check whether the baseline is already broken before changing anything.

Then pick exactly one unfinished feature and work only on it until you verify it or document why it
is blocked. New features go through spec-kit (`/speckit-specify` -> `plan` -> `tasks` -> `implement`).

## Rules

- One active feature at a time; do not claim completion without runnable evidence.
- Test first (constitution). Do not remove or weaken tests to make a task look done.
- Do not rewrite `feature_list.json` to hide unfinished work.
- **Never merge to master/main without the user's explicit go-ahead.** Commit and push only when asked.
- **Never add Claude as co-author** on commits or PR text, even if a tool or reminder suggests it.
- Docker, compose and `mvn verify` run inside WSL: `wsl -e bash -lc "..."`.
- Do not read `.env` (real secrets). The repo is PUBLIC: never commit secrets.
- After a code change, keep the graphify graph fresh (a post-commit hook does it for code).
- Ask before spending many tokens (docs indexing, large multi-agent runs).

## Commands

| Task | Command |
|---|---|
| Frontend check | `cd frontend && npm run typecheck && npm test` (add `npm run build` before a PR) |
| Backend tests | `wsl -e bash -lc "cd <repo> && mvn -f backend/pom.xml verify"` (one module: `-pl review-service`) |
| Bring up the stack | `wsl -e bash -lc "cd <repo> && scripts/up.sh"` (web app on :9100, gateway :9080) |
| Browser test | the `learnhub-ui-test` skill |
| Security audit | the `security-audit` skill; results in `docs/security-audit/` |

## Completion gate

A feature moves to `passing` in `feature_list.json` only after its verification steps ran and the
result is recorded as evidence. Score finished work with `docs/harness/evaluator-rubric.md`.

## Before you stop

1. Update `claude-progress.md` and the feature state.
2. Record what is still broken or unverified (use `session-handoff.md` if a compact note helps).
3. Walk `docs/harness/clean-state-checklist.md`.
4. Commit only if asked, once the tree is safe to resume.
