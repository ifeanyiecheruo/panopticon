package dbstore

import (
	"path/filepath"
	"testing"
)

func newTestStore(t *testing.T) *Store {
	t.Helper()
	store, err := Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { store.Close() })
	return store
}

// TestRegroupUnassignedSegments covers the one-time post-002 backfill: two
// contiguous segments, a gap wider than the threshold, then two more must
// collapse into exactly two clips with the right spans/sizes/counts, and a
// second run must be a no-op.
func TestRegroupUnassignedSegments(t *testing.T) {
	store := newTestStore(t)

	const phone = "ph_1"
	segs := []Segment{
		{PhoneID: phone, Filename: "s1.mp4", CreatedAtMs: 1_000, DurationMs: 1_000, EndMs: 2_000, SizeBytes: 10},
		{PhoneID: phone, Filename: "s2.mp4", CreatedAtMs: 3_500, DurationMs: 1_000, EndMs: 4_500, SizeBytes: 20},
		// s2 ends at 4_500; s3 starts at 10_000 -> 5_500ms gap > 3_000 threshold.
		{PhoneID: phone, Filename: "s3.mp4", CreatedAtMs: 10_000, DurationMs: 1_000, EndMs: 11_000, SizeBytes: 30},
		{PhoneID: phone, Filename: "s4.mp4", CreatedAtMs: 12_500, DurationMs: 1_000, EndMs: 13_500, SizeBytes: 40},
	}
	for _, s := range segs {
		// ClipID left "" -> unassigned, which is exactly what the 002
		// migration's INSERT ... SELECT produces.
		if err := store.UpsertSegment(s); err != nil {
			t.Fatalf("seed segment %s: %v", s.Filename, err)
		}
	}

	made, err := store.RegroupUnassignedSegments(3_000)
	if err != nil {
		t.Fatalf("regroup: %v", err)
	}
	if made != 2 {
		t.Fatalf("made %d clips, want 2", made)
	}

	clips, err := store.ListClips(phone, ClipActive)
	if err != nil {
		t.Fatalf("list clips: %v", err)
	}
	if len(clips) != 2 {
		t.Fatalf("got %d clips, want 2", len(clips))
	}

	// ListClips is started_at_ms DESC: clips[0] is the later run.
	later, earlier := clips[0], clips[1]
	if earlier.SegmentCount != 2 || later.SegmentCount != 2 {
		t.Errorf("segment counts = %d / %d, want 2 / 2", earlier.SegmentCount, later.SegmentCount)
	}
	if earlier.StartedAtMs != 1_000 || earlier.EndedAtMs != 4_500 {
		t.Errorf("earlier clip span = [%d,%d], want [1000,4500]", earlier.StartedAtMs, earlier.EndedAtMs)
	}
	if earlier.SizeBytes != 30 {
		t.Errorf("earlier clip size = %d, want 30", earlier.SizeBytes)
	}
	if later.StartedAtMs != 10_000 || later.EndedAtMs != 13_500 {
		t.Errorf("later clip span = [%d,%d], want [10000,13500]", later.StartedAtMs, later.EndedAtMs)
	}
	if later.SizeBytes != 70 {
		t.Errorf("later clip size = %d, want 70", later.SizeBytes)
	}

	// Every segment is now assigned and lists back in playback order.
	earlierSegs, err := store.ListSegmentsForClip(earlier.ID)
	if err != nil {
		t.Fatalf("list earlier segments: %v", err)
	}
	laterSegs, err := store.ListSegmentsForClip(later.ID)
	if err != nil {
		t.Fatalf("list later segments: %v", err)
	}
	if len(earlierSegs) != 2 || len(laterSegs) != 2 {
		t.Fatalf("segments per clip = %d / %d, want 2 / 2", len(earlierSegs), len(laterSegs))
	}
	if earlierSegs[0].Filename != "s1.mp4" || earlierSegs[1].Filename != "s2.mp4" {
		t.Errorf("earlier segment order = %s, %s", earlierSegs[0].Filename, earlierSegs[1].Filename)
	}

	// Idempotent: nothing left with clip_id = ''.
	made, err = store.RegroupUnassignedSegments(3_000)
	if err != nil {
		t.Fatalf("regroup (2nd): %v", err)
	}
	if made != 0 {
		t.Errorf("second regroup made %d clips, want 0", made)
	}
}

// TestRegroupUnassignedSegments_SeparatesPhones checks the grouping never
// bridges two phones even when their segments interleave in time.
func TestRegroupUnassignedSegments_SeparatesPhones(t *testing.T) {
	store := newTestStore(t)

	segs := []Segment{
		{PhoneID: "ph_a", Filename: "a1.mp4", CreatedAtMs: 1_000, DurationMs: 1_000, EndMs: 2_000},
		{PhoneID: "ph_b", Filename: "b1.mp4", CreatedAtMs: 1_500, DurationMs: 1_000, EndMs: 2_500},
		{PhoneID: "ph_a", Filename: "a2.mp4", CreatedAtMs: 2_500, DurationMs: 1_000, EndMs: 3_500},
		{PhoneID: "ph_b", Filename: "b2.mp4", CreatedAtMs: 3_000, DurationMs: 1_000, EndMs: 4_000},
	}
	for _, s := range segs {
		if err := store.UpsertSegment(s); err != nil {
			t.Fatalf("seed segment %s: %v", s.Filename, err)
		}
	}

	if _, err := store.RegroupUnassignedSegments(3_000); err != nil {
		t.Fatalf("regroup: %v", err)
	}

	for _, phone := range []string{"ph_a", "ph_b"} {
		clips, err := store.ListClips(phone, ClipActive)
		if err != nil {
			t.Fatalf("list clips for %s: %v", phone, err)
		}
		if len(clips) != 1 {
			t.Fatalf("%s: got %d clips, want 1", phone, len(clips))
		}
		if clips[0].SegmentCount != 2 {
			t.Errorf("%s: clip has %d segments, want 2", phone, clips[0].SegmentCount)
		}
	}
}
