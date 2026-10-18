# Quickstart: User Accounts, Login, and Gateway-Enforced Access

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

Validates this feature end to end through the gateway, the only entry point (constitution Principle IV).
Run after `scripts/up.sh` (see the platform-foundation [quickstart](../001-platform-foundation/quickstart.md)
for first-time startup). All requests go to the gateway at `http://localhost:9080`.

## Prerequisites

- The platform is up (`scripts/up.sh`) with this feature's code deployed.
- `ADMIN_EMAIL` and `ADMIN_PASSWORD` are set in `.env` (used once, at auth-service's first startup against
  an empty `auth` database, to create the first administrator — FR-015).
- `jq` and `curl` available for the walkthrough below (optional; raw `curl` output works too).

## 1. Register and log in (User Story 1)

```sh
curl -s -X POST http://localhost:9080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse-1"}'
# Expect 201, role "STUDENT", no password or token in the response.

curl -s -i -X POST http://localhost:9080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"ADA@example.com","password":"correct-horse-1"}'
# Expect 409: the email collides case-insensitively with the one just registered (FR-002).

TOKENS=$(curl -s -X POST http://localhost:9080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"correct-horse-1"}')
ACCESS=$(echo "$TOKENS" | jq -r .accessToken)
REFRESH=$(echo "$TOKENS" | jq -r .refreshToken)
# Expect 200 with accessToken, refreshToken and expiresInSeconds.

curl -s -i -X POST http://localhost:9080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ada@example.com","password":"wrong-password"}'
curl -s -i -X POST http://localhost:9080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"nobody@example.com","password":"whatever-1"}'
# Expect the same 401 body and status for both (FR-007).
```

## 2. Entry-point enforcement on the existing echo path (User Story 3)

```sh
curl -s -i http://localhost:9080/api/v1/echo/secure/ping
# Expect 401 (no token) — the behavior platform-foundation shipped as a stub now has a real cause.

curl -s -i http://localhost:9080/api/v1/echo/secure/ping \
  -H "Authorization: Bearer $ACCESS"
# Expect 200, succeeding for the first time since platform-foundation shipped this path (FR-022).

curl -s -i http://localhost:9080/api/v1/echo/secure/ping \
  -H "Authorization: Bearer not-a-real-token"
curl -s -i http://localhost:9080/api/v1/echo/secure/ping \
  -H "Authorization: Bearer ${ACCESS}tampered"
# Expect 401 for both (malformed and tampered, FR-018).
```

## 3. View own account (User Story 5)

```sh
curl -s http://localhost:9080/api/v1/auth/me -H "Authorization: Bearer $ACCESS"
# Expect 200 with id, email, role "STUDENT", createdAt — and no password or token field anywhere.
```

## 4. Stay logged in and log out (User Story 2)

```sh
NEW_TOKENS=$(curl -s -X POST http://localhost:9080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
NEW_ACCESS=$(echo "$NEW_TOKENS" | jq -r .accessToken)
NEW_REFRESH=$(echo "$NEW_TOKENS" | jq -r .refreshToken)
# Expect 200 with a new access token and a new refresh token.

curl -s -i -X POST http://localhost:9080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"
# Expect 401: the original refresh token was already used once (R2, FR-011).

curl -s -i -X POST http://localhost:9080/api/v1/auth/logout \
  -H "Authorization: Bearer $NEW_ACCESS" -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$NEW_REFRESH\"}"
# Expect 204.

curl -s -i -X POST http://localhost:9080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$NEW_REFRESH\"}"
# Expect 401: revoked by logout (FR-010, FR-011).
```

## 5. Roles and the first administrator (User Story 4)

```sh
ADMIN_TOKENS=$(curl -s -X POST http://localhost:9080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")
ADMIN_ACCESS=$(echo "$ADMIN_TOKENS" | jq -r .accessToken)
# Expect 200: the administrator account already exists from the platform's first startup (FR-015).

STUDENT_ID=$(curl -s http://localhost:9080/api/v1/auth/me -H "Authorization: Bearer $ACCESS" | jq -r .id)

curl -s -i -X PATCH "http://localhost:9080/api/v1/auth/accounts/$STUDENT_ID/role" \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"role":"INSTRUCTOR"}'
# Expect 403: a student cannot change roles, including their own (FR-013).

curl -s -i -X PATCH "http://localhost:9080/api/v1/auth/accounts/$STUDENT_ID/role" \
  -H "Authorization: Bearer $ADMIN_ACCESS" -H 'Content-Type: application/json' \
  -d '{"role":"INSTRUCTOR"}'
# Expect 200 from the administrator. ada's *next* login reflects INSTRUCTOR; her current $ACCESS
# token, issued before the change, keeps acting as STUDENT until it expires (spec.md Clarifications).
```

## 6. Confirm nothing else regressed (SC-009)

```sh
scripts/smoke-test.sh
# Expect the existing platform-foundation checks (startup, registry, config refresh, rate limiting)
# to still pass unchanged.
```

## Success criteria covered

SC-001 (register → protected call in one short session), SC-002/SC-003 (steps 2 and 5's negative cases,
repeated with varied tokens per the measured success criteria), SC-004 (step 4's reuse checks), SC-006
(step 5's first-login), SC-007 (steps 1, 2 and 4 chained), SC-009 (step 6). SC-005 (login throttling) and
SC-008 (no secrets in logs) are verified by automated tests in `backend/auth-service` rather than by hand
here (see tasks.md), since they require repeated rapid attempts and log inspection respectively.
