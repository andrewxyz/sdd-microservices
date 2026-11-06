# Scrape Contract: Platform Observability

**Feature**: `010-observability` | **Date**: 2026-11-06

This feature exposes no REST API, so there is no OpenAPI document. What it does have is a contract in
the real sense: a set of **scrape targets** and a set of **metric names and labels** that the
dashboards and alert rules depend on. A metric renamed upstream, or a target dropped from the list,
breaks a dashboard silently — no error, just an empty panel. This file is that contract, written down
so a future change can be checked against it.

---

## 1. Endpoint each service must expose

| | |
|---|---|
| Path | `/actuator/prometheus` |
| Port | the service's own port (its Compose internal port) |
| Method | `GET` |
| Response | `200` with Prometheus text exposition format |
| Auth | none under the `local` profile; basic auth required outside it (Principle VII, VIII) |
| Delivered by | `micrometer-registry-prometheus` via `platform-common` — **no per-service code** |

Negative obligation, from FR-002 and SC-004: **no service may contain metrics configuration of its
own.** A service satisfies this contract by depending on `platform-common` and nothing else.
`NoWiringCodeTest` is the guard.

---

## 2. Scrape targets

14 targets. Addresses are Compose DNS names on **internal** ports, so this keeps working if
`sec-002` (stop publishing internal service ports to the host) is implemented later.

### Job `learnhub-services` — path `/actuator/prometheus`

| Target | Address |
|---|---|
| auth-service | `auth-service:9001` |
| course-service | `course-service:9002` |
| enrollment-service | `enrollment-service:9003` |
| payment-service | `payment-service:9004` |
| certificate-service | `certificate-service:9005` |
| review-service | `review-service:9006` |
| notification-service | `notification-service:9007` |
| analytics-service | `analytics-service:9008` |
| api-gateway | `api-gateway:9080` |
| echo-service | `echo-service:9099` |

### Job `learnhub-platform` — path `/actuator/prometheus`

| Target | Address | Note |
|---|---|---|
| discovery-server | `discovery-server:9761` | open under `local` only |
| config-server | `config-server:9888` | open under `local` only |

Separated from the business services because these two need `basic_auth` outside the `local` profile
while the others sit behind the gateway. Keeping them in their own job means adding credentials later
is a change to one job block, not a rewrite.

### Job `rabbitmq-aggregated` — path `/metrics`

| Target | Address |
|---|---|
| rabbitmq | `rabbitmq:15692` |

### Job `rabbitmq-queues` — path `/metrics/detailed`, params `family=queue_coarse_metrics`

| Target | Address |
|---|---|
| rabbitmq | `rabbitmq:15692` |

Two RabbitMQ jobs, not one, because `/metrics` returns **aggregated** values only and cannot attribute
a depth to a named queue. See [research R5](../research.md). Requesting a single `family` keeps the
detailed endpoint's cost near the aggregated one.

**Adding a future service means adding it here as well as to `docker-compose.yml`.** The smoke-test
assertion in §5 turns a forgotten entry into a test failure rather than an unmonitored service.

---

## 3. Metrics relied on — Spring services

Emitted by Micrometer and Spring Boot's own instrumentation. Names are stable across Spring Boot 3.x.

| Metric | Type | Used for |
|---|---|---|
| `up` | gauge (synthetic, added by Prometheus) | service up/down panel; service-unreachable alert |
| `http_server_requests_seconds_count` | counter | request rate; error percentage |
| `http_server_requests_seconds_sum` | counter | average latency |
| `http_server_requests_seconds_bucket` | histogram | p95 latency |
| `jvm_memory_used_bytes` | gauge | heap used |
| `jvm_memory_committed_bytes` | gauge | heap committed |
| `jvm_gc_pause_seconds_count` / `_sum` | counter | GC pause time |

Labels on `http_server_requests_seconds_*`: `method`, `uri`, `status`, `outcome`, `exception`. The
error-rate alert uses `outcome="SERVER_ERROR"` rather than parsing `status`, because `outcome` is the
label Spring Boot populates for exactly this purpose.

Label on `jvm_memory_*`: `area` (`heap` / `nonheap`) and `id` (the pool). Heap panels must filter
`area="heap"` or they double-count.

**`http_server_requests_seconds_bucket` requires histogram buckets to be enabled.** Micrometer does
not publish them by default — a percentile query against it returns nothing. If enabling them turns
out to need configuration, it goes in `backend/config-repo/application.yml` as a shared default so
FR-002 still holds; if that proves impossible without per-service config, the p95 panel drops to an
average from `_sum / _count` and the spec is still met, since no requirement names a percentile.

---

## 4. Metrics relied on — RabbitMQ

**Verified against a live scrape on 2026-11-06** (tasks T010, T024, T025). The `rabbitmq_prometheus`
plugin is enabled in `rabbitmq:4.3-management` (`rabbitmq-plugins list -e` shows
`[E*] rabbitmq_prometheus 4.3.7`), so no `RABBITMQ_ENABLED_PLUGINS` is needed.

All come from `/metrics/detailed`, which uses the `rabbitmq_detailed_` prefix, **not** `rabbitmq_`.
Every series carries the labels `queue` and `vhost`.

| Metric | Family | Used for |
|---|---|---|
| `rabbitmq_detailed_queue_messages_ready` | `queue_coarse_metrics` | queue depth; **dead-letter depth** (the US1 panel and alert) |
| `rabbitmq_detailed_queue_messages_unacked` | `queue_coarse_metrics` | in-flight messages |
| `rabbitmq_detailed_queue_messages_acked_total` | `queue_delivery_metrics` | consumer throughput |
| `rabbitmq_detailed_queue_messages_redelivered_total` | `queue_delivery_metrics` | retries, which precede a dead-letter |

Two families are scraped, not the broader `queue_metrics`: that one is 41 series for two queues, against
7 for `queue_delivery_metrics`, and the Prometheus container is capped at 256 MB. `queue_metrics` is
where per-queue consumer counts live, so a consumers-per-queue panel is deliberately not included.

**Dead-letter naming.** Every consumer's dead-letter queue ends in `.dlq`. Read from all four
`RabbitTopologyConfig` classes, and observed live for the one consumer that was running:

| Queue | Dead-letter queue | Confirmed |
|---|---|---|
| `enrollment-service.payment-succeeded` | `enrollment-service.payment-succeeded.dlq` | source |
| `certificate.q` | `certificate.q.dlq` | source |
| `notification.q` | `notification.q.dlq` | source and live broker |
| `analytics.q` | `analytics.q.dlq` | source |

A suffix match therefore covers every current consumer, and a future one that follows the convention.

---

## 5. Prometheus API used by the smoke test

The smoke test consumes one endpoint, which makes it part of this contract.

| | |
|---|---|
| Request | `GET http://localhost:9090/api/v1/targets?state=active` |
| Assertion | every entry has `health == "up"`, and the number of entries equals the expected target count |

Both halves are needed: all-up alone would pass if a target were missing from the config entirely,
which is the very gap FR-021 exists to catch.

Also asserted, as plain reachability checks: `GET http://localhost:9090/-/healthy` → `200`, and
`GET http://localhost:9030/api/health` → `200`.

---

## 6. What this contract does not cover

- **No gateway route.** Prometheus and Grafana are operator tools, not client APIs, and get no route
  through `api-gateway` — the same position `notification-service` is in. Principle IV is about client
  entry points and is not engaged.
- **No frontend contract.** Nothing here is consumed by the SPA, so no generated types and no change
  under `frontend/`.
- **No event contract.** This feature publishes and consumes no events, and `shared-events` is
  untouched.
