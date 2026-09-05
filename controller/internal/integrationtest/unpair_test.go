package integrationtest

import (
	"context"
	"strings"
	"testing"
	"time"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/pairing"
	"panopticon-controller/internal/unpair"
)

func (fp *fakePhone) liveTokens() int {
	fp.mu.Lock()
	defer fp.mu.Unlock()
	return len(fp.tokens)
}

func pairForTest(t *testing.T, store *dbstore.Store, srvURL, code string) pairing.Result {
	t.Helper()
	res, err := pairing.AddPhone(context.Background(), store, strings.TrimPrefix(srvURL, "http://"), code)
	if err != nil {
		t.Fatalf("AddPhone: %v", err)
	}
	return res
}

// TestUnpair_Success: reachable phone, nothing pending -> token revoked on the
// phone and the local pairing row removed. Already-archived clips are kept.
func TestUnpair_Success(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, _ := newTestStore(t)
	res := pairForTest(t, store, srv.URL, "GOOD-CODE")

	// Simulate one already-archived clip so we can prove it survives.
	if err := store.UpsertClip(dbstore.Clip{
		PhoneID: res.PhoneID, Filename: "old.mp4", State: dbstore.ClipActive, CreatedAtMs: 1,
	}); err != nil {
		t.Fatalf("seed clip: %v", err)
	}

	r, err := unpair.Unpair(context.Background(), store, res.PhoneID, false)
	if err != nil {
		t.Fatalf("Unpair: %v", err)
	}
	if r.Outcome != unpair.OutcomeOK {
		t.Fatalf("outcome = %v (%q), want OK", r.Outcome, r.Message)
	}
	if _, err := store.GetPhone(res.PhoneID); err == nil {
		t.Errorf("phone row should be gone after a successful unpair")
	}
	if fp.liveTokens() != 0 {
		t.Errorf("phone should have revoked the token")
	}
	clips, _ := store.ListClips(res.PhoneID, dbstore.ClipActive)
	if len(clips) != 1 {
		t.Errorf("already-archived clips must be kept after unpair; got %d", len(clips))
	}
}

// TestUnpair_BlockedByUnsyncedClips: the phone still has a clip we never
// archived -> the first (unconfirmed) call returns needs_confirmation and
// changes nothing; a confirmed call goes through.
func TestUnpair_BlockedByUnsyncedClips(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, _ := newTestStore(t)
	res := pairForTest(t, store, srv.URL, "GOOD-CODE")

	fp.mu.Lock()
	fp.clips = []fakeClip{{filename: "pending.mp4", createdAtMs: time.Now().UnixMilli(), data: []byte("x")}}
	fp.mu.Unlock()

	r, err := unpair.Unpair(context.Background(), store, res.PhoneID, false)
	if err != nil {
		t.Fatalf("Unpair(false): %v", err)
	}
	if r.Outcome != unpair.OutcomeNeedsConfirmation || r.UnsyncedCount != 1 {
		t.Fatalf("got outcome=%v count=%d, want needs_confirmation/1", r.Outcome, r.UnsyncedCount)
	}
	if _, err := store.GetPhone(res.PhoneID); err != nil {
		t.Errorf("phone must still be paired after a blocked unpair")
	}
	if fp.liveTokens() != 1 {
		t.Errorf("token must not be revoked on a blocked unpair")
	}

	r, err = unpair.Unpair(context.Background(), store, res.PhoneID, true)
	if err != nil || r.Outcome != unpair.OutcomeOK {
		t.Fatalf("confirmed Unpair: outcome=%v err=%v", r.Outcome, err)
	}
	if _, err := store.GetPhone(res.PhoneID); err == nil {
		t.Errorf("phone row should be gone after the confirmed unpair")
	}
}

// TestUnpair_UnreachablePhoneKeepsPairing: an unreachable phone can't have its
// token revoked, so the safe path must leave local state intact.
func TestUnpair_UnreachablePhoneKeepsPairing(t *testing.T) {
	srv, _ := newFakePhoneServer(t, "GOOD-CODE")
	store, _ := newTestStore(t)
	res := pairForTest(t, store, srv.URL, "GOOD-CODE")
	srv.Close() // now unreachable

	r, err := unpair.Unpair(context.Background(), store, res.PhoneID, false)
	if err != nil {
		t.Fatalf("Unpair: %v", err)
	}
	if r.Outcome != unpair.OutcomeUnreachable {
		t.Fatalf("outcome = %v, want Unreachable", r.Outcome)
	}
	if _, err := store.GetPhone(res.PhoneID); err != nil {
		t.Errorf("phone must stay paired when it can't be reached to revoke")
	}
}

// TestForceUnpair_RemovesEvenWhenUnreachable: force removes local state
// regardless of reachability.
func TestForceUnpair_RemovesEvenWhenUnreachable(t *testing.T) {
	srv, _ := newFakePhoneServer(t, "GOOD-CODE")
	store, _ := newTestStore(t)
	res := pairForTest(t, store, srv.URL, "GOOD-CODE")
	srv.Close()

	r, err := unpair.Force(context.Background(), store, res.PhoneID)
	if err != nil {
		t.Fatalf("Force: %v", err)
	}
	if r.Outcome != unpair.OutcomeOK {
		t.Fatalf("outcome = %v, want OK", r.Outcome)
	}
	if _, err := store.GetPhone(res.PhoneID); err == nil {
		t.Errorf("force unpair must remove the local pairing even when unreachable")
	}
}
