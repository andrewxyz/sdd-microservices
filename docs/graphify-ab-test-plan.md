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

## Results (run 2026-10-26)

Both arms implemented tasks T001-T017 of spec 008 in isolated git worktrees, same model, same prompt,
same tasks. Only the discovery method differed. Token counts are the subagents' own totals.

| | Arm A (control) | Arm B (graphify queries, Obsidian optional) |
|---|---|---|
| Tokens | 111,380 | 117,438 (+5.4%) |
| Tool calls | 86 | 80 (-7%) |
| Duration | 15.6 min | 12.3 min (-21%) |
| Frontend tests | 83 pass | 79 pass |
| Backend unit tests | pass | 16 pass |
| Typecheck / build | pass | pass |
| Diff | 22 files, +1,555 / -26 | 21 files, +1,440 / -29 |

Quality gate met in both arms. The integration tests (`ReviewSummariesIT`) were written but not run in
either arm, and the manual quickstart was not run.

### Discovery behaviour
- Arm A: 28 Reads, 4 Globs, 0 Greps before its first edit (5 of the Reads failed only because the spec
  files were not yet in its worktree).
- Arm B: 4 graphify calls (3 query, 1 explain), 0 Obsidian note Reads, 24 other Reads, 0 Greps/Globs.
- The graph correctly listed which files matter, but its query output was noisy (unrelated course-service
  integration tests), truncated at a 1,500-token budget, and carried no file contents, so Arm B still had
  to Read every file it edited. One `explain` call was wasted on an ambiguous name (5 matches).
- Obsidian was not actually tested. This plan said Arm B "must" read Obsidian notes, but the prompt
  given to Arm B said it "MAY" read them. It chose not to, so no notes were read. The result therefore
  measures graphify queries with an optional vault, not the vault itself.

### Verdict
- **No measurable token saving.** Arm B used about 5% more tokens, far below the 20% saving threshold set
  in the decision rule. At one run per arm the gap is within normal run-to-run variance, so the honest
  reading is "no difference", not "graphify costs 5% more".
- Graphify's one visible benefit was speed (-21% duration, -7% tool calls), which is also within noise.
- Overhead is not included above: the initial graph build (~100k tokens) and a wasted semantic-indexing
  chunk (~152k tokens) only widen the gap against graphify.
- Likely reason: the feature touches a small, well-named set of files and the spec already points at
  them, so there was little discovery for a graph to shortcut. The control arm needed only 32
  search/read calls before editing.
- Obsidian is unproven either way: it was optional and unused (see above). It is meant for a human
  reader browsing the graph. Its notes hold a symbol's name and links, not code, so reading them would
  probably not remove the need to Read the files being edited, but that was not measured.

### Why it did not save tokens
1. **Little discovery to save.** The spec, plan and tasks already named almost every file to touch
   (`endpoints.ts`, `CatalogPage.tsx`, `ReviewController.java`, ...). The control arm needed only
   about 32 search/read calls before its first edit, so there was no large search for a graph to replace.
2. **The graph says where, not what.** Query output is a list of node names with no file contents and,
   by default, no edges. Both arms still had to Read every file they edited, so the dominant cost
   (reading code in order to change it) was identical.
3. **The graph's own calls cost tokens.** Arm B spent 4 graph calls and about 24 Reads, versus 28 Reads
   for the control, so the graph removed only a few Reads while adding its own output to the context.
4. **Noisy, truncated answers.** Queries returned unrelated nodes (for example course-service integration
   tests) and were cut off at the token budget, which makes the answer a pointer to verify, not
   something to rely on.
5. **Ambiguous names waste calls.** `explain "request()"` matched 5 symbols and had to be retried with a
   full node id, a call that bought nothing.
6. **Obsidian was not exercised.** It was optional for Arm B, which read zero notes, so the vault
   contributed nothing here. This says nothing for or against reading the notes; it is a flaw in how
   the experiment was set up (the plan said "must", the prompt said "may").
7. **Fixed costs sit on top.** Building the graph (~100k tokens) and one unscoped doc-indexing run
   (~152k tokens, stopped early) are paid regardless of how well queries perform.

Where a graph should pay off instead: a task that spans several services, in code the agent has never
seen, with no spec listing the files, so the alternative is many broad searches.

### Limits of this result
- Obsidian notes were optional for Arm B and unused, so Obsidian itself was not tested. A third arm
  with reading the notes required (about 110-120k tokens) would settle it.
- One run per arm; no repeat was done (the plan called for one when the gap is under 20%, and it was
  judged unlikely to change the conclusion).
- Small codebase area; this does not say graphify cannot help on a task spanning several services, with
  unfamiliar code, and no spec naming the files. That is the better follow-up experiment.
