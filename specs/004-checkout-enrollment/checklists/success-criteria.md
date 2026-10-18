# Success Criteria: measured results

**Feature**: [spec.md](../spec.md) | **Recorded**: 2026-10-18

Environment: same as platform-foundation's, 002's and 003's own records (Docker 29.1.3, Docker Compose
2.40.3, WSL2 Ubuntu on Windows 11, OpenJDK 21, repository on the Windows filesystem reached through
`/mnt/c`). All curl checks below were run against the real running platform (`scripts/up.sh`), not a mock.

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| SC-001 | Login → read lesson content, entry point only, for both a free and a paid course | **Met** | `CheckoutIT` (free and `sim-success` paid checkout) and `LessonContentAccessIT`/`MyEnrollmentsIT` (content and enrollment visible afterward) together prove the whole chain; confirmed live end to end through the gateway (HOWTORUN.md §3.21–3.22). |
| SC-002 | A declined or provider-unavailable checkout never results in an enrollment | **Met, functional coverage; not run as a bulk repeated-trial script** | `CheckoutIT` proves `sim-decline` and `sim-unavailable` each resolve with no `OutboxEvent` row created (so no enrollment can ever follow), once each. A dedicated many-repetition run was not performed — see Outstanding, the same kind of gap 002 and 003 carried forward. |
| SC-003 | 20 simultaneous identical checkouts under one idempotency key → exactly one payment and one enrollment | **Met, full volume run** | `IdempotencyIT.twentySimultaneousIdenticalCheckoutsProduceExactlyOnePayment` fires all 20 requests concurrently from a 20-thread pool released by a shared latch, and asserts exactly one `SUCCEEDED` payment afterward — the actual specified volume, not a smaller proxy. |
| SC-004 | Enrollment processing stopped at the moment a payment succeeds → exactly one enrollment once it resumes | **Met, functional coverage; single trial each of two failure modes** | `EnrollmentServiceDownIT` stops the listener container itself and confirms the event waits durably and is processed exactly once on resume; `OutboxPublisherIT`'s broker-outage test covers the broker-unreachable variant (research.md R1) by pausing the RabbitMQ container mid-checkout. Each run once, not repeated. |
| SC-005 | A non-enrolled user never reads full lesson content | **Met, functional coverage; not run as a bulk repeated-trial script** | `LessonContentAccessIT.nonEnrolledUserIsRefusedTheReadingView` and `unconfirmedEnrollmentStatusRefusesContentRatherThanShowingIt` prove the confirmed-not-enrolled and cannot-confirm cases once each; confirmed live (HOWTORUN.md §3.25). Volume not run — see Outstanding. |
| SC-006 | ≥95% of successful payments enroll within 5 seconds | **Outstanding — not measured as a volume/percentage run** | `PaymentSucceededConsumerIT` and the live walkthrough both show single payments enrolling well within 5 seconds, but no bulk run computing a percentage across many payments was performed (flagged during `/speckit-analyze`, finding F1). |
| SC-007 | All previously verified platform behaviors continue to pass unchanged | **Met** | `scripts/smoke-test.sh` on the platform with `payment-service` and `enrollment-service` included: **21 passed, 0 failed** — unaffected by this feature. |

## What the verification itself taught

Three defects were found only by actually running the tests (not by reasoning about the design), all
fixed and now covered by the tests that caught them:

- **Spring Cloud OpenFeign's circuit breaker names each breaker per *method*, not per client, by
  default.** `CourseLookupClientContractTest`'s plain-200 case failed with a spurious
  `TimeLimiter` timeout even though WireMock answered instantly — the
  `resilience4j.circuitbreaker.instances.course-service`/`resilience4j.timelimiter.instances.course-service`
  config blocks were silently being applied to an instance named `CourseLookupClientsummaryUUID`, not
  `course-service`, so every call fell back to Resilience4j's own 1-second default timeout. Fixed by
  also setting `spring.cloud.openfeign.circuitbreaker.group.enabled=true`, which groups breakers by the
  `@FeignClient` name instead (research.md R4).
- **A timed-out call that isn't cancelled keeps occupying its executor thread.** After fixing the above,
  a *different* test in the same class (`mapsA404ToNull`) started failing with its own spurious timeout
  — the thread pool backing the time limiter was still tied up running out the full duration of the
  *previous* test's deliberately slow (10-second) stub, since Resilience4j does not cancel a timed-out
  future by default. Fixed by setting `resilience4j.timelimiter.instances.*.cancel-running-future=true`.
- **A self-invoked `@Transactional` method cannot start a second, independent transaction.** The first
  version of `CheckoutService` caught a unique-constraint violation (two racers under the same
  idempotency key, SC-003) and tried to re-query within the same `@Transactional` method — but Postgres
  had already aborted that transaction, so the re-query would have failed with "current transaction is
  aborted" rather than cleanly resolving. Fixed by switching to an explicit `TransactionTemplate`, so the
  retry after a lost race runs in a genuinely new transaction (research.md R9).

Unlike 002 and 003, no gateway routing defect was found this time — `RealGatewayConfigTest`'s extension
for the four new routes, and the `course-content` route's narrower shape ordered ahead of
`courses-manage`, held on the first try.

## Status

Two criteria are fully measured with hard numbers at their specified volume (SC-003, SC-007); SC-001 is
fully proven end to end. SC-002, SC-004 and SC-005 are met at the functional-correctness level (each
distinct case proven at least once) but not as bulk, repeated-trial runs. SC-006 is the one criterion
genuinely not measured as a percentage — see Outstanding.

## Outstanding

- **SC-006** (≥95% of payments enrolled within 5 seconds) has not been measured as a volume run — only
  single-payment timing is proven. A dedicated script firing many payments and measuring the actual
  percentage within the 5-second window would close this (analyze finding F1).
- **SC-002 and SC-005 volume runs** (many varied declined/unavailable checkouts; many varied
  non-enrolled access attempts) have not been executed as bulk, repeated-trial runs — only
  single-instance functional correctness is measured so far, the same kind of gap 002 and 003 both
  carried forward for their own analogous success criteria.
- **`shellcheck`** was not re-run for this feature; no script under `scripts/` was modified beyond the
  port-table additions in `scripts/up.sh`, already covered by 003's own shellcheck pass.
