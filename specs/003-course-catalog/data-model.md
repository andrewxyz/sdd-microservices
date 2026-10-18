# Data Model: Course Catalog

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)

All persisted data lives in course-service's own `course` database (already provisioned, empty, by
platform-foundation's `db-init`). No other service's database is read or written by this feature.

## Course

The aggregate root. A single catalog offering (FR-008 to FR-018, FR-025 to FR-027).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key, generated at creation |
| `title` | Required to publish (FR-014); MAY be blank on a draft |
| `description` | Required to publish (FR-014); MAY be blank on a draft |
| `categoryId` | Required to publish (FR-014); MAY be unset on a draft; references `Category.id` |
| `level` | One of `BEGINNER`, `INTERMEDIATE`, `ADVANCED` (research.md R3); optional, never required to publish |
| `language` | Free-text short code (e.g. `en`, research.md R3); optional, never required to publish |
| `priceCents` | Required to publish (FR-014); whole, non-negative integer; zero is a complete, valid price (FR-015), not a missing one |
| `currency` | Fixed `USD` for every course (FR-026) |
| `instructorId` | Set at creation to the creator's account id (instructor or administrator, spec.md Clarifications 2026-10-18); immutable afterward |
| `status` | One of `DRAFT`, `PUBLISHED`, `ARCHIVED` (see State transitions below) |
| `publishedAt` | Null until first published; refreshed every time the course transitions into `PUBLISHED` (research.md R5), including a republish after archiving |
| `createdAt` | Set once at creation |
| `updatedAt` | Set on every change |
| `version` | JPA optimistic-lock column (research.md R1); incremented on every change to the course or any of its lessons |

Validation rules:

- Publish-readiness (FR-014, checked both when publishing and, per FR-018, on every edit to an
  already-published course): `title` non-blank, `description` non-blank, `categoryId` set, `priceCents`
  set (zero counts, FR-015), at least one `Lesson`.
- A `PUBLISHED` course can never be deleted (FR-013); only a `DRAFT` can be deleted.
- `instructorId` never changes after creation — ownership is fixed at creation (spec.md Assumptions: no
  "assign to another instructor" action).

State transitions:

```text
DRAFT --(publish, FR-014 met)--> PUBLISHED --(archive)--> ARCHIVED --(publish, FR-014 still met)--> PUBLISHED
DRAFT --(delete, owner/admin only)--> (gone)
PUBLISHED --(edit that would break FR-014)--> refused outright (FR-018); course stays PUBLISHED and unchanged
```

There is no `ARCHIVED --(delete)-->` transition and no `PUBLISHED --(delete)-->` transition: FR-013
refuses deletion for any non-`DRAFT` course.

## Lesson

A child of exactly one `Course` (FR-010, FR-024).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key |
| `courseId` | Foreign key to `Course.id`; a lesson never outlives or moves between courses |
| `title` | Required (part of the lesson outline every viewer who can see the course at all may see, FR-006) |
| `position` | Integer; defines display order within the course; unique per course (FR-010's "reorder") |
| `durationMinutes` | Required, non-negative integer; part of the public outline (FR-006) |
| `content` | Text; readable only by the owning instructor or an administrator (FR-024); never part of the public outline or the service-to-service lookup (FR-023) |

Validation rules:

- `position` values within one course are contiguous and unique; a reorder request that would produce a
  duplicate or a gap is refused (spec.md Edge Cases) with no partial change applied.
- Removing a course's only lesson from a `PUBLISHED` course is refused by the same FR-018 rule that
  governs any other edit that would break publish-readiness.

## Category

A named grouping courses are organized under (FR-019 to FR-022).

| Field | Rule |
|-------|------|
| `id` | UUID, primary key |
| `name` | Required; unique case-insensitively (consistent with how email uniqueness already works for accounts, spec.md Assumptions) |
| `version` | JPA optimistic-lock column (research.md R1); FR-025 explicitly includes "a category change" among the saves that must not silently overwrite a concurrent one |

Validation rules:

- Create or rename refuses a name that collides (case-insensitively) with any other category's name
  (FR-020); a rename to a category's own current name is not a collision with itself.
- Delete refuses whenever `COUNT(*) FROM courses WHERE category_id = ?` is greater than zero
  (FR-021, research.md R7).

## CourseSummary (internal lookup response, not persisted)

The shape returned by the service-to-service lookup (FR-023, research.md R6). Not a database entity;
projected from `Course` on every call.

| Field | Source |
|-------|--------|
| `courseId` | `Course.id` |
| `status` | `Course.status` |
| `priceCents` | `Course.priceCents` |
| `currency` | `Course.currency` |
| `instructorId` | `Course.instructorId` |
| `lessonCount` | `COUNT(*)` of the course's lessons |

Returned regardless of the course's status (draft, published or archived) — this lookup exists for
other backend services' own logic (future payment/enrollment specs), not for public display.
