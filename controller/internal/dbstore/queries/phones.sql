-- name: InsertPhone :exec
INSERT INTO phones (id, name, base_url, token, manufacturer, model, last_seen_ms, sync_cursor_ms, created_at_ms)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);

-- name: ListPhones :many
SELECT id, name, base_url, token, manufacturer, model, last_seen_ms, sync_cursor_ms, created_at_ms
FROM phones ORDER BY created_at_ms ASC;

-- name: GetPhone :one
SELECT id, name, base_url, token, manufacturer, model, last_seen_ms, sync_cursor_ms, created_at_ms
FROM phones WHERE id = ?;

-- name: UpdatePhoneLastSeen :exec
UPDATE phones SET last_seen_ms = ? WHERE id = ?;

-- name: UpdatePhoneName :exec
UPDATE phones SET name = ? WHERE id = ?;

-- name: AdvanceSyncCursor :exec
UPDATE phones SET sync_cursor_ms = ? WHERE id = ?;

-- name: DeletePhone :exec
DELETE FROM phones WHERE id = ?;

-- name: UpdatePhoneBaseURL :exec
UPDATE phones SET base_url = ? WHERE id = ?;

-- name: ListPhoneAddresses :many
SELECT base_url FROM phone_addresses WHERE phone_id = ? ORDER BY position ASC;

-- name: DeletePhoneAddresses :exec
DELETE FROM phone_addresses WHERE phone_id = ?;

-- name: InsertPhoneAddress :exec
INSERT INTO phone_addresses (phone_id, base_url, position) VALUES (?, ?, ?);
