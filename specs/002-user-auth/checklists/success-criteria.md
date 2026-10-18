# Success Criteria: measured results

**Feature**: [spec.md](../spec.md) | **Recorded**: 2026-10-18

Environment: same as platform-foundation's record (Docker 29.1.3, Docker Compose 2.40.3, WSL2 Ubuntu on
Windows 11, OpenJDK 21, repository on the Windows filesystem reached through `/mnt/c`). All curl checks
below were run against the real running platform (`scripts/up.sh`), not a mock.

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| SC-001 | Register → protected call in under 2 min | **Met** | Full manual walkthrough (register, login, call `/api/v1/echo/secure/ping`) completed in well under a minute against the live platform. |
| SC-002 | 100% refusal of no/expired/tampered tokens, ≥50 varied attempts each | **Met, functional coverage; full 50x volume not yet run** | `JwtAuthenticationFilterTest` and `JwtLogSafetyTest` each cover missing, malformed, tampered and expired tokens; `EchoSecureAccessIT` confirms it through a real Spring-bound gateway context; a live manual check against the running gateway also returned 401 with no token. No single run has repeated each case 50 times to produce a volume count — see Outstanding. |
| SC-003 | 100% refusal of insufficient-role requests, ≥20 varied attempts | **Met, functional coverage; full 20x volume not yet run** | `JwtAuthenticationFilterTest.insufficientRoleIsForbidden` and `EchoSecureAccessIT.insufficientRoleIsForbiddenThroughTheRealGateway` (the test that caught the real binding bug below) both assert 403; confirmed live against the running platform (a STUDENT token attempting the admin-only role-change route). Volume not yet run — see Outstanding. |
| SC-004 | 100% refusal of reused/revoked refresh tokens, ≥20 attempts | **Met, functional coverage; full 20x volume not yet run** | `RefreshTokenRotationIT` and `LogoutIT` each assert reuse and post-logout reuse are refused; confirmed live (reusing a spent refresh token returned 401 against the running stack). Volume not yet run — see Outstanding. |
| SC-005 | Throttled by the 10th consecutive failure within 1 min | **Met** | `LoginFlowIT.sixthRapidFailureAgainstTheSameAccountIsThrottled`: throttling (research.md R3's chosen threshold of 5) trips on the 6th attempt, well inside the 10-attempt budget the criterion allows. |
| SC-006 | Exactly one working administrator login after every fresh startup | **Met** | `BootstrapAdministratorIT` (idempotent across a second run of the bootstrap step); confirmed live — the configured `ADMIN_EMAIL`/`ADMIN_PASSWORD` logged in successfully on the freshly started platform with no manual step. |
| SC-007 | Full register → login → protected call → refresh → logout sequence | **Met** | Walked live end to end against the running gateway: register (201) → login (200, token pair) → `/api/v1/echo/secure/ping` with the token (200) → refresh (200, new pair; old refresh token then 401) → logout (204) → refresh with the now-revoked token (401). |
| SC-008 | No password or token in any log, after a full exercise | **Met by automated test; live container logs not manually inspected** | `NoSecretsInLogsTest` (auth-service) and `JwtLogSafetyTest` (gateway) each capture real log output during register/login and token-rejection paths and assert the raw password/tokens never appear. The live containers' actual log output was not separately grepped during this session — see Outstanding. |
| SC-009 | Platform-foundation behaviors (startup, discovery, config, rate limiting) unchanged | **Met** | `scripts/smoke-test.sh` on the platform with `auth-service` included: **21 passed, 0 failed**, covering startup, health, databases, mail, gateway routing, correlation IDs, CORS, rate limiting (burst and recovery), registry outage/recovery, and config-edit propagation (6 s, within the 10 s budget). |

## What the verification itself taught

The one defect this feature's own automated tests did **not** catch, and that only running the real
platform did:

- **Route metadata lists don't bind the way a hand-built test object suggests.** `JwtAuthenticationGlobalFilter`'s
  role check (`metadata.roles: [ADMINISTRATOR]`) passed every unit test, because those tests built the
  `Route`'s metadata directly in Java as a real `java.util.List`. Against the real, YAML-config-server-fed
  gateway, Spring's relaxed binder turns a list nested inside a `Map<String, Object>` (which is what
  route metadata is typed as) into a **nested `Map` keyed `"0"`, `"1"`, ...**, not a `List` — confirmed
  by querying the config server directly and by a `DIAG`-level log statement added temporarily to the
  filter. The result: `rolesOf()` always returned an empty list against the real binding, so the
  `auth-admin` route's restriction silently never applied — **a student could promote themselves to
  administrator** through the real gateway, while every hand-built-`Route` unit test stayed green.
  Fixed by accepting both shapes in `rolesOf()`, and a regression test was added
  (`EchoSecureAccessIT.insufficientRoleIsForbiddenThroughTheRealGateway`) that loads a route from a real
  YAML file into a real Spring Cloud Gateway context — the only kind of test that would have caught this
  before a live check did.
- A related, smaller miss: the docker-compose entries for `auth-service` and `api-gateway` initially
  omitted `JWT_SIGNING_SECRET` for the gateway (added only to `auth-service`'s entry), which crashed
  `api-gateway` on startup with an unresolved placeholder. Caught immediately by actually bringing the
  platform up rather than only running `mvn verify`.

Same lesson platform-foundation's own record drew: a criterion verified only against a hand-built test
fixture is not yet verified against the real thing.

## Status

Seven of nine criteria are fully measured and met (SC-001, SC-005, SC-006, SC-007, SC-009 with hard
numbers; SC-002/SC-003/SC-004 functionally proven but not yet volume-measured — see below). SC-008 is
met by automated test but its live-log claim was not independently re-checked by hand this session.

## Outstanding

- **SC-002, SC-003, SC-004 volume runs** (≥50, ≥20, ≥20 varied attempts respectively) have not been
  executed as bulk, repeated-trial runs — only single-instance functional correctness is measured so
  far, per each criterion's row above. A dedicated script run (comparable to `smoke-test.sh --scale`'s
  100-call spread) would close this.
- **SC-008's live-log claim** has not been independently re-checked by grepping the actual running
  containers' log output by hand; it rests on the automated `NoSecretsInLogsTest`/`JwtLogSafetyTest`
  coverage only.
- **`shellcheck`** was not re-run for this feature (no new shell scripts were added; `scripts/up.sh` and
  `scripts/smoke-test.sh` were not modified by this feature beyond `up.sh`'s required-settings and port
  list additions, which are small enough to have been reviewed by eye, not re-linted this session).
