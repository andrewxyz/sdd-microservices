# Quickstart: Checkout and Enrollment

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Validates this feature end to end through the gateway, the only entry point for clients (constitution
Principle IV). Run after `scripts/up.sh`. All requests go to the gateway at `http://localhost:9080`.
Needs a published free course and a published paid course — see
[specs/003-course-catalog/quickstart.md](../003-course-catalog/quickstart.md) for how an instructor
publishes one — and two distinct student accounts, see
[specs/002-user-auth/quickstart.md](../002-user-auth/quickstart.md) for how to register one.

## Prerequisites

- The platform is up (`scripts/up.sh`) with this feature's code deployed.
- `STUDENT_ACCESS` / `STUDENT2_ACCESS` — access tokens for two distinct, non-instructor accounts.
- `FREE_COURSE_ID` — a published course with `priceCents: 0`.
- `PAID_COURSE_ID` — a published course with `priceCents` greater than zero.

## 1. Checkout and read content for a free course (User Story 1)

```sh
curl -s -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$FREE_COURSE_ID\",\"idempotencyKey\":\"free-1\"}"
# Expect 201, status SUCCEEDED, amountCents 0, no payment provider involved (FR-005).

sleep 5
curl -s http://localhost:9080/api/v1/courses/$FREE_COURSE_ID/content \
  -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect 200 with full lesson content (FR-021), after automatic enrollment completes (FR-014).
```

## 2. Checkout and read content for a paid course (User Story 1)

```sh
curl -s -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$PAID_COURSE_ID\",\"idempotencyKey\":\"paid-1\",\"paymentToken\":\"sim-success\"}"
# Expect 201, status SUCCEEDED, amountCents equal to the course's current catalog price (FR-006).
PAYMENT_ID=$(curl -s -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$PAID_COURSE_ID\",\"idempotencyKey\":\"paid-1\",\"paymentToken\":\"sim-success\"}" | jq -r .id)

sleep 5
curl -s http://localhost:9080/api/v1/courses/$PAID_COURSE_ID/content \
  -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect 200 with full lesson content.
```

## 3. A declined or unavailable payment grants nothing (User Story 2)

```sh
curl -s -i -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT2_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$PAID_COURSE_ID\",\"idempotencyKey\":\"decline-1\",\"paymentToken\":\"sim-decline\"}"
# Expect 201 with status DECLINED and a failureReason (FR-008); no enrollment created.

curl -s http://localhost:9080/api/v1/courses/$PAID_COURSE_ID/content \
  -H "Authorization: Bearer $STUDENT2_ACCESS"
# Expect 403: never enrolled.

curl -s -i -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT2_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$PAID_COURSE_ID\",\"idempotencyKey\":\"unavail-1\",\"paymentToken\":\"sim-unavailable\"}"
# Expect 201 with status FAILED and a failureReason explaining the provider was unavailable; no
# enrollment created either.
```

## 4. Safe retries (User Story 4)

```sh
curl -s -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT2_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$FREE_COURSE_ID\",\"idempotencyKey\":\"retry-1\"}"
curl -s -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT2_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$FREE_COURSE_ID\",\"idempotencyKey\":\"retry-1\"}"
# Expect both to return the identical payment id (FR-011) — confirm by comparing the "id" field.

curl -s -i -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT2_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$PAID_COURSE_ID\",\"idempotencyKey\":\"retry-1\",\"paymentToken\":\"sim-success\"}"
# Expect 409: "retry-1" was already used for a different request (FR-012).
```

## 5. Payment history is private (User Story 5)

```sh
curl -s http://localhost:9080/api/v1/payments -H "Authorization: Bearer $STUDENT_ACCESS" | jq '.items | length'
# Expect at least the two payments from steps 1-2, and none of STUDENT2's.

curl -s http://localhost:9080/api/v1/payments -H "Authorization: Bearer $STUDENT2_ACCESS" | jq '.items | length'
# Expect only STUDENT2's own payments (the decline, the failure, and the retry one) — never STUDENT's.

curl -s -o /dev/null -w '%{http_code}\n' \
  http://localhost:9080/api/v1/payments/$PAYMENT_ID -H "Authorization: Bearer $STUDENT2_ACCESS"
# Expect 404: that payment belongs to STUDENT, not STUDENT2 (FR-020).
```

## 6. Instructors and already-enrolled users cannot check out (Edge cases)

```sh
curl -s -i -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $INSTRUCTOR_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$PAID_COURSE_ID\",\"idempotencyKey\":\"own-course-1\",\"paymentToken\":\"sim-success\"}"
# Expect 403 if $INSTRUCTOR_ACCESS owns $PAID_COURSE_ID (FR-003).

curl -s -i -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$PAID_COURSE_ID\",\"idempotencyKey\":\"double-pay-1\",\"paymentToken\":\"sim-success\"}"
# Expect 409: STUDENT already has a succeeded payment for this course (FR-004).
```

## 7. Reliability under failure (User Story 6)

```sh
docker compose stop enrollment-service
curl -s -X POST http://localhost:9080/api/v1/checkouts \
  -H "Authorization: Bearer $STUDENT2_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"courseId\":\"$PAID_COURSE_ID\",\"idempotencyKey\":\"resilience-1\",\"paymentToken\":\"sim-success\"}"
# Expect 201, status SUCCEEDED, same as always — enrollment-service being down never blocks checkout.

docker compose start enrollment-service
sleep 10
curl -s http://localhost:9080/api/v1/enrollments -H "Authorization: Bearer $STUDENT2_ACCESS" | \
  jq ".items[] | select(.courseId==\"$PAID_COURSE_ID\")"
# Expect exactly one enrollment, created after enrollment-service came back (FR-025).
```

## 8. Confirm nothing else regressed (SC-007)

```sh
scripts/smoke-test.sh
# Expect the existing platform-foundation, auth and course-catalog checks to still pass unchanged.
```

## Success criteria covered

SC-001 (steps 1-2), SC-002 (step 3), SC-005 (step 3's content check), SC-007 (step 8). SC-003
(20-concurrent-checkout) and SC-004/SC-006 (enrollment timing and exactly-once-under-failure volume
checks) are verified by automated concurrency/timing tests rather than by hand here — see tasks.md.
