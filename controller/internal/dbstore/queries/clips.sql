-- name: UpsertClip :exec
INSERT INTO clips (phone_id, filename, state, local_path, thumbnail_path, created_at_ms, duration_ms, size_bytes, width, height)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(phone_id, filename) DO NOTHING;

-- name: ClipExists :one
SELECT COUNT(1) FROM clips WHERE phone_id = ? AND filename = ?;

-- name: GetClip :one
SELECT phone_id, filename, state, local_path, thumbnail_path, created_at_ms, duration_ms, size_bytes, width, height
FROM clips WHERE phone_id = ? AND filename = ?;

-- name: SetClipState :exec
UPDATE clips SET state = ? WHERE phone_id = ? AND filename = ?;

-- sqlc.arg(phone_id) = "" means every phone, sqlc.arg(state) = "" means
-- every state - the sentinel-OR trick keeps this one static query doing
-- the work of what would otherwise be up to four hand-built variants.
-- name: ListClips :many
SELECT phone_id, filename, state, local_path, thumbnail_path, created_at_ms, duration_ms, size_bytes, width, height
FROM clips
WHERE (CAST(sqlc.arg(phone_id) AS TEXT) = '' OR phone_id = sqlc.arg(phone_id))
  AND (CAST(sqlc.arg(state) AS TEXT) = '' OR state = sqlc.arg(state))
ORDER BY created_at_ms DESC;

-- sqlc.arg(phone_id) = "" means every phone; see ListClips above.
-- name: DiskUsageBytes :one
SELECT CAST(COALESCE(SUM(size_bytes), 0) AS INTEGER) FROM clips
WHERE state IN ('active', 'trashed')
  AND (CAST(sqlc.arg(phone_id) AS TEXT) = '' OR phone_id = sqlc.arg(phone_id));
