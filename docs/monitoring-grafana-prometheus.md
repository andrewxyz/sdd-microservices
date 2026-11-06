# Monitoring LearnHub with Prometheus and Grafana

A guide for developers who are new to monitoring. It covers what was built (spec 010), why, how to run it,
and how to use both tools step by step. No prior Prometheus or Grafana knowledge is assumed.

- Spec and design records: [`specs/010-observability/`](../specs/010-observability/)
- Short summary: the "Monitoring" section of the [README](../README.md#monitoring)

## Contents

1. [The problem this solves](#1-the-problem-this-solves)
2. [Key concepts in plain words](#2-key-concepts-in-plain-words)
3. [What was built](#3-what-was-built)
4. [How to run it](#4-how-to-run-it)
5. [Using Grafana, step by step](#5-using-grafana-step-by-step)
6. [Using Prometheus, step by step](#6-using-prometheus-step-by-step)
7. [Worked exercise: trigger the dead-letter alert](#7-worked-exercise-trigger-the-dead-letter-alert)
8. [Worked exercise: a stopped service](#8-worked-exercise-a-stopped-service)
9. [How to change things](#9-how-to-change-things)
10. [Troubleshooting](#10-troubleshooting)
11. [Gotchas we hit while building it](#11-gotchas-we-hit-while-building-it)
12. [What is not included](#12-what-is-not-included)
13. [Where everything lives](#13-where-everything-lives)
14. [Appendix: the code changes, file by file](#14-appendix-the-code-changes-file-by-file)

---

## 1. The problem this solves

LearnHub is many small services that talk to each other, partly through RabbitMQ events. Before this
feature, the only way to know how the platform was doing was to read logs and call `/actuator/health`.
Nobody could answer questions such as:

- Is the error rate going up?
- Is a service getting slow?
- Is a service running out of memory?
- **Is a message stuck in a dead-letter queue?**

The last one matters most. When an event consumer fails three times, the message is moved to a
*dead-letter queue* (DLQ), a holding area for messages that could not be processed. Nothing crashes and
no error shows up in the app. For example, a student pays and the confirmation email is never sent, and
without monitoring nobody finds out. So a message sitting in a DLQ needs to be visible and needs to raise
an alert.

Scope: **metrics only**. Tracing and log aggregation are deliberately not included (see section 12).

## 2. Key concepts in plain words

| Term | Meaning |
|---|---|
| **Metric** | A number that changes over time, such as "heap memory used" or "requests handled so far". |
| **Scrape** | Prometheus fetching a service's metrics page over HTTP. It pulls the numbers; services do not push them. |
| **Target** | One thing Prometheus scrapes, for example `auth-service:9001`. |
| **Time series** | One metric with one set of labels, recorded over time. |
| **Label** | A tag on a metric, such as `service="auth-service"`. Labels are how you filter and group. |
| **PromQL** | Prometheus's query language. Used in Prometheus's query box and in Grafana panels. |
| **Counter** | A number that only goes up (total requests). You usually look at its *rate* (requests per second). |
| **Gauge** | A number that goes up and down (heap in use, queue depth). |
| **Histogram** | A set of counters that count how many requests were faster than 50 ms, 100 ms, 250 ms and so on. It is used to compute percentiles such as p95. |
| **Dashboard** | A page of graphs in Grafana. |
| **Panel** | One graph or tile on a dashboard. |
| **Provisioning** | Loading configuration from files at startup, so nobody has to click it together by hand. |
| **Alert rule** | A condition that is checked regularly and raises an alert when it holds for long enough. |

### Why two tools

- **Prometheus** collects the numbers, stores them, and answers queries about them.
- **Grafana** stores no metrics. It sends the same PromQL queries to Prometheus and draws the answers as
  dashboards you can save. It also owns the alert rules in this project.

Prometheus does have a small web page of its own. It is meant for debugging: type a query, see a result,
check which targets are up. It cannot save dashboards. Use Prometheus's page to explore and Grafana to watch.

### How the data flows

```
each service        /actuator/prometheus    (a page of numbers)
RabbitMQ            :15692                   (a page of numbers)
        |
        |  Prometheus fetches these every 15 seconds
        v
   Prometheus   (stores 24 hours of history)
        |
        |  Grafana runs queries against it
        v
   Grafana      (dashboards + alert rules)
        |
        v
   you, in a browser
```

## 3. What was built

### 3.1 Every service now exposes metrics

One dependency was added to `backend/platform-common/pom.xml`: `micrometer-registry-prometheus`. Every
service already depends on `platform-common` and on Spring Boot Actuator, so all of them gained a
`/actuator/prometheus` page with **no code in any service**. This is a rule of the project: services carry
no metrics wiring of their own. Because the dependency has no version number, the Spring Boot BOM chooses
it, which keeps the "versions only in the parent POM" rule intact.

It works for the servlet services and also for the WebFlux `api-gateway`. This was checked on both.

The endpoint also had to be switched on in configuration:

| File | Change |
|---|---|
| `backend/config-repo/application.yml` | Added `prometheus` to the exposed endpoints, and set latency buckets (see below). |
| `backend/config-repo/application-local.yml` | Added `prometheus` here as well. |
| `backend/discovery-server/.../application.yml` | Added `prometheus` (it reads its own local config). |
| `backend/config-server/.../application.yml` | Added `prometheus` (same reason). |

The second row exists because a Spring profile file **replaces** the list in the base file rather than
merging with it. If only the base file had been changed, the `local` profile, which is the one in use,
would still hide the endpoint.

Latency buckets: by default Spring records only count and sum for request timing, which gives an average
but not a p95. We set shared buckets (50 ms, 100 ms, 250 ms, 500 ms, 1 s, 2 s) in `application.yml` so the
p95 panel works. This is one shared setting, not per-service config.

### 3.2 Prometheus

- Config: `backend/docker/prometheus/prometheus.yml`
- Image `prom/prometheus:v3.1.0`, port **9090**
- Scrapes every 15 seconds. Keeps **24 hours** or 512 MB of history, whichever limit is hit first.
- Memory capped at 256 MB. Data is kept in the `prometheus-data` volume.
- It scrapes 14 targets in four jobs:

| Job | Targets | Why separate |
|---|---|---|
| `learnhub-services` | auth 9001, course 9002, enrollment 9003, payment 9004, certificate 9005, review 9006, notification 9007, analytics 9008, api-gateway 9080, echo 9099 | The business services. |
| `learnhub-platform` | discovery-server 9761, config-server 9888 | These two require basic auth outside the `local` profile, so they get their own block for credentials later. |
| `rabbitmq-aggregated` | rabbitmq 15692 `/metrics` | Broker-wide totals. |
| `rabbitmq-queues` | rabbitmq 15692 `/metrics/detailed` | **Per-queue** numbers, including each DLQ. |

Targets are a fixed list written in the file, not discovered automatically. Prometheus has no built-in
Eureka discovery, and the set of containers is fixed anyway, so a plain list is the simplest option.
**If you add a service you must add it to this file.** The smoke test counts targets, so forgetting
makes it fail rather than leaving the service unmonitored.

### 3.3 Grafana

- Files: `backend/docker/grafana/`
- Image `grafana/grafana:11.5.1`, host port **9030** (the container listens on 3000)
- Memory capped at 192 MB. Data is kept in the `grafana-data` volume.
- Everything is provisioned from files and mounted read-only:

| Item | File |
|---|---|
| Prometheus data source (default, cannot be edited in the UI) | `provisioning/datasources/prometheus.yml` |
| Tells Grafana where the dashboard files are (folder "LearnHub") | `provisioning/dashboards/dashboards.yml` |
| Alert rules | `provisioning/alerting/rules.yml` |
| Dashboard: Platform health | `dashboards/platform-health.json` |
| Dashboard: Event pipeline | `dashboards/event-pipeline.json` |

#### Dashboard "LearnHub - Platform health"

| Panel | Shows |
|---|---|
| Service up | Whether each target is reachable right now. |
| Request rate | Requests per second per service. Actuator calls are excluded so health checks do not drown real traffic. |
| Latency, 95th percentile | 95% of requests were faster than this. |
| Server error percentage | Share of requests that ended in a 5xx. |
| JVM heap: used | Heap in use against heap committed, per service. |
| Garbage collection: time paused per second | How much time the JVM spends paused. A rising line means memory pressure. |

#### Dashboard "LearnHub - Event pipeline"

| Panel | Shows |
|---|---|
| Dead-letter depth per queue | Current number of stuck messages in each `*.dlq` queue. **Should be 0.** |
| Dead-letter depth over time | Same, as a graph, so you can see when it started. |
| Messages ready (excluding dead-letter queues) | Messages waiting to be consumed. A growing number means consumers are not keeping up. |
| Consumer throughput | Messages acknowledged per second. |
| Redeliveries per second (retries) | Messages being retried, an early sign of a failing consumer. |
| Messages delivered but not yet acknowledged | Messages a consumer has picked up and not finished. |

There is no panel for Eureka registrations. Eureka exposes no such metric, so the panel would have
been empty.

#### The three alert rules

All three live in the group `learnhub-operations`, checked every 30 seconds.

| Alert | Fires when | For how long | Why that long |
|---|---|---|---|
| **Dead-letter queue not empty** | any `*.dlq` queue has at least one message | 1 minute | Fast, because a stuck message is a real failure. |
| **Service unreachable** | a target is down | 2 minutes | A service is legitimately unreachable while it boots, so a normal restart must not alert. |
| **Server error rate elevated** | more than 5% of a service's requests are 5xx | 5 minutes | Ignores a brief blip. |

All three clear by themselves once the condition goes away. There is no manual acknowledgement.

> Alerts show up in Grafana's UI. Nothing sends email or chat messages yet. That would be a "contact
> point" in Grafana and needs its own decision.

### 3.4 Compose, scripts and tests

| File | Change |
|---|---|
| `docker-compose.yml` | New `prometheus` and `grafana` services, each with a healthcheck, a memory cap and a named volume. Grafana waits for Prometheus to be healthy. |
| `.env.example` | New `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD`. |
| `scripts/up.sh` | Port preflight now also checks 9090 and 9030, and refuses to start if the two Grafana settings are missing. |
| `scripts/smoke-test.sh` | New checks: Prometheus is healthy, **all 14 targets exist and are up**, Grafana answers. |
| `backend/infra-tests/.../ComposeFileTest.java` | Extended to check the new ports, volumes, memory caps, read-only mounts and required credentials. |

### 3.5 Governance and docs

- `.specify/memory/constitution.md` was amended to **v1.4.0**. This project forbids new technology
  without an amendment. The change approved Prometheus, Grafana and the Micrometer registry, required the
  metrics endpoint to use basic auth outside `local`, and added the two ports to the port map.
- README, HOWTORUN (including a dead-letter walkthrough in section 3.33) and ARCHITECTURE section 4.3 were
  updated.

## 4. How to run it

### 4.1 One-time setup

Add two lines to your `.env` (copy them from `.env.example` if you are starting fresh):

```
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=<pick anything non-empty>
```

Do not commit `.env`. The repository is public.

### 4.2 Start everything

```bash
scripts/up.sh
```

Prometheus and Grafana start together with the rest of the platform. Docker and Compose run inside WSL
in this project, so from Windows use `wsl -e bash -lc "cd <repo> && scripts/up.sh"`.

### 4.3 Start only part of the platform (low memory)

The full platform has 18 containers and needs a lot of memory. To run a subset, pass service names:

```bash
scripts/up.sh prometheus grafana api-gateway config-server discovery-server rabbitmq postgres
```

Services you leave out will show as **down** in Prometheus and Grafana. That is expected and not a bug.

### 4.4 Check that it is healthy

```bash
docker compose ps prometheus grafana
```

Both should say `running` and `healthy`.

### 4.5 Open the tools

| Tool | URL | Login |
|---|---|---|
| Grafana | http://localhost:9030 | `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` from your `.env` |
| Prometheus | http://localhost:9090 | none |
| RabbitMQ console | http://localhost:9673 | `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` from `.env` |

### 4.6 Run the automated checks

```bash
scripts/smoke-test.sh                          # full platform must be up
mvn -f backend/pom.xml -pl infra-tests verify  # compose-file checks
```

## 5. Using Grafana, step by step

### 5.1 Sign in and find the dashboards

1. Open http://localhost:9030 and sign in.
2. In the left menu click **Dashboards**.
3. Open the folder **LearnHub**.
4. Click **LearnHub - Platform health**.

You do not import anything. The dashboards are already there.

### 5.2 Set the time range and refresh

At the top right of a dashboard:

1. Click the clock/time range picker and choose **Last 15 minutes** (or **Last 1 hour**). Prometheus keeps
   only 24 hours, so do not pick longer.
2. Next to it is the auto-refresh dropdown. Pick **10s** or **30s** to watch live.

### 5.3 Read the Platform health dashboard

Go through the panels top to bottom:

1. **Service up.** A green **UP** means reachable, a red **DOWN** means not. Start here, because if a service is down
   every other panel for it is meaningless.
2. **Request rate.** If nothing calls the platform, this is empty or zero. That is normal.
   To generate traffic, open http://localhost:9100 and click around, or run `scripts/seed-demo.sh` first.
3. **Latency p95.** Higher means slower. Compare services against each other.
4. **Server error percentage.** Should sit at zero or be empty. A line above zero means that service is
   returning 5xx responses.
5. **JVM heap.** Used should stay clearly below committed. Used climbing steadily without coming back down
   is a sign of a memory leak.
6. **GC pause.** Should be small and flat. A rising line means the JVM is struggling for memory.

Useful interactions:

- **Hover** over a graph to see exact values for every service at that moment.
- **Click a name in the legend** to show only that service. Click again to restore.
- **Drag across a graph** to zoom into a time range. Use the time-range picker's zoom-out to go back.
- Press `e` while hovering a panel, or use the panel menu, then **View** to enlarge it.

### 5.4 Read the Event pipeline dashboard

1. **Dead-letter depth per queue.** Every value should be **0**. Any other number means events failed
   repeatedly and were set aside. The queue name tells you which consumer is failing, for example
   `notification.q.dlq`.
2. **Dead-letter depth over time.** Shows when it started, which helps you match it to a deploy or an
   error in the logs.
3. **Messages ready.** A queue that keeps growing means its consumer is stopped or too slow.
4. **Consumer throughput.** Messages acknowledged per second.
5. **Redeliveries per second.** Above zero means messages are being retried. Retries come before the DLQ,
   so this is the early warning.
6. **Delivered but not acknowledged.** A number that stays high means a consumer picked up work and has
   not finished it.

### 5.5 View the alert rules

1. Left menu, **Alerting**, then **Alert rules**.
2. Open the folder **LearnHub** and the group **learnhub-operations**.
3. You should see three rules. Their state is one of:

| State | Meaning |
|---|---|
| **Normal** | The condition is not true. |
| **Pending** | The condition is true but has not lasted long enough yet (see the "for how long" column in section 3.3). |
| **Firing** | The condition has held long enough. This is the actual alert. |

4. Click a rule to see its query, its history and which services or queues are affected. Instances are
   listed per service or per queue.

### 5.6 Explore: run your own queries

**Explore** lets you try a query without building a dashboard.

1. Left menu, **Explore**.
2. Pick the data source **Prometheus** (it is the default).
3. Switch to **Code** mode if you see a "Builder / Code" toggle, and type a query, for example:
   ```
   up
   ```
4. Click **Run query** (or press Shift+Enter).
5. Try the queries in section 6.4.

### 5.7 Changing a dashboard permanently

Dashboards are files, and Grafana's UI is **read-only for them**. If you edit a panel in the UI and save, it
will appear to work, and the change **will be gone after the next restart**. This is intended: the files
in Git are the source of truth. To make a lasting change see section 9.

## 6. Using Prometheus, step by step

### 6.1 Is everything being scraped? (Targets page)

1. Open http://localhost:9090/targets (or the menu: **Status**, then **Targets**).
2. You should see 14 targets in four groups: `learnhub-services`, `learnhub-platform`,
   `rabbitmq-aggregated`, `rabbitmq-queues`.
3. Each row shows a state: **UP** (green) or **DOWN** (red).
4. For a DOWN target look at the **Error** column. It tells you why:
   - `connection refused` or `no such host` - the container is not running.
   - `404` - the path in `prometheus.yml` is wrong, not the service.
   - `401` - the service needs basic auth (see section 10).
   - `context deadline exceeded` - the service is too slow to answer within 10 seconds.

### 6.2 Run a query

1. Open http://localhost:9090/query (the **Query** or **Graph** tab).
2. Type a query in the box and press **Execute**.
3. The **Table** tab shows current values. The **Graph** tab shows them over time.

### 6.3 How to read a result

A result line looks like this:

```
jvm_memory_used_bytes{area="heap", id="G1 Eden Space", service="auth-service"}  52428800
```

- `jvm_memory_used_bytes` is the metric name.
- Everything in `{ }` is the labels.
- The number at the end is the value (here bytes).

To narrow things down, put label conditions in braces: `{service="auth-service"}`.
Operators: `=` equal, `!=` not equal, `=~` matches a pattern, `!~` does not match a pattern.

### 6.4 Queries worth knowing

All of these work against the running stack.

| Question | Query |
|---|---|
| Which targets are up? (1 = up, 0 = down) | `up` |
| Which are down? | `up == 0` |
| Heap used per service | `sum by (service) (jvm_memory_used_bytes{area="heap"})` |
| Requests per second per service | `sum by (service) (rate(http_server_requests_seconds_count[1m]))` |
| Requests per second excluding health checks | `sum by (service) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m]))` |
| Server error percentage | `100 * sum by (service) (rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m])) / sum by (service) (rate(http_server_requests_seconds_count[5m]))` |
| p95 latency per service | `histogram_quantile(0.95, sum by (service, le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))` |
| Dead-letter queue depth | `sum by (queue) (rabbitmq_detailed_queue_messages_ready{queue=~".*[.]dlq"})` |
| All queue depths | `rabbitmq_detailed_queue_messages_ready` |
| Messages acknowledged per second | `rate(rabbitmq_detailed_queue_messages_acked_total[1m])` |

Two PromQL building blocks explain most of these:

- `rate(x[1m])` turns a counter that only goes up into a "per second" figure over the last minute.
- `sum by (service) (...)` adds up all series and keeps one line per service.

### 6.4a More examples, grouped by what you are trying to find out

Paste any of these into the Prometheus query box (http://localhost:9090/query) or into Grafana Explore.
The metrics in this list were confirmed to exist on the running stack.
Where it matters, the text says what a good or bad result looks like.

**Is it alive?**

| Query | What it tells you |
|---|---|
| `up{service="auth-service"}` | 1 or 0 for one service. |
| `count(up == 1)` | How many targets are up right now. |
| `count(up == 0)` | How many are down. Should be 0 on a full platform. |
| `count by (job) (up)` | Targets per job, a quick check that nothing is missing from `prometheus.yml`. |
| `time() - process_start_time_seconds` | Seconds since each service started. A very small number means it just restarted. |
| `process_uptime_seconds` | The same idea, reported by the JVM. |
| `changes(process_start_time_seconds[1h]) > 0` | Services that restarted in the last hour. A service that keeps appearing here is crash-looping. |

**Is it busy or slow?**

| Query | What it tells you |
|---|---|
| `sum(rate(http_server_requests_seconds_count[1m]))` | Total requests per second across the whole platform. |
| `topk(3, sum by (service) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[5m])))` | The three busiest services. |
| `sum by (service, uri) (rate(http_server_requests_seconds_count{service="course-service"}[5m]))` | Which endpoints of one service get the traffic. |
| `sum by (service) (rate(http_server_requests_seconds_sum[5m])) / sum by (service) (rate(http_server_requests_seconds_count[5m]))` | **Average** latency in seconds per service. |
| `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{service="course-service"}[5m])))` | p99 latency of one service: 99% of its requests were faster than this. |
| `sum by (service) (rate(http_server_requests_seconds_bucket{le="0.5", uri!~"/actuator.*"}[5m])) / sum by (service) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[5m]))` | Share of requests that finished within 500 ms (1.0 means all). The `le` values available are 0.05, 0.1, 0.25, 0.5, 1 and 2. |

**Is it failing?**

| Query | What it tells you |
|---|---|
| `sum by (service, status) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))` | 5xx responses per second, by exact status code. |
| `sum by (service, uri) (rate(http_server_requests_seconds_count{outcome="CLIENT_ERROR"}[5m]))` | 4xx responses per endpoint. A burst of 401 or 403 can mean a broken login or token. |
| `sum by (service) (increase(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[1h]))` | How many 5xx errors each service returned in the last hour. |
| `sum by (service, level) (rate(logback_events_total{level="error"}[5m]))` | Error-level log lines per second. Useful because it shows trouble that never reaches an HTTP response, such as a failing event consumer. |

**Is memory or CPU under pressure?**

| Query | What it tells you |
|---|---|
| `sum by (service) (jvm_memory_used_bytes{area="heap"}) / sum by (service) (jvm_memory_max_bytes{area="heap"})` | Heap used as a fraction of the maximum (0.9 means 90%). Near 1 and staying there is dangerous. |
| `sum by (service) (jvm_memory_used_bytes{area="heap"}) / 1024 / 1024` | Heap used in MB, easier to read than bytes. |
| `process_cpu_usage` | CPU used by each JVM, from 0 to 1. |
| `jvm_threads_live_threads` | Thread count per service. A number that only ever climbs suggests a thread leak. |
| `sum by (service) (rate(jvm_gc_pause_seconds_count[5m]))` | How often garbage collection runs per second. |
| `jvm_classes_loaded_classes` | Loaded classes. Rarely interesting, but handy to compare services. |

**Database connections** (services with a database)

| Query | What it tells you |
|---|---|
| `hikaricp_connections_active` | Connections in use right now, per service. |
| `hikaricp_connections_active / hikaricp_connections_max` | Pool usage as a fraction. Near 1 means requests are about to wait for a connection. |
| `hikaricp_connections_pending` | Requests currently waiting for a connection. Should be 0. |

**RabbitMQ and the event pipeline**

| Query | What it tells you |
|---|---|
| `rabbitmq_detailed_queue_messages_ready` | Messages waiting, per queue. |
| `sum(rabbitmq_detailed_queue_messages_ready{queue=~".*[.]dlq"})` | Total messages in all dead-letter queues. The number to keep at 0. |
| `rabbitmq_detailed_queue_messages_ready{queue=~".*[.]dlq"} > 0` | Only the dead-letter queues that currently hold something. An empty result is the healthy answer. |
| `sum by (queue) (rate(rabbitmq_detailed_queue_messages_redelivered_total[5m]))` | Retries per second, per queue. Above 0 means a consumer is failing on some messages. |
| `sum by (queue) (increase(rabbitmq_detailed_queue_messages_acked_total[1h]))` | How many messages each queue processed in the last hour. |
| `sum by (queue) (rabbitmq_detailed_queue_messages_unacked)` | Messages handed to a consumer and not yet finished. |

**About Prometheus itself**

| Query | What it tells you |
|---|---|
| `scrape_duration_seconds` | How long each scrape took. Close to the 10 s timeout means a target is too slow. |
| `scrape_samples_scraped` | How many numbers each target returned. A sudden jump means a new metric family appeared. |
| `sum(scrape_samples_scraped)` | Total numbers collected per scrape round. This is what drives Prometheus's memory use, and what the 256 MB cap is protecting. |
| `count({__name__=~".+"})` | Count of every series stored. Can be slow, so use sparingly. |

Prometheus does not scrape itself in this setup (there is no job for it), so its own `prometheus_*`
metrics, such as `prometheus_tsdb_head_series`, are not available.

How to use them well:

1. **Start with the bare metric** (for example `http_server_requests_seconds_count`) and read the labels
   in the result. Then add filters one at a time.
2. **Use `rate()` on counters**, names ending `_total`, `_count`, `_sum` or `_bucket`. A raw counter just
   climbs forever and says little.
3. **Use `[1m]` for a quick look and `[5m]` for something steadier.** The window in square brackets must
   hold at least two scrapes, so it cannot be shorter than about 30 seconds here.
4. **A result that is empty is an answer too.** For `up == 0` or a `> 0` filter on a dead-letter queue,
   nothing returned means nothing is wrong.
5. **Division by zero gives no result, not an error.** If a service has had no requests, a percentage
   query for it returns nothing rather than 0.

### 6.5 Other useful pages

| Page | URL | Use |
|---|---|---|
| Targets | `/targets` | What is scraped and whether it works. |
| Alerts | `/alerts` | Prometheus's own alert rules. This project has none; alerts live in Grafana. |
| Configuration | `/config` (Status menu) | The config Prometheus actually loaded. |
| Runtime info | `/api/v1/status/runtimeinfo` | Retention settings and storage state. |

### 6.6 Look at the raw page a service exposes

```bash
curl -s localhost:9001/actuator/prometheus | head -20
curl -s localhost:9080/actuator/prometheus | head -20     # the gateway
```

This is exactly what Prometheus fetches. If a metric is missing in Grafana, check here first to see
whether the service produces it at all.

## 7. Worked exercise: trigger the dead-letter alert

This proves the feature does what it exists to do. Needs `rabbitmq`, `notification-service`,
`prometheus` and `grafana` running.

1. Open the **Event pipeline** dashboard. Confirm every dead-letter panel reads **0** and the alert
   **Dead-letter queue not empty** is **Normal**.
2. Publish a message that no consumer can parse. Load your `.env` values first, then:
   ```bash
   curl -s -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
     -H 'content-type: application/json' -X POST \
     'http://localhost:9673/api/exchanges/%2F/learning.events/publish' \
     -d '{"properties":{},"routing_key":"enrollment.created","payload":"{\"not\":\"a valid envelope\"}","payload_encoding":"string"}'
   ```
   It should answer `{"routed":true}`.
3. The consumer retries the message three times and then moves it to `notification.q.dlq`.
4. Within about a minute **Dead-letter depth per queue** shows `notification.q.dlq` at 1. (Measured: about
   17 seconds.)
5. Open **Alerting, Alert rules**. The rule goes **Pending**, then **Firing** after about 90 seconds in
   total (measured: 89 seconds).
6. Cross-check against the broker: RabbitMQ console at http://localhost:9673, then **Queues**, then
   `notification.q.dlq`.
7. Clean up by emptying the queue:
   ```bash
   curl -s -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" -X DELETE \
     'http://localhost:9673/api/queues/%2F/notification.q.dlq/contents'
   ```
8. The depth returns to 0 and the alert returns to **Normal** by itself (measured: about 42 seconds).

## 8. Worked exercise: a stopped service

1. Stop one service: `docker compose stop echo-service`.
2. In **Prometheus, Targets**, `echo-service` turns DOWN within one scrape (15 seconds).
3. In **Grafana, Platform health**, the **Service up** panel shows 0 for it.
4. In **Alert rules**, **Service unreachable** goes **Pending**, then **Firing** after about 2 minutes
   (measured: 138 seconds).
5. Start it again: `docker compose start echo-service`. The alert clears by itself (measured: 27 seconds).
6. For comparison, a **normal restart** (`docker compose restart echo-service`) must not alert, because
   the 2-minute window is longer than the startup time. This was tested and no alert fired.

## 9. How to change things

Always change the file, not the UI.

| I want to... | Change this | Then |
|---|---|---|
| Add a new service to monitoring | Add `<name>:<port>` to the `learnhub-services` job in `backend/docker/prometheus/prometheus.yml`. Update the expected target count in `scripts/smoke-test.sh` (`EXPECTED_TARGETS`). | `docker compose restart prometheus` |
| Change how often Prometheus scrapes | `scrape_interval` in `prometheus.yml` | `docker compose restart prometheus` |
| Keep history longer | The `--storage.tsdb.retention.time` and `.size` flags on `prometheus` in `docker-compose.yml` | `docker compose up -d prometheus` (watch the memory cap) |
| Edit a dashboard | `backend/docker/grafana/dashboards/*.json`. Tip: build the panel in the UI, then use the dashboard's **Export, Export as JSON** and paste it into the file. | `docker compose restart grafana` |
| Change an alert threshold or duration | `backend/docker/grafana/provisioning/alerting/rules.yml` (`for:` is the duration) | `docker compose restart grafana` |
| Send alerts to email or Slack | Not set up. Needs a Grafana contact point. Write a spec first. | n/a |
| Change the Grafana password | `GRAFANA_ADMIN_PASSWORD` in `.env`. Grafana only reads it on first start, so for an existing install either change it in the UI under your profile or run `docker compose down -v` (this also wipes data). | n/a |

After any change, run `scripts/smoke-test.sh` and `mvn -f backend/pom.xml -pl infra-tests verify`.

## 10. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `scripts/up.sh` stops and names a missing setting | `GRAFANA_ADMIN_USER` or `GRAFANA_ADMIN_PASSWORD` is missing from `.env`. Add it. |
| `scripts/up.sh` says a port is in use | Something else uses 9090 or 9030. Stop it, or find it with `netstat -ano \| findstr :9090`. |
| Grafana does not open | `docker compose ps grafana`, then `docker compose logs grafana`. It starts only after Prometheus is healthy. |
| Wrong password | Grafana saved the password from its first start. See the last row of section 9. |
| A target is DOWN | Read its **Error** on the Targets page (section 6.1). Most often the container is simply not running. |
| Target says `401` | Discovery/config server outside the `local` profile. Uncomment the `basic_auth` block in `prometheus.yml` and provide credentials. |
| Dashboard panel says "No data" | (a) The service is down - check **Service up**. (b) No traffic yet - generate some. (c) Time range is too short. (d) Check the metric exists with `curl .../actuator/prometheus`. |
| Only one service has data | The others are not running, or not in `prometheus.yml`. |
| Alert stays Pending | That is normal until the "for how long" time passes. |
| Service-unreachable alert fires for services you never started | Expected when running a reduced set of services. |
| My dashboard edit disappeared | By design (section 5.7). Edit the JSON file. |
| Docker is slow or won't start | Memory. Run fewer services (section 4.3) or free RAM. Prometheus plus Grafana use roughly 235 MB combined. |

## 11. Gotchas we hit while building it

Useful background if you extend this later.

1. **A profile file replaces a list; it does not merge.** `application-local.yml` listing
   `health,info,refresh` hid `prometheus` until it was added there too.
2. **Discovery and config servers read their own local config**, not `config-repo`, so they needed
   their own change and a rebuilt image.
3. **RabbitMQ's plain `/metrics` page only has broker-wide totals.** It cannot tell you which queue holds
   messages. Per-queue numbers come from `/metrics/detailed?family=...`, and those metric names are
   prefixed `rabbitmq_detailed_`. A query written with the short name silently returns nothing.
4. **Latency percentiles need histogram buckets**, which Spring does not record by default.
5. **Gateway requests are labelled `uri="UNKNOWN"`.** Per-route latency for the gateway is not
   available. This is a property of the metric, not a bug in the dashboard.
6. **The Docker images are tiny** and have `wget` but not `curl`, so the healthchecks use `wget`.
7. **The Docker Desktop memory setting** needs a contiguous block of free RAM. On a 16 GB machine,
   raising it while many apps are open failed with a Hyper-V "not enough memory" error.
8. **Actuator calls skew the numbers.** Health checks hit every service constantly, so the request-rate and
   latency panels filter out `/actuator*`.

## 12. What is not included

- **Distributed tracing** (OpenTelemetry, Tempo). Needs trace context threaded through the shared event
  envelope and every consumer. A much larger change.
- **Log aggregation** (Loki, ELK). Logs are still read with `docker compose logs`.
- **Alert delivery** to email or chat. Alerts are visible in Grafana only.
- **Long-term storage.** Only 24 hours are kept, on purpose, to protect memory.
- **Custom business metrics**, such as outbox lag. This would need per-service code and was deferred.

Adding any of these needs its own spec and, for new technology, a constitution amendment.

## 13. Where everything lives

```
backend/platform-common/pom.xml                      the one dependency
backend/config-repo/application.yml                  endpoint + latency buckets (all services)
backend/config-repo/application-local.yml            endpoint for the local profile
backend/docker/prometheus/prometheus.yml             what to scrape
backend/docker/grafana/provisioning/                 data source, dashboard loader, alert rules
backend/docker/grafana/dashboards/                   the two dashboards (JSON)
docker-compose.yml                                   prometheus and grafana services
.env.example                                         Grafana login settings
scripts/up.sh                                        start-up + port/setting preflight
scripts/smoke-test.sh                                monitoring checks
backend/infra-tests/.../ComposeFileTest.java         compose-file checks
specs/010-observability/                             spec, plan, research, contracts, quickstart, tasks
.specify/memory/constitution.md                      v1.4.0 amendment
```

Further reading: `specs/010-observability/research.md` explains why each choice was made, and
`specs/010-observability/quickstart.md` is the full validation walkthrough.

---

## 14. Appendix: the code changes, file by file

Everything below is what was added for spec 010. Lines starting with `+` are new. Comments are trimmed
in places; the real files carry longer explanations. Summary of how much code this took:

| Kind | Files | Notes |
|---|---|---|
| Java | 1 test file | `ComposeFileTest.java`. **No production Java changed.** No service has metrics code. |
| Maven | 1 | `platform-common/pom.xml` (one dependency) |
| Spring config (YAML) | 4 | `config-repo/application.yml`, `application-local.yml`, and the local config of config-server and discovery-server |
| Docker Compose | 1 | `docker-compose.yml` |
| Prometheus config | 1 new | `backend/docker/prometheus/prometheus.yml` |
| Grafana config | 5 new | data source, dashboard loader, alert rules, two dashboard JSON files |
| Shell scripts | 2 | `scripts/up.sh`, `scripts/smoke-test.sh` |
| Environment | 1 | `.env.example` |
| Governance and docs | several | constitution, README, HOWTORUN, ARCHITECTURE, this document |

Order of the sections follows the order the data travels: service, then Spring config, then Prometheus,
then Grafana, then Compose, then scripts and tests.

### 14.1 `backend/platform-common/pom.xml` (Maven) - the one dependency

This is the heart of the feature. Every service already depends on `platform-common`, so adding the
library here gives all of them the scrape endpoint.

```xml
+    <dependency>
+      <groupId>io.micrometer</groupId>
+      <artifactId>micrometer-registry-prometheus</artifactId>
+    </dependency>
```

- **No `<version>`.** The Spring Boot parent manages Micrometer's version, so the rule "versions live
  only in the parent POM" still holds.
- **Compile scope, not `<optional>`.** The `<optional>` block in this file exists to keep servlet classes
  away from the reactive `api-gateway`. This library has no servlet classes, so it is safe to share.

### 14.2 `backend/config-repo/application.yml` (Spring config) - expose the endpoint and add latency buckets

This file is served by config-server to the business services and the gateway.

```yaml
 management:
   endpoints:
     web:
       exposure:
-        include: health,info
+        include: health,info,prometheus
+  metrics:
+    distribution:
+      slo:
+        "[http.server.requests]": 50ms,100ms,250ms,500ms,1s,2s
```

- `include: ...,prometheus` makes `/actuator/prometheus` reachable over HTTP. Having the library on the
  classpath is not enough; Actuator endpoints are hidden unless listed.
- `slo` defines the histogram buckets for request timing. Without it Spring records only a count and a
  sum, so the p95 panel would return nothing. Six buckets keep the number of time series small, which
  matters under the 256 MB cap. The bracket syntax `"[http.server.requests]"` is required because the
  metric name contains dots. This setting is read at startup, so a change needs a service restart.

### 14.3 `backend/config-repo/application-local.yml` (Spring config) - keep the profile in step

```yaml
-        include: health,info,refresh
+        include: health,info,refresh,prometheus
```

A profile file **replaces** the list from `application.yml`; it does not add to it. `local` is the
profile used in development, so without this line the endpoint would be closed exactly where it is needed.
This was the easiest mistake to make in the whole feature.

### 14.4 `backend/discovery-server/.../application.yml` and `backend/config-server/.../application.yml`

Same one-line change in each:

```yaml
-        include: health,info
+        include: health,info,prometheus
```

These two servers configure themselves locally and never read `config-repo` (the project's constitution
requires this, because config-server cannot depend on itself). So they do not receive the change above and
need their own. Their images were rebuilt after the edit.

### 14.5 `backend/docker/prometheus/prometheus.yml` (new) - what to scrape

```yaml
global:
  scrape_interval: 15s
  scrape_timeout: 10s
  external_labels:
    platform: learnhub

scrape_configs:
  - job_name: learnhub-services
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - auth-service:9001
          - course-service:9002
          - enrollment-service:9003
          - payment-service:9004
          - certificate-service:9005
          - review-service:9006
          - notification-service:9007
          - analytics-service:9008
          - api-gateway:9080
          - echo-service:9099
    relabel_configs:
      - source_labels: [__address__]
        regex: '([^:]+):.*'
        target_label: service
        replacement: '$1'

  - job_name: learnhub-platform
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - discovery-server:9761
          - config-server:9888
    relabel_configs:
      - source_labels: [__address__]
        regex: '([^:]+):.*'
        target_label: service
        replacement: '$1'

  - job_name: rabbitmq-aggregated
    metrics_path: /metrics
    static_configs:
      - targets: [rabbitmq:15692]
    relabel_configs:
      - target_label: service
        replacement: rabbitmq

  - job_name: rabbitmq-queues
    metrics_path: /metrics/detailed
    params:
      family: [queue_coarse_metrics, queue_delivery_metrics]
    static_configs:
      - targets: [rabbitmq:15692]
    relabel_configs:
      - target_label: service
        replacement: rabbitmq
```

Points worth understanding:

- **Targets use Compose DNS names and internal ports**, never `localhost` or a published host port.
  `auth-service:9001` works from inside the Docker network.
- **The `relabel_configs` block** copies the host part of the address (`auth-service`) into a label
  called `service`. By default Prometheus identifies a target by `host:port`, which is unreadable in a
  graph legend. Every dashboard query groups by `service`.
- **Two RabbitMQ jobs.** The plain `/metrics` page has only broker-wide totals. The `/metrics/detailed`
  page, with the `family` parameter, gives per-queue values and is the only way to see each DLQ.
- RabbitMQ's Prometheus plugin was already enabled in the image, so RabbitMQ itself needed no change.

### 14.6 `backend/docker/grafana/provisioning/datasources/prometheus.yml` (new) - connect Grafana to Prometheus

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
    jsonData:
      timeInterval: 15s
      httpMethod: POST
```

- `uid: prometheus` is fixed on purpose. The dashboards and alert rules refer to the data source by this
  id. If it were left to default it would be random and every reference would point at nothing.
- `url` is the internal Compose address.
- `editable: false` stops anyone changing it from the UI.

### 14.7 `backend/docker/grafana/provisioning/dashboards/dashboards.yml` (new) - load the dashboard files

```yaml
apiVersion: 1
providers:
  - name: learnhub
    orgId: 1
    folder: LearnHub
    type: file
    allowUiUpdates: false
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
```

This tells Grafana to read every JSON file in `/var/lib/grafana/dashboards` (the folder mounted by
Compose, see 14.9) and put them in a folder called **LearnHub**. `allowUiUpdates: false` is what makes
UI edits non-persistent.

### 14.8 Dashboards and alert rules (new)

**Dashboard JSON** - `backend/docker/grafana/dashboards/platform-health.json` and `event-pipeline.json`.
Each is a list of panels. This is the first panel of Platform health, with the structure every panel shares:

```json
{
  "uid": "learnhub-platform-health",
  "title": "LearnHub - Platform health",
  "editable": false,
  "refresh": "15s",
  "time": { "from": "now-30m", "to": "now" },
  "panels": [
    {
      "id": 1,
      "type": "stat",
      "title": "Service up",
      "gridPos": { "h": 5, "w": 24, "x": 0, "y": 0 },
      "datasource": { "type": "prometheus", "uid": "prometheus" },
      "targets": [
        {
          "refId": "A",
          "expr": "up{job=~\"learnhub-.*\"}",
          "legendFormat": "{{service}}",
          "instant": true
        }
      ],
      "fieldConfig": {
        "defaults": {
          "mappings": [
            { "type": "value", "options": {
                "0": { "text": "DOWN", "color": "red" },
                "1": { "text": "UP", "color": "green" } } }
          ]
        }
      }
    }
  ]
}
```

How to read it: `expr` is the PromQL query, `legendFormat` labels each line with the `service` label,
`gridPos` places the panel on a 24-column grid, and `mappings` turns the raw 0/1 into the words DOWN/UP.

The queries behind the other panels are listed in section 6.4. The ones in the dashboards are:

| Dashboard | Panel | Query |
|---|---|---|
| Platform health | Request rate | `sum by (service) (rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m]))` |
| Platform health | Latency p95 | `histogram_quantile(0.95, sum by (service, le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m])))` |
| Platform health | Server error % | `100 * sum by (service) (rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m])) / sum by (service) (rate(http_server_requests_seconds_count[5m]))` |
| Platform health | JVM heap | `sum by (service) (jvm_memory_used_bytes{area="heap"})` and the same for `jvm_memory_committed_bytes` |
| Platform health | GC pause | `sum by (service) (rate(jvm_gc_pause_seconds_sum[5m]))` |
| Event pipeline | Dead-letter depth | `sum by (queue) (rabbitmq_detailed_queue_messages_ready{queue=~".*[.]dlq"})` |
| Event pipeline | Messages ready | same query with `queue!~".*[.]dlq"` |
| Event pipeline | Throughput | `sum by (queue) (rate(rabbitmq_detailed_queue_messages_acked_total[1m]))` |
| Event pipeline | Redeliveries | `sum by (queue) (rate(rabbitmq_detailed_queue_messages_redelivered_total[1m]))` |
| Event pipeline | Unacknowledged | `sum by (queue) (rabbitmq_detailed_queue_messages_unacked)` |

**Alert rules** - `backend/docker/grafana/provisioning/alerting/rules.yml`. Every rule has the same
three steps: **A** runs the query, **B** reduces each series to its latest value, **C** compares it with
a threshold. This is the first rule in full:

```yaml
apiVersion: 1
groups:
  - orgId: 1
    name: learnhub-operations
    folder: LearnHub
    interval: 30s
    rules:
      - uid: learnhub-dlq-not-empty
        title: Dead-letter queue not empty
        condition: C
        data:
          - refId: A
            relativeTimeRange: { from: 300, to: 0 }
            datasourceUid: prometheus
            model:
              refId: A
              datasource: { type: prometheus, uid: prometheus }
              expr: sum by (queue) (rabbitmq_detailed_queue_messages_ready{queue=~".*[.]dlq"})
              instant: false
              range: true
          - refId: B
            datasourceUid: __expr__
            model:
              refId: B
              type: reduce
              expression: A
              reducer: last
              datasource: { type: __expr__, uid: __expr__ }
          - refId: C
            datasourceUid: __expr__
            model:
              refId: C
              type: threshold
              expression: B
              datasource: { type: __expr__, uid: __expr__ }
              conditions:
                - evaluator: { type: gt, params: [0] }
        noDataState: OK
        execErrState: Error
        for: 1m
        labels: { severity: critical }
```

The other two rules differ only in the query and the numbers:

| Rule | `expr` (step A) | Threshold (step C) | `for` |
|---|---|---|---|
| Service unreachable | `up{job=~"learnhub-.*"}` | below 1 | `2m` |
| Server error rate elevated | the server-error-percentage query from the table above | above 5 | `5m` |

- `for: 1m` is the "Pending" window: the condition must stay true this long before the alert fires.
- `noDataState: OK` means "no series" is not treated as a problem. It simply means no DLQ exists yet.
- The alert clears by itself because the rule is re-evaluated every `interval: 30s`. When the query stops
  matching, the state returns to Normal. No code is needed for that.
- The DLQ rule matches any queue ending in `.dlq`, so a future consumer that follows the naming
  convention is covered with no change.

### 14.9 `docker-compose.yml` - two new services and two volumes

```yaml
+  prometheus:
+    image: prom/prometheus:v3.1.0
+    command:
+      - --config.file=/etc/prometheus/prometheus.yml
+      - --storage.tsdb.path=/prometheus
+      - --storage.tsdb.retention.time=24h
+      - --storage.tsdb.retention.size=512MB
+      - --web.enable-lifecycle
+    ports:
+      - "9090:9090"
+    volumes:
+      - ./backend/docker/prometheus:/etc/prometheus:ro
+      - prometheus-data:/prometheus
+    mem_limit: 256m
+    healthcheck:
+      test: ["CMD", "wget", "-qO-", "http://localhost:9090/-/healthy"]
+      interval: 10s
+      timeout: 5s
+      retries: 10
+      start_period: 15s
+
+  grafana:
+    image: grafana/grafana:11.5.1
+    environment:
+      GF_SECURITY_ADMIN_USER: ${GRAFANA_ADMIN_USER:?GRAFANA_ADMIN_USER is required; copy .env.example to .env}
+      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD is required; copy .env.example to .env}
+      GF_ANALYTICS_REPORTING_ENABLED: "false"
+      GF_ANALYTICS_CHECK_FOR_UPDATES: "false"
+      GF_USERS_ALLOW_SIGN_UP: "false"
+    ports:
+      - "9030:3000"
+    volumes:
+      - ./backend/docker/grafana/provisioning:/etc/grafana/provisioning:ro
+      - ./backend/docker/grafana/dashboards:/var/lib/grafana/dashboards:ro
+      - grafana-data:/var/lib/grafana
+    mem_limit: 192m
+    depends_on:
+      prometheus:
+        condition: service_healthy
+    healthcheck:
+      test: ["CMD", "wget", "-qO-", "http://localhost:3000/api/health"]
+      interval: 10s
+      timeout: 5s
+      retries: 10
+      start_period: 20s
```

and at the bottom, with the other volumes:

```yaml
 volumes:
   postgres-data:
   rabbitmq-data:
+  prometheus-data:
+  grafana-data:
```

What each part is for:

| Setting | Why |
|---|---|
| `retention.time=24h` and `retention.size=512MB` | Both limits, so history is bounded by age **and** by size. |
| `mem_limit` | 256 MB and 192 MB. The Docker VM is small, so the monitoring stack must not take memory from the services. |
| `:ro` on the config mounts | The running tool cannot rewrite its own configuration. A restart always restores what is in Git. |
| `wget` in the healthchecks | These images are minimal and have no `curl`. |
| `${VAR:?message}` for the Grafana login | Compose refuses to start if the value is missing **or empty**, so Grafana can never silently fall back to `admin/admin`. |
| `depends_on ... service_healthy` | Grafana starts only once Prometheus answers. |
| Named volumes | History and Grafana state survive a restart and are wiped by `docker compose down -v`. Dashboards and alerts are not in the volumes, because they come from files and reappear immediately. |

### 14.10 `.env.example`

```bash
+GRAFANA_ADMIN_USER=admin
+GRAFANA_ADMIN_PASSWORD=change-me-grafana-password
```

Copy these into your own `.env` with a real password. `.env` is git-ignored; never commit it.

### 14.11 `scripts/up.sh` - preflight

```bash
 PORTS=(
   ...
   "9100:frontend"
+  "9090:prometheus"
+  "9030:grafana"
 )

 REQUIRED_SETTINGS=(
   ...
   JWT_SIGNING_SECRET ADMIN_EMAIL ADMIN_PASSWORD
+  GRAFANA_ADMIN_USER GRAFANA_ADMIN_PASSWORD
 )
```

The first list is checked before startup so that a clash on 9090 or 9030 is reported with a clear message.
The second makes the script stop and name any required setting that is missing or empty.

### 14.12 `scripts/smoke-test.sh` - automated checks

```bash
# Prometheus is up
if curl -fsS http://localhost:9090/-/healthy >/dev/null 2>&1; then
  pass "Prometheus answers on 9090"
else
  fail "Prometheus answers on 9090" "no response from http://localhost:9090/-/healthy"
fi

EXPECTED_TARGETS=14

targets_json="$(curl -fsS "http://localhost:9090/api/v1/targets?state=active" 2>/dev/null)"
if [ -z "$targets_json" ]; then
  fail "Prometheus reports its scrape targets" "could not read /api/v1/targets"
else
  target_total="$(printf '%s' "$targets_json" | grep -o '"health":"[a-z]*"' | wc -l | tr -d ' ')"
  target_up="$(printf '%s' "$targets_json" | grep -o '"health":"up"' | wc -l | tr -d ' ')"

  if [ "$target_total" -eq "$EXPECTED_TARGETS" ]; then
    pass "Prometheus is scraping all $EXPECTED_TARGETS expected targets"
  else
    fail "Prometheus is scraping all $EXPECTED_TARGETS expected targets" \
      "found $target_total; a service may be missing from backend/docker/prometheus/prometheus.yml"
  fi

  if [ "$target_up" -eq "$target_total" ] && [ "$target_total" -gt 0 ]; then
    pass "every Prometheus scrape target reports up"
  else
    # ... prints which host:port values are not up
    fail "every Prometheus scrape target reports up" "$target_up of $target_total up; not up: ..."
  fi
fi

if curl -fsS http://localhost:9030/api/health >/dev/null 2>&1; then
  pass "Grafana answers on 9030"
else
  fail "Grafana answers on 9030" "no response from http://localhost:9030/api/health"
fi
```

There are two separate checks on targets on purpose. "Every target is up" alone would pass if a service
were missing from `prometheus.yml` entirely, because there would be nothing to be down. Counting the
targets as well catches that gap. This was tested by commenting out one target and confirming the test
failed.

### 14.13 `backend/infra-tests/.../ComposeFileTest.java` - the only Java

The project is test-first, so these tests were written before the Compose services. The existing
`everyLongRunningServiceHasHealthcheck()` test walks every service in the file, so it also covers
Prometheus and Grafana without a change.

Two existing tests got extra assertions:

```java
// hostPortsMatchTheConstitution()
+        assertThat(ports("prometheus")).contains("9090:9090");
+        assertThat(ports("grafana")).contains("9030:3000");

// dataUsesNamedVolumes()
-                .containsKeys("postgres-data", "rabbitmq-data");
+                .containsKeys("postgres-data", "rabbitmq-data", "prometheus-data", "grafana-data");
+        assertThat(service("prometheus").get("volumes").toString()).contains("prometheus-data");
+        assertThat(service("grafana").get("volumes").toString()).contains("grafana-data");
```

Two new tests:

```java
@Test
@DisplayName("the monitoring stack is capped, bounded and cannot rewrite its own configuration")
void monitoringStackIsConstrained() {
    assertThat(service("prometheus").get("mem_limit")).as("prometheus is capped").isNotNull();
    assertThat(service("grafana").get("mem_limit")).as("grafana is capped").isNotNull();

    String prometheusCommand = String.valueOf(service("prometheus").get("command"));
    assertThat(prometheusCommand)
            .contains("--storage.tsdb.retention.time=")
            .contains("--storage.tsdb.retention.size=");

    assertThat(service("prometheus").get("volumes").toString())
            .as("prometheus config is mounted read-only").contains(":ro");
    assertThat(service("grafana").get("volumes").toString())
            .as("grafana provisioning is mounted read-only").contains(":ro");

    assertThat(dependsOn("grafana")).containsKey("prometheus");
    assertThat(dependsOn("grafana").get("prometheus").toString()).contains("service_healthy");
}

@Test
@DisplayName("Grafana credentials come from the environment with no default")
void grafanaCredentialsAreRequiredSettings() {
    assertThat(raw).contains("${GRAFANA_ADMIN_USER:?").contains("${GRAFANA_ADMIN_PASSWORD:?");
}
```

These tests read `docker-compose.yml` as text and check its structure; they do not start containers, so
they run fast. They pin the decisions that are easiest to break by accident: someone deleting a memory cap,
dropping `:ro`, or giving Grafana a default password.

### 14.14 Not changed, and why it matters

- **No production Java.** No controller, service or configuration class in any of the business services
  was touched. This is the rule "no per-service metrics wiring", and `platform-common` plus YAML satisfy it.
- **`NoWiringCodeTest`** was not extended. It scans registration, config and health code only, so it
  does not enforce the metrics rule. The rule is held by the fact that nothing was added; the quickstart
  step 2 greps for `micrometer|prometheus` under `backend/*/src/main/` to confirm that.
- **RabbitMQ** needed no change: its Prometheus plugin was already enabled in the image.
