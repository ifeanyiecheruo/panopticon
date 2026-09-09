// Package identity manages the controller's persistent Ed25519 keypair,
// used as this controller's identity when pairing with a phone (POST
// /api/pair's "publicKey" field per docs/design/http-api.md).
package identity

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"os"

	"panopticon-controller/internal/dbstore"
)

// LoadOrCreate returns the controller's identity, generating and persisting
// a fresh Ed25519 keypair on first run. The keypair is real (not a
// placeholder) — crypto/rand + crypto/ed25519, standard library only.
func LoadOrCreate(store *dbstore.Store) (dbstore.Identity, error) {
	id, err := store.GetIdentity()
	if err == nil {
		return id, nil
	}
	if !errors.Is(err, dbstore.ErrNotFound) {
		return dbstore.Identity{}, fmt.Errorf("load identity: %w", err)
	}

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return dbstore.Identity{}, fmt.Errorf("generate keypair: %w", err)
	}

	name := defaultControllerName()
	fresh := dbstore.Identity{PublicKey: pub, PrivateKey: priv, Name: name}
	if err := store.SaveIdentity(fresh); err != nil {
		return dbstore.Identity{}, fmt.Errorf("save identity: %w", err)
	}
	return fresh, nil
}

// PublicKeyBase64 is the wire format POST /api/pair expects for
// "publicKey" (the doc's example shows a base64-ish SPKI-style string; we
// send the raw 32-byte Ed25519 public key, standard base64-encoded, which is
// what a Kotlin/Ktor phone-side implementation can decode straightforwardly
// with java.util.Base64 + a raw Ed25519 key constructor).
func PublicKeyBase64(id dbstore.Identity) string {
	return base64.StdEncoding.EncodeToString(id.PublicKey)
}

func defaultControllerName() string {
	host, err := os.Hostname()
	if err != nil || host == "" {
		return "Desktop controller"
	}
	return host + "'s Desktop"
}
