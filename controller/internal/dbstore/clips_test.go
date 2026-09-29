package dbstore

import "testing"

// TestMarkClipWatched covers the gallery's watched marker: only the first play
// is stored, and sync appending footage makes the clip unwatched again.
func TestMarkClipWatched(t *testing.T) {
	store := newTestStore(t)
	const phone = "ph_1"
	clip := Clip{ID: "c1", PhoneID: phone, StartedAtMs: 1_000, EndedAtMs: 2_000, SegmentCount: 1, State: ClipActive, CreatedAtMs: 2_000}
	if err := store.InsertClip(clip); err != nil {
		t.Fatalf("insert clip: %v", err)
	}

	watchedAt := func() int64 {
		t.Helper()
		c, err := store.GetClip(phone, clip.ID)
		if err != nil {
			t.Fatalf("get clip: %v", err)
		}
		return c.WatchedAtMs
	}

	if got := watchedAt(); got != 0 {
		t.Fatalf("new clip watched_at = %d, want 0", got)
	}
	if err := store.MarkClipWatched(phone, clip.ID, 5_000); err != nil {
		t.Fatalf("mark watched: %v", err)
	}
	if got := watchedAt(); got != 5_000 {
		t.Fatalf("after first play watched_at = %d, want 5000", got)
	}
	if err := store.MarkClipWatched(phone, clip.ID, 9_000); err != nil {
		t.Fatalf("mark watched again: %v", err)
	}
	if got := watchedAt(); got != 5_000 {
		t.Fatalf("a re-watch moved watched_at to %d, want it kept at 5000", got)
	}

	if err := store.ExtendClip(clip.ID, 3_000, 10); err != nil {
		t.Fatalf("extend clip: %v", err)
	}
	if got := watchedAt(); got != 0 {
		t.Fatalf("after sync appended footage watched_at = %d, want 0 (new footage is unwatched)", got)
	}
}
