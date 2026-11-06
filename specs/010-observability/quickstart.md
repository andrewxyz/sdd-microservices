# Quickstart: Platform Observability

**Feature**: `010-observability` | **Date**: 2026-11-06

Runnable validation for spec 010. Every step maps to a requirement or success criterion. Step 6 is the
one that actually proves User Story 1 — a dashboard showing zeros proves nothing until you have seen
it show a one.

All commands run from the repository root. Docker and Compose live in WSL in this project, so prefix
with `wsl -e bash -lc "cd <repo> && …"` when running from Windows.

---

## 0. Prerequisites

```bash
cp .env.example .env     # if you do not have one
```

**If you already have a `.env`, add the two new settings or the preflight will stop you:**

```bash
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=<anything non-empty>
```

`scripts/up.sh` rejects any required setting that is missing or empty and names it, so a missing one
is self-explaining rather than mysterious — but it does stop the start.

---

## 1. Start the platform — FR-019, FR-020

```bash
scripts/up.sh
```

**Expected**: the preflight reports ports 9090 and 9030 among those it checked, and the platform comes
up with `prometheus` and `grafana` alongside everything else, with no extra command.

```bash
docker compose ps --format "table {{.Service}}\t{{.State}}\t{{.Health}}"
```

**Expected**: `prometheus` and `grafana` both `running` / `healthy`.

Give it ~30 s after startup before trusting the gateway: services register with Eureka a moment after
reporting healthy.

---

## 2. Every service exposes metrics — FR-001, FR-003

```bash
curl -s localhost:9001/actuator/prometheus | head -20
```

**Expected**: Prometheus text format, including `jvm_memory_used_bytes` and (after any traffic)
`http_server_requests_seconds_count`.

Now the gateway, which is the one with a different web stack (WebFlux rather than servlet) and so the
one most likely to behave differently:

```bash
curl -s localhost:9080/actuator/prometheus | head -20
```

**Expected**: the same format. If this is empty while 9001 works, the registry is not reaching the
reactive application and R1's framework-agnostic claim is wrong.

Confirm the negative obligation too — FR-002, SC-004:

```bash
grep -rn "micrometer\|prometheus" backend/*/src/main/ || echo "no per-service metrics code (expected)"
```

**Expected**: no matches. Every service got its endpoint from `platform-common` alone.

---

## 3. Everything is being collected from — FR-005, FR-007, SC-003

```bash
curl -s "localhost:9090/api/v1/targets?state=active" \
  | python3 -c "import sys,json; d=json.load(sys.stdin)['data']['activeTargets']; [print(t['labels'].get('job'), t['labels'].get('instance'), t['health']) for t in d]; print('total:', len(d))"
```

**Expected**: 14 targets, every one `up` — 10 business services, 2 platform servers, 2 RabbitMQ jobs.

A target stuck `down` names itself and its error, so read the `lastError` field before assuming the
service is broken; a 404 there usually means the path is wrong in `prometheus.yml`, not that the
service is unhealthy.

---

## 4. Dashboards and alerts exist with no setup — FR-009, FR-010, FR-011, FR-016, SC-005

Open <http://localhost:9030> and sign in with the `.env` credentials.

**Expected**, with nothing imported or clicked:

- Two dashboards: **Platform health** and **Event pipeline**.
- Prometheus already the default data source.
- Three alert rules under Alerting → Alert rules, all `Normal`.

Prove the committed files are the source of truth — FR-011:

1. Edit a panel title in the Grafana UI and save.
2. `docker compose restart grafana`
3. Reload. **Expected**: the committed title is back. The UI edit did not survive, which is the point.

---

## 5. Live data appears — US2, SC-001

```bash
scripts/seed-demo.sh
```

Then browse <http://localhost:9100>: open the catalog, sign in, enrol in the free course, open a
lesson. Now look at **Platform health**.

**Expected**: non-zero request rate and latency for `course-service`, `auth-service` and
`api-gateway`; every service showing up; heap and GC populated. An idle service shows an empty or
zero panel, not an error.

---

## 6. The dead-letter drill — US1, FR-008, FR-013, FR-017, SC-002

**This is the step that proves the feature's reason for existing.** Everything above shows the
monitoring works when nothing is wrong.

Baseline:

```bash
# Event pipeline dashboard: every dead-letter panel reads 0, DLQ alert Normal.
```

Force a dead-letter by publishing an event no consumer can parse. It is retried three times and then
set aside. `notification-service` must be running, since it owns the queue bound to `enrollment.created`.
Uses the broker's HTTP API, which is how this was measured; the broker image was not checked for
`rabbitmqadmin`.

```bash
curl -s -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" -H 'content-type: application/json'   -X POST 'http://localhost:9673/api/exchanges/%2F/learning.events/publish'   -d '{"properties":{},"routing_key":"enrollment.created","payload":"{\"not\":\"a valid envelope\"}","payload_encoding":"string"}'
# {"routed":true}
```

**Expected within ~60 s** (SC-002):

- **Event pipeline** shows `notification.q.dlq` with depth ≥ 1, naming the queue.
- Within ~2 min the **DLQ not empty** alert moves `Pending` → `Firing`. (Measured 2026-11-06: depth visible at +17 s,
  alert firing at +89 s.)

Cross-check against the broker, so the dashboard is corroborated rather than trusted:

```bash
# RabbitMQ console at http://localhost:9673 -> Queues -> notification.q.dlq
```

Now prove self-resolution — FR-017:

```bash
curl -s -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" -X DELETE \n  'http://localhost:9673/api/queues/%2F/notification.q.dlq/contents'
```

**Expected**: depth returns to 0 and the alert returns to `Normal` by itself, with no acknowledgement
step.

---

## 7. A stopped service alerts, a starting one does not — FR-014, FR-018, SC-009

```bash
docker compose stop review-service
```

**Expected**: within ~2 min, `review-service` shows down and **Service unreachable** fires.

```bash
docker compose start review-service
```

**Expected**: recovers on its own, alert returns to `Normal`.

Then the FR-018 half, which is easy to forget and is the reason the window is 2 min:

```bash
docker compose down && scripts/up.sh
```

**Expected**: a normal start produces **no** service-unreachable alert. Services are legitimately
unreachable while booting; an alert here would be a false positive on every single start, which is
what SC-009 forbids.

---

## 8. Automated checks — FR-021, SC-007

```bash
scripts/smoke-test.sh
```

**Expected**: all checks pass, including the new monitoring ones.

Prove the gap-detection actually works, rather than assuming it:

1. Comment out one target in `backend/docker/prometheus/prometheus.yml`.
2. `docker compose restart prometheus`
3. `scripts/smoke-test.sh` → **expected: FAIL**, naming the target count mismatch.
4. Restore the target and re-run → passes.

A monitoring gap that does not fail the test is worse than no test, so this step is not optional.

```bash
mvn -f backend/pom.xml -pl infra-tests verify
```

**Expected**: `ComposeFileTest` passes — the two new services have health checks, the new ports match
the constitution's port map, and the new volumes are declared.

---

## 9. Resource budget — SC-006, SC-008

```bash
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}" | grep -E "prometheus|grafana"
```

**Expected**: under 500 MB combined, against caps of 256 MB and 192 MB.

```bash
curl -s "localhost:9090/api/v1/status/runtimeinfo" | python3 -m json.tool | grep -i storage
```

**Expected**: retention reflects the 24 h / 512 MB bounds.

---

## 10. Data survives a restart and is wiped by a reset — FR-023

```bash
docker compose restart prometheus
```

**Expected**: history from before the restart is still on the dashboards.

```bash
docker compose down -v && scripts/up.sh
```

**Expected**: dashboards and alert rules are present again immediately (they are provisioned from
committed files, not stored data), but metric history starts empty.

That asymmetry is the design working as intended: configuration is committed and always returns,
collected data is disposable and goes with the volumes.
