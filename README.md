# LearnHub

An online learning platform built as a monorepo: a Spring Cloud microservices backend now, and a
React frontend later.

This repository currently contains the **backend platform foundation**: the registry, the config
server, the API gateway, a placeholder echo service, and the local environment they run in. There is
no business domain yet. The point of this stage is that one command starts everything, and every
service added later plugs in without extra setup.

- `backend/` — every service and shared library, one folder per service, a self-contained Maven build
- `frontend/` — reserved for the React app; empty for now
- `specs/` — the specification, plan and tasks for each feature
- `.specify/` — Spec Kit, including the project constitution

## Prerequisites

- A container runtime with **Docker Compose v2**. No local JDK or Maven is needed to run the
  platform: the images are built inside containers.
- A **bash** shell for the scripts. On Windows use Git Bash or WSL. If Docker runs inside WSL rather
  than Docker Desktop, run these commands from inside WSL.
- The host ports in the table below must be free.

## Start, stop, restart, reset

```bash
cp .env.example .env     # then replace every change-me value
scripts/up.sh            # start everything
```

`scripts/up.sh` checks three things before starting any container, so a problem is reported clearly
rather than as a half-started stack: the container runtime is running, every required setting is
present and non-empty, and every host port is free. Each failure names exactly what is wrong.

| Action | Command | Effect |
|--------|---------|--------|
| Start | `scripts/up.sh` | Builds images if needed and waits until everything is healthy |
| Stop | `docker compose down` | Stops and removes containers. **Stored data is kept.** |
| Restart | `scripts/up.sh` | Starts again with the data still in place |
| Reset | `docker compose down -v` | Removes containers **and wipes all stored data** |

Running `scripts/up.sh` a second time while the platform is already up is harmless: it skips ports
its own containers already hold and leaves everything running.

**Healthy is not immediately routable.** Services register with the registry shortly *after* they
report healthy, and the gateway refreshes its view of the registry every five seconds. So for up to
about 30 seconds after startup finishes, a request through the gateway can return 503 because it has
no instance to route to yet. That is the registry working as intended; wait a moment and retry.

After a start:

| What | Where |
|------|-------|
| **Gateway (the only entry point for clients)** | <http://localhost:9080/api/v1/echo/ping> |
| Registry dashboard | <http://localhost:9761> |
| Config server | <http://localhost:9888> |
| Echo service | <http://localhost:9099/api/v1/echo/ping> |
| RabbitMQ console | <http://localhost:9673> |
| Mailpit (caught email) | <http://localhost:9825> |

Check it is all working:

```bash
scripts/smoke-test.sh              # health, databases, broker, mail, gateway, registry
scripts/smoke-test.sh --scale      # also: two echo copies, load spread, a crashed copy
scripts/smoke-test.sh --lifecycle  # also: data survives a restart, and down -v wipes it
```

## Ports

Every host port is in the 9xxx range. Third-party images keep their own internal ports and are
mapped into that range; the Spring services use the same port inside and outside their container.

| Component | Host port | Container port |
|-----------|-----------|----------------|
| api-gateway | 9080 | 9080 |
| discovery-server (Eureka) | 9761 | 9761 |
| config-server | 9888 | 9888 |
| auth-service | 9001 | 9001 |
| course-service | 9002 | 9002 |
| enrollment-service | 9003 | 9003 |
| payment-service | 9004 | 9004 |
| certificate-service | 9005 | 9005 |
| review-service | 9006 | 9006 |
| notification-service | 9007 | 9007 |
| analytics-service | 9008 | 9008 |
| echo-service | 9099 | 9099 |
| PostgreSQL | 9432 | 5432 |
| RabbitMQ (AMQP) | 9672 | 5672 |
| RabbitMQ (management) | 9673 | 15672 |
| Mailpit (SMTP) | 9025 | 1025 |
| Mailpit (web UI) | 9825 | 8025 |
| **frontend (nginx)** | **9100** | **80** |

## Changing a central setting

All settings live in `backend/config-repo/`, which the config server serves straight from the working
tree. **Edit a file there and a running service picks the change up within about ten seconds** — no
commit, no rebuild, no restart, and no manual trigger. Each service polls the config server every
five seconds and refreshes itself when the settings actually change.

| File | Applies to |
|------|-----------|
| `application.yml` | Shared defaults for every service |
| `<service-name>.yml` | One service, for example `echo-service.yml` |
| `application-<profile>.yml` | One environment, for example `application-local.yml` |

Precedence runs lowest to highest: `application.yml`, then `<service-name>.yml`, then
`application-<profile>.yml`. So a profile file can override a service's own value.

Try it: change `echo.greeting` in `backend/config-repo/echo-service.yml`, then call
<http://localhost:9099/api/v1/echo/ping> again and watch the greeting change.

Under the `local` profile only, `POST /actuator/refresh` on a service forces the update immediately.

Secrets never go in `backend/config-repo`. They come from environment variables listed in
`.env.example`, and `.env` itself is git-ignored.

## Adding a service

1. **Create the module** under `backend/<name>-service/`. Copy `backend/echo-service/` as the
   template: an application class, one `application.yml`, and nothing else in the way of wiring.
2. **Register it** in `backend/pom.xml` under `<modules>`. Declare no versions in the new POM; the
   parent POM is the only place versions live.
3. **Add its settings** at `backend/config-repo/<name>-service.yml`.
4. **Add a gateway route** in `backend/config-repo/api-gateway.yml`, pointing at
   `lb://<name>-service`. Mark a route `metadata: {public: true}` only if it needs no login;
   everything else is protected by default.
5. **Add a Compose entry** in `docker-compose.yml`, using its reserved port from the table above and
   the same build, health check and `depends_on` shape as `echo-service`.

That is all. Registration with the registry, central configuration, health probes, structured logging
and correlation IDs all arrive from `platform-common` and the shared config; you write no code for
them. `NoWiringCodeTest` enforces that no service adds its own.

A service with its own database (like `auth-service`, the first one) additionally needs
`SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD` environment variables in its Compose entry, pointing at
its own database (already created, empty, by `db-init`), and a Flyway migration under
`src/main/resources/db/migration/` for its schema.

## Accounts and login

`auth-service` (port 9001, behind the gateway at `/api/v1/auth/**`) owns accounts and sessions.
`register`, `login` and `refresh` are public; every other path — including changing another account's
role, restricted to administrators — requires a valid access token, which the gateway verifies and
replaces with `X-User-Id`/`X-User-Role` headers before forwarding the request. See
`specs/002-user-auth/contracts/auth-service.openapi.yaml` for the full endpoint contract and
`specs/002-user-auth/quickstart.md` for a curl walkthrough of the whole flow.

The first administrator is created automatically at startup from `ADMIN_EMAIL`/`ADMIN_PASSWORD`; the
HS256 token-signing secret is `JWT_SIGNING_SECRET` (at least 32 bytes). All three are in `.env.example`.

## Course catalog

`course-service` (port 9002, behind the gateway at `/api/v1/courses/**`, `/api/v1/my/courses` and
`/api/v1/categories/**`) owns courses, lessons and categories. Listing and viewing a published course
are public; everything else — creating and managing a course and its lessons, publishing, archiving,
and category administration — requires a valid token, with course ownership enforced by the service
itself (the gateway only tells it "this caller is authenticated," plus an administrator-only rule for
category mutations). Every mutating request needs an `If-Match` header carrying the resource's current
version, so two concurrent edits can never silently overwrite each other — the service responds `409`
to a stale one instead. A separate, gateway-unreachable endpoint
(`GET /api/v1/internal/courses/{id}`) lets other backend services look up a course directly. See
`specs/003-course-catalog/contracts/course-service.openapi.yaml` for the full endpoint contract and
`specs/003-course-catalog/quickstart.md` for a curl walkthrough of the whole flow.

## Checkout and enrollment

`payment-service` (port 9004, behind the gateway at `POST /api/v1/checkouts` and `/api/v1/payments/**`)
checks out a published course: free courses succeed immediately, paid ones go through an in-process
simulated payment provider driven by a caller-supplied test token (`sim-success`, `sim-decline`,
`sim-unavailable`). Every checkout carries a client-supplied idempotency key, so resending the same
request — including many times at once — never charges twice, and reusing the key with a different
request is refused. A successful payment is recorded durably (a transactional outbox) before it is ever
announced, so it is never lost even if the broker is briefly unreachable.

`enrollment-service` (port 9003, behind the gateway at `GET /api/v1/enrollments`) consumes the resulting
`payment.succeeded` event and creates the enrollment, idempotently and within a few seconds; a message
that cannot be processed is retried three times and then set aside in a dead-letter queue rather than
blocking the messages behind it. `course-service` gains one new, additive endpoint,
`GET /api/v1/courses/{id}/content`, which serves full lesson content to the owning instructor, an
administrator, or a confirmed-enrolled student — and refuses with a temporary error, never the content
itself, if enrollment status cannot be confirmed. See
`specs/004-checkout-enrollment/contracts/` for the full endpoint contracts and
`specs/004-checkout-enrollment/quickstart.md` for a curl walkthrough of the whole flow.

## Progress, certificates, and notifications

`enrollment-service` (still port 9003, now also behind the gateway at
`PUT`/`DELETE /api/v1/enrollments/{courseId}/lessons/{lessonId}/complete` and
`GET /api/v1/enrollments/{courseId}/progress`) lets an enrolled student mark or unmark a lesson complete
and see completed/total lessons and a percentage; a non-enrolled caller is refused, and marking the same
lesson twice changes nothing. Completing a course's last lesson flips the enrollment to completed exactly
once, permanently — even under ten concurrent completions of its last few lessons, and even if lessons are
later unmarked and remarked.

`certificate-service` (port 9005, behind the gateway at `GET /api/v1/certificates/mine` and the public
`GET /api/v1/certificates/verify/{code}`) consumes the resulting `course.completed` event and issues
exactly one certificate per student per course, normally within a few seconds, carrying a 16-character
verification code, the course title, and the completion date. A student can list their own certificates;
anyone can check a certificate by its code and see whether it is valid, the course title, and the date —
nothing about who holds it. The verification endpoint has its own, stricter rate limit, since it is
public and its code is guessable at scale.

`notification-service` (port 9007) consumes `enrollment.created` and `certificate.issued` and sends a
plain-text email for each, captured locally by Mailpit (`http://localhost:9825`) and never delivered
anywhere else. A redelivered event never sends a second email; a notification that keeps failing, or a
message that cannot even be parsed, is retried three times and then set aside in a dead-letter queue
rather than blocking the messages behind it. See
`specs/005-progress-certificates-notifications/contracts/` for the full endpoint contracts and
`specs/005-progress-certificates-notifications/quickstart.md` for a curl walkthrough of the whole flow.

## Course reviews

`review-service` (port 9006, behind the gateway at `POST /api/v1/reviews/courses/{courseId}`,
`PUT`/`DELETE /api/v1/reviews/{reviewId}`, and the public `GET /api/v1/reviews/courses/{courseId}` and
`GET /api/v1/reviews/courses/{courseId}/summary`) lets a student rate (1-5) and optionally comment on a
course, but only if they are enrolled in it — checked with a live call to enrollment-service, not an
event. A second submission for the same course updates the student's existing review rather than
creating a duplicate; a student can edit or delete their own review, and anyone, signed in or not, can
browse a course's reviews and see its average rating and review count. The aggregate rating is computed
directly from review-service's own data on every read, so it can never drift from the underlying
reviews — unlike the other business services, review-service publishes no events and needs no RabbitMQ
topology, since nothing else in the platform currently consumes a review change. See
`specs/007-review-service/contracts/` for the full endpoint contracts and
`specs/007-review-service/quickstart.md` for a curl walkthrough of the whole flow.

## Student web app

`frontend/` is a React/TypeScript SPA built with Vite and served by nginx. It talks to the gateway
exclusively through same-origin `/api/` requests — no CORS configuration is needed.

| | |
|---|---|
| Dev server | `http://localhost:9100` (port matches production) |
| Production | Container image served by nginx, proxying `/api/` to the gateway |
| Seed demo data | `scripts/seed-demo.sh` |

See [`frontend/README.md`](frontend/README.md) for the dev-server setup, API type generation, demo
data seeding, and the manual end-to-end walkthrough checklist.

## Instructor dashboard

`analytics-service` (port 9008) gives instructors a view of how their courses are doing, and
administrators a platform-wide view. It is a read model: it consumes `payment.succeeded`,
`enrollment.created`, `course.completed` and `certificate.issued` into per-course, per-UTC-day counters in
its own `analytics` database, stores no student identity, and never calls another service while handling
an event.

- **Pages** (student web app, signed in as an instructor or administrator): `/instructor` shows totals and
  a sortable table of your courses (drafts and archived included, zeros when idle); `/instructor/courses/:id`
  shows the funnel (enrolled, completed, certificate issued) and enrollments per day for 7, 30 or 90 days
  with a table alternative to the chart. Both show a "last updated" time and a Refresh button; figures
  follow a payment, enrollment, completion or certificate within about 10 seconds.
- **API** (through the gateway, `INSTRUCTOR` and `ADMINISTRATOR` only): `GET /api/v1/instructor/stats/summary`,
  `/stats/courses`, `/stats/courses/{courseId}?days=7|30|90`. Contract:
  `specs/009-instructor-dashboard/contracts/analytics-service.openapi.yaml`.
- **Access**: an instructor only ever sees their own courses, and another instructor's course looks exactly
  like one that does not exist (404). Students and visitors get 403 and 401. If course-service cannot confirm
  who owns what, the answer is a 503 with a reference code and no numbers.
- **Course list**: course-service gained one internal endpoint, `GET /api/v1/internal/courses?instructorId=`,
  reachable only service to service (no gateway route).

## Building and testing

```bash
mvn -f backend/pom.xml verify     # unit tests, plus Testcontainers tests that need Docker
```

Tests come first here: the project constitution makes test-first development non-negotiable, and
integration tests use Testcontainers rather than an in-memory database.

## Browser testing with playwright-cli

`playwright-cli` can drive the running web app in a real browser (login, registration, ratings, the
whole review flow). It is a manual, developer-side check, not part of `mvn verify` or `npm test`, and
the flow is packaged as the `learnhub-ui-test` Claude Code skill (run it with `/learnhub-ui-test` or just
ask Claude to test the UI in the browser). See
[`docs/playwright-ui-testing.md`](docs/playwright-ui-testing.md) for setup, what it found, and the
traps to avoid.

## Exploring the codebase with graphify

[graphify](https://github.com/Graphify-Labs/graphify) is an optional, local-only dev tool — not part of
LearnHub itself — that turns a folder of code into a queryable knowledge graph. It was used while
building spec 007 (review-service) to look up existing patterns (Feign client shape, event envelope,
gateway route conventions) before writing new code against them.

```bash
graphify .                                  # first build: AST-only for code, no LLM needed
graphify query "How does course-service verify enrollment?"
graphify explain "EnrollmentLookupClient"
graphify path "Course" "Certificate"
graphify . --update                         # refresh after changing files
```

Its output lives in `graphify-out/` (gitignored — it's a local cache, never committed) and needs no API
key for a code-only run: structural extraction is free, local tree-sitter parsing.

### Viewing the graph in Obsidian

[Obsidian](https://obsidian.md) can browse the graph as a vault of linked notes. Export it from the
existing `graph.json` — no re-scan, no LLM tokens:

```bash
graphify export obsidian --graph graphify-out/graph.json
```

This writes one note per node plus a `graph.canvas` into `graphify-out/obsidian/` (about 2,800 notes for
this repo). In Obsidian choose **Open folder as vault** and select that folder, then use the Graph view
or open `graph.canvas`. The vault is part of `graphify-out/`, so it is gitignored; re-run the export
after the graph changes. Prefer this over `graphify . --obsidian`, which re-scans the whole repo and can
trigger the expensive doc extraction described below. The graph covers `backend/` and `frontend/` only,
not the markdown under `specs/`.

### Keeping the graph fresh with git hooks

```bash
graphify hook install     # also: hook status, hook uninstall
```

This installs `post-commit`, `post-checkout`, `post-merge` and `post-rewrite` hooks in `.git/hooks/`
and registers a merge driver for `graphify-out/graph.json` (adding a line to `.gitattributes`). After
each commit the hook finds the changed code files (`git diff HEAD~1`), re-parses them, and rebuilds
`graph.json` and `GRAPH_REPORT.md` — AST only, so no LLM tokens. Doc and image changes are ignored by
the hook; index those with a deliberate, scoped `graphify --update`. Hooks live in `.git/`, so they are
not committed: each fresh clone has to run `graphify hook install` again. The trade-off is a few extra
seconds on every commit and checkout, and a graph that changes on disk without you asking.

What's genuinely useful about it:

- `graphify explain`/`path` on a symbol gives a quick, free (no LLM) map of what calls what, which is
  handy for a service this size before touching unfamiliar code.
- Incremental `--update` on code-only changes costs zero LLM tokens — it's just AST re-parsing.

What to watch out for, from using it on this repo:

- `graphify query` answers are BFS/DFS node lists with **no edges** by default, often truncated to a
  tiny fraction of the matched nodes unless you raise `--budget` a lot — useful as a pointer to where to
  look, not a substitute for reading the actual file.
- Indexing **docs** (not code) uses a real LLM call per batch of files and can be expensive if the scope
  isn't deliberately scoped — an unscoped `--update` on this repo once dispatched 5 subagents to
  semantically index ~94 markdown files, burning over 150k tokens before being stopped. Keep `--update`
  scoped to the code you actually changed (e.g. `graphify backend/ --update`) rather than the whole repo
  unless you mean to (re)index docs too.
- Net token cost for a single, moderate-size feature (like review-service) was **higher**, not lower,
  than just reading the handful of relevant files directly — the tool pays off more on a long-lived
  project queried many times, less on a one-off task.

## Security audits

Two source-only audits were run with the Cloudflare
[security-audit skill](https://github.com/cloudflare/security-audit-skill), kept in
`.claude/skills/security-audit/` and started with "do a security audit on …". Results live in
[docs/security-audit/](docs/security-audit/): each run folder has `REPORT.md` (read this first),
`NEEDS-VALIDATION.md`, `FINDINGS-DETAIL.md`, `findings.json` and `coverage-ledger.json`.

| Run | Scope | Confirmed | Needs validation | Rejected |
|---|---|---|---|---|
| run-1 | api-gateway + auth-service | 0 | 9 | 1 |
| run-2 | frontend | 0 | 0 | 1 |

- **Source-only.** This Windows host has no sandbox, so no target code was run. "Needs validation"
  means a lead that source reading alone cannot settle, not a confirmed vulnerability. Nothing is
  rated by severity until a lead is reproduced.
- **Top leads from run-1** to validate on a throwaway stack: the admin-only role-change route
  possibly bypassable with a non-normalised path (`//`, `/./`), service ports published on the host
  (a caller could skip the gateway and forge `X-User-Id`), a non-atomic refresh-token rotation, and
  a login throttle keyed on the gateway's address.
- **run-2** found no frontend vulnerability. Its one lead (`returnTo` open redirect) was rejected
  because the installed react-router rejects external navigation targets. Hardening notes cover
  encoding path ids, a tighter CSP, and pinning image tags.
- Both runs are **partial coverage**: budget-capped and not re-checked by a final clean pass, so
  they make no claim that nothing was missed.

## Harness engineering

The repo carries a small "harness" so an AI agent can pick up work across sessions without losing
context. It follows the [Learn Harness Engineering templates](https://walkinglabs.github.io/learn-harness-engineering/en/resources/templates/),
adapted to LearnHub.

| File | Purpose |
|---|---|
| `CLAUDE.md` | Rules the agent reads first: session start loop, one feature at a time, evidence before "done", never merge without approval, no co-author trailer, Docker via WSL |
| `init.sh` | Session start check: frontend typecheck + tests (`VERIFY_BACKEND=1 ./init.sh` adds backend tests through WSL) |
| `feature_list.json` | Machine-readable feature tracker (`not_started`, `in_progress`, `blocked`, `passing`) with verification steps and evidence |
| `claude-progress.md` | Per-session log: what ran, what was verified, next best step |
| `session-handoff.md` | Short note for the next session |
| `docs/harness/` | Clean-state checklist, evaluator rubric (0-2 scores, Accept/Revise/Block) and a quality document grading each domain and layer |

Each session reads the progress log and feature list, runs `./init.sh`, works on exactly one feature,
and records evidence before it stops. Completed specs 001-008 are listed as `passing` from their
merge history; `sec-001` to `sec-005` track the security leads and hardening notes from the audits.
This works alongside spec-kit: spec-kit defines *what* to build, the harness keeps *sessions* reliable.

## Governance

`.specify/memory/constitution.md` holds the rules this codebase is held to: service boundaries, the
gateway as the only entry point, test-first development, how errors and logging work, and the port
table. Changing one of those rules means amending the constitution, not working around it.
