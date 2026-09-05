package dbstore

import (
	"database/sql"
	"errors"
	"time"
)

// Phone is a paired phone's local record: identity, address, credential,
// and sync progress.
type Phone struct {
	ID           string
	Name         string
	BaseURL      string
	Token        string
	Manufacturer string
	Model        string
	LastSeenMs   int64
	SyncCursorMs int64
	CreatedAtMs  int64
}

var ErrNotFound = errors.New("not found")

// InsertPhone stores a newly paired phone. Fails if the id already exists.
func (s *Store) InsertPhone(p Phone) error {
	_, err := s.db.Exec(`
		INSERT INTO phones (id, name, base_url, token, manufacturer, model, last_seen_ms, sync_cursor_ms, created_at_ms)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		p.ID, p.Name, p.BaseURL, p.Token, p.Manufacturer, p.Model, p.LastSeenMs, p.SyncCursorMs, p.CreatedAtMs)
	return err
}

// ListPhones returns every paired phone, oldest-paired first.
func (s *Store) ListPhones() ([]Phone, error) {
	rows, err := s.db.Query(`
		SELECT id, name, base_url, token, manufacturer, model, last_seen_ms, sync_cursor_ms, created_at_ms
		FROM phones ORDER BY created_at_ms ASC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Phone
	for rows.Next() {
		var p Phone
		if err := rows.Scan(&p.ID, &p.Name, &p.BaseURL, &p.Token, &p.Manufacturer, &p.Model, &p.LastSeenMs, &p.SyncCursorMs, &p.CreatedAtMs); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// GetPhone looks up one paired phone by id. Returns ErrNotFound if absent.
func (s *Store) GetPhone(id string) (Phone, error) {
	var p Phone
	err := s.db.QueryRow(`
		SELECT id, name, base_url, token, manufacturer, model, last_seen_ms, sync_cursor_ms, created_at_ms
		FROM phones WHERE id = ?`, id).
		Scan(&p.ID, &p.Name, &p.BaseURL, &p.Token, &p.Manufacturer, &p.Model, &p.LastSeenMs, &p.SyncCursorMs, &p.CreatedAtMs)
	if errors.Is(err, sql.ErrNoRows) {
		return Phone{}, ErrNotFound
	}
	return p, err
}

// UpdatePhoneLastSeen stamps last_seen_ms to now (called whenever a phone
// answers a request successfully).
func (s *Store) UpdatePhoneLastSeen(id string, whenMs int64) error {
	_, err := s.db.Exec(`UPDATE phones SET last_seen_ms = ? WHERE id = ?`, whenMs, id)
	return err
}

// AdvanceSyncCursor persists how far the sync loop has pulled clips for this
// phone. Called only after each clip in the batch has been safely written to
// disk and inserted into the clips table — see syncer.Manager — so a crash
// mid-batch just re-pulls (idempotently) rather than skipping anything.
func (s *Store) AdvanceSyncCursor(id string, cursorMs int64) error {
	_, err := s.db.Exec(`UPDATE phones SET sync_cursor_ms = ? WHERE id = ?`, cursorMs, id)
	return err
}

// DeletePhone removes a phone's pairing record (used by unpair/force-unpair).
// Per the handoff doc, already-synced clips are deliberately NOT deleted —
// they remain visible in the aggregate Gallery as historical footage from a
// now-unpaired phone.
func (s *Store) DeletePhone(id string) error {
	_, err := s.db.Exec(`DELETE FROM phones WHERE id = ?`, id)
	return err
}

func NowMs() int64 {
	return time.Now().UnixMilli()
}
