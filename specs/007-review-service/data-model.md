# Data Model: Review Service

review-service owns one PostgreSQL database (`review`), per constitution Principle II. No other service
may read this database directly.

## Entity: Review

Maps to the `reviews` table (Flyway `V1__create_reviews.sql`).

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID, PK | Generated on create. |
| `user_id` | UUID, NOT NULL | The reviewing student's id, from `X-User-Id`. Not a foreign key (no cross-service DB reference, Principle II). |
| `course_id` | UUID, NOT NULL | The reviewed course's id. Not a foreign key for the same reason; validity is established by the enrollment lookup at write time, not by referential integrity. |
| `rating` | SMALLINT, NOT NULL | 1–5 inclusive; CHECK constraint `rating BETWEEN 1 AND 5` (FR-012). |
| `comment` | VARCHAR(500), NULL | Optional; max length enforced both at the DB column and in application validation (R4). |
| `status` | VARCHAR(16), NOT NULL, default `'ACTIVE'` | `ACTIVE` or `DELETED`. A soft delete: FR-005 removes the review from public listings/aggregate (FR-009) but the audit row is retained, matching the platform's general preference for soft state transitions over hard deletes (seen in enrollment/certificate domain objects' status fields). |
| `created_at` | TIMESTAMPTZ, NOT NULL | Set on insert. |
| `updated_at` | TIMESTAMPTZ, NOT NULL | Set on insert and every update (rating/comment edit or status change). |

**Constraints**:
- `UNIQUE (user_id, course_id)` — enforces FR-001/FR-003 (one review per student per course; a second
  submission updates the existing row) at the database level (R3).
- Index on `course_id` (and implicitly on `(course_id, status)` for the summary query) to make
  FR-008/FR-009's list and aggregate reads efficient.

**State transitions**:
- `(none)` → `ACTIVE`: created via `POST /api/v1/reviews/courses/{courseId}` after a successful
  enrollment check (FR-002).
- `ACTIVE` → `ACTIVE` (fields updated): `PUT /api/v1/reviews/{reviewId}` by the owning student (FR-004).
- `ACTIVE` → `DELETED`: `DELETE /api/v1/reviews/{reviewId}` by the owning student (FR-005). A
  `DELETED` review is excluded from `GET .../courses/{courseId}` and from the summary aggregate
  (FR-009), and is not resurrected by a later `POST` — a later `POST` from the same student for the
  same course is rejected as "unique constraint still holds on a deleted row" would be wrong; instead the
  upsert logic treats a `DELETED` row as eligible to be reactivated to `ACTIVE` with the new
  rating/comment, since the unique key is on `(user_id, course_id)` regardless of status and the student
  is still enrolled.

## Derived view: Course Rating Summary

Not a stored table — computed on read from `reviews` filtered to `status = 'ACTIVE'` for the given
`course_id`:

| Field | Type | Notes |
|-------|------|-------|
| `course_id` | UUID | Echoes the request path. |
| `average_rating` | DECIMAL | `AVG(rating)` over active reviews; `null`/omitted when `review_count` is 0 (FR-009's "no reviews yet" case, Edge Cases). |
| `review_count` | INTEGER | `COUNT(*)` over active reviews. |

Computed in the same query/transaction as the read, so it can never diverge from the underlying `reviews`
rows (R1, SC-003) — there is no cached/materialized copy to go stale.
