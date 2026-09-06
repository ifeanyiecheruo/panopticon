// Package dbstore is the controller's embedded-SQLite source of truth: the
// controller's own Ed25519 identity, paired phones (with their bearer token
// and sync cursor), and the clip index (active/trashed/purged lifecycle).
//
// Uses modernc.org/sqlite (a pure-Go SQLite driver) specifically to avoid a
// CGO/gcc toolchain dependency — this environment has no gcc on PATH, and
// the old prototype hit the same "pure driver only" requirement for the same
// reason (see its QUIRKS.md note on node:sqlite).
//
// The schema (schemas/db/*.sql, goose migrations) and every query
// (queries/*.sql) are generated into queries/ via sqlc — see queries.gen.json
// and ../../../tools/dbstore, and don't hand-edit anything under queries/,
// it's overwritten on every `make generate`. This package is the
// hand-written layer on top: Store keeps the same domain-shaped methods and
// types callers already used before the sqlc migration (ClipState enum,
// ed25519.PublicKey/PrivateKey, ErrNotFound), now implemented by delegating
// to the generated Queries instead of hand-rolled SQL strings and Scan
// calls.
package dbstore

import (
	"context"
	"database/sql"
	"fmt"
	"log"

	"panopticon-controller/internal/dbstore/queries"

	_ "modernc.org/sqlite"
)

// GroupingGapMs is the max gap (segment.createdAtMs - previous.endMs) for two
// segments to count as one contiguous clip. Loose for now because the current
// recording pipeline drops ~1-2s on every ~10s segment rotation; drops to
// ~500 once gapless rotation (setNextOutputFile) lands. The syncer and the
// one-time backfill both use this.
const GroupingGapMs int64 = 3000

// Store wraps the SQLite connection and provides typed accessors. All
// methods are safe for concurrent use (database/sql pools connections
// internally; SQLite itself serializes writers).
type Store struct {
	db *sql.DB
	q  *queries.Queries
}

// Open opens (creating if necessary) the SQLite database at path and applies
// every pending migration.
func Open(path string) (*Store, error) {
	// _pragma busy_timeout avoids "database is locked" errors when the sync
	// loop and a UI-triggered query land at the same instant — SQLite has a
	// single writer, this just makes concurrent callers wait briefly instead
	// of failing immediately.
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	// SQLite (via this driver) allows only one writer at a time; capping the
	// pool to a single connection avoids SQLITE_BUSY churn from the driver
	// itself opening multiple connections that then contend with each other.
	db.SetMaxOpenConns(1)

	if err := migrate(context.Background(), db); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	store := &Store{db: db, q: queries.New(db)}

	// One-time backfill after the 002 migration: group the copied-over
	// per-file rows into clips. No-op once every segment has a clip.
	if made, err := store.RegroupUnassignedSegments(GroupingGapMs); err != nil {
		db.Close()
		return nil, fmt.Errorf("regroup segments into clips: %w", err)
	} else if made > 0 {
		log.Printf("dbstore: grouped unassigned segments into %d clip(s)", made)
	}

	return store, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}
