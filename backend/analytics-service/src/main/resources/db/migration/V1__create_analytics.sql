-- Spec 009 data-model.md: per-course, per-UTC-day counters and the ids of events already counted.
-- No user, payment, enrollment or certificate identifier is stored anywhere (constitution XIV).

CREATE TABLE course_daily_stats (
    course_id     uuid   NOT NULL,
    day           date   NOT NULL,
    enrollments   bigint NOT NULL DEFAULT 0,
    revenue_cents bigint NOT NULL DEFAULT 0,
    completions   bigint NOT NULL DEFAULT 0,
    certificates  bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (course_id, day)
);

CREATE TABLE processed_events (
    event_id     uuid        PRIMARY KEY,
    processed_at timestamptz NOT NULL
);
