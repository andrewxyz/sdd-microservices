# Quickstart: Lesson Progress, Certificates, and Email Notifications

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Validates this feature end to end through the gateway, the only entry point for clients (constitution
Principle IV). Run after `scripts/up.sh`. All requests go to the gateway at `http://localhost:9080`.
Needs a published course with a small, known number of lessons and a student enrolled in it — see
[specs/004-checkout-enrollment/quickstart.md](../004-checkout-enrollment/quickstart.md) for checkout, and
[specs/003-course-catalog/quickstart.md](../003-course-catalog/quickstart.md) for publishing a course
with lessons.

## Prerequisites

- The platform is up (`scripts/up.sh`) with this feature's code deployed.
- `STUDENT_ACCESS` — an access token for a student already enrolled in `$COURSE_ID`, a course with
  exactly `$LESSON_COUNT` lessons (`$LESSON_1_ID`, `$LESSON_2_ID`, ... in order).

## 1. Mark lessons complete and watch progress change (User Story 1)

```sh
curl -s -X PUT "http://localhost:9080/api/v1/enrollments/$COURSE_ID/lessons/$LESSON_1_ID/complete" \
  -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect 200: completedLessons 1, totalLessons unchanged, courseCompletedAt still null.

curl -s -X PUT "http://localhost:9080/api/v1/enrollments/$COURSE_ID/lessons/$LESSON_1_ID/complete" \
  -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect 200, identical result: marking twice changes nothing (FR-004).

curl -s "http://localhost:9080/api/v1/enrollments/$COURSE_ID/progress" -H "Authorization: Bearer $STUDENT_ACCESS"

curl -s -i -X PUT "http://localhost:9080/api/v1/enrollments/$COURSE_ID/lessons/$(uuidgen)/complete" \
  -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect 404: that lesson id does not belong to this course (FR-006).
```

## 2. Unmark a lesson (User Story 1)

```sh
curl -s -X DELETE "http://localhost:9080/api/v1/enrollments/$COURSE_ID/lessons/$LESSON_1_ID/complete" \
  -H "Authorization: Bearer $STUDENT_ACCESS"
curl -s "http://localhost:9080/api/v1/enrollments/$COURSE_ID/progress" -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect completedLessons back to 0.
```

## 3. Complete every lesson — the enrollment completes exactly once (User Story 2)

```sh
for LESSON_ID in $LESSON_1_ID $LESSON_2_ID ...; do
  curl -s -X PUT "http://localhost:9080/api/v1/enrollments/$COURSE_ID/lessons/$LESSON_ID/complete" \
    -H "Authorization: Bearer $STUDENT_ACCESS" > /dev/null
done

curl -s "http://localhost:9080/api/v1/enrollments/$COURSE_ID/progress" -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect completedLessons == totalLessons, courseCompletedAt set.

curl -s -X DELETE "http://localhost:9080/api/v1/enrollments/$COURSE_ID/lessons/$LESSON_1_ID/complete" \
  -H "Authorization: Bearer $STUDENT_ACCESS" > /dev/null
curl -s -X PUT "http://localhost:9080/api/v1/enrollments/$COURSE_ID/lessons/$LESSON_1_ID/complete" \
  -H "Authorization: Bearer $STUDENT_ACCESS" > /dev/null
curl -s "http://localhost:9080/api/v1/enrollments/$COURSE_ID/progress" -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect the same courseCompletedAt as before — unmark-then-remark never resets it (FR-009).
```

## 4. Exactly one certificate appears, with the right fields (User Story 3)

```sh
sleep 10
curl -s "http://localhost:9080/api/v1/certificates/mine" -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect exactly one certificate for $COURSE_ID: a verificationCode, courseTitle, and issuedAt.
```

## 5. Anyone can verify it, learning nothing about the holder (User Story 4)

```sh
CODE=$(curl -s "http://localhost:9080/api/v1/certificates/mine" -H "Authorization: Bearer $STUDENT_ACCESS" \
  | jq -r '.items[0].verificationCode')

curl -s "http://localhost:9080/api/v1/certificates/verify/$CODE"
# Expect 200: valid true, the course title, the issued date — no userId, no email, nothing about who holds it.

curl -s -i "http://localhost:9080/api/v1/certificates/verify/NOTAREALCODE0000"
# Expect 404: a clear "not valid" result, not a 500.
```

## 6. Two emails are visible in the local catcher (User Story 6)

```sh
# Mailpit's own API lists captured messages; check the student's address appears twice (once per
# milestone) since enrolling and completing the course above.
curl -s http://localhost:9825/api/v1/messages | jq '.messages[] | select(.To[0].Address=="'"$STUDENT_EMAIL"'") | .Subject'
# Expect two distinct subjects: one for enrollment, one for the certificate.
```

## 7. Reliability: stop certificate-service mid-completion (User Story 7)

```sh
docker compose stop certificate-service
curl -s -X PUT "http://localhost:9080/api/v1/enrollments/$COURSE2_ID/lessons/$LAST_LESSON_ID/complete" \
  -H "Authorization: Bearer $STUDENT_ACCESS"
# Expect 200: the enrollment still completes normally — certificate-service being down never blocks progress.

docker compose start certificate-service
sleep 15
curl -s "http://localhost:9080/api/v1/certificates/mine" -H "Authorization: Bearer $STUDENT_ACCESS" \
  | jq '[.items[] | select(.courseId=="'"$COURSE2_ID"'")] | length'
# Expect exactly 1, created once certificate-service came back (FR-025).
```

## 8. The three internal lookups have no gateway route

```sh
curl -s -i http://localhost:9080/api/v1/internal/courses/$COURSE_ID
curl -s -i http://localhost:9080/api/v1/internal/accounts/$(uuidgen)
# Expect 404 "No route matches this path" for both — not 401/403, there is simply no route at all.
```

## 9. Confirm nothing else regressed (SC-007)

```sh
scripts/smoke-test.sh
# Expect the existing platform-foundation, auth, course-catalog and checkout/enrollment checks to still pass.
```

## Success criteria covered

SC-001 (steps 3-4), SC-002 (step 5), SC-003 (step 6), SC-005 (step 1), SC-007 (step 9). SC-004
(certificate-service outage) is step 7. SC-006 (10-second issuance) is verified by the timing in steps 4
and 7 here and by automated tests rather than a strict bound in this manual walkthrough — see tasks.md.
