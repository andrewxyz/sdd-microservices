# Research: Course Catalog

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-18

No technical brief was supplied with `/speckit-plan` for this feature; every decision below was made
here, bound by the constitution and by the two clarifications already recorded in spec.md. No
`NEEDS CLARIFICATION` remains.

## R1. Optimistic concurrency (FR-025)

- **Decision**: JPA `@Version` on the `Course` aggregate root (lessons are children of the same
  aggregate, so adding, editing, removing or reordering a lesson also increments the course's version).
  Exposed over HTTP as a strong `ETag` on every course representation; every mutating request
  (edit, lesson change, publish, archive, delete) MUST send `If-Match` with that value. A missing or
  stale `If-Match` is refused with `409 Conflict` in the platform's standard problem-details shape.
- **Rationale**: `ETag`/`If-Match` is the standard HTTP mechanism for exactly this ("don't let a second
  writer silently clobber a first writer's save"), needs no bespoke version-in-body convention, and
  maps directly onto JPA's built-in `@Version` column with zero extra library.
- **Alternatives considered**: A `version` field carried in the request/response body instead of a
  header. Rejected: no behavioral difference, but `If-Match` is the contract HTTP already has for this,
  and keeping it out of the body keeps every DTO focused on domain data.

## R2. Gateway route design avoids the kind of path collision that bit 002

- **Decision**: Course-service's routes use the narrowest predicate shape that still does the job, and
  every path that needs different visibility at a different depth gets a path shape that structurally
  cannot collide with another route, rather than relying on route `order` to break a tie:
  - `GET /api/v1/courses` (exact path) — public, the catalog list.
  - `GET /api/v1/courses/*` (**single**-segment wildcard) — public, one course's public detail. A
    single `*` in Spring's `AntPathMatcher` matches exactly one segment, so this can never also match
    `/api/v1/courses/{id}/full` or `/api/v1/courses/{id}/publish` (two segments) — no ordering trick
    needed to keep those separate from the public detail view.
  - `GET /api/v1/my/courses` for an instructor's own listing (including drafts) lives under a distinct
    top-level segment (`/api/v1/my/**`), not something that could ever be mistaken for a course
    identifier under `/api/v1/courses/*`.
  - Everything else — create, edit, delete, publish, archive, the full/owner detail view, and all
    lesson management — falls under one protected catch-all, `Path=/api/v1/courses/**` (**double**-star,
    zero or more segments), ordered after the two public routes above (this part still needs order,
    the same way `echo-public`/`echo` and `auth-public`/`auth` already do, since `**` does overlap the
    exact-path and single-segment routes).
- **Rationale**: specs/002-user-auth's own postmortem (`checklists/success-criteria.md`) found a real
  production bug from assuming a routing/metadata shape without verifying it against a real, running
  gateway context. This design sidesteps the one place a similar ambiguity could recur — an
  instructor's own "list including drafts" endpoint living at the same path depth as the public
  single-course view — by giving it a structurally distinct path instead of depending on predicate
  evaluation order to disambiguate two routes that could otherwise both match. Confirmed with
  `AntPathMatcher`'s documented `*` vs `**` semantics: `*` never crosses a `/`, `**` always can.
- **Verification plan**: tasks.md includes a `RealGatewayConfigTest` extension (route shapes) and a
  real, YAML-loaded-into-a-real-context test (the `EchoSecureAccessIT`-style pattern), not only
  hand-built `Route` objects — the exact gap that let 002's role-metadata bug through.

## R3. `level` and `language` fields

- **Decision**: `level` is one of `BEGINNER`, `INTERMEDIATE`, `ADVANCED`; `language` is a free-text,
  short string (an IETF/ISO 639-1-style code such as `en`, not validated against a fixed list in this
  feature). Neither is required to create a draft, and neither is part of the publish-readiness check
  (FR-014 lists title, description, category, price and at least one lesson only — spec.md is explicit
  about exactly those five).
- **Rationale**: the spec never states level's allowed values or language's format, and nothing in the
  spec's publish checklist requires either; a fixed three-value enum for level is the obvious minimal
  set the filter in FR-003 needs, and treating language as unconstrained text avoids inventing a
  locale-list requirement nobody asked for.
- **Alternatives considered**: Validating `language` against a fixed ISO list. Rejected as unnecessary
  scope for this feature; nothing downstream (payment, enrollment) depends on it being validated.

## R4. Search, sort and the 10,000-course performance target (SC-004)

- **Decision**: Keyword search is a case-insensitive substring match (`ILIKE '%term%'`) against title
  OR description. Catalog list queries filter on an indexed `status = PUBLISHED` first, then apply
  category/level/price filters, then sort, then paginate (offset/limit, default page size 20, max 100 —
  spec.md Assumptions). Flyway adds indexes on `status`, `category_id`, and `published_at`.
- **Rationale**: 10,000 rows is small for PostgreSQL; filtering first on an indexed `status` column
  narrows the scan before the `ILIKE` ever runs, which is more than enough to clear the 2-second budget
  without a dedicated full-text search index (e.g., `pg_trgm` or a GIN index). That remains a
  reasonable place to improve later if the catalog grows well past this feature's measured scale.
- **Alternatives considered**: A `pg_trgm` trigram index for `ILIKE` performance, or a separate search
  engine. Rejected as unjustified complexity (Principle X) at the specified scale; revisit only if a
  later measurement shows it's needed.

## R5. "Newest" sort semantics

- **Decision**: Every course carries a `publishedAt` timestamp, set whenever it transitions into
  `PUBLISHED` — including a republish after archiving, which refreshes `publishedAt` to the moment it
  became visible again. "Newest" sorts descending on `publishedAt`. A course that has never been
  published never appears in this (or any) public listing at all, so it never needs an ordering
  position.
- **Rationale**: spec.md's Assumptions already settle that "newest" means "most recently became
  visible," not "most recently created" (a long-drafted course shouldn't jump to the top the moment it
  first publishes relative to a course drafted yesterday but published an hour ago — visibility order,
  not authorship order, is what a catalog visitor actually means by "newest"). Refreshing on republish
  is the natural reading of "became visible," consistent with archiving truly hiding a course in the
  interim.

## R6. The internal, service-to-service lookup (FR-023, US6)

- **Decision**: `GET /api/v1/internal/courses/{id}` on course-service itself, called directly by other
  services via Spring Cloud LoadBalancer (`lb://course-service`) per constitution Principle V — not
  through the gateway. The gateway's route table simply never adds a route under `/api/v1/internal/**`,
  so a request to the gateway for that path is a plain 404 ("no route matches this path"), the same
  mechanism that already marks every other unlisted path not found. No additional authentication is
  added on top of that: the only way to reach it is already to be a container on the platform's own
  Docker network, consistent with how every other internal, non-client-facing call already works in
  this project (no service-to-service auth layer exists anywhere yet).
- **Rationale**: this is additive — it introduces no new access-control concept, just one more path the
  gateway's config doesn't mention, exactly like how nothing routes to Eureka's or the config server's
  admin surface either.
- **Alternatives considered**: A shared internal API key or mTLS between services. Rejected as
  unjustified complexity for a single-Docker-network deployment with no cross-service auth precedent
  anywhere else in the codebase yet; would need a constitution amendment to introduce as a pattern.

## R7. Category deletion guard (FR-021)

- **Decision**: A category delete first checks `COUNT(*) FROM courses WHERE category_id = ?`; a count
  greater than zero refuses with `409 Conflict`, naming how many courses still reference it.
- **Rationale**: simplest possible implementation of the stated rule; no need for a foreign-key
  `ON DELETE RESTRICT` vs. application-level check debate — both work, but the application-level check
  lets the error response name the exact reason (platform's standard "clear message" requirement,
  FR-027) rather than surfacing a raw database constraint violation.

## R8. Module layout and dependencies

- **Decision**: `backend/course-service`, structured exactly like `auth-service`
  (`api`/`application`/`domain`/`infrastructure`), same starter set (`spring-boot-starter-web`,
  `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `flyway-core` +
  `flyway-database-postgresql`, `org.postgresql:postgresql`, `spring-cloud-starter-netflix-eureka-client`,
  `spring-cloud-starter-config`, `spring-retry` + `spring-boot-starter-aop`, `spring-boot-starter-actuator`,
  `platform-common`). No security library is needed: course-service never validates a token itself,
  it only reads the `X-User-Id`/`X-User-Role` headers the gateway already sets (platform-common
  `IdentityHeaders`, built for 002 and reused here unchanged).
- **Rationale**: every one of these dependencies is already an approved, versioned-once choice from
  `backend/pom.xml`; course-service needs no new technology at all, only a new module using what's
  already there.

## R9. Caller-role enforcement split between gateway and service

- **Decision**: The gateway enforces only what is uniformly true for an entire route regardless of the
  specific action: anonymous access on the two public routes (R2), and `ADMINISTRATOR`-only on the
  categories' mutating catch-all (reusing the `roles` route-metadata mechanism from 002, now fixed and
  proven against a real binding). Everything with caller-specific nuance — "an instructor may act on
  their own course; an administrator may act on any course; a student may act on neither" — is enforced
  inside course-service itself, by comparing the course's `instructorId` against `X-User-Id` and
  checking `X-User-Role`.
- **Rationale**: the gateway's role metadata is a per-route, not per-record, mechanism — it has no way
  to know which course a request targets or who owns it. Course-level ownership is inherently a
  course-service concern; the gateway's job stops at "this caller is at least authenticated" for those
  routes. This mirrors 002's own split (FR-013's "only an administrator" rule for role changes was
  gateway-enforced because it needed no record-level nuance; FR-014's last-administrator guard was
  service-enforced because it did).
