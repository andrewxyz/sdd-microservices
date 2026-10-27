# Quality Document

A quality snapshot per product domain and architectural layer, for agents and humans. Update it after
each significant session or before a new phase of work. Grades are **initial estimates from existing
docs and the two security audits, not fresh measurements**; replace them as `./init.sh` and the
backend tests are re-run.

**Grading scale:** A all verification passing, clean, agent-legible, stable tests; B passing, minor gaps;
C partially working or known gaps; D not working or major structural issues.

## Product Domains

| Domain | Grade | Verification | Agent Legibility | Test Stability | Key Gaps | Last Updated |
|--------|-------|-------------|-----------------|---------------|----------|-------------|
| Auth and sessions | B | unit + Testcontainers ITs, UI-tested | spec 002, constitution | stable | run-1 leads (sec-001..004) unvalidated | 2026-10-27 |
| Course catalog | A | tests + UI-tested | spec 003 | stable | none known | 2026-10-27 |
| Checkout and enrollment | A | tests + UI-tested | spec 004 | stable | none known | 2026-10-27 |
| Progress, certificates, notifications | B | tests; email via mailpit | spec 005 | stable | verification endpoint rate limits are gateway-level | 2026-10-27 |
| Reviews | A | tests + UI-tested | specs 007, 008 | stable | none known | 2026-10-27 |
| Instructor dashboard (analytics) | B | 52 unit + 13 IT classes, 155 frontend tests, live smoke 6/6, browser walkthrough | spec 009, wiki pending | stable (ITs are slow in WSL, about 2 minutes per class) | constitution Principle II count; no frontend e2e in CI | 2026-10-27 |
| Student web app | B | Vitest + typecheck + build | spec 006, wiki Frontend | stable | hardening notes (sec-005) | 2026-10-27 |

## Architectural Layers

| Layer | Grade | Boundary Enforcement | Agent Legibility | Key Gaps | Last Updated |
|-------|-------|---------------------|-----------------|----------|-------------|
| Gateway | B | JWT + route metadata; services trust X-User headers | constitution, ARCHITECTURE.md | published service ports bypass it (sec-002); path-normalisation lead | 2026-10-27 |
| Backend services | B | hexagonal layout, Flyway, outbox | specs, wiki | no @Valid on auth bodies; non-atomic refresh rotation | 2026-10-27 |
| Messaging (RabbitMQ outbox) | B | shared-events contracts; analytics-service consumer proved idempotent and DLQ-safe | specs 005, 009 | older consumers not re-run this session | 2026-10-27 |
| Frontend | B | same-origin /api, memory access token | wiki Frontend | unencoded path ids; CSP could be tighter | 2026-10-27 |
| Build / CI / deploy | C | CI workflow, compose | HOWTORUN.md | floating image tags, no TLS, many published ports | 2026-10-27 |

## Change History

### 2026-10-27 (after spec 009)

- Changes: added the instructor dashboard domain; messaging row updated
- Domains promoted: none
- Domains demoted: none
- New gaps identified: Dockerfile and several tests list services by hand (found by running them); Principle II count
- Gaps closed: none

### 2026-10-27

- Changes: document created during harness adoption
- Domains promoted: none
- Domains demoted: none
- New gaps identified: see sec-001..sec-005 in `feature_list.json`
- Gaps closed: none
