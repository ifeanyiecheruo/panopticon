-- name: GetIdentity :one
SELECT public_key, private_key, controller_name FROM identity WHERE id = 1;

-- name: SaveIdentity :exec
INSERT INTO identity (id, public_key, private_key, controller_name) VALUES (1, ?, ?, ?)
ON CONFLICT(id) DO UPDATE SET public_key = excluded.public_key, private_key = excluded.private_key, controller_name = excluded.controller_name;
