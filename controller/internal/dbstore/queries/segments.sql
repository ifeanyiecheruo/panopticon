-- name: UpsertSegment :exec
INSERT INTO segments (phone_id, filename, clip_id, local_path, thumbnail_path, created_at_ms, duration_ms, end_ms, size_bytes, width, height)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(phone_id, filename) DO NOTHING;

-- name: SegmentExists :one
SELECT COUNT(1) FROM segments WHERE phone_id = ? AND filename = ?;

-- name: ListSegmentsForClip :many
SELECT phone_id, filename, clip_id, local_path, thumbnail_path, created_at_ms, duration_ms, end_ms, size_bytes, width, height
FROM segments WHERE clip_id = ? ORDER BY created_at_ms ASC;

-- name: SetSegmentClip :exec
UPDATE segments SET clip_id = ? WHERE phone_id = ? AND filename = ?;

-- name: DeleteSegment :exec
DELETE FROM segments WHERE phone_id = ? AND filename = ?;

-- name: ListUnassignedSegments :many
SELECT phone_id, filename, clip_id, local_path, thumbnail_path, created_at_ms, duration_ms, end_ms, size_bytes, width, height
FROM segments WHERE clip_id = '' ORDER BY phone_id ASC, created_at_ms ASC;
