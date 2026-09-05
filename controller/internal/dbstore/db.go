// Package dbstore is the controller's embedded-SQLite source of truth: the
// controller's own Ed25519 identity, paired phones (with their bearer token
// and sync cursor), and the clip index (active/trashed/purged lifecycle).
//
// Uses modernc.org/sqlite (a pure-Go SQLite driver) specifically to avoid a
// CGO/gcc toolchain dependency — this environment has no gcc on PATH, and
// the old prototype hit the same "pure driver only" requirement for the same
// reason (see its QUIRKS.md note on node:sqlite).
package dbstore

import (
	"database/sql"
	"fmt"

	_ "modernc.org/sqlite"
)

// Store wraps the SQLite connection and provides typed accessors. All
// methods are safe for concurrent use (database/sql pools connections
// internally; SQLite itself serializes writers).
type Store struct {
	db *sql.DB
}

// Open opens (creating if necessary) the SQLite database at path and applies
// the schema migration.
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

	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return s, nil
}

func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) migrate() error {
	const schema = `
	CREATE TABLE IF NOT EXISTS identity (
		id INTEGER PRIMARY KEY CHECK (id = 1),
		public_key BLOB NOT NULL,
		private_key BLOB NOT NULL,
		controller_name TEXT NOT NULL
	);

	CREATE TABLE IF NOT EXISTS phones (
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

	CREATE TABLE IF NOT EXISTS clips (
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

	CREATE INDEX IF NOT EXISTS idx_clips_state ON clips(state);
	CREATE INDEX IF NOT EXISTS idx_clips_created_at ON clips(created_at_ms);

	-- Calibration data model: shared by manufacturer+model, not per-phone.
	-- See docs/implementation/HANDOFF-controller-ux.md "Calibration data model".
	-- Not populated/consumed by this vertical slice (no calibration UI yet),
	-- but the table exists so the shape is settled and a later pass just
	-- fills it in.
	CREATE TABLE IF NOT EXISTS calibration (
		manufacturer_model TEXT PRIMARY KEY,
		source_phone_id TEXT NOT NULL,
		calibrated_at_ms INTEGER NOT NULL,
		result_json TEXT NOT NULL
	);
	`
	_, err := s.db.Exec(schema)
	return err
}
