# Feature Specification: Review Service

**Feature Branch**: `007-review-service`

**Created**: 2026-10-20

**Status**: Draft

**Input**: User description: "Review-service: allow enrolled students to rate and review courses they're enrolled in. Follows the established per-service pattern (certificate-service, course-service): domain entity + repository, enrollment lookup client with fallback, transactional outbox publishing to learning.events, idempotent consumers with DLQs, database-per-service with Flyway, REST via API gateway with JWT auth. One review per student/course, public read of reviews and aggregate rating, authenticated create/update/delete restricted to the review's owner, basic profanity/length validation only. Out of scope: moderation workflows beyond basic validation, admin review-management UI, review replies, helpfulness voting, analytics dashboards."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enrolled student reviews a course (Priority: P1)

A student who is enrolled in a course wants to rate it (1-5 stars) and optionally leave a written comment, so future students can see their feedback and the course's reputation reflects real learner experience.

**Why this priority**: This is the core value of the feature — without the ability to submit a review, there is nothing to browse, moderate, or aggregate. It is also the smallest independently valuable slice.

**Independent Test**: Can be fully tested by enrolling a test student in a course, submitting a review with a rating and comment, and confirming the review is stored and attributed to that student and course.

**Acceptance Scenarios**:

1. **Given** a student is enrolled in a course and has not yet reviewed it, **When** they submit a rating (1-5) and an optional comment, **Then** the review is saved and associated with their account and the course.
2. **Given** a student is NOT enrolled in a course, **When** they attempt to submit a review for it, **Then** the submission is rejected with a clear explanation that enrollment is required.
3. **Given** a student submits a comment containing blocked profanity or exceeding the maximum length, **When** they submit the review, **Then** the submission is rejected with a message identifying the problem.

---

### User Story 2 - Student updates or removes their own review (Priority: P2)

A student who already reviewed a course changes their mind about the rating/comment, or wants to retract their review entirely.

**Why this priority**: Builds directly on Story 1's data model; important for trust (reviews should reflect current opinion) but the platform is still usable without it for an initial release.

**Independent Test**: Can be fully tested by having a student submit a review, then edit its rating/comment, and separately by deleting a review, confirming each change persists and old content is no longer visible.

**Acceptance Scenarios**:

1. **Given** a student has an existing review for a course, **When** they submit a new rating/comment for the same course, **Then** their existing review is updated in place rather than a second review being created.
2. **Given** a student has an existing review, **When** they delete it, **Then** the review no longer appears in that course's review list and no longer contributes to the course's aggregate rating.
3. **Given** a student attempts to edit or delete a review they do not own, **When** the request is made, **Then** it is rejected.

---

### User Story 3 - Prospective student browses course reviews and rating (Priority: P1)

A prospective or current student browsing the catalog or a course detail page wants to see the course's average rating, how many reviews it has, and read individual reviews, without needing to log in.

**Why this priority**: This is the payoff that makes Story 1 worthwhile — reviews only create value once they're visible to influence enrollment decisions. Tied with Story 1 as foundational, listed second only because it depends on reviews existing.

**Independent Test**: Can be fully tested by seeding a course with several reviews (via Story 1) and, without authenticating, requesting that course's review list and aggregate rating, confirming both are accurate and publicly accessible.

**Acceptance Scenarios**:

1. **Given** a course has one or more reviews, **When** anyone (authenticated or not) requests the course's reviews, **Then** the list of reviews (rating, comment, reviewer identity, timestamp) is returned.
2. **Given** a course has reviews, **When** anyone requests the course's summary, **Then** the average rating and total review count are returned and match the underlying reviews.
3. **Given** a course has no reviews yet, **When** anyone requests its rating summary, **Then** the system returns a clear "no reviews yet" result rather than an error.

---

### Edge Cases

- What happens when a student's enrollment is later cancelled/refunded after they already left a review? (Assumption: the review is left in place; retroactive removal is out of scope for this spec.)
- How does the system handle a review submission for a course that does not exist or has been removed? Rejected with a not-found error.
- How does the system handle concurrent edits to the same review (e.g., two tabs submitting at once)? The latest write wins.
- What happens to a student's reviews if their account is deleted? Out of scope for this spec; assume reviews persist with a reference to the (possibly deactivated) student until a future account-deletion feature addresses it.
- How is the aggregate rating kept consistent if the review-service is temporarily unavailable when a review changes? The aggregate is derived from the service's own review data (directly or via published events), so once the service recovers, the aggregate reflects the true state — no permanent drift.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST allow a student to submit exactly one review (a 1-5 star rating, required, plus an optional text comment) per course they are enrolled in.
- **FR-002**: System MUST verify the submitting student has an enrollment (active or completed) in the target course before accepting a review, and MUST reject the submission otherwise with a clear reason.
- **FR-003**: System MUST treat a second review submission from the same student for the same course as an update to their existing review, not a new review.
- **FR-004**: System MUST allow a student to edit the rating and/or comment of their own existing review.
- **FR-005**: System MUST allow a student to delete their own existing review.
- **FR-006**: System MUST prevent a student from editing or deleting a review they do not own.
- **FR-007**: System MUST validate review comments against a minimum set of abuse rules (blocked-word/profanity list and a maximum comment length) and reject submissions that fail validation with a specific reason.
- **FR-008**: System MUST allow any visitor, authenticated or not, to list the reviews for a given course.
- **FR-009**: System MUST allow any visitor, authenticated or not, to retrieve a course's aggregate rating (average rating and total review count), reflecting only currently-active (non-deleted) reviews.
- **FR-010**: System MUST update a course's aggregate rating whenever a review is created, updated, or deleted, so the aggregate never diverges from the underlying reviews.
- **FR-011**: System MUST record, for each review, which student wrote it, which course it is for, the rating, the optional comment, and when it was created and last updated.
- **FR-012**: System MUST reject review submissions with a rating outside the 1-5 range.
- **FR-013**: System MUST make review listing and aggregate-rating retrieval reachable through the platform's public API without requiring the caller to be signed in.
- **FR-014**: System MUST require the caller to be signed in and identified as the review's owner for create, update, and delete operations.

### Key Entities

- **Review**: A single student's evaluation of a single course — rating (1-5), optional comment text, the reviewing student's identity, the reviewed course's identity, created/updated timestamps, and lifecycle status (active or deleted). Unique per (student, course) pair.
- **Course Rating Summary**: The aggregate view for a course — average rating and total count of active reviews. Derived from Review data; not independently editable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An enrolled student can submit a review for a course in under 30 seconds of interaction.
- **SC-002**: 100% of review submissions from students without a qualifying enrollment are rejected, with zero false acceptances.
- **SC-003**: A course's displayed average rating and review count always match a direct count/average of that course's currently-active reviews (zero drift incidents).
- **SC-004**: A prospective student can view a course's rating and reviews without creating an account or signing in, in a single request/page load.
- **SC-005**: A student can update or remove their own review and see the change reflected the next time they (or anyone else) view the course's reviews, with no stale/duplicate review left behind.

## Assumptions

- Only students with an enrollment record (active or completed) in a course may review it; course instructors/staff reviewing their own course is out of scope.
- "Enrolled" is determined by the existing enrollment data already maintained elsewhere in the platform; this feature consumes that information rather than owning it.
- Profanity/length validation is a simple, static rule set (blocked-word list + max length), not AI-based moderation — consistent with the explicit scope boundary in the input.
- A deleted review is excluded from public listings and the aggregate rating, but the feature does not require a formal "moderation" workflow, admin UI, review replies, helpfulness voting, or analytics dashboard — all explicitly out of scope.
- Reviewer identity shown alongside a review is the student's existing public display name/identity already used elsewhere in the platform; no new identity concept is introduced.
- Aggregate rating recalculation may happen synchronously or via an asynchronous notification to other parts of the system; either way, the requirement is that the externally visible aggregate stays accurate, not the internal mechanism.
