-- name: InsertClip :exec
INSERT INTO clips (id, phone_id, started_at_ms, ended_at_ms, segment_count, size_bytes, state, created_at_ms)
VALUES (?, ?, ?, ?, ?, ?, ?, ?);

-- name: GetOpenClip :one
SELECT id, phone_id, started_at_ms, ended_at_ms, segment_count, size_bytes, state, created_at_ms
FROM clips
WHERE phone_id = ? AND state = 'active'
ORDER BY ended_at_ms DESC LIMIT 1;

-- name: ExtendClip :exec
UPDATE clips
SET ended_at_ms = ?, segment_count = segment_count + 1, size_bytes = size_bytes + ?
WHERE id = ?;

-- name: GetClip :one
SELECT id, phone_id, started_at_ms, ended_at_ms, segment_count, size_bytes, state, created_at_ms
FROM clips WHERE phone_id = ? AND id = ?;

-- sqlc.arg(phone_id) = "" means every phone, sqlc.arg(state) = "" means every
-- state - the sentinel-OR trick keeps this one static query doing what would
-- otherwise be several hand-built variants.
-- name: ListClips :many
SELECT id, phone_id, started_at_ms, ended_at_ms, segment_count, size_bytes, state, created_at_ms
FROM clips
WHERE (CAST(sqlc.arg(phone_id) AS TEXT) = '' OR phone_id = sqlc.arg(phone_id))
  AND (CAST(sqlc.arg(state) AS TEXT) = '' OR state = sqlc.arg(state))
ORDER BY started_at_ms DESC;

-- name: SetClipState :exec
UPDATE clips SET state = ? WHERE phone_id = ? AND id = ?;

-- sqlc.arg(phone_id) = "" means every phone. Disk usage is the on-disk bytes of
-- segments belonging to non-purged clips (purged clips have had their files
-- removed).
-- name: DiskUsageBytes :one
SELECT CAST(COALESCE(SUM(s.size_bytes), 0) AS INTEGER)
FROM segments s
JOIN clips c ON c.id = s.clip_id
WHERE c.state IN ('active', 'trashed')
  AND (CAST(sqlc.arg(phone_id) AS TEXT) = '' OR c.phone_id = sqlc.arg(phone_id));
