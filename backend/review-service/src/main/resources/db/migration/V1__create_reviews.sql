-- An enrolled student's rating and optional comment for a course (data-model.md "Review"). At most one
-- row per (user_id, course_id); a soft-delete status keeps the audit row while excluding it from public
-- listings and the aggregate rating (FR-005, FR-009).
CREATE TABLE reviews (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL,
    course_id       UUID NOT NULL,
    rating          SMALLINT NOT NULL,
    comment         VARCHAR(500),
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT reviews_rating_range CHECK (rating BETWEEN 1 AND 5)
);

CREATE UNIQUE INDEX reviews_user_course_unique ON reviews (user_id, course_id);
CREATE INDEX reviews_course_id_idx ON reviews (course_id);
