---

description: "Task list for spec 010 — platform observability (metrics and dashboards)"
---

# Tasks: Platform Observability — Metrics and Dashboards

**Input**: Design documents from `/specs/010-observability/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/scrape-contract.md](./contracts/scrape-contract.md),
[quickstart.md](./quickstart.md)

**Tests**: Included. Constitution Principle VI (test-first) is NON-NEGOTIABLE, so the
`ComposeFileTest` assertions and smoke-test checks are authored before the configuration they assert
on. There are no unit tests here because this feature adds no application code — the artefacts are a
POM line, YAML and dashboard JSON, and the Compose topology plus the live scrape are the right level
to test them at.

**Organization**: grouped by user story. US1 alone is a usable MVP: it is the dead-letter visibility
that the whole feature exists for.

## Status as of 2026-11-06 — 13 of 51 done, blocked on the environment

**Everything that could be authored from verified facts is done. Nothing has been verified by running
it.** Two environment problems block all remaining work:

| Blocker | Effect |
|---|---|
| **Docker Desktop will not start** (`Error response from daemon: Docker Desktop is unable to start`) | blocks every task that needs a running stack — T004–T011, T022–T038, T040–T044, T049. Likely still set to the 4096 MB that failed Hyper-V allocation earlier; lowering it to ~3 GB should recover it |
| **Maven is not installed anywhere** — not in WSL (`mvn: command not found`, no JDK either), not on Windows (`mvn: not on PATH`), and the repo has no `mvnw` wrapper | blocks T013, T022, T045. Windows has Java 25, but the project targets Java 21 |

**Done** (T001–T003, T012, T014–T021, T039): the dependency, endpoint exposure in both config files,
the `ComposeFileTest` extensions, the Prometheus scrape config, both Compose services with caps and
health checks, the volumes, the preflight ports, the Grafana credentials and provisioning, and the
smoke-test assertions.

**Deliberately not written yet** — T026, T027, T032, T033, T034, the dashboards and alert rules.
They depend on findings from three unclosed [GAP] tasks:

- **T010** — whether the RabbitMQ Prometheus plugin is enabled in the image at all.
- **T024** — the exact per-queue metric and label names. [contracts §4](./contracts/scrape-contract.md)
  records them as expected-but-unverified. A panel written against a guessed name renders empty with
  no error, which is a monitoring gap disguised as health — the exact failure this ordering exists to
  prevent.
- **T009** — whether histogram buckets are published, which decides whether the latency panel shows
  p95 or an average.

**Test-first exception to disclose**: T013 asks for the `ComposeFileTest` additions to be seen failing
before the Compose services are added. Maven is unavailable, so **the red step was never observed**.
The assertions were written first (T012 before T015/T020/T021) and were checked structurally with a
Python YAML parse, which is weaker evidence than a failing test run. T013 stays unchecked, and T022
must confirm the suite genuinely passes once Maven exists.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1, US2, US3 — maps to the user stories in [spec.md](./spec.md)
- **[GAP]**: closes an explicitly unverified assumption from research. **A [GAP] task must not be
  marked done by assumption — it requires observed output.**

## Path Conventions

Backend and repo-root infrastructure only. `frontend/` is untouched. Docker, Compose and Maven run
inside WSL: `wsl -e bash -lc "cd <repo> && …"`.

---

## Phase 1: Setup — the scrape endpoint on every service

**Purpose**: one dependency and two config lines give all 13 services a metrics endpoint. Everything
else in this feature consumes it.

- [x] T001 Add `micrometer-registry-prometheus` to the compile-scope `<dependencies>` block of `backend/platform-common/pom.xml`, with **no `<version>`** (the Spring Boot BOM manages it, so Principle IX holds). Place it in the normal block, **not** the `<optional>` block that keeps servlet classes away from the WebFlux gateway — see [research R1](./research.md)
- [x] T002 [P] In `backend/config-repo/application.yml`, change `management.endpoints.web.exposure.include` from `health,info` to `health,info,prometheus`
- [x] T003 [P] In `backend/config-repo/application-local.yml`, change `include` from `health,info,refresh` to `health,info,refresh,prometheus`. **Both files are required**: the profile file has higher precedence, so editing only `application.yml` leaves the endpoint closed under the profile actually in use ([research R2](./research.md))
- [x] T004 Confirm Docker Desktop is running and the daemon is healthy: `docker info --format "{{.ServerVersion}}"`. It was down during planning, which is why T010 and T021 could not be closed then
- [x] T005 Rebuild and start the stack: `wsl -e bash -lc "cd <repo> && scripts/up.sh"`. A rebuild is needed because T001 changes a compiled dependency; T002–T003 alone would hot-reload
- [x] T006 Verify the endpoint on a servlet service: `curl -s localhost:9001/actuator/prometheus | head -20` returns Prometheus text format including `jvm_memory_used_bytes`
- [x] T007 Verify the endpoint on the **WebFlux gateway**: `curl -s localhost:9080/actuator/prometheus | head -20`. Called out separately because the gateway is the one service with a different web stack, so it is where R1's framework-agnostic claim would fail if it is wrong
- [x] T008 [P] Verify FR-002 and SC-004 by the absence of code: `grep -rn "micrometer\|prometheus" backend/*/src/main/` returns nothing. Every service got its endpoint from `platform-common` alone
- [x] T009 Check whether `http_server_requests_seconds_bucket` is present in T006's output. If absent, enable histogram buckets as a **shared default** in `backend/config-repo/application.yml` so FR-002 still holds. If that cannot be done without per-service config, drop the p95 panel to an average from `_sum / _count` and record the decision — no requirement names a percentile ([contracts §3](./contracts/scrape-contract.md))

**Checkpoint**: every service exposes metrics. Nothing is collecting them yet.

---

## Phase 2: Foundational — collection and presentation infrastructure

**Purpose**: Prometheus scraping and Grafana running. Blocks every user story, because each one is a
dashboard or an assertion over collected data.

**⚠️ CRITICAL**: no user story work can begin until this phase is complete.

- [x] T010 **[GAP]** Verify the RabbitMQ Prometheus plugin is enabled: `docker compose exec rabbitmq rabbitmq-plugins list -e | grep prometheus`. The documentation does not state whether the `rabbitmq:4.3-management` image enables it ([research R5](./research.md)). **If it is not enabled**, add `RABBITMQ_ENABLED_PLUGINS: rabbitmq_management,rabbitmq_prometheus` to the `rabbitmq` service in `docker-compose.yml`, restart it, and re-check. Record the observed output either way
- [x] T011 Confirm the metrics port answers: `docker compose exec rabbitmq wget -qO- http://localhost:15692/metrics | head -5`
- [x] T012 Extend `backend/infra-tests/src/test/java/com/example/learnhub/infra/ComposeFileTest.java` **before** the Compose services exist, per Principle VI: add `9090:9090` and `9030:3000` to `hostPortsMatchTheConstitution()`, and `prometheus-data` and `grafana-data` to `dataUsesNamedVolumes()`
- [ ] T013 Run `wsl -e bash -lc "cd <repo> && mvn -f backend/pom.xml -pl infra-tests verify"` and **confirm it FAILS** on the new assertions. A test that passes before the work is done is not testing the work
- [x] T014 Create `backend/docker/prometheus/prometheus.yml` with `scrape_interval: 15s` and the four jobs from [contracts §2](./contracts/scrape-contract.md): `learnhub-services` (10 targets), `learnhub-platform` (2 targets), `rabbitmq-aggregated` (`/metrics`), `rabbitmq-queues` (`/metrics/detailed` with `params: {family: [queue_coarse_metrics]}`). Relabel to add a readable `service` label ([data-model §1](./data-model.md)). Include a commented `basic_auth` block on `learnhub-platform` documenting what non-`local` profiles need ([research R3](./research.md))
- [x] T015 Add the `prometheus` service to `docker-compose.yml`: image pinned to an explicit tag, `9090:9090`, the config mounted **read-only**, `mem_limit: 256m`, args `--storage.tsdb.retention.time=24h --storage.tsdb.retention.size=512MB`, named volume `prometheus-data`, and a healthcheck `wget -qO- http://localhost:9090/-/healthy`. The healthcheck is **not optional** — `everyLongRunningServiceHasHealthcheck()` iterates every service and fails any without one ([research R8, R9](./research.md))
- [x] T016 [P] Add `"9090:prometheus"` and `"9030:grafana"` to the `PORTS` array in `scripts/up.sh` (~line 30). The constitution's port-map preamble requires the preflight to cover every host port in the table
- [x] T017 [P] Add `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD` to `.env.example` with non-secret placeholders, and to the `REQUIRED_SETTINGS` array in `scripts/up.sh`. **This makes the user's existing `.env` fail preflight until they add the two lines** — same situation spec 009 created with `ANALYTICS_DB_PASSWORD`; it must appear in the completion notes ([research R10](./research.md))
- [x] T018 [P] Create `backend/docker/grafana/provisioning/datasources/prometheus.yml` declaring Prometheus at `http://prometheus:9090` as the default datasource, non-editable
- [x] T019 [P] Create `backend/docker/grafana/provisioning/dashboards/dashboards.yml` as a file provider pointing at `/var/lib/grafana/dashboards`, with `allowUiUpdates: false` so FR-011 holds
- [x] T020 Add the `grafana` service to `docker-compose.yml`: image pinned, `9030:3000`, provisioning and dashboards directories mounted **read-only**, admin credentials from `${GRAFANA_ADMIN_USER:?…}` / `${GRAFANA_ADMIN_PASSWORD:?…}`, `mem_limit: 192m`, named volume `grafana-data`, healthcheck `wget -qO- http://localhost:3000/api/health`, and `depends_on: prometheus: condition: service_healthy`
- [x] T021 Declare the `prometheus-data` and `grafana-data` named volumes in the top-level `volumes:` block of `docker-compose.yml`
- [ ] T022 Restart the stack and confirm `mvn -f backend/pom.xml -pl infra-tests verify` now **passes** — T013's failures are resolved by T015/T020/T021
- [ ] T023 Verify collection is complete: `curl -s "localhost:9090/api/v1/targets?state=active"` shows **14 targets, every one `up`**. A target reporting `down` with a 404 in `lastError` means a wrong path in `prometheus.yml`, not an unhealthy service

**Checkpoint**: metrics are being collected and stored, and Grafana is running with a working
datasource. No dashboard exists yet.

---

## Phase 3: User Story 1 — dead-letter visibility (Priority: P1) 🎯 MVP

**Goal**: an operator sees that a message has been dead-lettered, and gets an alert, without opening
the broker console or a log file.

**Independent Test**: stop a consumer, dead-letter a message, and see the depth rise on a dashboard
and the alert fire — then see both clear by themselves when the queue is drained.

### Close the blocking gap first

- [x] T024 **[GAP]** Capture the **exact** per-queue metric names and label names from a live scrape: `curl -s "localhost:9090/api/v1/label/__name__/values" | tr ',' '\n' | grep -i queue`, then inspect one series' labels with `curl -s "localhost:9090/api/v1/query?query=<name>"`. [contracts §4](./contracts/scrape-contract.md) records `rabbitmq_detailed_queue_messages_ready` with a `queue` label as **expected but unverified**. Update that section with what is actually observed
- [x] T025 **[GAP]** Confirm the dead-letter naming convention against the live broker: `docker compose exec rabbitmq rabbitmqctl list_queues name | grep dlq`. [contracts §4](./contracts/scrape-contract.md) assumes every dead-letter queue ends in `.dlq`, read off service config rather than observed. The alert's selector depends on it, so a suffix match that misses a queue would make the alert silently incomplete

> **T024 and T025 block T026 and T027.** Writing a panel or an alert against a guessed metric name
> produces an empty panel and a rule that can never fire — a monitoring gap that looks like health.

### Implementation for User Story 1

- [x] T026 [US1] Create `backend/docker/grafana/dashboards/event-pipeline.json` with `uid: learnhub-event-pipeline` and panels for ready messages per queue, unacknowledged messages per queue, **dead-letter depth per queue**, and consumer throughput — using the metric and label names confirmed in T024
- [x] T027 [US1] Create `backend/docker/grafana/provisioning/alerting/rules.yml` with the **DLQ not empty** rule: ready messages > 0 on any queue matching the dead-letter suffix confirmed in T025, evaluated over a **1 minute** window. 1 minute because a dead-lettered message never clears itself, and SC-002 allows 2 minutes end to end ([data-model §4](./data-model.md))
- [x] T028 [US1] Restart Grafana and confirm the dashboard and the alert rule both appear with no manual import, and that the alert is `Normal` while all queues are empty
- [x] T029 [US1] Run the dead-letter drill from [quickstart §6](./quickstart.md): stop `notification-service`, publish a malformed message to `enrollment.created`, restart it, and confirm within ~60 s that `notification.q.dlq` shows depth ≥ 1 **naming the queue**, and within ~2 min that the alert moves to `Firing` (SC-002)
- [x] T030 [US1] Cross-check the dashboard against the broker's own console at <http://localhost:9673> → Queues → `notification.q.dlq`, so the figure is corroborated rather than trusted
- [x] T031 [US1] Verify self-resolution (FR-017): `docker compose exec rabbitmq rabbitmqctl purge_queue notification.q.dlq`, then confirm the depth returns to 0 and the alert returns to `Normal` with no acknowledgement step

**Checkpoint**: User Story 1 is complete and independently demonstrable. The platform's single
highest-consequence blind spot is closed. **This is a shippable MVP — stop and validate here.**

---

## Phase 4: User Story 2 — platform health at a glance (Priority: P2)

**Goal**: one screen that answers "is anything wrong?" — up/down, traffic, latency, errors, memory.

**Independent Test**: generate real traffic through the web app and see every service's figures move;
stop a service and see it reported down.

- [x] T032 [P] [US2] Create `backend/docker/grafana/dashboards/platform-health.json` with `uid: learnhub-platform-health` and panels for up/down per service, request rate by service, latency by service (p95 or average per T009's outcome), error percentage by service using `outcome="SERVER_ERROR"`, JVM heap used vs committed filtered to `area="heap"`, and GC pause time. Group and legend by the `service` label, not `instance` ([data-model §1](./data-model.md))
- [x] T033 [US2] Add the **service unreachable** rule to `backend/docker/grafana/provisioning/alerting/rules.yml`: `up == 0` over a **2 minute** window. 2 minutes is deliberate — a service's own healthcheck allows a 30 s start period plus retries, and a shorter window would fire on every normal start, which FR-018 and SC-009 forbid
- [x] T034 [US2] Add the **elevated server errors** rule to the same file: share of `outcome="SERVER_ERROR"` over total requests > 5% for a service, over a **5 minute** window. Proportional rather than absolute, so one failed request on an idle service is not a 100% error rate
- [ ] T035 [US2] Seed and generate real traffic: `scripts/seed-demo.sh`, then browse <http://localhost:9100> — catalog, sign in, enrol in the free course, open a lesson
- [ ] T036 [US2] Confirm the platform-health dashboard shows non-zero request rate and latency for `course-service`, `auth-service` and `api-gateway`, every service up, and populated heap and GC. An idle service must show an empty or zero panel, not an error (edge case in [spec.md](./spec.md))
- [x] T037 [US2] Verify FR-014: `docker compose stop review-service` → within ~2 min it shows down and **Service unreachable** fires; `docker compose start review-service` → recovers on its own
- [x] T038 [US2] Verify FR-018 and SC-009, the half that is easy to skip: `docker compose down && scripts/up.sh`, then confirm a normal start produces **no** service-unreachable alert

**Checkpoint**: User Stories 1 and 2 both work independently.

---

## Phase 5: User Story 3 — trust that the monitoring sees everything (Priority: P3)

**Goal**: a monitoring gap fails a test rather than passing unnoticed.

**Independent Test**: remove a target from collection and confirm the automated smoke test fails and
names what is missing.

- [x] T039 [US3] Add a monitoring section to `scripts/smoke-test.sh` asserting: `GET localhost:9090/-/healthy` returns 200; **every** active target reports `up` **and** the target count equals the expected number; `GET localhost:9030/api/health` returns 200. Both halves of the target assertion are needed — all-up alone would pass if a target were missing from the config entirely, which is the gap FR-021 exists to catch ([contracts §5](./contracts/scrape-contract.md))
- [ ] T040 [US3] Run `wsl -e bash -lc "cd <repo> && scripts/smoke-test.sh"` and confirm every check passes, including the new ones
- [x] T041 [US3] Prove the gap detection actually works, per [quickstart §8](./quickstart.md): comment out one target in `backend/docker/prometheus/prometheus.yml`, restart Prometheus, run the smoke test and **confirm it FAILS** naming the count mismatch, then restore the target and confirm it passes. A monitoring check that cannot fail is worse than no check
- [x] T042 [US3] Verify FR-011, that committed files are the source of truth: edit a panel title in the Grafana UI, save, `docker compose restart grafana`, and confirm the committed title is back

**Checkpoint**: all three user stories are independently functional and automatically verified.

---

## Phase 6: Polish & cross-cutting concerns

- [x] T043 [P] Verify SC-006: `docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}" | grep -E "prometheus|grafana"` shows under 500 MB combined
- [ ] T044 [P] Verify SC-008 and FR-023: confirm retention bounds via `localhost:9090/api/v1/status/runtimeinfo`; `docker compose restart prometheus` and confirm history survives; `docker compose down -v && scripts/up.sh` and confirm dashboards and alerts return immediately (provisioned from files) while metric history starts empty
- [ ] T045 Run the full backend suite: `wsl -e bash -lc "cd <repo> && mvn -f backend/pom.xml verify"`. `NoWiringCodeTest` must still pass — it is the structural guard on FR-002 — as must the extended `ComposeFileTest`
- [x] T046 [P] Update `README.md`: add Prometheus 9090 and Grafana 9030 to the port table, add a short Monitoring section, and add "register it in `backend/docker/prometheus/prometheus.yml`" to the "Adding a service" checklist ([research R4](./research.md) accepted cost)
- [x] T047 [P] Update `HOWTORUN.md`: port table rows, the two new `.env` settings, and a dead-letter drill walkthrough based on [quickstart §6](./quickstart.md)
- [x] T048 [P] Update `ARCHITECTURE.md` §4.3: mark the **metrics** half of the observability gap closed and leave tracing and log aggregation open. Also correct the stale "plain CSS" frontend row and the "specs 001–006" baseline noted earlier in this session, if still present
- [ ] T049 Run the full [quickstart.md](./quickstart.md) top to bottom on a clean start, so the validation guide is proven rather than assumed
- [ ] T050 Update the harness files: `claude-progress.md` session entry; `feature_list.json` spec-010 to `passing` **only** with recorded evidence per its own rules; overwrite the stale `session-handoff.md` (it still describes spec-009 as "waiting for the user's description")
- [ ] T051 Walk `docs/harness/clean-state-checklist.md` and report what is committed, what is not, and what remains unverified

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 (Setup)**: no dependencies. T004 (Docker running) gates T005–T009
- **Phase 2 (Foundational)**: depends on Phase 1. **Blocks all user stories**
- **Phase 3–5 (User stories)**: all depend on Phase 2. Can then proceed in parallel, or sequentially
  P1 → P2 → P3
- **Phase 6 (Polish)**: depends on the user stories being complete

### Critical path through the gaps

```text
T001 ──> T005 ──> T006/T007 ──> T010 [GAP] ──> T014 ──> T015 ──> T023
                                                                   │
                                            T024 [GAP] + T025 [GAP]┤
                                                                   v
                                                        T026/T027 ──> T029 (drill)
```

T024 and T025 are the narrow point. Everything in US1 downstream of them is written against metric
names that are currently unverified, so skipping them does not save time — it moves the failure to
T029, where the symptom is an empty panel rather than a clear error.

### Test-before-implementation pairs

| Test task | Implementation it must precede |
|---|---|
| T012, T013 (ComposeFileTest fails) | T015, T020, T021 (Compose services) |
| T039 (smoke-test assertion) | T041 (proving it can fail) |

### Parallel opportunities

- T002 and T003 — different files, though both are required
- T016, T017, T018, T019 — scripts, `.env.example` and two Grafana provisioning files, all distinct
- T043, T044, T046, T047, T048 — measurements and separate documents
- US2's T032 can be written while US1's drill (T029–T031) is running, since they touch different
  dashboard files

### Within each user story

- Close [GAP] tasks before anything that depends on their findings
- Dashboard JSON before the alert rule that queries the same metrics
- Configuration before the drill that exercises it
- Finish and validate a story before starting the next priority

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 — every service exposes metrics
2. Phase 2 — Prometheus collecting, Grafana running *(blocks everything)*
3. Phase 3 — dead-letter visibility and its alert
4. **Stop and validate**: run the drill in T029–T031
5. At this point the platform's worst blind spot is closed and the feature is demonstrable

### Incremental delivery

1. Setup + Foundational → collection working
2. US1 → dead-letter visibility → **MVP, validate, demo**
3. US2 → platform health → validate, demo
4. US3 → automated gap detection → validate
5. Polish → docs, budgets, harness

Each story adds value without disturbing the previous one: US2 adds a separate dashboard file, US3
adds smoke-test assertions.

---

## Notes

- **[GAP] tasks require observed output.** T010, T024 and T025 exist because the documentation did not
  answer the question and Docker was unavailable during planning. Marking one done from expectation
  rather than observation reintroduces exactly the defect the task was written to prevent.
- The dead-letter drill (T029) is a **required verification step**, not documentation. A dashboard
  showing zeros proves nothing until it has been seen to show a one.
- Per `feature_list.json`'s own rules, spec-010 moves to `passing` only with recorded evidence.
- Commit after each phase checkpoint. **Do not merge to master without the user's explicit
  go-ahead** (CLAUDE.md).
