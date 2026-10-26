# Research: Course Ratings and Reviews UI

## R1 Batch endpoint shape
**Decision**: `GET /api/v1/reviews/summaries?courseIds=<uuid>,<uuid>` returning `{ "items": [{courseId, averageRating|null, reviewCount}] }` with one entry per requested id (zero-filled for unreviewed courses). More than 50 ids, zero ids or a malformed id returns 400 problem details.
**Rationale**: one request per catalog page (SC-001); zero-filling keeps the client free of "missing means none" logic.
**Alternatives**: one summary request per card (N+1, rejected); embedding ratings in course-service's list (needs a cross-service call or event, rejected under Principle II/X).

## R2 Routing
**Decision**: no gateway change. The existing `reviews-read` route (`GET /api/v1/reviews/**`, public) already matches the new path.

## R3 Contract and generated types
**Decision**: the new endpoint's contract lives in `specs/008-review-ui/contracts/review-summaries.openapi.yaml`; `scripts/gen-api.mjs` has no glob, so two entries are added by hand (`review` for the 007 contract, `reviewSummaries` for this one) and `npm run gen:api` is run.
**Rationale**: constitution XIII requires generated types; keeping 007's shipped contract untouched keeps this feature's diff self-contained.

## R4 Frontend data fetching
**Decision**: plain `useEffect` + `useState` with a `cancelled` flag, as `CourseDetailPage` and `CatalogPage` already do; no data library. The catalog requests summaries for the visible page's ids after the course list resolves; a failure leaves cards without ratings (FR-004).

## R5 Enrollment gating in the UI
**Decision**: the page does not pre-check enrollment; it submits and renders the backend's 403 via the existing `ErrorNotice` (shows the correlationId, constitution XII).

## R6 Tests
**Decision**: backend unit test for the id-parsing/limit logic plus an IT for the grouped query; frontend Vitest tests for the helpers, `StarRating`, the form, and both pages. `CourseDetailPage.test.tsx` currently returns one response for every URL, so it moves to a URL-dispatching fetch mock.
