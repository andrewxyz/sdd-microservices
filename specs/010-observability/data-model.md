# Data Model: Platform Observability

**Feature**: `010-observability` | **Date**: 2026-11-06

This feature adds **no database table, no Flyway migration and no entity class**. Its data lives in
Prometheus' time-series store and in committed configuration files. What follows models those
artefacts, because they are what reviewers and future specs need to reason about.

---

## 1. Metric sample (Prometheus time series)

The unit of collected data. Not persisted by any service — each service computes its current values on
request, and Prometheus stores the history.

| Field | Meaning |
|---|---|
| name | the metric, e.g. `http_server_requests_seconds_count` |
| labels | key/value pairs identifying the exact series |
| value | the number at scrape time |
| timestamp | when Prometheus scraped it |

**Labels every series carries**, because Prometheus attaches them from the scrape config:

| Label | Source | Example |
|---|---|---|
| `job` | the `job_name` of the scrape job | `learnhub-services` |
| `instance` | the target address | `auth-service:9001` |
| `service` | a relabelled, readable service name | `auth-service` |

`instance` defaults to `host:port`, which is unreadable on a dashboard legend. A `service` label is
added by relabelling so panels can be grouped and legended by service name. Dashboards must use
`service`, not `instance`.

**Retention**: 24 h or 512 MB, whichever binds first. Nothing here is a system of record — losing it
costs recent history and nothing else, which is why FR-023 asks only that it survive a restart and be
wiped by the existing reset command, not that it be backed up.

---

## 2. Scrape target

A thing Prometheus is expected to collect from. The **set** of targets is the operative definition of
"everything being watched", which is why FR-021 asserts on it: a target silently missing from this set
is exactly the failure mode that makes a dashboard lie.

| Field | Meaning |
|---|---|
| address | Compose DNS name and internal port |
| path | metrics path (default `/metrics`; Spring services use `/actuator/prometheus`) |
| params | query parameters, needed for RabbitMQ's detailed endpoint |
| health | `up` / `down` / `unknown`, reported by Prometheus itself |

Three classes of target, differing in path:

| Class | Count | Path |
|---|---|---|
| Spring services | 12 | `/actuator/prometheus` |
| RabbitMQ aggregated | 1 | `/metrics` |
| RabbitMQ per-queue | 1 | `/metrics/detailed?family=queue_coarse_metrics` |

Full addresses in [contracts/scrape-contract.md](./contracts/scrape-contract.md).

**State transition**: a target is `unknown` before its first scrape, then `up` or `down` after each.
This matters for FR-018 and SC-009: a service inside its startup window is legitimately `down`, so the
service-unreachable alert needs an evaluation window longer than a normal start, or it will cry wolf on
every `scripts/up.sh`.

---

## 3. Dashboard

A committed JSON file, provisioned at startup, holding panels that query the data source. Identity is
its `uid`, which must be stable so links to it do not rot.

| Dashboard | uid | Answers |
|---|---|---|
| Platform health | `learnhub-platform-health` | is anything wrong right now? (US2) |
| Event pipeline | `learnhub-event-pipeline` | is anything stuck? (US1) |

Per FR-011, the file is the source of truth. The provisioning directory is mounted read-only, so an
edit made in the Grafana UI does not survive a restart — deliberate, not a limitation.

**Platform health panels**: up/down per service; request rate by service; p95 latency by service;
error percentage by service; JVM heap used vs committed; GC pause time.

**Event pipeline panels**: ready messages per queue; unacknowledged messages per queue; **dead-letter
depth per queue** (the US1 panel); consumer throughput.

---

## 4. Alert rule

A committed condition over the collected data, evaluated on a schedule, moving between `Normal`,
`Pending` and `Firing` on its own. FR-017's self-resolution is inherent to that model, not something
to implement.

| Rule | Condition | Window | Requirement |
|---|---|---|---|
| DLQ not empty | ready messages > 0 on any queue whose name marks it a dead-letter queue | 1 min | FR-013, US1 |
| Service unreachable | target `up` == 0 | 2 min | FR-014, FR-018 |
| Elevated server errors | share of 5xx responses over total > 5% for a service | 5 min | FR-015 |

**Why these windows**: the DLQ rule is 1 min because a dead-lettered message never clears itself, so
there is nothing to wait out and SC-002 allows 2 min end to end. The service rule is 2 min because a
Spring service's own health check allows a 30 s start period plus retries, and a shorter window would
fire during every normal startup — the FR-018/SC-009 requirement. The error-rate rule is 5 min and
proportional rather than absolute, so one failed request on an idle service is not a 100% error rate.

---

## 5. Relationships

```text
service ──exposes──> metrics endpoint ──scraped by──> Prometheus ──stores──> metric samples
                                                           │
                                          queried by ──────┴──────> Grafana ──> dashboards
                                                                          └──> alert rules ──> firing state
```

Two properties follow from this shape and are worth stating because both are requirements:

- **No service knows it is being monitored.** Each only exposes an endpoint. So no service needs
  metrics code (FR-002), and a scrape failure cannot affect request handling.
- **The target list is the trust boundary.** Everything downstream — dashboards, alerts — is only as
  complete as that list, which is why it is asserted in an automated test rather than reviewed by eye.
