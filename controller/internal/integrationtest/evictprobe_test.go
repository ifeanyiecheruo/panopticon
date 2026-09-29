package integrationtest

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/pairing"
	"panopticon-controller/internal/syncer"
)

// syncTwoClips pairs with a fresh fake phone and syncs two clips from it: an
// older two-segment one and a newer one-segment one, returned oldest first.
func syncTwoClips(t *testing.T) (*fakePhone, func(), *dbstore.Store, string, []dbstore.Clip) {
	t.Helper()
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, dirs := newTestStore(t)

	now := time.Now().UnixMilli()
	fp.segments = []fakeSegment{
		{filename: "seg_old1.mp4", createdAtMs: now - 30000, data: []byte("A")},
		{filename: "seg_old2.mp4", createdAtMs: now - 28900, data: []byte("BB")},
		{filename: "seg_new1.mp4", createdAtMs: now - 20000, data: []byte("CCC")},
	}

	result, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone failed: %v", err)
	}

	mgr := syncer.NewManager(store, dirs, 50*time.Millisecond, dbstore.GroupingGapMs, 50*time.Millisecond)
	mgr.Start()
	t.Cleanup(mgr.Stop)
	waitForSyncedSegments(t, store, result.PhoneID, 3)

	clips, err := store.ListClips(result.PhoneID, dbstore.ClipActive)
	if err != nil || len(clips) != 2 {
		t.Fatalf("expected 2 synced clips, got %d (err %v)", len(clips), err)
	}
	// ListClips is newest first.
	return fp, srv.Close, store, result.PhoneID, []dbstore.Clip{clips[1], clips[0]}
}

func purge(t *testing.T, store *dbstore.Store, phoneID string, clips ...dbstore.Clip) {
	t.Helper()
	for _, c := range clips {
		if err := store.SetClipState(phoneID, c.ID, dbstore.ClipPurged); err != nil {
			t.Fatalf("purge %s: %v", c.ID, err)
		}
	}
}

func evictFromPhone(fp *fakePhone, filenames ...string) {
	fp.mu.Lock()
	defer fp.mu.Unlock()
	kept := fp.segments[:0]
	for _, s := range fp.segments {
		evicted := false
		for _, f := range filenames {
			evicted = evicted || s.filename == f
		}
		if !evicted {
			kept = append(kept, s)
		}
	}
	fp.segments = kept
}

func clipGone(store *dbstore.Store, phoneID, clipID string) bool {
	_, err := store.GetClip(phoneID, clipID)
	return errors.Is(err, dbstore.ErrNotFound)
}

// TestEvictionProbe_DropsOnlyFullyEvictedPurgedClips: a purged clip whose
// segments all 404 loses its clip and segment rows; a purged clip still on the
// phone keeps them.
func TestEvictionProbe_DropsOnlyFullyEvictedPurgedClips(t *testing.T) {
	fp, _, store, phoneID, clips := syncTwoClips(t)
	older, newer := clips[0], clips[1]

	evictFromPhone(fp, "seg_old1.mp4", "seg_old2.mp4")
	purge(t, store, phoneID, older, newer)

	deadline := time.Now().Add(5 * time.Second)
	for !clipGone(store, phoneID, older.ID) {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for the evicted purged clip's tombstone to be dropped")
		}
		time.Sleep(20 * time.Millisecond)
	}
	for _, f := range []string{"seg_old1.mp4", "seg_old2.mp4"} {
		if exists, _ := store.SegmentExists(phoneID, f); exists {
			t.Errorf("segment tombstone %s should have been dropped with its clip", f)
		}
	}

	// A few more probe passes must leave the still-present clip alone.
	time.Sleep(300 * time.Millisecond)
	if c, err := store.GetClip(phoneID, newer.ID); err != nil || c.State != dbstore.ClipPurged {
		t.Errorf("purged clip still on the phone should keep its row; got %+v, err %v", c, err)
	}
	if exists, _ := store.SegmentExists(phoneID, "seg_new1.mp4"); !exists {
		t.Errorf("purged clip still on the phone should keep its segment tombstone")
	}
}

// TestEvictionProbe_LeavesPartlyEvictedAndNonPurgedClips: a purged clip with
// even one segment still on the phone is kept, as is an evicted clip the user
// only trashed.
func TestEvictionProbe_LeavesPartlyEvictedAndNonPurgedClips(t *testing.T) {
	fp, _, store, phoneID, clips := syncTwoClips(t)
	older, newer := clips[0], clips[1]

	evictFromPhone(fp, "seg_old1.mp4", "seg_new1.mp4")
	purge(t, store, phoneID, older)
	if err := store.SetClipState(phoneID, newer.ID, dbstore.ClipTrashed); err != nil {
		t.Fatalf("trash: %v", err)
	}

	time.Sleep(500 * time.Millisecond)
	if clipGone(store, phoneID, older.ID) {
		t.Errorf("purged clip with a segment still on the phone must not be dropped")
	}
	if clipGone(store, phoneID, newer.ID) {
		t.Errorf("a trashed clip must never be dropped, evicted or not")
	}
}

// TestEvictionProbe_SkipsUnreachablePhone: a phone that can't be reached is
// not evidence of eviction.
func TestEvictionProbe_SkipsUnreachablePhone(t *testing.T) {
	_, closeServer, store, phoneID, clips := syncTwoClips(t)
	closeServer()
	purge(t, store, phoneID, clips...)

	time.Sleep(500 * time.Millisecond)
	for _, c := range clips {
		if clipGone(store, phoneID, c.ID) {
			t.Errorf("purged clip %s dropped although the phone was unreachable", c.ID)
		}
	}
}

// TestEvictionProbe_PhoneWithoutMissingRouteDropsNothing: an older phone app
// 404s the batch route itself, which must never read as "everything missing".
func TestEvictionProbe_PhoneWithoutMissingRouteDropsNothing(t *testing.T) {
	fp, _, store, phoneID, clips := syncTwoClips(t)
	fp.mu.Lock()
	fp.noMissingRoute = true
	fp.mu.Unlock()
	evictFromPhone(fp, "seg_old1.mp4", "seg_old2.mp4")
	purge(t, store, phoneID, clips...)

	time.Sleep(500 * time.Millisecond)
	for _, c := range clips {
		if clipGone(store, phoneID, c.ID) {
			t.Errorf("purged clip %s dropped by a phone that can't answer the probe", c.ID)
		}
	}
}
