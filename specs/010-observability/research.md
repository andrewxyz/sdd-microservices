# Research: Platform Observability

**Feature**: `010-observability` | **Date**: 2026-11-06 | **Spec**: [spec.md](./spec.md)

Each decision below records what was verified and how, because two of them corrected an assumption
the spec carried in. Items still unverified are marked **GAP** and have a verification task in
`tasks.md` that must pass before dependent work is written.

---

## R1. Where the scrape endpoint comes from

**Decision**: add `micrometer-registry-prometheus` to `backend/platform-common/pom.xml` in the
compile-scope `<dependencies>` block, with no `<version>`.

**Rationale**: verified by grepping every module POM — all 13 runnable modules already declare both
`platform-common` and `spring-boot-starter-actuator`:

| | |
|---|---|
| Declare both | auth, course, enrollment, payment, certificate, review, notification, analytics, api-gateway, echo-service, discovery-server, config-server |
| Declare neither | `shared-events`, `infra-tests` (not runnable services) |

So one dependency line reaches every service and no service needs metrics code of its own.

**Correction (2026-11-06):** an earlier draft said `NoWiringCodeTest` enforces this. It does not: that
test scans for registration, config and health code (`@EnableEurekaClient`, `HealthIndicator`, ...) and
never looks for metrics code. The evidence for FR-002 is the grep in task T008
(`MeterRegistry|MeterBinder|micrometer.core|micrometer.registry|prometheus` over `backend/*/src/main`),
which returns nothing. A guard in `NoWiringCodeTest` was considered and not added: forbidding
`MeterRegistry` outright would also block legitimate future business counters, which VIII does not ask for.

No `<version>` is correct and required: `spring-boot-starter-parent` 3.5.15 is the POM's parent and
manages the Micrometer BOM, so the version resolves from it. Principle IX ("All versions MUST be
defined in the parent POM and nowhere else") is satisfied without adding a pin.

**Why the compile block and not the `<optional>` block**: `platform-common` deliberately marks
`jakarta.servlet-api`, `spring-webmvc` and `feign-core` optional, with a comment explaining that the
WebFlux `api-gateway` depends on this module and must not gain servlet classes.
`micrometer-registry-prometheus` is web-framework-agnostic — it brings `micrometer-core` and
`prometheus-metrics-*` only — so it cannot confuse Spring Boot's web-application-type detection and
belongs in the normal compile block where every consumer inherits it.

**Alternatives considered**:

- *Add it to each service POM* — 13 edits instead of 1, and each new service would have to remember
  it. Fails FR-002.
- *Add it to a shared `<dependencies>` block in the parent POM* — the parent currently has only
  `<dependencyManagement>` and no shared `<dependencies>`. Introducing one would push the registry
  onto `shared-events` and `infra-tests` too, which are not services. Rejected as less precise.

---

## R2. Exposing the endpoint

**Decision**: change `backend/config-repo/application.yml` from `include: health,info` to
`include: health,info,prometheus`, and `application-local.yml` from `health,info,refresh` to
`health,info,refresh,prometheus`.

**Rationale**: that shared file is delivered to every config client, so this is one edit for the whole
platform. Because the config server serves the working tree and services poll every 5 s, the change
takes effect in about 10 s with no rebuild and no restart.

**Correction (2026-11-06): not every service reads this file.** `discovery-server` and `config-server`
are configured locally by Principle III, so they never load `config-repo`. Each carries its own
`management.endpoints.web.exposure.include: health,info`, and the shared change did not reach them: a live
scrape showed `discovery-server` answering 404 and `config-server` answering its own JSON (its
`/{application}/{profile}` handler read `/actuator/prometheus` as an application named `actuator`). The
fix is one `prometheus` entry in each server's own `application.yml`. That is a configuration line, not
wiring code, and both servers are explicitly exempt from `NoWiringCodeTest`; but it means FR-002's
"no service-specific configuration" holds for the ten business services and the gateway, not for these
two.

Both `config-repo` files must change: `application-<profile>.yml` has higher precedence than `application.yml`
(stated in the header comment of `application.yml`), so under the `local` profile the profile file's
`include` list wins outright. Editing only the base file would leave the endpoint closed in exactly
the profile used for development — the mistake worth calling out.

---

## R3. Protecting the endpoint outside `local`

**Decision**: rely on Principle VII's existing rule, now extended to the metrics endpoint by the
amended Principle VIII. Under `local` the endpoint is open; outside `local` it requires basic auth.
No new mechanism is introduced in this spec.

**Rationale**: Principle VII already requires basic auth on Eureka and the config server in every
profile other than `local`, exempting only `/actuator/health/**`. The metrics endpoint reveals
request paths, error counts and JVM internals, so it belongs on the protected side of that line.

**Consequence for the scrape config**: `prometheus.yml` needs a commented `basic_auth` block showing
what non-local deployment requires. The stack runs under `local` (`.env.example` sets
`SPRING_PROFILES_ACTIVE=local`), so no credentials are needed today — but leaving the file silent
about it would hide a real deployment step.

---

## R4. How Prometheus finds the services

**Decision**: `static_configs` in a new `backend/docker/prometheus/prometheus.yml`, one target per
service by Compose DNS name and internal port, `scrape_interval: 15s`.

**Rationale**: Prometheus has no Eureka service-discovery mechanism, so Eureka cannot be reused even
though the platform already runs it. The realistic options were a static list, DNS-based discovery on
the Compose network, or an HTTP discovery adapter that reads Eureka. The Compose topology is fixed and
known at start time, so the static list is the simplest thing that works, which Principle X requires.
Targets use internal ports over the Compose network, so this keeps working if `sec-002` (stop
publishing internal service ports to the host) is later implemented.

**Accepted cost**: adding a future service means editing `prometheus.yml` as well as
`docker-compose.yml`. FR-021's smoke-test assertion turns that omission into a test failure rather
than a silent monitoring gap, and the README "Adding a service" checklist gains the step.

**Targets** (12 Spring services + RabbitMQ):

| Target | Address |
|---|---|
| auth, course, enrollment, payment, certificate, review, notification, analytics | `<name>-service:900{1..8}` |
| api-gateway | `api-gateway:9080` |
| echo-service | `echo-service:9099` |
| discovery-server | `discovery-server:9761` |
| config-server | `config-server:9888` |
| rabbitmq | `rabbitmq:15692` — see R5 for the path |

---

## R5. RabbitMQ metrics — assumption corrected

The spec assumed "the message broker can already report its own metrics", flagged "to be confirmed
during planning". Confirmed against <https://www.rabbitmq.com/docs/prometheus>, and **one part of the
plan's original approach was wrong**.

**Verified**:

- The plugin serves metrics on TCP **15692** by default (`prometheus.tcp.port = 15692`), on path
  **`/metrics`**.
- Authentication is **off** by default; `prometheus.authentication.enabled = true` turns it on. So no
  credentials are needed to scrape, and no host port needs publishing — Prometheus reaches 15692 over
  the Compose network.

**Corrected — this is the important finding**: `GET /metrics` returns **aggregated** metrics, not
per-queue ones. Scraping it would give totals across all queues and therefore **could not satisfy
FR-008 or User Story 1 at all**, because a non-empty dead-letter queue has to be attributable to a
named queue. Three ways to get per-queue data:

| Option | Behaviour | Verdict |
|---|---|---|
| `prometheus.return_per_object_metrics = true` | changes `/metrics` itself to per-object | needs broker config change; makes the default endpoint expensive |
| `GET /metrics/per-object` | always per-object, ignores the setting above | works, but returns every metric family for every object |
| `GET /metrics/detailed?family=queue_coarse_metrics` | returns nothing unless `family` is given; docs state it is "up to 60 times more efficient" than `/metrics/per-object` for a small number of metrics | **chosen** |

**Decision**: scrape **two** RabbitMQ targets, which is why this is a design decision and not a
detail:

1. `rabbitmq:15692/metrics` — aggregated broker health (connections, churn, memory). Cheap.
2. `rabbitmq:15692/metrics/detailed` with `params: {family: [queue_coarse_metrics, queue_delivery_metrics]}`
   — per-queue depth for FR-008 and the dead-letter alert, plus per-queue acked/delivered/redelivered
   counters for the throughput panel. Two families, not all: the broader `queue_metrics` is 41 series for
   two queues against 7 for `queue_delivery_metrics`, which matters under a 256 MB cap.

Metrics from the detailed endpoint carry the prefix **`rabbitmq_detailed_`** rather than `rabbitmq_`
(documented), so dashboard and alert queries must use the detailed names. Writing
`rabbitmq_queue_messages_ready` against the detailed endpoint is the trap here and would silently
return no data.

**Both gaps CLOSED on 2026-11-06 against a live stack:**

- The plugin **is** enabled in `rabbitmq:4.3-management` (`[E*] rabbitmq_prometheus 4.3.7`).
- The metric names are `rabbitmq_detailed_queue_messages_ready` and `..._unacked` with labels `queue` and
  `vhost`, exactly as expected; see [contracts §4](./contracts/scrape-contract.md).

---

## R6. Dashboards and alerts as committed configuration

**Decision**: Grafana file-based provisioning under `backend/docker/grafana/`, mounted read-only:

```text
provisioning/datasources/prometheus.yml    # Prometheus as the default datasource
provisioning/dashboards/dashboards.yml     # provider pointing at the dashboards directory
provisioning/alerting/rules.yml            # alert rules + a notification policy
dashboards/platform-health.json
dashboards/event-pipeline.json
```

**Rationale**: FR-011 and FR-016 require definitions that are committed and provisioned at startup,
and that a restart restores over any hand edit. File provisioning does exactly that. Mounting the
directory `:ro` mirrors how `config-server` already mounts `config-repo` read-only, and gives the
same guarantee for the same reason — the running tool cannot rewrite its own source of truth.

**Alternatives considered**: creating dashboards in the UI and exporting JSON by hand (fails FR-011 —
the UI becomes the real source); Grafana's HTTP API driven by a provisioning script (more moving
parts, and a failed call leaves a half-configured instance).

**Alerting choice**: Grafana-managed alert rules rather than Prometheus `alerting_rules` with
Alertmanager. Decided with the user. Alertmanager would add a third container and a third port on a
host that has under 4 GB; the spec's assumption section already limits this feature to alerts that
fire visibly in-tool, with no email, chat or pager delivery. Prometheus keeps its rule-evaluation
capability unused for now, which is acceptable and reversible.

---

## R7. Resource budget

**Decision**: both services always-on (no Compose profile), with `mem_limit: 256m` on Prometheus and
`mem_limit: 192m` on Grafana, and Prometheus started with
`--storage.tsdb.retention.time=24h --storage.tsdb.retention.size=512MB`.

**Rationale**: decided with the user. Always-on is what FR-019 requires and it is what lets the smoke
test check the monitoring on every run (FR-021); a Compose profile would have made the default stack
unmonitored and the assertion conditional. The caps come from the host: under 4 GB of VM memory
already running 15 containers, and this session hit a Hyper-V out-of-memory while trying to raise
that. 256 MB + 192 MB = 448 MB, inside SC-006's 500 MB budget with headroom to spare.

Both retention bounds are set, not just time: `retention.time` alone leaves size unbounded if scrape
volume grows, and SC-008 asks for a bounded size indefinitely. 14 scrape targets at 15 s will not
approach 512 MB in 24 h, so the size bound is a guard rather than the operative limit.

**Risk accepted and stated**: if the stack becomes unstartable on this host, moving both services
behind a `profiles: ["monitoring"]` entry is a two-line change, at the cost of relaxing FR-019.

---

## R8. Health checks for the new containers

**Decision**: `wget`-based health checks on both.

- Prometheus: `wget -qO- http://localhost:9090/-/healthy`
- Grafana: `wget -qO- http://localhost:3000/api/health`

**Rationale**: not optional — `ComposeFileTest.everyLongRunningServiceHasHealthcheck()` iterates every
service in `docker-compose.yml` and fails any without one, exempting only the one-shot `db-init`. So
omitting these breaks an existing test.

`wget` rather than `curl` because `prom/prometheus` and `grafana/grafana` are built on minimal
busybox/Alpine bases where `wget` is present and `curl` may not be; the existing `frontend` service
already uses `wget` in its health check for the same reason. Grafana also gets
`depends_on: prometheus: condition: service_healthy`, so it never starts against a datasource that is
not yet answering.

**GAP — low risk**: that `wget` exists in both images is inferred from their base images, not verified.
The health check failing to start is loud and immediate, so this is cheap to correct during
implementation.

---

## R9. Existing tests that must be extended

Checked `backend/infra-tests/.../ComposeFileTest.java` directly, because adding Compose services
touches assertions that iterate the whole file:

| Test | Effect of this feature |
|---|---|
| `everyLongRunningServiceHasHealthcheck()` | iterates all services — **breaks** without R8's health checks |
| `hostPortsMatchTheConstitution()` | asserts specific mappings — **extend** with `9090:9090` and `9030:3000` |
| `dataUsesNamedVolumes()` | asserts declared volumes — **extend** with `prometheus-data`, `grafana-data` |
| `reservedPortsAreNotUsed()` | guards only 9003–9007 — passes unchanged (9090 and 9030 are outside) |
| `requiredSettingsFailFast()` | checks a fixed list of `${VAR:?}` names — passes unchanged; the Grafana credentials may be added to it |

---

## R10. Grafana credentials and the existing `.env`

**Decision**: add `GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD` to `.env.example` and to the
`REQUIRED_SETTINGS` array in `scripts/up.sh`, using the `${VAR:?message}` form in Compose.

**Rationale**: FR-012 requires credentials with no default or blank password, and this is how every
other credential in the project is handled. Anonymous access or Grafana's `admin`/`admin` default
would both fail FR-012.

**Known friction, stated deliberately**: the preflight in `scripts/up.sh` rejects any required setting
that is missing or empty, so **the user's existing `.env` will fail the next `scripts/up.sh` until
they add these two lines.** This is the same situation spec 009 created with `ANALYTICS_DB_PASSWORD`,
and the error names the missing setting, so it is self-explaining. It must be called out in the
completion notes rather than discovered at startup.

---

## R11. Outbox lag — deferred

**Decision**: do not implement custom outbox-lag gauges in this feature.

**Rationale**: considered as a stretch goal. A gauge over the unpublished-outbox-row count would need
a `MeterBinder` inside `payment-service`, `enrollment-service` and `certificate-service` — per-service
metrics code, which is precisely what FR-002 and the amended Principle VIII forbid. Making it generic
enough to live in `platform-common` would mean the shared library knowing about outbox table shapes,
which Principle II forbids (no domain logic in a shared library).

Queue depth and DLQ depth from R5 already cover the failure mode outbox lag would warn about — events
not moving — so deferring costs little. Revisit in a later spec if a generic mechanism appears.

---

## R12. Findings from the first live run (2026-11-06)

Recorded here because each one corrected or refined something above.

- **Latency buckets were not published** (`http_server_requests_seconds_bucket`: 0 series), so a p95 panel
  would have been empty. Fixed as a shared default in `config-repo/application.yml`
  (`management.metrics.distribution.slo."[http.server.requests]": 50ms,100ms,250ms,500ms,1s,2s`). Six
  explicit buckets rather than `percentiles-histogram`, which adds dozens of series per endpoint. This is
  read at startup, so unlike most of that file it needs a service restart to take effect.
- **Eureka exposes no registration metrics** through Micrometer, so the "Eureka registrations" panel in
  the original request cannot be built from real data and was omitted. FR-009 never required it.
- **Gateway requests are labelled `uri="UNKNOWN"`**: Spring Boot has no handler pattern to attach to a
  routed request. Per-service rate, latency and error percentage still work because panels group by
  `service`; per-route detail would need `spring_cloud_gateway_requests_seconds`, out of scope here.
- **A brand-new series reads as rate 0 for its first scrape**, because `rate()` needs two samples. Not a
  dashboard fault, but it explains an empty-looking panel right after a service's first request.
- **The datasource needed a fixed `uid: prometheus`.** Provisioned dashboards and alert rules refer to it
  by uid; left to default it is random and every committed reference points at nothing.
