# Architecture summary (run-2: frontend, standard profile)

## Product, principals, protected resources
LearnHub is a course platform. This run covers only the student web app in `frontend/` (React 19 + TypeScript + Vite + Tailwind v4 SPA, react-router 8). Principals: anonymous visitor, signed-in student (also INSTRUCTOR/ADMINISTRATOR roles exist server-side), and any third-party web page that can link a victim to the SPA. Protected resources: the student's access token (memory) and refresh token (sessionStorage), the student's enrolments, payments, progress, certificates and reviews, all held server-side and reached through `/api`.

## Comparable software
Typical token-in-JS-memory SPAs accept that an XSS defeats the session; the usual defences are no raw-HTML sinks, strict CSP and URL validation. Used only to calibrate severity.

## Stack, deployment, local limits
Static bundle built by `npm run build` (node:24-alpine) and served by nginx:alpine (`frontend/Dockerfile`, `frontend/nginx/default.conf.template`), which also proxies `/api/` to the gateway (reviewed in run-1). Dev server proxies `/api` to localhost:9080 (`frontend/vite.config.ts`). No cookies, no service worker, no postMessage, no WebSocket, no localStorage (greps). Source-only run: no OS sandbox on this Windows host, so no target code (npm, node, vitest, browsers) is executed.

## Entry surfaces and paths
- URL: `/login?returnTo=` (LoginPage navigates to it), `/courses/:courseId`, `/learn/:courseId`, `/verify/:code`, catalog search params (`CatalogPage`).
- Server text rendered by React: course titles/descriptions, lesson content, reviews, certificate verification, error `title/detail/correlationId`.
- Network: `src/api/client.ts` (`/api` + bearer, single-flight refresh on 401), `src/api/endpoints.ts` (ids interpolated into paths), `src/hooks/useCheckout.ts` (idempotent checkout and polling).
- Storage: `src/auth/session.ts` (access token in a module variable, refresh token in sessionStorage).

## Trust boundaries and strongest visible control
- Browser origin vs attacker page: nginx adds `Content-Security-Policy: default-src 'self'`, `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer` (repeated in the `index.html` location).
- Server text vs DOM: React text interpolation; a test (`src/pages/LearnPage.xss.test.tsx`) asserts no raw HTML.
- Authentication state: `RequireAuth` route guard (UX only; the gateway enforces access).

## Starting paths
`frontend/src/api`, `frontend/src/auth`, `frontend/src/pages`, `frontend/src/components`, `frontend/src/hooks`, `frontend/nginx`, `frontend/Dockerfile`, `frontend/package.json`, `frontend/scripts/gen-api.mjs`.

## Prior coverage
run-1 covered api-gateway and auth-service only; its ledger is compatible but shares no frontend unit. Only `frontend/nginx/default.conf.template` was touched in run-1 (as the gateway's edge). No carried confirmed records exist (run-1 confirmed none), so there are no hunter exclusions.

## Companions selected
CLIENT-SIDE.md (DOM and navigation sinks, browser storage, clickjacking) and WEB-PROTOCOL-AND-AUTH.md (session invalidation, cache/header trust at the nginx edge). Others excluded: no native code, AI, broker, multi-tenant store, desktop/mobile IPC, cloud account or CI workflow in the frontend source.
