-- +goose Up

CREATE TABLE identity (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    public_key BLOB NOT NULL,
    private_key BLOB NOT NULL,
    controller_name TEXT NOT NULL
);

CREATE TABLE phones (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    token TEXT NOT NULL,
    manufacturer TEXT NOT NULL DEFAULT '',
    model TEXT NOT NULL DEFAULT '',
    last_seen_ms INTEGER NOT NULL DEFAULT 0,
    sync_cursor_ms INTEGER NOT NULL DEFAULT 0,
    created_at_ms INTEGER NOT NULL
);

CREATE TABLE clips (
    phone_id TEXT NOT NULL,
    filename TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active','trashed','purged')),
    local_path TEXT NOT NULL DEFAULT '',
    thumbnail_path TEXT NOT NULL DEFAULT '',
    created_at_ms INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    width INTEGER NOT NULL DEFAULT 0,
    height INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (phone_id, filename)
);

CREATE INDEX idx_clips_state ON clips (state);
CREATE INDEX idx_clips_created_at ON clips (created_at_ms);

-- Calibration data model: shared by manufacturer+model, not per-phone.
-- See docs/implementation/HANDOFF-controller-ux.md "Calibration data model".
-- Not populated/consumed by this vertical slice (no calibration UI yet),
-- but the table exists so the shape is settled and a later pass just fills
-- it in.
CREATE TABLE calibration (
    manufacturer_model TEXT PRIMARY KEY,
    source_phone_id TEXT NOT NULL,
    calibrated_at_ms INTEGER NOT NULL,
    result_json TEXT NOT NULL
);

-- +goose Down

DROP TABLE calibration;
DROP INDEX idx_clips_created_at;
DROP INDEX idx_clips_state;
DROP TABLE clips;
DROP TABLE phones;
DROP TABLE identity;
