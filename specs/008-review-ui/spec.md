# Feature Specification: Course Ratings and Reviews in the Student Web App

**Feature Branch**: `008-review-ui`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "Course ratings and reviews in the student web app (backend + frontend). Catalog cards show each course's average rating and review count; the course detail page shows the rating summary, the list of reviews, and for signed-in students a form to submit, edit or delete their own review. Submitting while not enrolled shows the backend's refusal as a clear error with its reference code. Backend: review-service gets one new public batch endpoint for rating summaries of many courses in one call (at most 50 course ids). Frontend: types generated from the review-service contract, no new dependencies, server text rendered as text only. Out of scope: moderation, replies, helpfulness votes, instructor/admin screens, sorting or filtering the catalog by rating."

**Touches**: backend (`backend/review-service`, its contract under `specs/007-review-service/contracts/`) and frontend (`frontend/`).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See ratings while browsing (Priority: P1)

A visitor or student browsing the catalog sees, on each course card, the course's average rating and how many reviews it has, so they can compare courses at a glance. A course with no reviews says so instead of showing a rating.

**Why this priority**: Ratings influence which course a person opens; this is the most visible value and needs no sign-in.

**Independent Test**: Open the catalog with several courses, some reviewed and some not; each card shows the right average and count, or "No reviews yet".

**Acceptance Scenarios**:

1. **Given** a course with reviews, **When** the catalog loads, **Then** its card shows the average rating and the review count.
2. **Given** a course with no reviews, **When** the catalog loads, **Then** its card shows "No reviews yet" and no rating.
3. **Given** the ratings cannot be loaded, **When** the catalog loads, **Then** the courses still appear without ratings and the page is not blocked.

---

### User Story 2 - Read reviews on a course (Priority: P1)

On a course's detail page anyone sees the rating summary and the list of reviews (rating, comment, date), without signing in.

**Why this priority**: Reading reviews is the reason ratings exist; it is the second public half of the feature.

**Independent Test**: Open a reviewed course while signed out; summary and review list are shown, long lists can be paged.

**Acceptance Scenarios**:

1. **Given** a course with reviews, **When** a visitor opens its page, **Then** the average, the count and the reviews are shown.
2. **Given** a course with more reviews than one page, **When** the visitor asks for more, **Then** the next reviews are shown.
3. **Given** a review comment contains markup-like text, **When** it is shown, **Then** it appears as plain text.

---

### User Story 3 - Write, edit and delete my review (Priority: P2)

A signed-in student can submit a rating (1-5) with an optional comment on a course page, then later change or remove their own review. If they are not enrolled, they see a clear explanation with the error's reference code.

**Why this priority**: It creates the data the first two stories display, but the earlier stories are useful on their own.

**Independent Test**: Sign in as an enrolled student, submit, edit and delete a review and see the page update each time; sign in as a non-enrolled student and see the refusal.

**Acceptance Scenarios**:

1. **Given** an enrolled student with no review, **When** they submit a rating and comment, **Then** the review appears and the summary updates.
2. **Given** a student with a review, **When** they edit or delete it, **Then** the page shows the change.
3. **Given** a student not enrolled in the course, **When** they submit, **Then** a clear error with its reference code is shown and nothing is saved.
4. **Given** a signed-out visitor, **When** they view a course, **Then** no review form is offered and they are invited to sign in.

---

### Edge Cases

- A rating outside 1-5 or an over-long comment is rejected with the service's message and reference code.
- The catalog asks for ratings of more than 50 courses in one request: the request is refused; the page must stay within the limit by asking in groups.
- A course id in the batch that does not exist or has no reviews returns a zero count with no average rather than an error.
- Sign-in expires while writing: the student is asked to sign in again without losing the typed text.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST provide one public request that returns the rating summary (average and count) for up to 50 courses at once; a course with no reviews MUST be reported with count 0 and no average.
- **FR-002**: The system MUST refuse a request for more than 50 course ids, or with malformed ids, with a standard error response.
- **FR-003**: The catalog MUST show each card's average rating and review count, or "No reviews yet", using a single request for the visible page of courses.
- **FR-004**: The catalog MUST remain usable if ratings fail to load.
- **FR-005**: The course page MUST show the summary and a pageable list of reviews to any visitor.
- **FR-006**: Signed-in students MUST be able to submit, edit and delete their own review from the course page; signed-out visitors MUST NOT be offered the form.
- **FR-007**: A refusal such as "not enrolled", validation failure or service unavailability MUST be shown as a clear message including the error's reference code.
- **FR-008**: All text coming from the server MUST be displayed as plain text.
- **FR-009**: The frontend's types for the review endpoints MUST be generated from the review-service contract, which MUST include the new batch request.
- **FR-010**: The feature MUST NOT add any new frontend dependency.

### Key Entities

- **Review**: a student's rating (1-5), optional comment and dates for one course (already exists).
- **Course Rating Summary**: average rating and review count for one course; the new batch request returns many of these keyed by course.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The catalog page needs one ratings request regardless of how many courses it shows (up to 50).
- **SC-002**: A student can submit a review from the course page in under 30 seconds.
- **SC-003**: Every error shown for a review action includes a reference code the support team can trace.
- **SC-004**: Every acceptance scenario above is covered by an automated test that passes.
- **SC-005**: The catalog remains fully usable when the rating service is unavailable.

## Assumptions

- The review-service endpoints from spec 007 are available through the gateway and unchanged except for the new batch request.
- Enrollment is enforced by the backend; the page does not pre-check enrollment and relies on the refusal message.
- Reviews are listed newest first, 20 per page, matching the existing list endpoint.
- Sorting or filtering the catalog by rating, moderation, replies and helpfulness votes are out of scope.
