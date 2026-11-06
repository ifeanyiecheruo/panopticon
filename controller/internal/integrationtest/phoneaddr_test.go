package integrationtest

import (
	"context"
	"strings"
	"testing"
	"time"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/pairing"
	"panopticon-controller/internal/phoneaddr"
	"panopticon-controller/internal/syncer"
)

// deadAddress refuses connections near-instantly (see TestAddPhone_Unreachable).
const deadAddress = "http://127.0.0.1:1"

func TestPhoneAddr_CheckTellsWrongPhoneFromUnreachable(t *testing.T) {
	srv, _ := newFakePhoneServer(t, "GOOD-CODE")
	other, _ := newFakePhoneServer(t, "OTHER-CODE")
	store, _ := newTestStore(t)
	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone: %v", err)
	}
	phone := mustGetPhone(t, store, res.PhoneID)

	got := phoneaddr.CheckAll(context.Background(), []string{srv.URL, other.URL, deadAddress}, phone.Token)
	want := []phoneaddr.Reach{phoneaddr.ReachOK, phoneaddr.ReachWrongPhone, phoneaddr.ReachUnreachable}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("address %d: %q, want %q", i, got[i], want[i])
		}
	}
}

// A phone whose address in use stopped answering is moved onto another of its
// known addresses by the sync loop, and syncs from there.
func TestPhoneAddr_SyncFailsOverToAnotherAddress(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, dirs := newTestStore(t)
	fp.segments = []fakeSegment{{filename: "seg_1.mp4", createdAtMs: time.Now().UnixMilli() - 5000, data: []byte("A")}}
	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone: %v", err)
	}
	if err := store.SetPhoneAddresses(res.PhoneID, []string{deadAddress, srv.URL}, deadAddress); err != nil {
		t.Fatalf("SetPhoneAddresses: %v", err)
	}

	mgr := syncer.NewManager(store, dirs, 50*time.Millisecond, dbstore.GroupingGapMs, 50*time.Millisecond)
	mgr.Start()
	defer mgr.Stop()
	waitForSyncedSegments(t, store, res.PhoneID, 1)

	if phone := mustGetPhone(t, store, res.PhoneID); phone.BaseURL != srv.URL {
		t.Errorf("address in use = %q, want the one that answered (%q)", phone.BaseURL, srv.URL)
	}
}

func TestPhoneAddr_FailoverWithNowhereElseKeepsAddress(t *testing.T) {
	srv, _ := newFakePhoneServer(t, "GOOD-CODE")
	other, _ := newFakePhoneServer(t, "OTHER-CODE")
	store, _ := newTestStore(t)
	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srv.URL, "http://"), "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone: %v", err)
	}
	// The other address answers, but as a different phone: never a failover target.
	if err := store.SetPhoneAddresses(res.PhoneID, []string{deadAddress, other.URL}, deadAddress); err != nil {
		t.Fatalf("SetPhoneAddresses: %v", err)
	}
	phone, moved := phoneaddr.Failover(context.Background(), store, mustGetPhone(t, store, res.PhoneID))
	if moved || phone.BaseURL != deadAddress || mustGetPhone(t, store, res.PhoneID).BaseURL != deadAddress {
		t.Errorf("moved=%v to %q; want no move", moved, phone.BaseURL)
	}
}
