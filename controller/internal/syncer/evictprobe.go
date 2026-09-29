package syncer

import (
	"context"
	"log"

	"panopticon-controller/internal/phoneapi"
)

// probeEvictions drops the tombstones of purged clips the phone can no longer
// serve, per docs/design/decisions/0006-segments-clips-and-tombstones.md: a
// purged clip keeps its clip + segment rows only so a resync can't
// redownload what the user deleted, and that stops mattering once the phone
// has evicted every one of its segments.
//
// Every tombstoned filename goes to the phone in batches
// (POST /api/segments/missing), which answers with the ones whose /file would
// 404; a clip is dropped once all of its segments are among them. Any failure
// ends the pass without dropping anything - only the phone saying "missing"
// counts as gone, and a clip is only judged once every batch has answered.
//
// Trashed clips are not probed: dropping their rows is never right (the user
// can still Restore them), and nothing in the UI shows whether one is still
// on the phone.
func (m *Manager) probeEvictions(ctx context.Context, client *phoneapi.Client, phoneID string) {
	tombstones, err := m.store.ListPurgedClipSegments(phoneID)
	if err != nil {
		log.Printf("syncer[%s]: list purged clip segments: %v", phoneID, err)
		return
	}
	if len(tombstones) == 0 {
		return
	}

	filenames := make([]string, len(tombstones))
	for i, t := range tombstones {
		filenames[i] = t.Filename
	}
	missing := make([]bool, len(filenames))
	for start := 0; start < len(filenames); start += phoneapi.MaxSegmentsMissingBatch {
		end := min(start+phoneapi.MaxSegmentsMissingBatch, len(filenames))
		indices, err := client.SegmentsMissing(ctx, filenames[start:end])
		if err != nil {
			log.Printf("syncer[%s]: eviction probe: %v", phoneID, err)
			return
		}
		for _, i := range indices {
			missing[start+i] = true
		}
	}

	stillOnPhone := make(map[string]bool)
	clipOrder := make([]string, 0)
	for i, t := range tombstones {
		if _, seen := stillOnPhone[t.ClipID]; !seen {
			stillOnPhone[t.ClipID] = false
			clipOrder = append(clipOrder, t.ClipID)
		}
		if !missing[i] {
			stillOnPhone[t.ClipID] = true
		}
	}

	dropped := 0
	for _, clipID := range clipOrder {
		if stillOnPhone[clipID] {
			continue
		}
		ok, err := m.store.DropPurgedClip(phoneID, clipID)
		if err != nil {
			log.Printf("syncer[%s]: drop purged clip %s: %v", phoneID, clipID, err)
			break
		}
		if ok {
			dropped++
		}
	}
	if dropped > 0 {
		log.Printf("syncer[%s]: dropped %d evicted clip tombstone(s)", phoneID, dropped)
	}
}
