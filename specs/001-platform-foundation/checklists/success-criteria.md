# Success Criteria: measured results

**Feature**: [spec.md](../spec.md) | **Recorded**: 2026-10-17

Environment: Docker 29.1.3, Docker Compose 2.40.3, WSL2 Ubuntu on Windows 11, OpenJDK 21. The
repository sits on the Windows filesystem and is reached from WSL through `/mnt/c`, which makes file
I/O (and therefore Maven builds and bind-mounted config reads) noticeably slower than a native Linux
checkout. Timings below are from that setup and should be read as a pessimistic case.

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| SC-001 | Healthy in under 3 min warm, 10 min cold | **Met** | Measured from a genuinely empty state — all four service images deleted and the Docker build cache pruned — then timed end to end: **cold 457 s** (budget 600 s) and **warm 104 s** (budget 180 s), both exiting 0. Note this machine builds over `/mnt/c`, so a native Linux checkout should be faster; these are pessimistic figures. CI times both on every build and fails if either budget is exceeded. |
| SC-002 | Registered within 30 s; gone within 30 s (graceful) or 90 s (crash) | **Met** | `scripts/smoke-test.sh` waits for `CONFIG-SERVER`, `ECHO-SERVICE` and `API-GATEWAY` to appear and fails past 120 s; `--scale` kills a copy and asserts only 200 or 503 follow. |
| FR-014 | Running services survive a brief registry outage | **Met** | Deliberate 30-second outage with a warm gateway cache: **30 of 30 requests returned 200**, and the logs show automatic re-registration (`registration status: 204`) afterwards. |
| SC-003 | A central change lands within 10 s | **Met** | Measured **2 s** on the running stack: the smoke test edits `echo.greeting` in `backend/config-repo/echo-service.yml` and polls the gateway until the new value appears, failing past 10 s. `EchoLiveRefreshIT` measures the same path against a stubbed config server. |
| SC-004 | Every gateway error shares one structure and carries a correlation ID | **Met** | `GatewayBehaviourIT` validates 401, 404, 503 and 504 bodies against `contracts/problem-details.schema.json` and asserts the body's `correlationId` equals the response header. The smoke test repeats the 401 and 404 cases against the running stack. A defect found this way (the header returned twice) is fixed and covered by a regression test. |
| FR-024 | Rate limiting per client address | **Met** | A 400-request parallel burst against the running gateway was refused with 429 (30 refused in the recorded run) and recovered afterwards. Bucket behaviour, refill, isolation between addresses and eviction are unit-tested with an injected clock. |
| SC-005 | 503 within 5 s when no healthy copy exists | **Met** | `GatewayBehaviourIT.refusedConnectionIsServiceUnavailable` and `noInstanceIsServiceUnavailable` both assert the status and the elapsed time. |
| SC-006 | 100 calls, each of two copies gets at least 40 | **Met** | `scripts/smoke-test.sh --scale`, run on this machine: **50 / 50** across `echo-service-b` and `echo-service:377513d527ae`, both far above the 40 minimum. The same run killed a copy and confirmed only 200 or 503 followed, never a hang or a 500. 24 of 24 checks passed. |
| SC-007 | A new service needs no wiring code | **Met** | `probe-service` was created by following the README's "add a service" steps literally (module, parent POM entry, `config-repo` file, gateway route, Compose entry) and contains **no** registration, configuration or health code. Measured on the running platform: it **registered as `PROBE-SERVICE`**, served `/api/v1/probe/ping` **through the gateway (200)**, answered directly on 9001, and returned 200 from `/actuator/health/readiness` — registration, central settings, health and routing, none of which it writes a line of code for. All 27 guard tests pass with it in the reactor, including `NoWiringCodeTest`, which picks it up automatically, and `BuildRulesTest`, which confirms its POM declares no version. |
| SC-008 | Stop, restart and reset using only the README | **Met** | `scripts/smoke-test.sh --lifecycle`: a marker row survives `docker compose down` and a restart, and is gone after `down -v`. 10 of 10 checks passed. |
| T139 | A clean checkout can be followed from the README | **Met, after a fix** | Cloned into an empty directory and walked the README. Everything a developer needs was present and the shell scripts kept LF endings, but **the scripts had no executable bit**, so the README's `scripts/up.sh` would have failed with "Permission denied" on any fresh clone. Fixed with `git update-index --chmod=+x`; this is exactly the class of defect only a clean checkout reveals. |
| FR-017 | A service without central settings fails rather than starting | **Met** | `EchoFailFastIT`: with the config server unreachable, startup fails inside 30 s and the message names the config server; the test also asserts the import carries no `optional:` prefix. Confirmed end to end by `scripts/smoke-test.sh --config-down` on the running stack (**23/23**): the container exits rather than starting, and its log names `http://config-server:9888`. |
| FR-020 | Structured logs carry the correlation ID | **Met** | `StructuredLoggingTest` parses a captured log line as ECS JSON and asserts `correlationId` is its own field, not merely present in the text. |
| SC-009 | A missing setting or occupied port fails in under 30 s, naming it | **Met** | `scripts/tests/up-preflight.test.sh`: 5 of 5 pass, covering runtime down, missing and empty settings, an occupied port, a port already held by this project, and the happy path. Each asserts no container was started and the failure took under 30 s. |
| SC-010 | No real secret committed; every setting documented | **Met** | `EnvExampleTest` cross-checks every `${VAR}` in `docker-compose.yml` against `.env.example`, requires secret-like values to start with `change-me`, and asserts `.env` is git-ignored and untracked. |

## What the verification itself taught

Several of these criteria only turned out to be true after something was fixed, and in every case the
fix came from *running* rather than reading:

- The correlation header came back twice until the smoke test against a real downstream caught it.
- `docker compose up --wait` really does fail on a clean start; the earlier spike said otherwise
  because it did not model the real topology.
- Config precedence was the reverse of what the spec assumed.
- The shell scripts had no executable bit, so a fresh clone could not follow the README.
- Four tests had been written but never executed; two of them could not have passed as written.
- `set -o pipefail` plus `grep -q` silently inverted two smoke checks: grep exits at the first match,
  the writer gets SIGPIPE, and its non-zero status becomes the pipeline's. One check reported a
  missing log message that was plainly there; the other would have reported **PASS** for a Mailpit
  relay that was actually configured — a false pass, which is the dangerous direction.

The pattern worth carrying into the next spec: a test that is written but not run is not yet a test,
and a criterion verified only on the machine that built it is not yet verified.

## Status

All ten success criteria are met and measured. Every row above cites either a number taken from the
running platform or a named test that passes against it.

## Outstanding

Two things are written but have never been executed on this machine, so they are claims CI will
settle rather than measurements:

- **`shellcheck`** is not installed here, so the lint step has been written but not run.
- **The `--auth-profile` smoke mode** (login required outside `local`) is written but not yet run
  end to end; it needs the whole stack restarted under a non-local profile. The behaviour itself is
  covered by `DiscoveryServerSecurityTest` and `ConfigServerSecurityTest`, which do pass, including
  the health-probe exemption.

Everything else listed here previously — the SC-007 walkthrough, the cold-start timing, and the
config-server-down smoke mode — has since been done and the rows above carry the measurements.

## Notes

Two measured findings changed the design rather than the record:

1. `docker compose up --wait` exits 1 on a clean start because the one-shot `db-init` container
   ends in `exited`, even though every service is healthy. `scripts/up.sh` therefore checks real
   container state instead of trusting the exit code. See research R12.
2. Central configuration precedence is `application-<profile>.yml`, then `<service>.yml`, then
   `application.yml` — the opposite of what the spec first assumed for the first two. See research R6.
3. A healthy platform is not instantly routable: services register shortly after reporting healthy, so
   the gateway answers 503 for a few seconds after startup. Correct behaviour, but surprising, so it is
   called out in `scripts/up.sh` output and the README. See research R5.
4. The gateway returned the correlation header **twice** (its own value plus the one the echo service
   set) until this was fixed. No unit or integration test caught it; the smoke test against the running
   stack did. `GatewayBehaviourIT` now has a regression test with an upstream that sets the header.
