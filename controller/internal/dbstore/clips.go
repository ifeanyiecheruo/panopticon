package dbstore

import (
	"context"
	"database/sql"
	"errors"

	"panopticon-controller/internal/dbstore/queries"
)

// ClipState mirrors the three-state lifecycle from HANDOFF-controller-ux.md:
// active (visible in Gallery) -> trashed (visible in Trash, still on disk) ->
// purged (tombstone only, file gone). The tombstone itself is only dropped
// once eviction from the phone's ring buffer is confirmed — that probe loop
// is out of scope for this slice (see README.md), so purged rows just
// accumulate here for now.
type ClipState string

const (
	ClipActive  ClipState = "active"
	ClipTrashed ClipState = "trashed"
	ClipPurged  ClipState = "purged"
)

type Clip struct {
	PhoneID       string
	Filename      string
	State         ClipState
	LocalPath     string
	ThumbnailPath string
	CreatedAtMs   int64
	DurationMs    int64
	SizeBytes     int64
	Width         int
	Height        int
}

func clipFromRow(c queries.Clip) Clip {
	return Clip{
		PhoneID:       c.PhoneID,
		Filename:      c.Filename,
		State:         ClipState(c.State),
		LocalPath:     c.LocalPath,
		ThumbnailPath: c.ThumbnailPath,
		CreatedAtMs:   c.CreatedAtMs,
		DurationMs:    c.DurationMs,
		SizeBytes:     c.SizeBytes,
		Width:         int(c.Width),
		Height:        int(c.Height),
	}
}

// UpsertClip inserts a newly-synced clip, or is a no-op if (phone_id,
// filename) already exists — the sync loop calls this once per clip
// discovered via GET /api/clips, and dedupes on filename per
// phone-http-api.md.
func (s *Store) UpsertClip(c Clip) error {
	return s.q.UpsertClip(context.Background(), queries.UpsertClipParams{
		PhoneID:       c.PhoneID,
		Filename:      c.Filename,
		State:         string(c.State),
		LocalPath:     c.LocalPath,
		ThumbnailPath: c.ThumbnailPath,
		CreatedAtMs:   c.CreatedAtMs,
		DurationMs:    c.DurationMs,
		SizeBytes:     c.SizeBytes,
		Width:         int64(c.Width),
		Height:        int64(c.Height),
	})
}

// ClipExists reports whether (phoneID, filename) is already indexed —
// used by the sync loop to skip re-downloading a clip it already has
// (including one the user has since trashed/purged; per the data model,
// resync must never resurrect a purged clip's file).
func (s *Store) ClipExists(phoneID, filename string) (bool, error) {
	n, err := s.q.ClipExists(context.Background(), queries.ClipExistsParams{
		PhoneID:  phoneID,
		Filename: filename,
	})
	return n > 0, err
}

// ListClips returns clips in a given state (or all states if state == "")
// optionally filtered to one phone (phoneID == "" means every phone),
// newest first.
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

// GetClip looks up a single clip by its composite key.
func (s *Store) GetClip(phoneID, filename string) (Clip, error) {
	row, err := s.q.GetClip(context.Background(), queries.GetClipParams{
		PhoneID:  phoneID,
		Filename: filename,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Clip{}, ErrNotFound
	}
	if err != nil {
		return Clip{}, err
	}
	return clipFromRow(row), nil
}

// SetClipState transitions a clip's state (Trash/Restore/Delete per the
// Trash bin action table in HANDOFF-controller-ux.md). Callers are
// responsible for actually removing the on-disk file before transitioning
// to purged; this method only updates the DB row.
func (s *Store) SetClipState(phoneID, filename string, state ClipState) error {
	return s.q.SetClipState(context.Background(), queries.SetClipStateParams{
		State:    string(state),
		PhoneID:  phoneID,
		Filename: filename,
	})
}

// DiskUsageBytes sums size_bytes across active+trashed clips (purged clips
// have no file left to count) — the aggregate disk-usage stat on Fleet.
func (s *Store) DiskUsageBytes(phoneID string) (int64, error) {
	return s.q.DiskUsageBytes(context.Background(), phoneID)
}
