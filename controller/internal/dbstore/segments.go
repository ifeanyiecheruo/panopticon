package dbstore

import (
	"context"

	"panopticon-controller/internal/dbstore/queries"
)

// Segment is one recorded file - what the phone HTTP API calls a "segment"
// and what the code called a "clip" before the split. Segments belong to a
// Clip (a contiguous run); the segment carries no lifecycle state of its own,
// it follows its clip.
type Segment struct {
	PhoneID       string
	Filename      string
	ClipID        string
	LocalPath     string
	ThumbnailPath string
	CreatedAtMs   int64
	DurationMs    int64
	EndMs         int64
	SizeBytes     int64
	Width         int
	Height        int
}

func segmentFromRow(s queries.Segment) Segment {
	return Segment{
		PhoneID:       s.PhoneID,
		Filename:      s.Filename,
		ClipID:        s.ClipID,
		LocalPath:     s.LocalPath,
		ThumbnailPath: s.ThumbnailPath,
		CreatedAtMs:   s.CreatedAtMs,
		DurationMs:    s.DurationMs,
		EndMs:         s.EndMs,
		SizeBytes:     s.SizeBytes,
		Width:         int(s.Width),
		Height:        int(s.Height),
	}
}

// UpsertSegment inserts a newly-synced segment (already assigned to a clip),
// or is a no-op if (phone_id, filename) already exists - the sync loop calls
// this once per segment discovered via GET /api/segments and dedupes on
// filename.
func (s *Store) UpsertSegment(seg Segment) error {
	return s.q.UpsertSegment(context.Background(), queries.UpsertSegmentParams{
		PhoneID:       seg.PhoneID,
		Filename:      seg.Filename,
		ClipID:        seg.ClipID,
		LocalPath:     seg.LocalPath,
		ThumbnailPath: seg.ThumbnailPath,
		CreatedAtMs:   seg.CreatedAtMs,
		DurationMs:    seg.DurationMs,
		EndMs:         seg.EndMs,
		SizeBytes:     seg.SizeBytes,
		Width:         int64(seg.Width),
		Height:        int64(seg.Height),
	})
}

// SegmentExists reports whether (phoneID, filename) is already indexed - used
// by the sync loop to skip re-downloading a segment it already has (including
// one whose clip the user has since trashed/purged; resync must never
// resurrect a purged clip's files).
func (s *Store) SegmentExists(phoneID, filename string) (bool, error) {
	n, err := s.q.SegmentExists(context.Background(), queries.SegmentExistsParams{
		PhoneID:  phoneID,
		Filename: filename,
	})
	return n > 0, err
}

// ListSegmentsForClip returns a clip's segments oldest-first (playback order).
func (s *Store) ListSegmentsForClip(clipID string) ([]Segment, error) {
	rows, err := s.q.ListSegmentsForClip(context.Background(), clipID)
	if err != nil {
		return nil, err
	}
	out := make([]Segment, len(rows))
	for i, r := range rows {
		out[i] = segmentFromRow(r)
	}
	return out, nil
}

// DeleteSegment drops a segment row (used when purging a clip's on-disk files,
// though the current DeleteClip path keeps the rows as tombstones - see app.go).
func (s *Store) DeleteSegment(phoneID, filename string) error {
	return s.q.DeleteSegment(context.Background(), queries.DeleteSegmentParams{
		PhoneID:  phoneID,
		Filename: filename,
	})
}
