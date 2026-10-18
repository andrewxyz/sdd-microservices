-- Course: the aggregate root, a single catalog offering (data-model.md Course).
CREATE TABLE courses (
    id              UUID PRIMARY KEY,
    title           VARCHAR(200),
    description     TEXT,
    category_id     UUID REFERENCES categories (id),
    level           VARCHAR(20) CHECK (level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    language        VARCHAR(20),
    price_cents     INTEGER CHECK (price_cents >= 0),
    currency        VARCHAR(3) NOT NULL DEFAULT 'USD',
    instructor_id   UUID NOT NULL,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    published_at    TIMESTAMPTZ NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0
);

-- SC-004: a filtered, sorted, paginated catalog query over 10,000 published courses must return
-- within 2 seconds (research.md R4).
CREATE INDEX courses_status_idx ON courses (status);
CREATE INDEX courses_category_id_idx ON courses (category_id);
CREATE INDEX courses_published_at_idx ON courses (published_at);
