-- +goose Up

-- Split the old `clips` table (one row per ~10s file) into:
--   segments  - one row per file, exactly what `clips` held
--   clips     - a contiguous run of segments (the user-facing gallery item)
--
-- Forward-only and NOT cleanly reversible: the down migration drops both new
-- tables but does not reconstruct the original `clips` shape, and rolling
-- 001's down after this would fail. Per internal/dbstore/README.md, rollback
-- isn't part of normal use.
--
-- Per-segment trash/purge state is intentionally dropped - it was near
-- meaningless at 10s granularity. Everything already synced becomes an
-- `active` clip; the Go backfill in regroup.go assigns clip_id + creates the
-- clip rows on the next Open().

CREATE TABLE segments (
    phone_id TEXT NOT NULL,
    filename TEXT NOT NULL,
    clip_id TEXT NOT NULL DEFAULT '',
    local_path TEXT NOT NULL DEFAULT '',
    thumbnail_path TEXT NOT NULL DEFAULT '',
    created_at_ms INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    end_ms INTEGER NOT NULL DEFAULT 0,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    width INTEGER NOT NULL DEFAULT 0,
    height INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (phone_id, filename)
);

INSERT INTO segments (phone_id, filename, clip_id, local_path, thumbnail_path, created_at_ms, duration_ms, end_ms, size_bytes, width, height)
SELECT phone_id, filename, '', local_path, thumbnail_path, created_at_ms, duration_ms, created_at_ms + duration_ms, size_bytes, width, height
FROM clips;

DROP INDEX IF EXISTS idx_clips_state;
DROP INDEX IF EXISTS idx_clips_created_at;
DROP TABLE clips;

CREATE TABLE clips (
    id TEXT PRIMARY KEY,
    phone_id TEXT NOT NULL,
    started_at_ms INTEGER NOT NULL,
    ended_at_ms INTEGER NOT NULL,
    segment_count INTEGER NOT NULL DEFAULT 0,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active','trashed','purged')),
    created_at_ms INTEGER NOT NULL
);

CREATE INDEX idx_segments_clip ON segments (clip_id);
CREATE INDEX idx_segments_created_at ON segments (created_at_ms);
CREATE INDEX idx_clips_state ON clips (state);
CREATE INDEX idx_clips_started_at ON clips (started_at_ms);

-- +goose Down

DROP INDEX idx_clips_started_at;
DROP INDEX idx_clips_state;
DROP INDEX idx_segments_created_at;
DROP INDEX idx_segments_clip;
DROP TABLE clips;
DROP TABLE segments;
