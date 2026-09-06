package dbstore

import (
	"context"
	"database/sql"
	"errors"

	"panopticon-controller/internal/dbstore/queries"
)

// ClipState mirrors the three-state lifecycle from HANDOFF-controller-ux.md:
// active (visible in Gallery) -> trashed (visible in Trash, files still on
// disk) -> purged (tombstone only, files gone). The tombstone is only dropped
// once eviction from the phone's ring buffer is confirmed - that probe loop is
// out of scope (see README.md), so purged rows accumulate here for now.
type ClipState string

const (
	ClipActive  ClipState = "active"
	ClipTrashed ClipState = "trashed"
	ClipPurged  ClipState = "purged"
)

// Clip is a contiguous run of segments - the user-facing gallery item. Its
// bytes/duration are aggregates over its segments; its `state` is what
// trash/restore/delete act on (segments follow their clip).
type Clip struct {
	ID           string
	PhoneID      string
	StartedAtMs  int64
	EndedAtMs    int64
	SegmentCount int
	SizeBytes    int64
	State        ClipState
	CreatedAtMs  int64
}

func clipFromRow(c queries.Clip) Clip {
	return Clip{
		ID:           c.ID,
		PhoneID:      c.PhoneID,
		StartedAtMs:  c.StartedAtMs,
		EndedAtMs:    c.EndedAtMs,
		SegmentCount: int(c.SegmentCount),
		SizeBytes:    c.SizeBytes,
		State:        ClipState(c.State),
		CreatedAtMs:  c.CreatedAtMs,
	}
}

// InsertClip creates a new clip row (called when a synced segment isn't
// contiguous with the phone's current open clip).
func (s *Store) InsertClip(c Clip) error {
	return s.q.InsertClip(context.Background(), queries.InsertClipParams{
		ID:           c.ID,
		PhoneID:      c.PhoneID,
		StartedAtMs:  c.StartedAtMs,
		EndedAtMs:    c.EndedAtMs,
		SegmentCount: int64(c.SegmentCount),
		SizeBytes:    c.SizeBytes,
		State:        string(c.State),
		CreatedAtMs:  c.CreatedAtMs,
	})
}

// GetOpenClip returns the phone's most recently-ended *active* clip - the one
// a newly-synced contiguous segment extends. ErrNotFound if the phone has no
// active clip yet.
func (s *Store) GetOpenClip(phoneID string) (Clip, error) {
	row, err := s.q.GetOpenClip(context.Background(), phoneID)
	if errors.Is(err, sql.ErrNoRows) {
		return Clip{}, ErrNotFound
	}
	if err != nil {
		return Clip{}, err
	}
	return clipFromRow(row), nil
}

// ExtendClip folds a newly-synced contiguous segment into an existing clip:
// pushes ended_at_ms out, bumps the segment count, adds the bytes.
func (s *Store) ExtendClip(clipID string, endedAtMs, addBytes int64) error {
	return s.q.ExtendClip(context.Background(), queries.ExtendClipParams{
		EndedAtMs: endedAtMs,
		SizeBytes: addBytes,
		ID:        clipID,
	})
}

// GetClip looks up one clip by (phoneID, clipID). ErrNotFound if absent.
func (s *Store) GetClip(phoneID, clipID string) (Clip, error) {
	row, err := s.q.GetClip(context.Background(), queries.GetClipParams{PhoneID: phoneID, ID: clipID})
	if errors.Is(err, sql.ErrNoRows) {
		return Clip{}, ErrNotFound
	}
	if err != nil {
		return Clip{}, err
	}
	return clipFromRow(row), nil
}

// ListClips returns clips in a given state (or all states if state == "")
// optionally filtered to one phone (phoneID == "" means every phone), newest
// first.
func (s *Store) ListClips(phoneID string, state ClipState) ([]Clip, error) {
	rows, err := s.q.ListClips(context.Background(), queries.ListClipsParams{
		PhoneID: phoneID,
		State:   string(state),
	})
	if err != nil {
		return nil, err
	}
	out := make([]Clip, len(rows))
	for i, r := range rows {
		out[i] = clipFromRow(r)
	}
	return out, nil
}

// SetClipState transitions a clip (Trash/Restore/Delete per the Trash bin
// action table in HANDOFF-controller-ux.md). Callers are responsible for
// removing the on-disk segment files before transitioning to purged.
func (s *Store) SetClipState(phoneID, clipID string, state ClipState) error {
	return s.q.SetClipState(context.Background(), queries.SetClipStateParams{
		State:   string(state),
		PhoneID: phoneID,
		ID:      clipID,
	})
}

// DiskUsageBytes sums the on-disk bytes of segments belonging to active+trashed
// clips (purged clips have no files left) - the aggregate disk-usage stat.
func (s *Store) DiskUsageBytes(phoneID string) (int64, error) {
	return s.q.DiskUsageBytes(context.Background(), phoneID)
}
