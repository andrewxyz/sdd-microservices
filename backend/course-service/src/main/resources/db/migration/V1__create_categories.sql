-- Category: a named grouping courses are organized under (data-model.md Category).
CREATE TABLE categories (
    id      UUID PRIMARY KEY,
    name    VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

-- Case-insensitive uniqueness (FR-020).
CREATE UNIQUE INDEX categories_name_lower_idx ON categories (lower(name));
