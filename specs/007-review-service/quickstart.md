# Quickstart: Review Service

Validates the feature end-to-end against the running Docker stack (`docker-compose.yml` at repo root).
Run everything through WSL per project convention: `wsl -e bash -lc "<command>"`.

## Prerequisites

- Platform is up: `docker compose up -d` (or `scripts/up.sh`), including the new `review-service`
  container and its `review` Postgres database.
- A test student account exists and is enrolled in a test course (reuse the seed data / accounts from
  specs/004-checkout-enrollment's or specs/006-student-web-app's quickstart — a logged-in student with at
  least one active enrollment).
- An `ACCESS_TOKEN` for that student, obtained the same way as in prior specs' quickstarts
  (`POST /api/v1/auth/login`).

## Scenario 1 — Enrolled student submits a review (User Story 1, FR-001, FR-002)

```bash
curl -s -X POST http://localhost:9080/api/v1/reviews/courses/$COURSE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"rating":5,"comment":"Clear and practical."}'
```

Expected: `201 Created` with the new `ReviewView` (FR-001). Repeating the same request returns `200 OK`
with the same review id and the new rating/comment (FR-003 — upsert, not a duplicate).

## Scenario 2 — Non-enrolled student is rejected (FR-002)

Using a token for a student with no enrollment in `$COURSE_ID`, repeat Scenario 1's request. Expected:
`403 Forbidden` problem-details body naming the missing enrollment.

## Scenario 3 — Validation rejects abuse (FR-007, FR-012)

```bash
curl -s -X POST http://localhost:9080/api/v1/reviews/courses/$COURSE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"rating":7,"comment":"fine"}'
```

Expected: `400 Bad Request` (rating out of range). Repeat with a 600+ character comment, and separately
with a comment containing a word from the blocked list — both `400 Bad Request`.

## Scenario 4 — Student edits and deletes their own review (User Story 2, FR-004, FR-005, FR-006)

```bash
REVIEW_ID=$(curl -s http://localhost:9080/api/v1/reviews/courses/$COURSE_ID | jq -r '.items[0].id')

curl -s -X PUT http://localhost:9080/api/v1/reviews/$REVIEW_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"rating":4,"comment":"Updated after finishing the course."}'

curl -s -X DELETE http://localhost:9080/api/v1/reviews/$REVIEW_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" -w '%{http_code}\n' -o /dev/null
```

Expected: edit returns `200 OK` with the updated fields; delete returns `204 No Content`. A second
delete, or an edit/delete attempted with a *different* student's token, returns `404 Not Found` (FR-006).

## Scenario 5 — Public browsing with no authentication (User Story 3, FR-008, FR-009, SC-004)

```bash
curl -s http://localhost:9080/api/v1/reviews/courses/$COURSE_ID
curl -s http://localhost:9080/api/v1/reviews/courses/$COURSE_ID/summary
```

Expected: both succeed with no `Authorization` header. The summary's `reviewCount` matches the number of
currently-active reviews returned by the list endpoint, and `averageRating` matches their mean (SC-003).
For a course with zero active reviews, the summary returns `reviewCount: 0` and no `averageRating` rather
than an error.

## Contract and integration test entry points

- Feign contract test for the reused enrollment lookup: mirror
  `backend/course-service/.../EnrollmentLookupClientContractTest.java`, targeting review-service's own
  `EnrollmentLookupClient`.
- Integration tests: Testcontainers PostgreSQL only (no RabbitMQ container needed — R1), following the
  shape of `backend/certificate-service/.../support/AbstractCertificateServiceIT.java` minus its RabbitMQ
  setup.
