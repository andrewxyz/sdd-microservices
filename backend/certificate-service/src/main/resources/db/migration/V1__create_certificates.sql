-- Proof a student completed a course (data-model.md "Certificate"). At most one per student per course.
CREATE TABLE certificates (
    id                  UUID PRIMARY KEY,
    user_id             UUID NOT NULL,
    course_id           UUID NOT NULL,
    verification_code   VARCHAR(16) NOT NULL,
    course_title        TEXT NOT NULL,
    issued_at           TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX certificates_user_course_unique ON certificates (user_id, course_id);
CREATE UNIQUE INDEX certificates_verification_code_unique ON certificates (verification_code);
