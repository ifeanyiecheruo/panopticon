package dbstore

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"

	"panopticon-controller/internal/dbstore/queries"
)

// RegroupUnassignedSegments assigns a clip to every segment that doesn't have
// one yet (clip_id = '') and creates the corresponding clip rows. It's the
// one-time backfill after the 002 migration - the migration copies the old
// per-file rows into `segments` with no clip, this groups them.
//
// Greedy, single pass per phone in time order: a segment extends the current
// clip when it starts within gapMs of that clip's end, otherwise it opens a
// new clip. Runs in one transaction and is idempotent - re-running it is a
// no-op once every segment has a clip. Called from Open() after migrate().
func (s *Store) RegroupUnassignedSegments(gapMs int64) (int, error) {
	ctx := context.Background()

	pending, err := s.q.ListUnassignedSegments(ctx)
	if err != nil {
		return 0, fmt.Errorf("list unassigned segments: %w", err)
	}
	if len(pending) == 0 {
		return 0, nil
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	qtx := s.q.WithTx(tx)

	clipsMade := 0
	var (
		curPhone string
		curClip  queries.InsertClipParams
		haveClip bool
	)

	flush := func() error {
		if !haveClip {
			return nil
		}
		if err := qtx.InsertClip(ctx, curClip); err != nil {
			return err
		}
		clipsMade++
		haveClip = false
		return nil
	}

	for _, seg := range pending {
		endMs := seg.EndMs
		if endMs == 0 {
			endMs = seg.CreatedAtMs + seg.DurationMs
		}
		contiguous := haveClip &&
			seg.PhoneID == curPhone &&
			seg.CreatedAtMs-curClip.EndedAtMs <= gapMs

		if !contiguous {
			if err := flush(); err != nil {
				return 0, err
			}
			curPhone = seg.PhoneID
			curClip = queries.InsertClipParams{
				ID:           NewClipID(),
				PhoneID:      seg.PhoneID,
				StartedAtMs:  seg.CreatedAtMs,
				EndedAtMs:    endMs,
				SegmentCount: 0,
				SizeBytes:    0,
				State:        string(ClipActive),
				CreatedAtMs:  NowMs(),
			}
			haveClip = true
		}

		if err := qtx.SetSegmentClip(ctx, queries.SetSegmentClipParams{
			ClipID:   curClip.ID,
			PhoneID:  seg.PhoneID,
			Filename: seg.Filename,
		}); err != nil {
			return 0, err
		}
		curClip.EndedAtMs = endMs
		curClip.SegmentCount++
		curClip.SizeBytes += seg.SizeBytes
	}
	if err := flush(); err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return clipsMade, nil
}

// NewClipID mints a random opaque clip id ("clip_" + 16 hex chars). Used by
// the backfill here and by the syncer when a synced segment opens a new clip.
func NewClipID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "clip_" + hex.EncodeToString(b)
}
