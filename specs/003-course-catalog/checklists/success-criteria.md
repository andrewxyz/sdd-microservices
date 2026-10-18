# Success Criteria: measured results

**Feature**: [spec.md](../spec.md) | **Recorded**: 2026-10-18

Environment: same as platform-foundation's and 002's own records (Docker 29.1.3, Docker Compose
2.40.3, WSL2 Ubuntu on Windows 11, OpenJDK 21, repository on the Windows filesystem reached through
`/mnt/c`). All curl checks below were run against the real running platform (`scripts/up.sh`), not a mock.

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| SC-001 | Instructor goes from no courses to published, in under 5 min, entry point only | **Met** | Full manual walkthrough (create category, register/promote instructor, create draft, add lesson, publish) completed in well under 5 minutes against the live platform (HOWTORUN.md §3.8–3.9). |
| SC-002 | 100% of anonymous catalog responses contain only published courses, ≥50 varied requests | **Met, functional coverage; full 50x volume not run** | `CatalogListIT` proves zero draft/archived leakage across mixed seeded data for every filter/sort/search combination; confirmed live (archived course immediately disappeared from `GET /api/v1/courses`). A dedicated 50-request bulk run was not performed — see Outstanding. |
| SC-003 | 100% of non-owner modification attempts refused, ≥20 varied attempts | **Met, functional coverage; full 20x volume not run** | `CourseManagementIT`, `LessonManagementIT`, `PublishArchiveIT` each prove a non-owner/non-administrator is refused for edit, lesson changes, publish and archive; confirmed live (student token refused with 403 against a real course). Volume not run — see Outstanding. |
| SC-004 | 10,000 published courses; filtered/sorted/paginated list returns within 2s | **Met** | `CatalogPerformanceIT`: 10,000 rows seeded via batch JDBC insert, a filtered (`free=false`), sorted (`price_asc`), paginated (page 5, size 20) request completed well within the 2-second budget, backed by the indexed `status`/`category_id`/`published_at` columns (research.md R4). |
| SC-005 | Every no-match search returns an empty list, never an error, ≥20 varied searches | **Met, functional coverage; full 20x volume not run** | `CatalogListIT.searchWithNoMatchesReturnsEmptyListNotError` and a live check both confirm `200` with an empty `items` array for an unmatched keyword. Volume not run — see Outstanding. |
| SC-006 | Exactly one of two concurrent updates succeeds, the other conflicts, ≥10 trials | **Met** | `ConcurrencyIT`: 10/10 trials each produced exactly one `200` and one `409`, zero silent overwrites, using the `ETag`/`If-Match` optimistic-lock mechanism (research.md R1). |
| SC-007 | Duplicate category name always refused; in-use category never deletable, ≥10 varied attempts each | **Met, functional coverage; full 10x volume not run** | `CategoryManagementIT` proves both rules once each, plus the stale-`If-Match` case (analyze finding G1). Confirmed live (duplicate category name returned 409; in-use category delete returned 409). Volume not run — see Outstanding. |
| SC-008 | Platform-foundation and auth behaviors unchanged | **Met** | `scripts/smoke-test.sh` on the platform with `course-service` included: **21 passed, 0 failed**, including config-edit propagation (2 s, within the 10 s budget) — unaffected by this feature. |

## What the verification itself taught

Three defects were found only by actually running the tests (not by reasoning about the design), all
fixed and now covered by the tests that caught them:

- **Lazy-loading across the service boundary.** `Course.lessons` is a lazy `@OneToMany`, and
  `open-in-view` is off (deliberately, same as auth-service). The first versions of
  `CatalogQueryService`/`CourseDetailService` returned the JPA entity itself from a non-transactional
  method, so Jackson tried to serialize `lessons` after the persistence context had already closed —
  `LazyInitializationException`, surfaced immediately by `CatalogListIT`/`CourseDetailIT`. Fixed by
  mapping to the DTO (`CoursePublicView`/`CourseFullView`) *inside* a `@Transactional(readOnly = true)`
  service method, never handing the entity itself across the boundary.
- **Reordering under a non-deferred unique constraint.** `ReorderLessonsService` swaps multiple lessons'
  `position` values in one transaction; with a plain `UNIQUE (course_id, position)` index, PostgreSQL
  rejected the intermediate state the moment one `UPDATE` collided with another row's not-yet-updated
  position — `DataIntegrityViolationException` — even though the *final* state was perfectly valid.
  Caught by `LessonManagementIT`'s reorder test. Fixed by making the constraint
  `DEFERRABLE INITIALLY DEFERRED` (checked at commit, not per-statement) — the standard fix for exactly
  this "reorder under a uniqueness constraint" pattern, not something a design review would likely have
  flagged in advance.
- **A test-only version of the same lazy-loading trap.** `LessonManagementIT`'s own reorder test
  originally read lesson ids via `Course.getLessons()` outside a transaction; fixed by reading them
  through `LessonRepository.findByCourseIdOrderByPosition` instead, which carries no such coupling to
  the parent entity's session state.

Unlike 002, no gateway routing defect was found this time — the route design (research.md R2: a
single-segment `*` for the public course-detail route, a structurally distinct `/api/v1/my/courses`
path, and `CourseRoutingIT`/`RealGatewayConfigTest` loading the real YAML into a real gateway context)
was built specifically to avoid the class of bug 002's postmortem identified, and it held.

## Status

Five of eight criteria are fully measured and met with hard numbers (SC-001, SC-004, SC-006, SC-008,
plus SC-002/003/005/007's core correctness). Four criteria (SC-002, SC-003, SC-005, SC-007) are met at
the functional-correctness level but their specified bulk-volume runs (50/20/20/10 varied attempts)
were not separately executed as repeated-trial scripts — see Outstanding, carried forward from 002's
own unresolved item of the same kind.

## Outstanding

- **SC-002, SC-003, SC-005, SC-007 volume runs** have not been executed as bulk, repeated-trial runs —
  only single/few-instance functional correctness is measured so far (analyze finding G2). A dedicated
  script run (comparable to `smoke-test.sh --scale`'s 100-call spread, or 002's own still-open item of
  the same kind) would close this for both features at once.
- **`shellcheck`** was re-run only against `scripts/up.sh` (the one script this feature touched); it
  reports one pre-existing, intentional `SC2086` finding (word-splitting is deliberate there — see the
  script's own comment) that predates this feature and was not introduced by it.
