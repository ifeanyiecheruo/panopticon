-- +goose Up

-- When the user first played the clip in the controller's gallery, or 0 if never. Drives the
-- "watched" marker that lets new footage stand out. Reset to 0 when sync appends a segment,
-- since the clip then holds footage nobody has seen.
ALTER TABLE clips ADD COLUMN watched_at_ms INTEGER NOT NULL DEFAULT 0;

-- +goose Down

ALTER TABLE clips DROP COLUMN watched_at_ms;
