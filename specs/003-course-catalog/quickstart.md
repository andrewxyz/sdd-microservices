# Quickstart: Course Catalog

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Validates this feature end to end through the gateway, the only entry point for clients (constitution
Principle IV). Run after `scripts/up.sh`. All requests go to the gateway at `http://localhost:9080`.
Needs an instructor account and an administrator account — see
[specs/002-user-auth/quickstart.md](../002-user-auth/quickstart.md) for how to register and promote one.

## Prerequisites

- The platform is up (`scripts/up.sh`) with this feature's code deployed.
- `INSTRUCTOR_ACCESS` — an access token for an account with the INSTRUCTOR role.
- `ADMIN_ACCESS` — an access token for an account with the ADMINISTRATOR role.

## 1. Administrator creates a category (User Story 4)

```sh
curl -s -X POST http://localhost:9080/api/v1/categories \
  -H "Authorization: Bearer $ADMIN_ACCESS" -H 'Content-Type: application/json' \
  -d '{"name":"Web Development"}'
# Expect 201. Save the returned id as $CATEGORY_ID.

curl -s -i -X POST http://localhost:9080/api/v1/categories \
  -H "Authorization: Bearer $ADMIN_ACCESS" -H 'Content-Type: application/json' \
  -d '{"name":"web development"}'
# Expect 409: the name collides case-insensitively (FR-020).

curl -s -i -X POST http://localhost:9080/api/v1/categories \
  -H "Authorization: Bearer $INSTRUCTOR_ACCESS" -H 'Content-Type: application/json' \
  -d '{"name":"Not Allowed"}'
# Expect 403: only an administrator may manage categories (FR-022).
```

## 2. Instructor creates a draft and tries to publish too early (User Stories 2 and 3)

```sh
DRAFT=$(curl -s -X POST http://localhost:9080/api/v1/courses \
  -H "Authorization: Bearer $INSTRUCTOR_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"title\":\"Intro to LearnHub\",\"description\":\"A starter course.\",\"categoryId\":\"$CATEGORY_ID\",\"priceCents\":0}")
echo "$DRAFT"
COURSE_ID=$(echo "$DRAFT" | jq -r .id)
VERSION=$(curl -si http://localhost:9080/api/v1/courses/$COURSE_ID/full -H "Authorization: Bearer $INSTRUCTOR_ACCESS" | grep -i '^etag:' | tr -d '\r')

curl -s -i -X PATCH "http://localhost:9080/api/v1/courses/$COURSE_ID/publish" \
  -H "Authorization: Bearer $INSTRUCTOR_ACCESS" -H "If-Match: $VERSION"
# Expect 422: no lessons yet (FR-014).
```

## 3. Add a lesson and publish for real (User Story 3)

```sh
curl -s -X POST "http://localhost:9080/api/v1/courses/$COURSE_ID/lessons" \
  -H "Authorization: Bearer $INSTRUCTOR_ACCESS" -H "If-Match: $VERSION" -H 'Content-Type: application/json' \
  -d '{"title":"Welcome","durationMinutes":5,"content":"Full lesson text goes here."}'
# Expect 201.

VERSION=$(curl -si "http://localhost:9080/api/v1/courses/$COURSE_ID/full" -H "Authorization: Bearer $INSTRUCTOR_ACCESS" | grep -i '^etag:' | tr -d '\r')
curl -s -i -X PATCH "http://localhost:9080/api/v1/courses/$COURSE_ID/publish" \
  -H "Authorization: Bearer $INSTRUCTOR_ACCESS" -H "If-Match: $VERSION"
# Expect 200.
```

## 4. Anonymous visitors see exactly the published course (User Story 1)

```sh
curl -s http://localhost:9080/api/v1/courses
# Expect the new course in the list; no draft or archived course ever appears (FR-005).

curl -s "http://localhost:9080/api/v1/courses?q=nonexistentkeyword12345"
# Expect an empty items array, not an error (FR-004).

curl -s "http://localhost:9080/api/v1/courses/$COURSE_ID"
# Expect the public view: no lesson "content" field anywhere (FR-006).
```

## 5. Ownership and the concurrency guard (User Stories 2 and 5; FR-025)

```sh
curl -s -i -X PUT "http://localhost:9080/api/v1/courses/$COURSE_ID" \
  -H "Authorization: Bearer $ADMIN_ACCESS" -H 'Content-Type: application/json' \
  -H "If-Match: stale-or-missing-value" -d '{"title":"Edited by someone else"}'
# Expect 409: a stale/missing If-Match is refused (FR-025), even for an administrator.

VERSION=$(curl -si "http://localhost:9080/api/v1/courses/$COURSE_ID/full" -H "Authorization: Bearer $INSTRUCTOR_ACCESS" | grep -i '^etag:' | tr -d '\r')
curl -s -i -X PUT "http://localhost:9080/api/v1/courses/$COURSE_ID" \
  -H "Authorization: Bearer $STUDENT_ACCESS" -H "If-Match: $VERSION" -d '{"title":"Not mine"}'
# Expect 403: a non-owner, non-administrator is refused (FR-011).
```

## 6. Archive and republish (User Story 3)

```sh
VERSION=$(curl -si "http://localhost:9080/api/v1/courses/$COURSE_ID/full" -H "Authorization: Bearer $INSTRUCTOR_ACCESS" | grep -i '^etag:' | tr -d '\r')
curl -s -o /dev/null -w '%{http_code}\n' -X PATCH "http://localhost:9080/api/v1/courses/$COURSE_ID/archive" \
  -H "Authorization: Bearer $INSTRUCTOR_ACCESS" -H "If-Match: $VERSION"
# Expect 200.

curl -s "http://localhost:9080/api/v1/courses" | grep -c "$COURSE_ID" || echo "0 (correctly absent)"

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE "http://localhost:9080/api/v1/courses/$COURSE_ID" \
  -H "Authorization: Bearer $INSTRUCTOR_ACCESS"
# Expect 409: only a draft can be deleted (FR-013); archived still isn't draft.
```

## 7. The service-to-service lookup has no public route (User Story 6, FR-023)

```sh
curl -s -i http://localhost:9080/api/v1/internal/courses/$COURSE_ID
# Expect 404 "No route matches this path" — not 401/403, there is simply no route at all.
```

## 8. Confirm nothing else regressed (SC-008)

```sh
scripts/smoke-test.sh
# Expect the existing platform-foundation and auth checks to still pass unchanged.
```

## Success criteria covered

SC-001 (steps 2-3), SC-002 (step 4), SC-003 (step 5), SC-005 (step 4's search), SC-007 (step 1), SC-008
(step 8). SC-004 (10,000-course performance) and SC-006 (concurrent-edit volume) are verified by
automated load/concurrency tests rather than by hand here — see tasks.md.
