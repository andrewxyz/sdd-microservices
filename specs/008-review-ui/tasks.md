---

description: "Task list for spec 008 (course ratings and reviews UI)"
---

# Tasks: Course Ratings and Reviews in the Student Web App

**Input**: `specs/008-review-ui/` (spec, plan, research, data-model, contracts, quickstart)
**Tests**: required (constitution VI): write each test first and see it fail before its implementation.
**Paths**: backend `backend/review-service/src/...` (package `com.example.learnhub.review`), frontend `frontend/src/...`.

## Phase 1: Setup and contracts (blocks everything)

- [ ] T001 Register contracts in `frontend/scripts/gen-api.mjs`: add `["review", "specs/007-review-service/contracts/review-service.openapi.yaml"]` and `["reviewSummaries", "specs/008-review-ui/contracts/review-summaries.openapi.yaml"]` to the `contracts` array and update the script's contract-count comments.
- [ ] T002 Run `npm run gen:api` in `frontend/` so `src/api/generated/review.ts`, `src/api/generated/reviewSummaries.ts` and `src/api/schema.ts` exist (generated, do not hand-edit).
- [ ] T003 Add a `review` section to `frontend/src/api/endpoints.ts` with typed helpers `listReviews(courseId, page)`, `getReviewSummary(courseId)`, `getReviewSummaries(courseIds)`, `createReview(courseId, body)`, `updateReview(reviewId, body)`, `deleteReview(reviewId)`, in the existing one-line `request()` style; `getReviewSummaries` joins ids with commas and never sends more than 50 (callers chunk).

## Phase 2: User Story 1 - Ratings on catalog cards (P1)

- [ ] T004 [P] [US1] Unit test for the batch id handling (1-50 distinct UUIDs, reject 0, 51 and malformed) in `backend/review-service/src/test/java/com/example/learnhub/review/ReviewSummariesValidationTest.java`.
- [ ] T005 [P] [US1] Integration test `ReviewSummariesIT` in `backend/review-service/src/test/java/com/example/learnhub/review/ReviewSummariesIT.java` (Testcontainers Postgres, no auth header): reviewed course returns average and count, unreviewed id returns count 0 and null average, `DELETED` reviews excluded, one item per requested id in request order, 51 ids returns 400.
- [ ] T006 [US1] Add a grouped summary query to `backend/review-service/src/main/java/com/example/learnhub/review/infrastructure/ReviewRepository.java` (count and average of `ACTIVE` reviews per course id for a set of ids).
- [ ] T007 [US1] Add `GET /api/v1/reviews/summaries` to `backend/review-service/src/main/java/com/example/learnhub/review/api/ReviewController.java`: parse `courseIds`, enforce 1-50 distinct valid UUIDs (400 problem details otherwise, like the other endpoints), zero-fill missing courses, reuse `CourseRatingSummaryView`.
- [ ] T008 [P] [US1] Vitest tests in `frontend/src/components/StarRating.test.tsx` (renders average and count, "No reviews yet" when count is 0, accessible label) and in `frontend/src/api/endpoints.review.test.ts` (paths, comma joining, body shapes).
- [ ] T009 [P] [US1] Create `frontend/src/components/StarRating.tsx` (display only; takes `average: number | null` and `count`; Tailwind styling consistent with the existing components).
- [ ] T010 [US1] Update `frontend/src/components/CourseCard.tsx` to show `StarRating` from an optional `summary` prop (existing tests keep passing without it).
- [ ] T011 [US1] Update `frontend/src/pages/CatalogPage.tsx`: after the course list loads, make one `getReviewSummaries` call for the visible ids (chunk at 50), pass each card its summary; on failure show cards without ratings and no error banner (FR-004). Add tests to `frontend/src/pages/CatalogPage.test.tsx` for success, "No reviews yet" and failure.

## Phase 3: User Story 2 - Read reviews on a course (P1)

- [ ] T012 [P] [US2] Create `frontend/src/components/ReviewList.tsx` (rating, comment as plain text, date; "Show more" paging with 20 per page) with `frontend/src/components/ReviewList.test.tsx` (including a comment containing `<b>` rendered as text).
- [ ] T013 [US2] Update `frontend/src/pages/CourseDetailPage.tsx` to fetch `getReviewSummary` and `listReviews` and render summary plus `ReviewList` for any visitor; convert `frontend/src/pages/CourseDetailPage.test.tsx` to a URL-dispatching fetch mock and add tests for the review section.

## Phase 4: User Story 3 - Write, edit, delete my review (P2)

- [ ] T014 [P] [US3] Tests in `frontend/src/components/ReviewForm.test.tsx`: not shown when signed out (sign-in prompt instead); submit calls create and refreshes; edit and delete of own review; a 403 shows `ErrorNotice` with the correlationId and saves nothing; 400 shows the service message; typed text is kept on failure.
- [ ] T015 [US3] Create `frontend/src/components/ReviewForm.tsx` (rating 1-5 selector plus optional comment limited to 500 characters; uses `useAuth`; reuses `Field` and `ErrorNotice`; no new dependencies).
- [ ] T016 [US3] Wire `ReviewForm` into `frontend/src/pages/CourseDetailPage.tsx` (find the caller's own review in the list result by `userId`, pre-fill for edit, offer delete) and refresh summary and list after each change.

## Phase 5: Polish and verification

- [ ] T017 Run `cd frontend && npm run typecheck && npm test && npm run build`; all must pass.
- [ ] T018 Run `cd backend && mvn -pl review-service -am verify` (via `wsl -e bash -lc`); all tests must pass.
- [ ] T019 Walk the manual scenarios in `specs/008-review-ui/quickstart.md` against the running stack if it is up; note any step not run.

## Dependencies
Phase 1 first. US1 backend (T004-T007) and US1 frontend (T008-T011) can proceed in parallel after Phase 1. US2 needs T003 and T009. US3 needs US2's page changes (T013). Tests before implementation within each story.
