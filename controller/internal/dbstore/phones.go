package dbstore

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"slices"
	"time"

	"panopticon-controller/internal/dbstore/queries"
)

// Phone is a paired phone's local record: identity, address, credential,
// and sync progress. BaseURL is the address in use; PhoneAddresses lists every
// address the phone is known to answer at.
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

// InsertPhone stores a newly paired phone, with BaseURL as its one known
// address. Fails if the id already exists.
func (s *Store) InsertPhone(p Phone) error {
	ctx := context.Background()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	q := s.q.WithTx(tx)
	err = q.InsertPhone(ctx, queries.InsertPhoneParams{
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
	if err != nil {
		return err
	}
	err = q.InsertPhoneAddress(ctx, queries.InsertPhoneAddressParams{PhoneID: p.ID, BaseUrl: p.BaseURL, Position: 0})
	if err != nil {
		return err
	}
	return tx.Commit()
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

// PhoneAddresses returns every address a phone is known to answer at, in the
// order the user listed them. The phone's BaseURL is always one of them.
func (s *Store) PhoneAddresses(id string) ([]string, error) {
	return s.q.ListPhoneAddresses(context.Background(), id)
}

// SetPhoneAddresses replaces a phone's known addresses and sets the one in use.
// addresses must be non-empty and already normalized; active must be one of
// them.
func (s *Store) SetPhoneAddresses(id string, addresses []string, active string) error {
	if len(addresses) == 0 {
		return errors.New("a phone needs at least one address")
	}
	if !slices.Contains(addresses, active) {
		return fmt.Errorf("active address %q is not one of the phone's addresses", active)
	}
	ctx := context.Background()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	q := s.q.WithTx(tx)
	if _, err := q.GetPhone(ctx, id); errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	} else if err != nil {
		return err
	}
	if err := q.DeletePhoneAddresses(ctx, id); err != nil {
		return err
	}
	for i, a := range addresses {
		err := q.InsertPhoneAddress(ctx, queries.InsertPhoneAddressParams{PhoneID: id, BaseUrl: a, Position: int64(i)})
		if err != nil {
			return err
		}
	}
	if err := q.UpdatePhoneBaseURL(ctx, queries.UpdatePhoneBaseURLParams{BaseUrl: active, ID: id}); err != nil {
		return err
	}
	return tx.Commit()
}

// SetPhoneActiveAddress switches which of a phone's known addresses is in use
// (see phoneaddr.Failover). It doesn't check that address is a known one -
// callers pick it from PhoneAddresses.
func (s *Store) SetPhoneActiveAddress(id, baseURL string) error {
	return s.q.UpdatePhoneBaseURL(context.Background(), queries.UpdatePhoneBaseURLParams{BaseUrl: baseURL, ID: id})
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

// DeletePhone removes a phone's pairing record (used by unpair/force-unpair),
// and its addresses with it (ON DELETE CASCADE).
// Per docs/design/decisions/0003-pairing-and-unpairing.md, already-synced clips are deliberately NOT deleted —
// they remain visible in the aggregate Gallery as historical footage from a
// now-unpaired phone.
func (s *Store) DeletePhone(id string) error {
	return s.q.DeletePhone(context.Background(), id)
}

func NowMs() int64 {
	return time.Now().UnixMilli()
}
