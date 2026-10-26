# Implementation Plan: Course Ratings and Reviews in the Student Web App

**Branch**: `008-review-ui` | **Date**: 2026-10-26 | **Spec**: [spec.md](./spec.md)

## Summary

Add the missing UI for spec 007's review-service. Backend: one new public batch endpoint
`GET /api/v1/reviews/summaries?courseIds=...` (max 50) so the catalog needs one request. Frontend:
generated types for the review contracts, a `review` section in the API helpers, a star-rating display,
rating on course cards, and a reviews section (summary, paged list, own-review form) on the course page.
Covers **both** `backend/review-service` and `frontend/`; touches no other service and no gateway
config (the existing public `reviews-read` route already matches `GET /api/v1/reviews/**`).

## Technical Context

**Language/Version**: Java 21 (review-service); TypeScript strict, React 19 (frontend)
**Primary Dependencies**: existing only (Spring Data JPA; React Router, Tailwind v4, Vitest + Testing Library). No new dependency (FR-010).
**Storage**: PostgreSQL `review` database, read-only aggregate query, no schema change
**Testing**: JUnit 5 + Testcontainers (`*IT`) and unit tests for review-service; Vitest for frontend
**Target Platform**: Docker compose stack; browser SPA behind nginx
**Project Type**: web application (backend + frontend)
**Performance Goals**: catalog ratings in one request for up to 50 courses (SC-001)
**Constraints**: constitution VIII (RFC 7807 + correlationId), XI-XIII (no new deps, text rendered as text, types generated from contracts, same-origin `/api/`)
**Scale/Scope**: 1 endpoint, ~6 frontend helpers, 2 components, 2 pages touched

## Constitution Check

| Principle | Status |
|---|---|
| I Layout: backend and frontend stay independent; spec names both folders | PASS |
| II Data isolation: only review-service's own table is queried | PASS |
| IV Gateway only entry: frontend uses `/api/` through the existing public route | PASS |
| V Sync/async: no new inter-service call, no new event | PASS |
| VI Test-first: tests precede implementation in tasks.md | PASS |
| VIII Errors: batch endpoint returns problem details for bad input | PASS |
| IX Conventions: `/api/v1` path, base package unchanged, no Flyway change needed | PASS |
| X Simplicity: no new service, library or table | PASS |
| XI-XIII Frontend: no new deps, text rendering, generated types, no absolute URLs | PASS |

No violations; Complexity Tracking not needed.

## Project Structure

```text
specs/008-review-ui/   spec.md plan.md research.md data-model.md quickstart.md contracts/ tasks.md

backend/review-service/src/
├── main/java/com/example/learnhub/review/
│   ├── api/ReviewController.java            # add GET /summaries
│   ├── api/CourseRatingSummaryView.java     # reuse
│   └── infrastructure/ReviewRepository.java # add grouped summary query
└── test/java/com/example/learnhub/review/   # new unit test + IT for summaries

frontend/
├── scripts/gen-api.mjs                      # register review + reviewSummaries contracts
└── src/
    ├── api/{endpoints.ts, schema.ts, generated/}  # review helpers + generated types
    ├── components/{StarRating.tsx, ReviewForm.tsx, ReviewList.tsx, CourseCard.tsx}
    └── pages/{CatalogPage.tsx, CourseDetailPage.tsx}  # + tests beside each
```

**Structure Decision**: web application layout already in force; new files sit beside their peers.
