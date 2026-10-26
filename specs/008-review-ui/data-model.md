# Data Model: Course Ratings and Reviews UI

No schema change. review-service's `reviews` table (spec 007, `V1__create_reviews.sql`) is unchanged.

## Course Rating Summary (derived, not stored)
| Field | Type | Rule |
|---|---|---|
| `courseId` | UUID | echoes a requested id |
| `averageRating` | number or null | `AVG(rating)` over `ACTIVE` reviews; null when `reviewCount` is 0 |
| `reviewCount` | integer | `COUNT(*)` over `ACTIVE` reviews; 0 when none |

## Batch request rules
- `courseIds`: 1 to 50 distinct UUIDs, comma separated; anything else is a 400.
- Response contains exactly one summary per distinct requested id, in request order.

## Frontend view types
Generated from the contracts (not hand-written): `ReviewView`, `ReviewWriteRequest`, `CourseRatingSummary`, and the batch response `CourseRatingSummaries`.
