package dbstore

import (
	"crypto/ed25519"
	"database/sql"
	"errors"
)

// Identity is the controller's own persistent Ed25519 keypair plus the
// display name sent as `name` in POST /api/pair. One row, created on first
// run and reused for the life of the install — phone-http-api.md's pairing
// model keys each phone's bearer token to this controller's public key, so
// it must be stable across restarts.
type Identity struct {
	PublicKey  ed25519.PublicKey
	PrivateKey ed25519.PrivateKey
	Name       string
}

// GetIdentity returns the single stored identity row, or ErrNotFound if
// none has been created yet.
func (s *Store) GetIdentity() (Identity, error) {
	var pub, priv []byte
	var name string
	err := s.db.QueryRow(`SELECT public_key, private_key, controller_name FROM identity WHERE id = 1`).
		Scan(&pub, &priv, &name)
	if errors.Is(err, sql.ErrNoRows) {
		return Identity{}, ErrNotFound
	}
	if err != nil {
		return Identity{}, err
	}
	return Identity{PublicKey: ed25519.PublicKey(pub), PrivateKey: ed25519.PrivateKey(priv), Name: name}, nil
}

// SaveIdentity persists the identity row (insert-or-replace; there is only
// ever one).
func (s *Store) SaveIdentity(id Identity) error {
	_, err := s.db.Exec(`
		INSERT INTO identity (id, public_key, private_key, controller_name) VALUES (1, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET public_key = excluded.public_key, private_key = excluded.private_key, controller_name = excluded.controller_name`,
		[]byte(id.PublicKey), []byte(id.PrivateKey), id.Name)
	return err
}
