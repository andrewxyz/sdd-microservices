# Success Criteria: measured results

**Feature**: [spec.md](../spec.md) | **Recorded**: 2026-10-19

Environment: same as platform-foundation's, 002's, 003's and 004's own records (Docker 29.1.3, Docker
Compose 2.40.3, WSL2 Ubuntu on Windows 11, OpenJDK 21, repository on the Windows filesystem reached
through `/mnt/c`). Automated-test evidence below comes from `mvn -f backend/pom.xml verify` runs against
real PostgreSQL, RabbitMQ and Mailpit Testcontainers instances (constitution Principle VI) — never a mock.

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| SC-001 | A student who completes every lesson ends up with exactly one certificate | **Met, functional coverage** | `CourseCompletionIT` (enrollment-service) proves completion fires exactly once, including under ten concurrent completions of a course's last lessons; `CourseCompletedConsumerIT` (certificate-service) proves exactly one `Certificate` results from the resulting `course.completed` event, with the right fields. The full chain (enroll → complete → certificate) was proven through automated tests end to end, not yet re-confirmed by a live manual walkthrough — see Outstanding (T070). |
| SC-002 | Anyone can verify the certificate by its code: valid/invalid, course title, date, nothing about the holder | **Met** | `CertificateVerificationIT` proves a real code returns `valid: true` with the course title and date and no `userId`/holder field anywhere in the response body; an unknown code returns 404. |
| SC-003 | Exactly one email for enrollment, one for the certificate; a redelivered event never adds an extra one | **Met** | `EnrollmentCreatedConsumerIT` and `CertificateIssuedConsumerIT` each prove one email per event via Mailpit's own message count, and that replaying the identical event (same `eventId`) never increments that count again — the two-phase `PENDING`→`SENT` write (research.md R7) holds under a redelivery sent immediately after the first send completes. |
| SC-004 | certificate-service unavailable at course completion → exactly one certificate once it recovers | **Met, functional coverage; single trial** | `CertificateServiceDownIT` stops `CourseCompletedListener`'s container via `RabbitListenerEndpointRegistry.stop()`, publishes the event, confirms no certificate exists while stopped, restarts the container, and confirms exactly one certificate results. Run once, not repeated — the same kind of gap 004's own SC-004 record carried forward for its own analogous criterion. |
| SC-005 | Marking the same lesson complete repeatedly never changes reported progress beyond the first mark | **Met** | `LessonProgressIT.markingTheSameLessonTwiceChangesNothing` and the unmark/remark-after-completion case in `CourseCompletionIT` both prove this directly. |
| SC-006 | Certificate issuance normally completes within 10 seconds of course completion | **Outstanding — not measured as a strict bound** | No automated test asserts a 10-second ceiling; `CourseCompletedConsumerIT`'s own Awaitility budget was deliberately widened to 20s during implementation to absorb a one-time JIT/connection-pool "cold start" tax measured on this specific machine (research.md), which weakens rather than confirms a 10-second claim. Manual timing during the T070 walkthrough (steps 4 and 7 of quickstart.md) is the only remaining check against the literal figure, same honesty gap 004's SC-006 recorded for its own timing criterion. |
| SC-007 | All previously verified platform behaviors continue to pass unchanged | **Pending full-reactor confirmation (T071)** | To be updated once `mvn -f backend/pom.xml verify` completes for the whole reactor and `scripts/smoke-test.sh` is run against the live stack. |

## What the verification itself taught

Several defects were found only by actually running the tests (not by reasoning about the design), all
fixed and now covered by the tests that caught them:

- **A duplicate Spring bean nearly broke enrollment-service's own context.** A newly-added
  `RabbitMessagingConfig` declared `learningEventsExchange`/`jsonMessageConverter` beans that already
  existed, verbatim, in the pre-existing `RabbitTopologyConfig` from 004 — caught immediately by
  `BeanDefinitionOverrideException` on the very first verify. Fixed by deleting the duplicate file; the
  one topology config already declared everything idempotently.
- **A bulk JPQL `UPDATE` never refreshes the in-memory entity that issued it.** `LessonProgressService`'s
  conditional `UPDATE enrollments SET completed_at = :now WHERE ... AND completed_at IS NULL` (the
  mechanism that keeps concurrent completions down to one event, research.md R1) correctly updated the
  database row, but the `Enrollment` instance already loaded in the same transaction kept showing
  `completedAt: null` — so the HTTP response for the very request that completed a course reported
  `courseCompletedAt: null`, even though the database was already correct. `CourseCompletionIT` caught
  this because it asserts on the response body, not just the database. Fixed by having the completing
  method return the instant it just claimed (or the pre-existing one) explicitly, rather than re-reading
  the now-stale entity.
- **An outbox-row-count assertion wasn't scoped to its own test's enrollment.** `CourseCompletionIT`'s
  sticky-completion test counted *every* `course.completed` outbox row in the shared Testcontainers
  database instead of filtering by its own enrollment id, so it occasionally counted a sibling test's
  leftover row too. Fixed by filtering on `enrollmentId`, matching the pattern its own sibling test
  already used correctly.
- **`Awaitility.untilAsserted` does not retry a plain `NoSuchElementException`.** Several new consumer
  tests read `Optional.orElseThrow()` inside an `untilAsserted` block to fetch a row that the
  asynchronous listener hadn't created yet; since `orElseThrow()` throws a `NoSuchElementException`, not
  an `AssertionError`, Awaitility treated it as a fatal error on the very first poll instead of retrying
  for the configured budget. Fixed by replacing it with a proper `assertThat(...).hasSize(1)`-style
  assertion that Awaitility actually retries on.
- **`spring.cloud.openfeign.client.config.<name>.url` does not reliably bypass Spring Cloud LoadBalancer
  for a Feign call made from a `@RabbitListener` thread**, even though the identical property reliably
  works for a Feign call made from an HTTP request thread (`payment-service`'s and `enrollment-service`'s
  own established pattern). `certificate-service`'s and `notification-service`'s listener-triggered calls
  intermittently resolved to a dead `lb://` lookup (`No servers available for service: course-service`)
  instead of the stubbed WireMock URL. Fixed by giving each `@FeignClient` its own
  `url = "${course-service.url:}"` attribute (empty in production, unchanged load-balanced behavior
  there) and having tests override that plain property directly — `FeignClientFactoryBean` then builds a
  non-load-balanced target outright, never touching the load balancer. See research.md R5 for the full
  writeup, including why every test class's `application.yml` now needs a harmless placeholder URL even
  when it never calls the client.
- **Failsafe's default `reuseForks=true` leaks LoadBalancer/Resilience4j state across IT classes in the
  same module.** Even after the fix above, certificate-service's and notification-service's listener-
  triggered Feign calls still failed intermittently — but only when the full module's IT suite ran
  together, never when a single class ran alone. Toggling only `reuseForks` (nothing else) reproduced the
  failure 100% of the time at `true` and eliminated it 100% of the time at `false`. Fixed by setting
  `<reuseForks>false</reuseForks>` on `maven-failsafe-plugin` in both modules' own `pom.xml`; left
  `enrollment-service` on the shared-fork default since its own Feign calls are HTTP-triggered, not
  listener-triggered, and its suite passed repeatedly without the change.
- **`RepublishMessageRecoverer`'s default constructor prepends `"error."` to the dead-lettered message's
  routing key.** `notification-service`'s single dead-letter queue is bound to the bare
  `enrollment.created`/`certificate.issued` routing keys, so every message the recoverer republished
  (`error.enrollment.created`, `error.certificate.issued`) never matched either binding and effectively
  vanished rather than landing on the DLQ. `DeadLetterIT` caught this directly (`rabbitTemplate.receive`
  on the DLQ kept returning `null`). Fixed by calling `setErrorRoutingKeyPrefix("")` on the recoverer.
  `certificate-service`'s own recoverer was never affected — it uses the constructor overload that takes
  an explicit fixed routing key, which does not get the prefix treatment.
- **A fresh JVM still pays a one-time "cold start" tax on its first real Feign call.** Independently of
  the fork-reuse bug above, a brand-new context's very first Feign call (JIT warm-up, connection pool,
  Resilience4j/LoadBalancer bean initialization) measured ~5 seconds locally even when everything else
  was healthy, against production's 3-second `resilience4j.timelimiter` budget. Test profiles for all
  three touched services raise this to 15 seconds (production is untouched); the WireMock "times out"
  contract-test fixtures were bumped from a 10s to a 20s fixed delay so they still exceed the new budget;
  and the new consumer/outage ITs' own `Awaitility` windows were widened to 20–30s to match.

## Status

SC-002, SC-003 and SC-005 are fully met with direct, repeatable test evidence. SC-001 and SC-004 are met
at the functional-correctness level (each case proven at least once via automated tests) but not yet
re-confirmed live or run as repeated trials. SC-006 is not measured against its literal 10-second figure
by any automated assertion — see Outstanding. SC-007 awaits the full-reactor run (T071) and live smoke
test (T070).

## Outstanding

- **T070** (live platform walkthrough) has not yet been run: `docker compose down -v`, `scripts/up.sh`,
  every step of `quickstart.md` by hand (including the certificate-service stop/restart step and the
  Mailpit API check for two distinct emails), then `scripts/smoke-test.sh`.
- **SC-006** has not been measured as a strict "within 10 seconds" bound by any automated assertion — only
  a widened, non-strict Awaitility budget exists. A dedicated timing measurement during the T070
  walkthrough, or a new test asserting a tight bound under normal (non-cold-start) conditions, would
  close this.
- **SC-004** has been proven once, not as a repeated-trial run — the same kind of gap 004's own SC-004
  record carried forward for its analogous criterion.
