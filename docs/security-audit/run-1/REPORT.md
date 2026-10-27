# Security audit: api-gateway and auth-service (LearnHub)

## 1. Run summary
| | |
|---|---|
| Run | `run-1`, profile **standard**, scoped run (partial coverage, see section 7) |
| Target | `sdd-microservices` @ `ea2fd6c22985` (branch master). The only uncommitted change was the audit skill folder itself; no reviewed source differed from the commit |
| Scope | `backend/api-gateway`, `backend/auth-service`, `backend/config-repo/{api-gateway,application,application-local,auth-service}.yml`, the gateway/auth/frontend entries of `docker-compose.yml`, `.env.example`, `frontend/nginx/default.conf.template`. `platform-common` and `shared-events` were read for context only |
| Execution | **Source-only.** This Windows host has no OS-enforced sandbox, so no target code was run (no build, test, container or request against a service). Every check is a source read; anything decisive that needs a running system is a `needs_validation` lead |
| Budget | Cap of 40 agent calls (set by the parent, not the owner): **34 spent**, 6 left |
| Prior runs | None, so no carried confirmations and no prior-ledger gaps |
| Coverage | **Partial.** 19 ledger units: 5 covered with no lead, 11 produced leads, 3 deferred (section 7). The final-clean critic did not run, so **no clean-coverage claim is made** |

## 2. Security posture in one paragraph
**No vulnerability was confirmed.** Nothing could be executed, and a finding needs an observed result, so every issue below is a *lead*, not a finding. Nine source-grounded leads survived independent validation and a second independent record check; one more was rejected. The gateway's core controls read well: identity headers are stripped and re-injected on every route, routing is default-deny, and JWT verification rejects unsigned tokens. The leads concentrate on one theme: **authorisation lives only at the gateway**. Services trust identity headers and have no authentication of their own, so anything that skips or confuses the gateway (a differently parsed path, a directly published port) matters. Each lead names the cheapest check that would settle it.

## 3. Confirmed findings
None. See sections 5 and 6.

## 4. (Reserved)
`FINDINGS-DETAIL.md` holds the detailed reproduction for confirmed medium, high or critical findings; there are none this run.

## 5. NEEDS VALIDATION: leads in suggested validation order (no severity assigned)
Each lead is source-grounded, was independently re-checked twice, and is unresolved only because a decisive fact needs a running system or a deployment observation. Full traces, blockers and plans are in `NEEDS-VALIDATION.md`.

| # | Lead | Boundary | Why this order |
|---|---|---|---|
| 1 | ADMINISTRATOR-only role-change route (and the unrouted internal account lookup) may be reachable through a non-normalized path that the gateway matches differently from Tomcat | Gateway role policy versus servlet path normalisation | If it holds, any signed-in student could promote themselves to ADMINISTRATOR, and the unauthenticated internal lookup could be reached. Cheap to settle: one gateway-plus-stub test on a throwaway stack. |
| 2 | Directly published service ports let a network caller bypass the gateway and forge X-User-Id | Gateway-only authentication versus directly published service ports | If the ports are reachable from an untrusted network, identity headers can be forged and the gateway's checks skipped entirely. Settled by one reachability check from a second host. |
| 3 | Refresh token single-use rotation is a non-atomic read-check-write, allowing concurrent double redemption | Refresh-token single-use rotation under concurrency | A stolen refresh token could yield an independent session if the attacker wins a race. Needs a stolen token, so it is narrower; the fix is small and standard. |
| 4 | Login throttle keyed by the gateway's address lets one anonymous caller lock out all logins | Login throttle address key versus the gateway proxy address | One anonymous caller could keep every user's login returning 429. Availability only, but trivially cheap to attempt if the address assumption is true. |
| 5 | Actuator refresh endpoint is exposed without authentication on the gateway and auth-service ports under the default local profile | Actuator management endpoint versus gateway authentication | Under the default local profile an anonymous caller may trigger a config refresh and clear rate-limit state. Impact is bounded (operator-controlled config); depends on the profile in use. |
| 6 | Gateway rate-limit bucket keyed on the nginx peer address lets one client exhaust the budget for all users | Per-client rate limit versus a single proxy peer address | One client could drain the shared bucket and cause 429 for everyone. A documented design trade-off; real impact depends on the deployed network path. |
| 7 | Last-administrator guard in ChangeRoleService is a non-atomic check-then-act, so concurrent demotions can leave zero administrators | Last-administrator invariant under concurrent role changes | Two administrators demoting each other at once could leave no administrator. Requires administrator access and a narrow race; bootstrap may recover on restart. |
| 8 | Account existence can be enumerated through register 409 and login BCrypt timing | Account-existence disclosure by response and timing | The register 409 is a deliberate product requirement (owner decision); the login timing gap is unmeasured and bounded by the login throttle. |
| 9 | Login throttle map never evicts keys; attacker-chosen email keys (up to the body-size cap) are retained until restart | In-memory throttle map growth versus heap | Corrected by verification: growth is bounded to roughly 5 new keys per minute through the gateway, so this matters only for very large keys or direct callers. Lowest priority. |

## 6. Hardening notes and positive patterns
**Hardening (not findings):**
- Add `@Valid` plus `@Email`/`@Size` limits to every request body in auth-service (register, login, refresh, logout, role change). Today `@NotBlank`/`@NotNull` are inert, so a null field gives a 500, and email and password lengths are unbounded (BCrypt ignores bytes after 72).
- Reject the published `change-me*` placeholder values at startup for `JWT_SIGNING_SECRET` and `ADMIN_PASSWORD`, and have `scripts/up.sh` warn on them. (This is the rejected lead `deploy/placeholder-secrets-accepted-at-startup`: no default is hardcoded and the example file says to replace every value, so it is a missing guard, not a defect.)
- Add `iss`/`aud`/`nbf` claims and an enum check of `role` to the JWT, and pin the verifier to HS256. One shared HS256 secret between signer and verifier means a gateway-environment leak allows token forgery; asymmetric keys would remove that.
- Add a dummy BCrypt comparison on the unknown-account login path (removes the timing difference).
- Bind management endpoints to a separate, unpublished port (`management.server.port`) and make a non-`local` profile the example default, so `refresh` is not exposed on the application port.
- Bind Postgres, RabbitMQ (including the management UI) and Mailpit to `127.0.0.1`; they publish on all interfaces with `.env.example` placeholder passwords.
- Extend `.gitignore` to `.env.*`, `*.pem`, `*.key`, `*.p12`, `*.jks`, keeping `.env.example` re-included.
- Have `ChangeRoleService` also check `X-User-Role`, revoke the target's refresh tokens on demotion, and consider a shorter lifetime for ADMINISTRATOR access tokens (a demoted admin keeps the old role for up to 900 s; documented as FR-021).
- `toLowerCase()` without `Locale.ROOT` in register, login and bootstrap code (Turkish-locale hazard).
- `TokenBucketRateLimiter.evictIdle` scans every bucket on every request; evict on a schedule or by size.
- No TLS or HSTS anywhere (browser to nginx to gateway to services); terminate TLS in front of nginx for any non-local deployment.
- Spring Boot 3.5.15 and Spring Cloud 2025.0.3 are end-of-line releases and dependency CVE status could not be checked offline; run a software-composition scan.
- Tests do not cover: concurrent refresh, concurrent last-admin demotion, `alg=none` and non-enum role tokens, admin-route path normalisation, null request bodies, or actuator authentication.

**Positive source patterns (verified in source):**
- Client-supplied `X-User-Id`/`X-User-Role` are stripped on every route (public and protected), then set only from verified claims; `Authorization` is removed before forwarding.
- Default-deny routing: a route without `public` metadata is protected; a request that matches no route fails closed. `RealGatewayConfigTest` asserts that no route targets `/api/v1/internal/**`.
- JWT verification: unsigned and `alg=none` tokens are rejected, the key is server-pinned with no `kid`/`jku`/`x5u` selection, `exp` is mandatory, failures reveal no token content, and the secret must be at least 32 bytes.
- Refresh tokens are 256-bit `SecureRandom` values stored only as SHA-256 hashes; logout checks that the token belongs to the caller; refresh re-reads the current role from the database.
- Passwords use BCrypt with a per-hash salt and a strength rule enforced before hashing; only parameterised Spring Data queries are used (no injection sink); error bodies are fixed phrases and never echo exception text.
- The correlation ID is accepted only if it matches `^[A-Za-z0-9._-]{8,64}$`, so the one hand-built JSON fallback is safe.
- Compose requires every secret with `${VAR:?}` and hardcodes none; `.env` is git-ignored and has no history in git; no cookies, redirects, `eval`/`exec` or key blocks appear in either service.
- The register race is backstopped by a unique index on `lower(email)`.

## 7. Coverage statement (from the ledger)
- **Covered with no lead (5 units):** gateway JWT verification, nginx header and framing trust, gateway error/log injection, auth credential handling, the role-change/internal-lookup/bootstrap access control.
- **Units that produced leads (11):** route policy, rate limiting, input validation (wave 1 closed on a thin read of size bounds), login throttle and enumeration, refresh/logout lifecycle, check-then-act races, deployment exposure, placeholder secrets, session lifecycle after role change, wildcard and basic-exposure checklist (both re-hunted in wave 2).
- **Deferred, NOT reviewed (3 units), reason `budget_cannot_reserve_critics_and_validation`:**
  - Dot-segment, encoded-slash and double-slash path forms reaching unauthenticated /api/v1/internal/accounts/{id} through the gateway auth route (class: Access control (path canonicalisation differential to internal endpoint))
  - Request size, header size, connection and timeout limits across nginx, gateway and auth-service, plus unbounded email, password and token fields (class: Resource exhaustion (unbounded input and buffering))
  - auth-service: JSON request bodies of register, login, refresh, logout and role change (class: Injection (request-body validation and error mapping))
    The first is folded into the verification scope of lead 1 (same root cause, internal-lookup sink); the other two (request-size/field-length caps, and a re-hunt of the validation unit) were never examined and remain open work.
- **Critics:** a post-wave-1 critic reopened two thin units and proposed one new unit (all done in wave 2). A post-wave-2 critic then proposed two more units and reopened one; these were deferred under the budget rule. **The separate final-clean critic did not run** (chosen to save cost; it would likely have queued more deferred units). Therefore this run is a partial pass and says nothing about the rest of the target.
- **Excluded by design:** MEMORY-SAFETY, AI/LLM, CLIENT-SIDE, SUPPLY-CHAIN, CLOUD-AND-DEPLOYMENT, PROTOCOLS-RPC, DATA-ISOLATION and DESKTOP-MOBILE companions (no such surface in scope); the SPA frontend and the other five business services were out of scope.
- Running the audit again with a larger budget will pick the deferred units up as current work (prior-run state is preserved in `coverage-ledger.json`).

## 8. Process notes and disclosures
- Source ref and file contents were read from the working tree; the audit never contacted a deployed service.
- One reconnaissance agent ran a grep against the git-ignored `.env` with its output redacted (it reported no values); no secret value was printed or stored, and every hunter and verifier was instructed not to read `.env`.
- The skill's validators cannot run on Windows (they need POSIX no-follow file opens), so they were run inside a `node:24-alpine` container with no network and read-only mounts (that image was pulled for this purpose). Both validators pass.
- Agent result files were empty, so the parent persisted each agent's evidence from its hand-back message (long result texts condensed, conclusions preserved; the nine retained candidates are stored in full). One hunter used the key `verdict` instead of `proposed_verdict`; the parent normalised it.
- One critic launch was aborted before it started (a prompt-file error) and relaunched; it counts toward the 34 calls.
- Nothing was written to the audited repository.
