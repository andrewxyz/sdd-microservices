# Implementation Plan: Platform Observability — Metrics and Dashboards

**Branch**: `010-observability` | **Date**: 2026-11-06 | **Spec**: [spec.md](./spec.md)

## Summary

Give every service a Prometheus scrape endpoint through one dependency added to `platform-common` and
one line changed in the shared config, so no service carries metrics wiring of its own. Add two
containers — Prometheus (host 9090) to scrape and retain, Grafana (host 9030) to present — with
dashboards and alert rules as committed, provisioned configuration. Wire both into the existing
one-command startup, the preflight port check and the smoke test. Covers **`backend/` and repo-root
infrastructure only**; `frontend/` is untouched, because this is operator telemetry rather than
anything a student or instructor sees.

Metrics only. Distributed tracing and log aggregation stay out of scope and remain unapproved under
Principle X.

## Corrections to the brief (verified during research)

| Brief / spec assumed | Verified reality | Plan uses |
|---|---|---|
| Scrape `rabbitmq:15692/metrics` for per-queue DLQ depth | `/metrics` returns **aggregated** metrics; per-queue data needs `/metrics/per-object` or `/metrics/detailed?family=…` ([research R5](./research.md)) | **two** RabbitMQ targets: `/metrics` for broker health, `/metrics/detailed?family=queue_coarse_metrics` for per-queue depth |
| Metric names like `rabbitmq_queue_messages_ready` | the detailed endpoint uses the prefix `rabbitmq_detailed_` | detailed names, **confirmed against a live scrape before dashboards are written** (T011) |
| RabbitMQ plugin is enabled by default | not stated in the docs for the Docker images; unverified | verification task T010 before the compose entry is finalised |
| Change `application.yml` to expose the endpoint | `application-local.yml` overrides the `include` list and has higher precedence | **both** files changed, else the endpoint stays closed under the profile actually used |
| One `mem_limit` and `retention.time` | `retention.time` alone leaves size unbounded, but SC-008 wants a bounded size | both `--storage.tsdb.retention.time` and `--storage.tsdb.retention.size` |

## Technical Context

**Language/Version**: Java 21 (Spring Boot 3.5.15, Spring Cloud 2025.0.3). No application code is
written in this feature — the changes are one POM dependency, YAML configuration, Compose services,
shell scripts and two existing Java test classes.

**Primary Dependencies**: one new library, `micrometer-registry-prometheus`, version managed by the
Spring Boot BOM (no `<version>`, so Principle IX holds). Two new images: `prom/prometheus`,
`grafana/grafana`, both pinned to an explicit tag. No new frontend dependency.

**Storage**: two named Docker volumes, `prometheus-data` and `grafana-data`. No database, no Flyway
migration, no change to any service's schema.

**Testing**: existing `backend/infra-tests` `ComposeFileTest` extended (ports, volumes, and its
whole-file health-check assertion now covers two more services); `scripts/smoke-test.sh` extended
with target-up assertions; a manual dead-letter drill in `quickstart.md` for the P1 user story. No
Testcontainers test is added — the thing under test is the Compose topology and the scrape wiring,
which `ComposeFileTest` and the smoke test already cover at the right level.

**Target Platform**: Docker Compose on a single host.

**Project Type**: backend + infrastructure (no frontend change).

**Performance Goals**: a dead-lettered message visible within 60 s and alerting within 2 min
(SC-002), which a 15 s scrape interval and a 1 min alert evaluation window satisfy with margin.

**Constraints**: constitution VII (basic auth outside `local`), VIII (as amended), IX (versions in
parent POM only, port map), X (approved technology only). Host budget: under 4 GB of Docker VM memory
already running 15 containers, so ≤500 MB total for both new containers (SC-006).

**Scale/Scope**: 14 scrape targets, 2 dashboards, 3 alert rules, 2 new containers, 1 new dependency,
0 new application classes.

## Constitution Check

*GATE: passed before Phase 0, re-checked after Phase 1 design. Constitution v1.4.0.*

| Principle | Status |
|---|---|
| I Layout: Prometheus and Grafana config under `backend/docker/`, Compose at the root; frontend untouched; spec names the folders | PASS |
| II Data isolation: no service reads another's data; `platform-common` gains a technical library and no domain logic | PASS |
| III Discovery/config: no change to registration or config import; Prometheus uses static targets because Eureka discovery does not exist for it ([R4](./research.md)) | PASS |
| IV Gateway only entry point: Prometheus and Grafana are operator tools, not client APIs, and get no gateway route — consistent with `notification-service` having none | PASS |
| V Communication: unchanged; no new Feign client, no new queue or event | PASS |
| VI Test-first: `ComposeFileTest` assertions and smoke-test checks are written before the Compose services they describe (T004, T005 precede T012) | PASS |
| VII Security: metrics endpoint open under `local` only, basic auth required outside it; Grafana credentials from `.env` with no default, `.env.example` carries placeholders only | PASS |
| VIII Errors/observability: this feature is the implementation of the amended principle | PASS |
| IX Conventions: no `<version>` on the new dependency (BOM-managed); ports 9090 and 9030 taken from the amended port map; preflight covers both | PASS |
| X Simplicity: Prometheus, Grafana and `micrometer-registry-prometheus` are approved by v1.4.0 for observability only; static targets over a discovery adapter; Grafana alerts over a third Alertmanager container; outbox-lag gauges deferred ([R11](./research.md)) | PASS |
| XI–XIII Frontend: no frontend change in this feature | N/A |
| XIV Read-model services: operator telemetry is explicitly separate from `analytics-service`'s instructor statistics, as the amended Development Workflow section now requires | PASS |

**Notes**: no violations, so Complexity Tracking is empty and omitted. Two points worth recording for
review. First, the amendment to Principle VIII was made in this session specifically to permit this
feature; the gate passes because of that amendment, not in spite of it. Second, Principle VI is
satisfied in the sense available here — the artefacts are configuration, so "test first" means the
Compose and smoke-test assertions are authored before the configuration they assert on, rather than a
failing unit test for a YAML file.

## Project Structure

```text
specs/010-observability/
  spec.md  plan.md  research.md  data-model.md  quickstart.md  tasks.md
  checklists/requirements.md
  contracts/scrape-contract.md          # scrape targets + the metric names dashboards depend on

backend/platform-common/pom.xml         # + micrometer-registry-prometheus (the only POM change)

backend/config-repo/
  application.yml                       # include: health,info -> health,info,prometheus
  application-local.yml                 # include: health,info,refresh -> + prometheus

backend/docker/prometheus/              # NEW
  prometheus.yml                        # static_configs, 14 targets, 15s interval

backend/docker/grafana/                 # NEW
  provisioning/datasources/prometheus.yml
  provisioning/dashboards/dashboards.yml
  provisioning/alerting/rules.yml
  dashboards/platform-health.json
  dashboards/event-pipeline.json

docker-compose.yml                      # + prometheus, grafana; + 2 named volumes
.env.example                            # + GRAFANA_ADMIN_USER, GRAFANA_ADMIN_PASSWORD
scripts/up.sh                           # + 9090 and 9030 in PORTS; + 2 REQUIRED_SETTINGS
scripts/smoke-test.sh                   # + monitoring section
backend/infra-tests/src/test/java/com/example/learnhub/infra/ComposeFileTest.java

README.md  HOWTORUN.md  ARCHITECTURE.md  # port tables, monitoring section, gap status
claude-progress.md  feature_list.json  session-handoff.md
```

**Structure Decision**: configuration for a third-party image lives under `backend/docker/<tool>/`,
following the existing `backend/docker/db-init/` precedent, and is mounted read-only exactly as
`config-server` mounts `config-repo`. Nothing new appears at the repository root, which Principle I
restricts to a fixed list.

## Implementation order

Ordered so that each gap closes before anything depends on it, and so the two unverified facts from
research cannot silently produce broken dashboards.

1. **Dependency and exposure** — POM line, both config files. Verify `/actuator/prometheus` answers on
   a servlet service *and* on the WebFlux gateway, since the gateway is the one with a different web
   stack.
2. **Close the research gaps** — confirm the RabbitMQ plugin is enabled (T010) and capture the exact
   detailed metric names and labels from a live scrape (T011). Both block step 4.
3. **Prometheus** — scrape config, Compose service, preflight ports, and the `ComposeFileTest`
   extensions. Verify every target reports `up`.
4. **Grafana** — datasource, dashboards and alert rules, written against the metric names captured in
   step 2 rather than assumed ones.
5. **Smoke test and the dead-letter drill** — the drill is what actually proves User Story 1, so it is
   a required verification step and not a documentation nicety.
6. **Docs and harness bookkeeping.**

## Risks

| Risk | Handling |
|---|---|
| Per-queue metric names guessed wrong, so the DLQ dashboard and alert are silently empty | T011 captures them from a live scrape before any query is written; the drill in step 5 would catch a mistake anyway |
| RabbitMQ plugin not enabled in the image | T010 checks before the compose entry is finalised; remedy is `RABBITMQ_ENABLED_PLUGINS`, a one-line addition |
| The two new containers tip the host into out-of-memory | memory caps and 24 h retention from the start; fallback is a `monitoring` Compose profile, a two-line change that costs FR-019 |
| The user's existing `.env` fails preflight once the Grafana variables become required | called out explicitly in the completion notes; the preflight error names the missing setting ([R10](./research.md)) |
| `wget` missing from one of the images, breaking its health check | fails loudly and immediately at first start; swap to the image's own CLI if so ([R8](./research.md)) |
