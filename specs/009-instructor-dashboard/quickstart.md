# Quickstart: validating the instructor dashboard

Run Docker, Compose and Maven inside WSL (`wsl -e bash -lc "..."`). Contracts:
[analytics](contracts/analytics-service.openapi.yaml),
[course internal list](contracts/course-service-internal-list.openapi.yaml). Data shapes:
[data-model.md](data-model.md).

## 1. Fast checks (no stack)

```bash
cd frontend && npm run gen:api && npm run typecheck && npm test && npm run build
wsl -e bash -lc "cd <repo> && mvn -f backend/pom.xml -pl analytics-service,course-service,api-gateway -am verify"
```
Expected: all pass. The analytics ITs prove duplicate delivery counts once, 20 concurrent events give
exact sums, out-of-order events give the same totals, a malformed message lands in `analytics.q.dlq`,
and the UTC day boundary holds. Contract tests prove the course-service list/detail calls and a 503
with no data when the circuit is open. Gateway tests prove STUDENT gets 403 and anonymous gets 401 on
`/api/v1/instructor/**`, and that no route targets `/api/v1/internal/**`.

## 2. Stack up

```bash
wsl -e bash -lc "cd <repo> && cp -n .env.example .env && scripts/up.sh"
```
Expected: `analytics-service` healthy on 9008 and registered in Eureka; port 9008 and
`ANALYTICS_DB_PASSWORD` accepted by the preflight; databases include `analytics`.

## 3. End-to-end (extended `scripts/smoke-test.sh`)

1. Register two instructors (promote through the administrator) and a student.
2. Instructor A creates and publishes a paid course; the student enrolls with `sim-success` and
   completes every lesson.
3. Within 10 seconds, `GET /api/v1/instructor/stats/summary` as A shows enrollments 1, revenue equal to
   the price, completions 1, certificates 1.
4. Instructor B gets 404 for A's course; the student gets 403; an anonymous call gets 401.
5. `analytics.q` has no unacked messages and `analytics.q.dlq` is empty.
6. All earlier smoke checks still pass.
7. Activity from before the service first started is not counted (nothing is backfilled).

## 4. Browser (learnhub-ui-test skill)

Sign in as instructor A at http://localhost:9100 -> "Dashboard" link -> overview totals and table sort;
open the course -> funnel, 7/30/90 toggle, chart and its table; Refresh updates "last updated". Sign
in as the student -> `/instructor` shows the not-allowed page. Stop course-service -> the dashboard
shows "temporarily unavailable" with a reference code and no numbers.
