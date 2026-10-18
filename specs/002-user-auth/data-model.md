# Data Model: User Accounts, Login, and Gateway-Enforced Access

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

All persisted data lives in auth-service's own `auth` database (already provisioned, empty, by the
platform-foundation `db-init` service). No other service's database is read or written by this feature.

## Account

A registered user of the platform (FR-001 to FR-004, FR-012 to FR-015, FR-023).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key, generated at creation |
| `email` | Stored lowercased; unique index on the lowercased value (FR-002: case-insensitive uniqueness) |
| `passwordHash` | BCrypt hash (R7); never selected into any API response; never logged (FR-024) |
| `role` | One of `STUDENT`, `INSTRUCTOR`, `ADMINISTRATOR` (FR-012); database check constraint on the column |
| `createdAt` | Set once at creation; returned by the "view own account" response (FR-023) |

Validation rules:

- Email: syntactically valid address; required; case-insensitively unique (FR-002).
- Password (registration and bootstrap only; never stored): minimum 10 characters, containing at least one
  letter and one digit (FR-003, spec.md Assumptions). Rejected with a message naming which part failed.
- Role: immutable by the account itself; only changeable by an administrator, through the dedicated role
  endpoint (FR-013), and never to a value that would leave zero administrator accounts (FR-014).
- Registration always creates `role = STUDENT` (FR-004); no other value is accepted at registration.

State: accounts are not soft-deleted or disabled in this feature (account deletion is explicitly out of
scope); the only mutable field after creation is `role`.

## RefreshTokenRecord (session)

Represents one login's ability to renew its access, independent of every other session belonging to the
same account (spec.md Clarifications, 2026-10-18; FR-009 to FR-011).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key |
| `accountId` | Foreign key to `Account.id`; an account may have any number of concurrent rows (no cap) |
| `tokenHash` | SHA-256 hash of the refresh token value (R2); the raw value is never stored |
| `issuedAt` | Set when the row is created (at login, or at rotation from a prior row) |
| `expiresAt` | `issuedAt` + 30 days (FR-006) |
| `usedAt` | Null until this token is presented to `/refresh`; set exactly once, at which point a replacement row is created (R2) |
| `revokedAt` | Null until logout (FR-010) explicitly revokes this row |

State transitions:

```text
issued --(presented to /refresh, valid)--> used (replacement row issued) --(reused)--> rejected
issued --(presented to /logout)----------> revoked --(reused)---------------------------> rejected
issued --(expiresAt passes)--------------> rejected (expired)
```

A row is accepted by `/refresh` only when `usedAt IS NULL AND revokedAt IS NULL AND expiresAt > now()`
(FR-011). Any other presentation — already used, already revoked, or expired — is refused with the same
401 problem-details structure used for every other authentication failure at the entry point.

## AccessToken (not persisted)

A short-lived, self-contained JWT (R1, R9), issued by auth-service, verified by the gateway, never written
to any database or log (FR-024). Documented here because its claim shape is a contract other components
(the gateway, and this feature's tests) rely on; see
[`contracts/jwt-claims.schema.json`](contracts/jwt-claims.schema.json).

| Claim | Rule |
|-------|------|
| `sub` | `Account.id` (string form of the UUID) |
| `role` | The account's role **at the time this token was issued** (spec.md Clarifications, 2026-10-18: a later role change takes effect on next login, not retroactively on an already-issued token) |
| `iat` | Issued-at time |
| `exp` | `iat` + 15 minutes (FR-006) |

## LoginAttemptWindow (in-memory only, not persisted)

Tracks recent failed login attempts for throttling (FR-008, SC-005, R3). Not a database entity; kept in
auth-service process memory, keyed independently by:

- normalized (lowercased) email, and
- source IP address of the login request.

Each key records a rolling count of failures within the last 60 seconds. A key at or above 5 failures in
that window causes further attempts under that key to be refused (429) until the window rolls over. A
successful login for an email clears that email's window; it does not clear the source-IP window (a shared
address, e.g. a NAT gateway, may still be throttled independently).
