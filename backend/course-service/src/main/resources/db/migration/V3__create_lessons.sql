-- Lesson: a child of exactly one course (data-model.md Lesson).
CREATE TABLE lessons (
    id               UUID PRIMARY KEY,
    course_id        UUID NOT NULL REFERENCES courses (id),
    title            VARCHAR(200) NOT NULL,
    position         INTEGER NOT NULL,
    duration_minutes INTEGER NOT NULL CHECK (duration_minutes >= 0),
    content          TEXT NOT NULL DEFAULT ''
);

-- Unique per course (data-model.md: "position... unique per course"). DEFERRABLE INITIALLY DEFERRED:
-- a reorder swaps multiple lessons' positions in one transaction, and a plain (non-deferred) unique
-- index rejects the intermediate state the moment any one UPDATE collides with another row's
-- not-yet-updated position, even though the final state is perfectly valid. Deferring the check to
-- commit time is the standard fix for exactly this "reorder under a uniqueness constraint" pattern.
ALTER TABLE lessons
    ADD CONSTRAINT lessons_course_id_position_unique UNIQUE (course_id, position) DEFERRABLE INITIALLY DEFERRED;
