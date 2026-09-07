package dbstore

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"panopticon-controller/internal/dbstore/queries"
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

func phoneFromRow(p queries.Phone) Phone {
	return Phone{
		ID:           p.ID,
		Name:         p.Name,
		BaseURL:      p.BaseUrl,
		Token:        p.Token,
		Manufacturer: p.Manufacturer,
		Model:        p.Model,
		LastSeenMs:   p.LastSeenMs,
		SyncCursorMs: p.SyncCursorMs,
		CreatedAtMs:  p.CreatedAtMs,
	}
}

// InsertPhone stores a newly paired phone. Fails if the id already exists.
func (s *Store) InsertPhone(p Phone) error {
	return s.q.InsertPhone(context.Background(), queries.InsertPhoneParams{
		ID:           p.ID,
		Name:         p.Name,
		BaseUrl:      p.BaseURL,
		Token:        p.Token,
		Manufacturer: p.Manufacturer,
		Model:        p.Model,
		LastSeenMs:   p.LastSeenMs,
		SyncCursorMs: p.SyncCursorMs,
		CreatedAtMs:  p.CreatedAtMs,
	})
}

// ListPhones returns every paired phone, oldest-paired first.
func (s *Store) ListPhones() ([]Phone, error) {
	rows, err := s.q.ListPhones(context.Background())
	if err != nil {
		return nil, err
	}
	out := make([]Phone, len(rows))
	for i, r := range rows {
		out[i] = phoneFromRow(r)
	}
	return out, nil
}

// GetPhone looks up one paired phone by id. Returns ErrNotFound if absent.
func (s *Store) GetPhone(id string) (Phone, error) {
	row, err := s.q.GetPhone(context.Background(), id)
	if errors.Is(err, sql.ErrNoRows) {
		return Phone{}, ErrNotFound
	}
	if err != nil {
		return Phone{}, err
	}
	return phoneFromRow(row), nil
}

// UpdatePhoneName renames a paired phone locally. Called when the device name
// is changed via the Phone-detail config form so the new name flows through the
// whole controller UI (Fleet list, Gallery filter, clip attribution).
func (s *Store) UpdatePhoneName(id, name string) error {
	return s.q.UpdatePhoneName(context.Background(), queries.UpdatePhoneNameParams{
		Name: name,
		ID:   id,
	})
}

// UpdatePhoneLastSeen stamps last_seen_ms to now (called whenever a phone
// answers a request successfully).
func (s *Store) UpdatePhoneLastSeen(id string, whenMs int64) error {
	return s.q.UpdatePhoneLastSeen(context.Background(), queries.UpdatePhoneLastSeenParams{
		LastSeenMs: whenMs,
		ID:         id,
	})
}

// AdvanceSyncCursor persists how far the sync loop has pulled clips for this
// phone. Called only after each clip in the batch has been safely written to
// disk and inserted into the clips table — see syncer.Manager — so a crash
// mid-batch just re-pulls (idempotently) rather than skipping anything.
func (s *Store) AdvanceSyncCursor(id string, cursorMs int64) error {
	return s.q.AdvanceSyncCursor(context.Background(), queries.AdvanceSyncCursorParams{
		SyncCursorMs: cursorMs,
		ID:           id,
	})
}

// DeletePhone removes a phone's pairing record (used by unpair/force-unpair).
// Per the handoff doc, already-synced clips are deliberately NOT deleted —
// they remain visible in the aggregate Gallery as historical footage from a
// now-unpaired phone.
func (s *Store) DeletePhone(id string) error {
	return s.q.DeletePhone(context.Background(), id)
}

func NowMs() int64 {
	return time.Now().UnixMilli()
}
