# Plan: A/B test whether graphify + Obsidian save tokens (spec 008, course ratings UI)

## Context
The review-service work was not a fair test: it was one run with no baseline, graph build cost was
never counted, and an unscoped update wasted 150k+ tokens. This plan builds one backend+frontend
feature twice under identical conditions (control vs graphify-first) and compares measured tokens.
Spec 007 shipped backend only, so the natural next feature is the missing UI.

## The feature (spec 008): course ratings and reviews in the student web app
Chosen because it crosses areas where a graph should help: frontend (api client, generated types,
pages, tests), review-service, and the gateway contract.

Backend (`backend/review-service`):
- New public endpoint `GET /api/v1/reviews/summaries?courseIds=a,b,c` returning rating summaries for
  many courses in one call (avoids N+1 on the catalog). Add repository GROUP BY query
  (`ReviewRepository`), controller method (`ReviewController`), unit/slice test, and extend
  `specs/007-review-service/contracts/review-service.openapi.yaml`. Gateway route `reviews-read`
  (`Path=/api/v1/reviews/**`, GET, public) already covers it - no gateway change.

Frontend (`frontend/`, no new dependencies):
- `scripts/gen-api.mjs`: add `["review", "specs/007-review-service/contracts/review-service.openapi.yaml"]`
  (no glob; must be added by hand), run `npm run gen:api`, update the "seven contracts" comments.
- `src/api/endpoints.ts`: new `review` section (`listReviews`, `getReviewSummary`, `getReviewSummaries`,
  `createReview`, `updateReview`, `deleteReview`) following the existing one-liner `request()` style.
- New `src/components/StarRating.tsx` (display) and a review form/list component.
- `src/components/CourseCard.tsx`: show average rating + count; `CatalogPage.tsx`: one batch fetch.
- `src/pages/CourseDetailPage.tsx`: summary, review list, and a form for signed-in users; a 403 from
  the backend (not enrolled) shown through `ErrorNotice` with the correlationId (constitution XII).
- Tests (Vitest + Testing Library): new tests for the review endpoints/component/page. Existing
  `CourseDetailPage.test.tsx` uses one response for every URL and must move to a URL-dispatching
  fetch mock. Server text rendered as text only.

## Experiment design
1. Shared prep (done once, cost excluded from both arms): run speckit specify -> plan -> tasks for the
   new spec, commit to a base branch. Both arms run only the implement phase from the same `tasks.md`.
2. Two arms, each a subagent in its own git worktree (`isolation: worktree`), same model
   (claude-sonnet-5-5), same prompt, fresh context, same task list:
   - Arm A (control): discover with Grep/Glob/Read only. `graphify-out/` is gitignored, so the
     worktree has no graph; explicitly forbid reading the main checkout's `graphify-out/`.
   - Arm B (graphify + Obsidian): copy `graphify-out/` into its worktree; must start discovery with
     `graphify query/explain/path` and read relevant Obsidian notes from `graphify-out/obsidian/`,
     then Read only the files the graph points to. Before launch: confirm the graph is current
     (`graphify hook status`, last commit rebuilt it) and re-run `graphify export obsidian`.
3. Metrics, all taken from the subagent completion notifications and git:
   - Primary: `subagent_tokens`, plus `tool_uses` and duration per arm.
   - Each arm logs a discovery log: number of Read/Grep/graphify calls before its first edit.
   - Quality gate (must be equal for the comparison to count): `npm run typecheck`, `npm test`,
     `npm run build`, `mvn -pl review-service test` (unit tests only), diff size, and a checklist of
     the spec's acceptance criteria scored by hand.
   - Overhead accounting, reported separately: graph build ~100k tokens, the wasted 152k chunk,
     hook/update runs. Break-even = overhead / per-feature saving.
4. Decision rule: graphify counts as "saving" only if Arm B uses at least 20% fewer tokens than Arm A
   with equal quality AND the saving covers overhead within a stated number of features. n=1 per arm is
   anecdotal; if the gap is under about 20%, run one repeat of each arm before concluding.
5. Obsidian: for an agent it is just markdown files, so no token saving is expected from it. Its value
   is for a human reader. Optional qualitative check: open the vault and try to answer three questions
   (for example "what does CourseDetailPage call?") and judge whether it helps.
6. Cleanup: arms stay on throwaway branches (`008-control`, `008-graphify`). Compare, then merge only
   the arm the owner picks (or neither) after an explicit go-ahead.

## Known limits to state in the result
- WSL/Docker is broken, so Testcontainers integration tests and the live stack cannot be exercised;
  verification is typecheck, Vitest, build and backend unit tests. Re-run ITs once WSL is repaired.
- The frontend is small, which can favor the control arm; graphify is strongest on cross-service
  questions, so a result here does not generalize to every task.
- Token counts include each agent's own reasoning, which varies run to run.

## Verification (end to end)
- Both worktrees: `cd frontend && npm run gen:api && npm run typecheck && npm test && npm run build`;
  `cd backend && mvn -pl review-service test` (via WSL once it works; otherwise Windows Maven).
- Compare the two diffs for equivalence against the acceptance checklist, then tabulate tokens,
  tool calls and duration, and write the verdict plus break-even calculation.
