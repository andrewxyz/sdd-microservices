-- Evidence that a user has access to a course (data-model.md "Enrollment").
CREATE TABLE enrollments (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL,
    course_id         UUID NOT NULL,
    enrolled_at       TIMESTAMPTZ NOT NULL,
    source_payment_id UUID NOT NULL
);

-- FR-015: a user never holds more than one enrollment in the same course.
CREATE UNIQUE INDEX enrollments_user_course_unique ON enrollments (user_id, course_id);
