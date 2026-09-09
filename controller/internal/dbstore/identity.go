package dbstore

import (
	"context"
	"crypto/ed25519"
	"database/sql"
	"errors"

	"panopticon-controller/internal/dbstore/queries"
)

// Identity is the controller's own persistent Ed25519 keypair plus the
// display name sent as `name` in POST /api/pair. One row, created on first
// run and reused for the life of the install — docs/design/http-api.md's pairing
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
	row, err := s.q.GetIdentity(context.Background())
	if errors.Is(err, sql.ErrNoRows) {
		return Identity{}, ErrNotFound
	}
	if err != nil {
		return Identity{}, err
	}
	return Identity{
		PublicKey:  ed25519.PublicKey(row.PublicKey),
		PrivateKey: ed25519.PrivateKey(row.PrivateKey),
		Name:       row.ControllerName,
	}, nil
}

// SaveIdentity persists the identity row (insert-or-replace; there is only
// ever one).
func (s *Store) SaveIdentity(id Identity) error {
	return s.q.SaveIdentity(context.Background(), queries.SaveIdentityParams{
		PublicKey:      []byte(id.PublicKey),
		PrivateKey:     []byte(id.PrivateKey),
		ControllerName: id.Name,
	})
}
