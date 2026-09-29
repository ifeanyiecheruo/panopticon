package dbstore

import (
	"context"
	"database/sql"
	"errors"

	"panopticon-controller/internal/dbstore/queries"
)

// ClipState mirrors the three-state lifecycle from docs/design/decisions/0006-segments-clips-and-tombstones.md:
// active (visible in Gallery) -> trashed (visible in Trash, files still on
// disk) -> purged (tombstone only, files gone). The tombstone is only dropped
// once the phone's ring buffer has evicted every one of its segments - see the
// syncer's eviction probe and DropPurgedClip.
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
	// WatchedAtMs is when the clip was first played in the gallery, 0 if never (or since sync
	// last appended footage to it).
	WatchedAtMs int64
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
		WatchedAtMs:  c.WatchedAtMs,
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
// action table in docs/design/decisions/0006-segments-clips-and-tombstones.md). Callers are responsible for
// removing the on-disk segment files before transitioning to purged.
func (s *Store) SetClipState(phoneID, clipID string, state ClipState) error {
	return s.q.SetClipState(context.Background(), queries.SetClipStateParams{
		State:   string(state),
		PhoneID: phoneID,
		ID:      clipID,
	})
}

// PurgedSegment is one segment tombstone of a purged clip.
type PurgedSegment struct {
	ClipID   string
	Filename string
}

// ListPurgedClipSegments returns every segment tombstone of one phone's
// purged clips - what the eviction probe checks against the phone.
func (s *Store) ListPurgedClipSegments(phoneID string) ([]PurgedSegment, error) {
	rows, err := s.q.ListPurgedClipSegments(context.Background(), phoneID)
	if err != nil {
		return nil, err
	}
	out := make([]PurgedSegment, len(rows))
	for i, r := range rows {
		out[i] = PurgedSegment{ClipID: r.ClipID, Filename: r.Filename}
	}
	return out, nil
}

// DropPurgedClip deletes a purged clip's row and its segment tombstones in one
// transaction. Only call it once the phone has confirmed (404) that none of
// the clip's segments exist any more: without the tombstones, a resync would
// happily download anything the phone still has. Returns false, dropping
// nothing, if the clip isn't (or is no longer) purged.
func (s *Store) DropPurgedClip(phoneID, clipID string) (bool, error) {
	ctx := context.Background()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	q := s.q.WithTx(tx)

	n, err := q.DeletePurgedClip(ctx, queries.DeletePurgedClipParams{PhoneID: phoneID, ID: clipID})
	if err != nil || n == 0 {
		return false, err
	}
	if err := q.DeleteSegmentsForClip(ctx, clipID); err != nil {
		return false, err
	}
	return true, tx.Commit()
}

// MarkClipWatched records the first time a clip was played; later calls are no-ops.
func (s *Store) MarkClipWatched(phoneID, clipID string, atMs int64) error {
	return s.q.MarkClipWatched(context.Background(), queries.MarkClipWatchedParams{
		WatchedAtMs: atMs,
		PhoneID:     phoneID,
		ID:          clipID,
	})
}

// DiskUsageBytes sums the on-disk bytes of segments belonging to active+trashed
// clips (purged clips have no files left) - the aggregate disk-usage stat.
func (s *Store) DiskUsageBytes(phoneID string) (int64, error) {
	return s.q.DiskUsageBytes(context.Background(), phoneID)
}
