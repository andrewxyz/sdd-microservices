# Feature Specification: User Accounts, Login, and Gateway-Enforced Access

**Feature Branch**: `002-user-auth`

**Created**: 2026-10-18

**Status**: Draft

**Input**: User description: "Add user accounts and login to LearnHub, and make the entry point enforce them. This spec covers the backend only (auth-service under backend/, plus the gateway's protected-route behavior). The frontend is not touched. ... (full text provided by user)"

## Clarifications

### Session 2026-10-18

- Q: When an administrator changes someone's role, must every request that person makes immediately see the new role, even from a token issued before the change? → A: Role changes take effect on that account's next login; an already-issued access token keeps acting on the role it was issued with until it expires (≤15 min).
- Q: Can one account be logged in from more than one place at a time, each with its own working refresh token, or does logging in somewhere new end any other active session? → A: Multiple concurrent sessions allowed — each login creates an independent refresh token; logout or reuse-detection on one session does not affect the others.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Register and log in (Priority: P1)

A prospective student creates an account with an email and password, then logs in and receives tokens that let them reach the rest of the platform without logging in on every request.

**Why this priority**: Nothing else in this feature has value until a person can create an account and prove who they are. This is the foundation every other story depends on.

**Independent Test**: Can be fully tested by registering a new account with a unique email and a password that meets the strength rule, then logging in with those credentials and receiving an access token and a refresh token.

**Acceptance Scenarios**:

1. **Given** no account exists for an email address, **When** a person registers with that email and a password meeting the strength rule, **Then** an account is created with the student role and no token is issued at registration time.
2. **Given** an email address already has an account, **When** a person tries to register with that same email, **Then** registration is refused and the existing account is not changed.
3. **Given** a password that does not meet the strength rule, **When** a person tries to register with it, **Then** registration is refused with a message naming which rule was not met, and no account is created.
4. **Given** a registered account, **When** the person logs in with the correct email and password, **Then** they receive a short-lived access token and a longer-lived refresh token.
5. **Given** a registered account, **When** the person logs in with the correct email but the wrong password, or with an email that has no account, **Then** login is refused with the same generic error message in both cases.
6. **Given** several failed login attempts in a row from the same address or against the same account, **When** another attempt is made immediately after, **Then** the response is delayed or refused rather than processed at the normal speed.

---

### User Story 2 - Stay logged in and log out (Priority: P2)

A logged-in person keeps using the platform across multiple sessions without re-entering credentials each time, and can deliberately end their session on demand.

**Why this priority**: Depends on Story 1 (an account and a first login must exist). It is next in priority because short-lived access tokens are only usable in practice if there is a way to renew them and a way to safely stop using them.

**Independent Test**: Can be fully tested by logging in to obtain a refresh token, using it to obtain a new access token after the original expires, then logging out and confirming the refresh token no longer works.

**Acceptance Scenarios**:

1. **Given** a valid, unused refresh token, **When** it is presented to renew a session, **Then** a new access token is issued without requiring the email and password again.
2. **Given** a person logs out, **When** their refresh token is presented afterward to renew a session, **Then** the request is refused.
3. **Given** a refresh token that has already been used once to renew a session, **When** it is presented again, **Then** the request is refused.
4. **Given** a refresh token that was never issued, has expired, or belongs to a revoked session, **When** it is presented, **Then** the request is refused with the platform's standard error structure.

---

### User Story 3 - Entry point enforces access on every route (Priority: P1)

A developer or operator of any other service behind the gateway can rely on the gateway to have already verified who is calling, so protected routes are safe by default and services never have to parse a token themselves.

**Why this priority**: This is what makes the whole feature matter beyond auth-service itself — it changes the behavior of every protected route already in the platform (the echo service's secure path) and every one added later. Tied with Story 1 as foundational, but sequenced after it because there must be a token to enforce before enforcement can be demonstrated end to end.

**Independent Test**: Can be fully tested by calling the previously-protected echo path with no token (expect refusal), with a valid token (expect success and identity/role information reaching the service), and with an expired or altered token (expect refusal).

**Acceptance Scenarios**:

1. **Given** a request to a public route (register, log in, or refresh), **When** no token is presented, **Then** the request still reaches the service that handles it.
2. **Given** a request to any other route, **When** no token is presented, **Then** the request is refused with the same 401 error structure the platform already uses for unauthenticated requests.
3. **Given** a request to a protected route, **When** a token that is expired or has been altered is presented, **Then** the request is refused with that same 401 error structure.
4. **Given** a request to a protected route, **When** a valid token is presented but the account's role does not meet that route's requirement, **Then** the request is refused with a 403 response.
5. **Given** a request to a protected route, **When** a valid token is presented and the role requirement is met, **Then** the request reaches the service, and the service receives the caller's identity and role without ever receiving the token itself.
6. **Given** the echo service's existing secure path, **When** it is called with a valid token, **Then** it responds successfully, which it did not before this feature.

---

### User Story 4 - Roles and the first administrator (Priority: P2)

An administrator manages what level of access other accounts have, and the platform has at least one administrator available as soon as it starts, without a manual database edit.

**Why this priority**: Depends on accounts existing (Story 1) and on enforcement being in place (Story 3) to be meaningful, since role changes only matter once routes actually check roles. It unlocks instructor- and administrator-only capabilities for future features.

**Independent Test**: Can be fully tested by starting the platform fresh, confirming an administrator account already exists and can log in, then using it to change another account's role and confirming a non-administrator cannot do the same.

**Acceptance Scenarios**:

1. **Given** a freshly started platform with no prior data, **When** it finishes starting, **Then** exactly one administrator account exists, created from configuration rather than through the registration flow.
2. **Given** a logged-in administrator, **When** they change another account's role, **Then** that account's role is updated and takes effect the next time that account logs in; any access token already issued to that account keeps working under its old role until it expires.
3. **Given** a logged-in student or instructor, **When** they attempt to change any account's role, **Then** the request is refused with a 403 response and no role is changed.

---

### User Story 5 - View own account (Priority: P3)

A logged-in person checks their own account details to confirm who the platform thinks they are and what role they hold.

**Why this priority**: Useful and low-risk, but not required for the core register/login/enforce/role loop to deliver value, so it is last.

**Independent Test**: Can be fully tested by logging in and requesting the current account's profile, confirming it shows the account's own email and role and nothing else.

**Acceptance Scenarios**:

1. **Given** a logged-in person, **When** they request their own profile, **Then** they receive their email, role, and account creation date, with no password or token data present anywhere in the response.
2. **Given** no token or an invalid token, **When** a profile request is made, **Then** it is refused the same way any other protected route refuses it (Story 3).

---

### Edge Cases

- What happens when someone registers with an email that differs from an existing account only by letter case (e.g., `A@x.com` vs `a@x.com`)? Treated as the same email and refused as a duplicate.
- What happens when an administrator tries to change their own role to something other than administrator, and they are the only administrator? Refused, so the platform is never left without at least one administrator. (See Assumptions.)
- What happens when a refresh token request and a logout request for the same refresh token arrive at nearly the same time? At most one of them succeeds; the token ends up fully revoked either way, never left usable.
- What happens when the access token's signing material is unavailable when the gateway starts? The gateway MUST fail to start rather than start in a state where it cannot verify tokens.
- What happens when a client sends an access token to a public route (register, log in, refresh)? The route still works; a token is never required on these routes, and an invalid one on them is ignored rather than refused.
- What happens when the token says a role that no longer matches the account, because the role was changed after the token was issued? The token's role claim still governs until the token expires (at most 15 minutes); the account must log in again to get a token with the new role (see FR-021).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The platform MUST let a person register an account with an email address and a password.
- **FR-002**: The platform MUST treat email addresses as unique regardless of letter case, and MUST refuse registration when the email is already registered, without revealing whether the conflict was case-only.
- **FR-003**: The platform MUST reject a registration whose password does not meet the password strength rule (minimum 10 characters, containing at least one letter and one digit) and MUST state which part of the rule was not met.
- **FR-004**: Every account created through registration MUST be assigned the student role; no other role MAY be chosen at registration time.
- **FR-005**: The platform MUST let a person log in with an email and password and, on success, MUST issue one access token and one refresh token.
- **FR-006**: Access tokens MUST expire no more than 15 minutes after issuance. Refresh tokens MUST expire no more than 30 days after issuance.
- **FR-007**: The platform MUST respond to a login attempt with the wrong password and to a login attempt for an email with no account using the same generic error message and the same response structure, so the two cases are indistinguishable to the caller.
- **FR-008**: The platform MUST slow down or refuse repeated failed login attempts, counted both per source address and per targeted account, before they reach the normal processing path.
- **FR-009**: The platform MUST let the holder of a valid, unused, unexpired refresh token obtain a new access token without supplying the email and password again.
- **FR-010**: The platform MUST let a logged-in person log out, which MUST immediately invalidate the refresh token used for that session.
- **FR-011**: The platform MUST refuse any attempt to reuse a refresh token that has already been used to renew a session or that has been revoked by logout, regardless of whether it has expired yet.
- **FR-012**: Every account MUST have exactly one role: student, instructor, or administrator.
- **FR-013**: The platform MUST refuse a role change requested by anyone other than an administrator.
- **FR-014**: The platform MUST refuse a role change that would leave the platform with zero administrator accounts.
- **FR-015**: On first startup against an empty database, the platform MUST create exactly one administrator account from configuration-supplied values (email and password), without requiring the registration flow.
- **FR-016**: The platform's entry point (gateway) MUST allow registration, login, and refresh requests through without requiring a token.
- **FR-017**: The platform's entry point MUST require a valid, unexpired, untampered access token on every route not explicitly marked public, and MUST refuse requests without one.
- **FR-018**: The platform's entry point MUST refuse requests bearing a missing, expired, malformed, or tampered access token using the platform's existing 401 problem-details structure, including the correlation ID.
- **FR-019**: The platform's entry point MUST refuse a request to a role-restricted route when the presented token's account role does not meet that route's role requirement, using a 403 response in the same problem-details structure.
- **FR-020**: The platform's entry point MUST pass the caller's account identity and role to the destination service for every authenticated request, and the destination service MUST NOT receive the raw access token.
- **FR-021**: Role and identity information the entry point passes to a destination service MUST be the role carried by the presented access token at the time it was issued. A role change MUST take effect the next time the affected account logs in (new token); it MUST NOT retroactively change what an already-issued, still-valid access token is authorized to do before it expires.
- **FR-022**: The echo service's existing protected path MUST succeed when called with a valid access token carrying a sufficient role, through the entry point.
- **FR-023**: The platform MUST let a logged-in person retrieve their own account's email, role, and creation date, and that response MUST NOT include the account's password (in any form) or any token value.
- **FR-024**: The platform MUST NOT write a password, an access token, or a refresh token to any log output, in plain form or otherwise recoverable form.
- **FR-025**: Auth-service's account, credential, and token-session data MUST be stored in a database dedicated to auth-service, not shared with any other service's data.
- **FR-026**: Every error response produced by auth-service MUST use the platform's standard problem-details error structure, including the correlation ID, consistent with every other backend service.

### Key Entities

- **Account**: A registered user of the platform. Attributes: unique identifier, email (case-insensitively unique), password (never stored or transmitted in a readable form), role (student, instructor, or administrator), creation date. One account has zero or more sessions over its lifetime.
- **Session (refresh token record)**: Represents one login's ability to renew its access. Attributes: which account it belongs to, when it was issued, when it expires, whether it has been used to renew, whether it has been revoked (e.g., by logout). An account MAY have more than one active session at a time (e.g., logged in from two places); each session's refresh token, logout, and reuse detection are independent of every other session belonging to the same account.
- **Access token**: A short-lived, self-contained proof of identity and role, issued to a session, verified by the entry point, and never stored server-side. Not a persisted entity, but referenced by every enforcement requirement above.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new person can go from registering to successfully calling a protected route in under 2 minutes, performing only register, log in, and one protected call.
- **SC-002**: 100% of requests to protected routes that present no token, an expired token, or a tampered token are refused, across at least 50 varied attempts of each kind, with zero false acceptances.
- **SC-003**: 100% of requests to protected routes that present a valid token but an insufficient role are refused with a role-mismatch response, across at least 20 varied attempts.
- **SC-004**: A revoked or already-used refresh token is refused 100% of the time it is presented again, across at least 20 attempts, with zero successful reuses.
- **SC-005**: Repeated failed login attempts against one account or from one source are measurably slower or refused by the 10th consecutive failure within a 1-minute window, compared to an isolated first attempt.
- **SC-006**: A freshly started platform, with no manual steps, has exactly one working administrator login available immediately after startup completes, in 100% of startup attempts.
- **SC-007**: The full register, log in, call a protected route, refresh, and log out sequence completes successfully end to end through the entry point in 100% of attempts under normal operating conditions.
- **SC-008**: None of the platform's logs, inspected after a full exercise of every flow in this feature, contain a password or a token value in any recoverable form.
- **SC-009**: All platform behaviors already relied upon before this feature — startup, service discovery, central configuration pickup, and request rate limiting at the entry point — continue to pass their existing checks unchanged after this feature is added.

## Assumptions

- A person is identified only by email and password; no phone number, username, or social identity is part of this feature.
- The password strength rule (minimum 10 characters, at least one letter and one digit) is a reasonable default in the absence of a specified policy; it can be tightened later without changing this spec's intent.
- Access tokens expire in 15 minutes and refresh tokens in 30 days; these are reasonable defaults for a short-lived/long-lived pair and may be tuned later as configuration rather than re-specified.
- "Slowed down" login attempts (FR-008, SC-005) may be implemented as a delay, a temporary lockout, or a request limit — this spec requires the observable effect (attacker's attempts measurably penalized) and leaves the exact mechanism to the plan.
- The first administrator's email and password come from configuration (e.g., environment values) read once at startup; rotating or changing that account afterward uses the normal role-change and login flows, not a special path.
- Concurrent sessions are explicitly supported (see Clarifications): one account may hold several independent, simultaneously-valid refresh tokens, one per login, with no cap imposed by this feature.
- "The entry point" refers to the platform's existing gateway, which already enforces route-level public/protected marking and produces the existing problem-details error structure (established by the platform-foundation feature); this feature extends that enforcement to require real tokens instead of blocking everything protected.
- Course, payment, and notification-triggering features (email verification, password reset) are out of scope, as stated by the user, and nothing in this spec depends on them existing.
