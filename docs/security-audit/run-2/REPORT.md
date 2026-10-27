# Security audit report: LearnHub frontend (run-2)

- **Target:** `frontend/` (React 19 + TypeScript + Vite SPA, nginx edge, build files), commit `ea2fd6c`
- **Profile:** standard, scoped to the frontend. Source-only: no OS sandbox exists on this Windows host, so no target code (npm, node, vitest, a browser) was run. Library behaviour was checked by reading installed source.
- **Prior run:** run-1 (gateway and auth-service) shares no frontend unit; no carried records.
- **Agents:** 8 of a 28-call budget (5 hunters, 2 critics, 1 verifier).

## Result

| Confirmed | Needs validation | Rejected |
|---|---|---|
| 0 | 0 | 1 |

**No exploitable vulnerability was found in the frontend.** One lead was raised and then rejected after the source settled it. The audit makes no claim that the frontend is free of defects, only that no source-grounded boundary violation survived verification.

### Rejected lead

`frontend/login-returnto-unvalidated-navigate`: `LoginPage` passes the `returnTo` query value straight to `navigate()` ([LoginPage.tsx:17,30](../../../frontend/src/pages/LoginPage.tsx)). A hunter flagged a possible open redirect after login. A verifier read the installed react-router 8.4.0 source, and the parent re-checked it: `useNavigateUnstable` runs `validateNavigationTarget(..., "reject")` and throws `External navigation is not allowed` for absolute URLs, `//host` and `javascript:` targets (`node_modules/react-router/dist/development/lib/hooks.js:294`, `.../router/navigation.js:39`). The user stays on the SPA origin; the only effect is a generic error message. A `/`-prefix check would be harmless defence in depth.

## What the audit checked (11 units + 1 added by the final critic)

Token storage and lifecycle, refresh and 401 handling, `returnTo` navigation, rendering of server text (no raw-HTML sinks anywhere in non-test source), error display, URL ids interpolated into API paths, the checkout and idempotency flow, public certificate verification, the nginx edge (CSP, framing, header inheritance, forwarding, caching), build and dependency configuration, and a wildcard sweep. After the final critic, the docker-compose frontend entry and `.env.example` `GATEWAY_URL` were added and covered.

## Things the code does well

- Access token lives only in memory; the refresh token is in per-tab `sessionStorage`; nothing in `localStorage`; no cookies, so no CSRF surface.
- Every server string is rendered as React text. `LearnPage.xss.test.tsx` locks this in.
- Single-flight refresh with one retry, no refresh loop; logout always clears local state.
- nginx sets CSP `default-src 'self'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, repeated inside the `index.html` location to defeat `add_header` inheritance.
- Idempotency key handling in `useCheckout` is sound against double submit.
- Dependencies are exact-pinned (two `^4.3.3` build-time exceptions) and installed with `npm ci`.

## Hardening notes (not findings)

No reachable boundary violation backs these; they are cheap improvements.

1. **Encode path ids.** `courseId`, `lessonId`, `reviewId` are interpolated into `/api/...` paths without `encodeURIComponent` ([endpoints.ts](../../../frontend/src/api/endpoints.ts)). A decoded `%2F` or `..` in a route param could re-point a same-origin request that carries the user's own bearer. No cross-user effect or exfiltration path was found. Wrap ids in `encodeURIComponent` or validate them as UUIDs.
2. **Refresh/logout race.** A refresh still in flight when `logout()` runs can write a new token pair back ([client.ts](../../../frontend/src/api/client.ts)). Use a session-generation counter. A failed refresh also clears a newer login, and any transient 5xx on refresh signs the user out.
3. **Tighten the CSP.** Add `frame-ancestors 'none'`, `base-uri 'self'`, `form-action 'self'` (and `object-src 'none'`); `default-src` does not cover the first three. Share the header block between locations through an include so the two copies cannot drift.
4. **No TLS or HSTS** in the nginx config (port 80 only). Confirm TLS termination in front of it for any real deployment.
5. **Floating image tags** (`node:24-alpine`, `nginx:alpine`) and `npx --yes openapi-typescript` unpinned in `scripts/gen-api.mjs`; use the installed binary with `shell: false`.
6. **Add a `/`-prefix check on `returnTo`** as defence in depth.
7. Minor: `useCheckout` polling is not cancelled on unmount; `StarRating` could throw on an out-of-range server average.
8. Carried from run-1: all SPA users share one gateway rate-limit bucket behind nginx, and `docker-compose.yml` publishes many internal ports to the host.

## Coverage statement and limits

- **Partial coverage, no clean claim.** The post-wave critic accepted coverage, but the final-clean critic found one gap (the docker-compose frontend entry). An extra hunter covered it and no further critic was run, so the audit cannot state that nothing was missed.
- **Reconnaissance** was done by the parent reading the source directly instead of four reconnaissance agents, to save cost.
- **Stale critic scope.** Both critics were given the run-1 scope text in their prompt (backend as well as frontend) because of a prompt-builder slip. Neither used it to widen the work, but their judgement of frontend coverage was made against that text.
- **Rejected record** was verified by one agent and re-checked by the parent against library source; no separate Phase 5 agent was used.
- **`.env`** was never read. **Lockfile** was not audited for known CVEs (no network).
- Validators: `validate-coverage-ledger.cjs` (12 units) and `validate-findings.cjs` (1 record) both pass, run in a no-network `node:24-alpine` container.
