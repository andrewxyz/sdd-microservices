-- specs/005-progress-certificates-notifications: course completion is a sticky, one-time fact on the
-- existing enrollment (FR-008, FR-009), and lesson progress is tracked per enrollment per lesson
-- (FR-001 to FR-006). Not "V2" — that number is already taken by 004's V2__create_processed_events.sql
-- (research.md R0).

ALTER TABLE enrollments ADD COLUMN completed_at TIMESTAMPTZ;

CREATE TABLE lesson_progress (
    id              UUID PRIMARY KEY,
    enrollment_id   UUID NOT NULL REFERENCES enrollments(id),
    lesson_id       UUID NOT NULL,
    completed_at    TIMESTAMPTZ NOT NULL
);

-- FR-004: a lesson is either complete or not; there is no "complete again" state.
CREATE UNIQUE INDEX lesson_progress_enrollment_lesson_unique ON lesson_progress (enrollment_id, lesson_id);
