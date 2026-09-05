// Package integrationtest exercises the Add-phone (pairing) flow and the
// background sync loop end to end against an in-process instance of
// tools/mock-phone's server (not the shipped mockphone binary — a local
// http.Server built from the same handlers would require importing a
// main package from a separate module, so this spins up a small
// equivalent inline). This is how the pairing + sync code was verified
// without a real Android phone available.
package integrationtest

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"panopticon-controller/internal/appdirs"
	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/pairing"
	"panopticon-controller/internal/syncer"
)

type fakeClip struct {
	filename    string
	createdAtMs int64
	data        []byte
}

type fakePhone struct {
	mu          sync.Mutex
	invite      string
	inviteUsed  bool
	tokens      map[string]bool
	clips       []fakeClip
	evictedName string // if set, this filename 404s on download even though listed

	manufacturer string // defaults to "Google" if empty
	model        string // defaults to "Pixel 6" if empty
	phoneName    string // defaults to "Test Phone" if empty

	// calibrationResult, if non-empty, is served verbatim from
	// GET /api/calibration/result; empty means the phone 404s it (never
	// calibrated).
	calibrationResult string
	// calRunning drives a faked sweep: POST /start sets it, GET /status
	// reports "running" until calStatusPolls polls have happened, then
	// "completed".
	calRunID       string
	calStatusPolls int
}

func newFakePhoneServer(t *testing.T, invite string) (*httptest.Server, *fakePhone) {
	t.Helper()
	fp := &fakePhone{invite: invite, tokens: make(map[string]bool)}

	devManufacturer := func() string {
		if fp.manufacturer != "" {
			return fp.manufacturer
		}
		return "Google"
	}
	devModel := func() string {
		if fp.model != "" {
			return fp.model
		}
		return "Pixel 6"
	}
	devName := func() string {
		if fp.phoneName != "" {
			return fp.phoneName
		}
		return "Test Phone"
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/pair", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			// Self-unpair: revoke the calling token. 401 if it's already gone.
			token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			fp.mu.Lock()
			_, ok := fp.tokens[token]
			delete(fp.tokens, token)
			fp.mu.Unlock()
			if !ok {
				w.WriteHeader(http.StatusUnauthorized)
				return
			}
			writeJSON(w, map[string]bool{"unpaired": true})
			return
		}
		if r.URL.Query().Get("invite") != fp.invite {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		fp.mu.Lock()
		if fp.inviteUsed {
			fp.mu.Unlock()
			w.WriteHeader(http.StatusGone)
			return
		}
		fp.inviteUsed = true
		token := "tok-" + strconv.Itoa(len(fp.tokens)+1)
		fp.tokens[token] = true
		fp.mu.Unlock()

		writeJSON(w, map[string]any{
			"controllerId": "ctl_1",
			"token":        token,
			// phoneId keyed off the invite so two fake phones in one test
			// don't collide on the controller's phones-table primary key.
			"phone": map[string]string{"phoneId": "ph_" + fp.invite, "name": devName()},
		})
	})
	mux.HandleFunc("/api/device", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]string{"manufacturer": devManufacturer(), "model": devModel(), "device": "test"})
	}))
	mux.HandleFunc("/api/calibration/result", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		fp.mu.Lock()
		body := fp.calibrationResult
		fp.mu.Unlock()
		if body == "" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(body))
	}))
	mux.HandleFunc("/api/calibration/start", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		if fp.calRunID != "" {
			w.WriteHeader(http.StatusConflict)
			return
		}
		fp.calRunID = "cal-fake1"
		fp.calStatusPolls = 0
		writeJSON(w, map[string]any{"runId": fp.calRunID, "status": "running", "startedAtMs": 1, "cameraIds": []string{"0"}})
	}))
	mux.HandleFunc("/api/calibration/status", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		if fp.calRunID == "" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		fp.calStatusPolls++
		status := "running"
		if fp.calStatusPolls >= 2 {
			status = "completed"
			// A finished sweep is what the phone would now persist as its result.
			if fp.calibrationResult == "" {
				fp.calibrationResult = sampleCalibrationResult
			}
		}
		writeJSON(w, map[string]any{
			"runId": fp.calRunID, "status": status, "currentCameraId": "0",
			"camerasCompleted": 1, "camerasTotal": 1, "currentStep": "zoom-quality",
			"stepsCompleted": 1, "stepsTotal": 2,
			"progressWithinStep": map[string]int{"index": 15, "total": 15}, "startedAtMs": 1,
		})
	}))
	mux.HandleFunc("/api/calibration/", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		// DELETE /api/calibration/:runId
		fp.mu.Lock()
		defer fp.mu.Unlock()
		if fp.calRunID == "" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		fp.calRunID = ""
		writeJSON(w, map[string]bool{"cancelled": true})
	}))
	mux.HandleFunc("/api/status", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{
			"mode": "record", "status": "recording", "cameraHealthy": true, "liveViewers": 0,
			"storageUsedBytes": 100, "storageCapBytes": 1000, "batteryPercent": 50, "charging": false,
			"serverTimeMs": time.Now().UnixMilli(),
		})
	}))
	mux.HandleFunc("/api/config", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"deviceName": "Test Phone", "motionSensitivity": "medium", "storageCapBytes": 1000, "ringBufferMaxAgeMs": 1000, "rotationDegrees": 0})
	}))
	mux.HandleFunc("/api/clips", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
		fp.mu.Lock()
		defer fp.mu.Unlock()
		var out []map[string]any
		for _, c := range fp.clips {
			if c.createdAtMs <= since {
				continue
			}
			out = append(out, map[string]any{
				"filename": c.filename, "url": "/api/clips/" + c.filename + "/file",
				"createdAtMs": c.createdAtMs, "durationMs": 1000, "endMs": c.createdAtMs + 1000,
				"sizeBytes": len(c.data), "width": 100, "height": 100,
			})
		}
		writeJSON(w, map[string]any{"clips": out})
	}))
	mux.HandleFunc("/api/clips/", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/api/clips/")
		parts := strings.SplitN(rest, "/", 2)
		if len(parts) != 2 {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		filename, kind := parts[0], parts[1]
		if filename == fp.evictedName {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		fp.mu.Lock()
		var found *fakeClip
		for i := range fp.clips {
			if fp.clips[i].filename == filename {
				found = &fp.clips[i]
			}
		}
		fp.mu.Unlock()
		if found == nil {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		switch kind {
		case "file":
			w.Write(found.data)
		case "thumbnail":
			w.Write([]byte("thumb:" + filename))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))

	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv, fp
}

func authed(fp *fakePhone, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		fp.mu.Lock()
		ok := fp.tokens[token]
		fp.mu.Unlock()
		if !ok {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func newTestStore(t *testing.T) (*dbstore.Store, appdirs.Dirs) {
	t.Helper()
	dir := t.TempDir()
	dirs := appdirs.Dirs{Root: dir, DataDir: dir, ArchiveDir: dir}
	store, err := dbstore.Open(dirs.DBPath())
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { store.Close() })
	return store, dirs
}

// TestAddPhone_Success verifies the whole POST /api/pair round trip: a
// real Ed25519 identity is generated and sent, the mock phone issues a
// token, and the phone record lands correctly in SQLite.
func TestAddPhone_Success(t *testing.T) {
	srv, _ := newFakePhoneServer(t, "GOOD-CODE")
	store, _ := newTestStore(t)

	address := strings.TrimPrefix(srv.URL, "http://")
	result, err := pairing.AddPhone(context.Background(), store, address, "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone failed: %v", err)
	}
	if result.PhoneID == "" {
		t.Fatalf("expected non-empty phone id")
	}
	if result.Manufacturer != "Google" || result.Model != "Pixel 6" {
		t.Errorf("expected device info to be pulled post-pair, got %+v", result)
	}

	phones, err := store.ListPhones()
	if err != nil || len(phones) != 1 {
		t.Fatalf("expected 1 stored phone, got %d (err=%v)", len(phones), err)
	}
	if phones[0].Token == "" {
		t.Errorf("expected a bearer token to be stored")
	}
}

// TestAddPhone_InvalidInvite checks the distinct-error-copy requirement:
// a bad invite code must be classified as OutcomeInvalidInvite, not a
// generic failure.
func TestAddPhone_InvalidInvite(t *testing.T) {
	srv, _ := newFakePhoneServer(t, "GOOD-CODE")
	store, _ := newTestStore(t)

	address := strings.TrimPrefix(srv.URL, "http://")
	_, err := pairing.AddPhone(context.Background(), store, address, "WRONG-CODE")
	if err == nil {
		t.Fatalf("expected an error for a wrong invite code")
	}
	var pe *pairing.Error
	if !asPairingError(err, &pe) {
		t.Fatalf("expected *pairing.Error, got %T: %v", err, err)
	}
	if pe.Outcome != pairing.OutcomeInvalidInvite {
		t.Errorf("expected OutcomeInvalidInvite, got %v (message=%q)", pe.Outcome, pe.Message)
	}
}

// TestAddPhone_Unreachable checks that a dead address is classified as
// OutcomeUnreachable — the other distinct-error-copy case.
func TestAddPhone_Unreachable(t *testing.T) {
	store, _ := newTestStore(t)

	// Port 1 should refuse the connection near-instantly on Windows.
	_, err := pairing.AddPhone(context.Background(), store, "127.0.0.1:1", "ANY-CODE")
	if err == nil {
		t.Fatalf("expected an error for an unreachable address")
	}
	var pe *pairing.Error
	if !asPairingError(err, &pe) {
		t.Fatalf("expected *pairing.Error, got %T: %v", err, err)
	}
	if pe.Outcome != pairing.OutcomeUnreachable {
		t.Errorf("expected OutcomeUnreachable, got %v (message=%q)", pe.Outcome, pe.Message)
	}
}

func asPairingError(err error, target **pairing.Error) bool {
	pe, ok := err.(*pairing.Error)
	if ok {
		*target = pe
	}
	return ok
}

// TestSyncLoop_DownloadsClipsAndAdvancesCursor is the end-to-end sync
// verification: pair against the mock phone (which is seeded with 2 clips),
// start the syncer.Manager, and confirm the clip files+thumbnails actually
// land on disk and get indexed in SQLite, and that the cursor advances so a
// second run doesn't redownload anything.
func TestSyncLoop_DownloadsClipsAndAdvancesCursor(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, dirs := newTestStore(t)

	now := time.Now().UnixMilli()
	fp.clips = []fakeClip{
		{filename: "clip_0000001.mp4", createdAtMs: now - 5000, data: []byte("VIDEO-ONE-DATA")},
		{filename: "clip_0000002.mp4", createdAtMs: now - 3000, data: []byte("VIDEO-TWO-DATA")},
	}

	address := strings.TrimPrefix(srv.URL, "http://")
	result, err := pairing.AddPhone(context.Background(), store, address, "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone failed: %v", err)
	}

	mgr := syncer.NewManager(store, dirs, 50*time.Millisecond)
	mgr.Start()
	defer mgr.Stop()

	deadline := time.Now().Add(5 * time.Second)
	for {
		clips, err := store.ListClips(result.PhoneID, dbstore.ClipActive)
		if err != nil {
			t.Fatalf("ListClips: %v", err)
		}
		if len(clips) == 2 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for sync; got %d/2 clips", len(clips))
		}
		time.Sleep(20 * time.Millisecond)
	}

	clips, _ := store.ListClips(result.PhoneID, dbstore.ClipActive)
	for _, c := range clips {
		if _, statErr := os.Stat(c.LocalPath); statErr != nil {
			t.Errorf("expected clip file on disk at %s: %v", c.LocalPath, statErr)
		}
		if c.ThumbnailPath == "" {
			t.Errorf("expected a thumbnail path for %s", c.Filename)
		} else if _, statErr := os.Stat(c.ThumbnailPath); statErr != nil {
			t.Errorf("expected thumbnail file on disk at %s: %v", c.ThumbnailPath, statErr)
		}
	}

	phone, err := store.GetPhone(result.PhoneID)
	if err != nil {
		t.Fatalf("GetPhone: %v", err)
	}
	if phone.SyncCursorMs < fp.clips[1].createdAtMs {
		t.Errorf("expected cursor to advance past last clip's createdAtMs; cursor=%d want>=%d", phone.SyncCursorMs, fp.clips[1].createdAtMs)
	}
}

// TestSyncLoop_TreatsEvictedClipAsSkipNotError verifies the "404 on
// download == already evicted, normal skip" rule from phone-http-api.md and
// the old prototype's identical lesson: a clip that 404s on file download
// must be skipped (never indexed, never retried forever) while the OTHER
// listed clip still syncs normally.
func TestSyncLoop_TreatsEvictedClipAsSkipNotError(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, dirs := newTestStore(t)

	now := time.Now().UnixMilli()
	fp.clips = []fakeClip{
		{filename: "clip_evicted.mp4", createdAtMs: now - 5000, data: []byte("GONE")},
		{filename: "clip_ok.mp4", createdAtMs: now - 3000, data: []byte("STILL-HERE")},
	}
	fp.evictedName = "clip_evicted.mp4"

	address := strings.TrimPrefix(srv.URL, "http://")
	result, err := pairing.AddPhone(context.Background(), store, address, "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone failed: %v", err)
	}

	mgr := syncer.NewManager(store, dirs, 50*time.Millisecond)
	mgr.Start()
	defer mgr.Stop()

	deadline := time.Now().Add(5 * time.Second)
	for {
		phone, err := store.GetPhone(result.PhoneID)
		if err != nil {
			t.Fatalf("GetPhone: %v", err)
		}
		if phone.SyncCursorMs >= fp.clips[1].createdAtMs {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for cursor to pass the evicted+ok clips; cursor=%d", phone.SyncCursorMs)
		}
		time.Sleep(20 * time.Millisecond)
	}

	if exists, _ := store.ClipExists(result.PhoneID, "clip_evicted.mp4"); exists {
		t.Errorf("evicted clip should never be indexed")
	}
	if exists, _ := store.ClipExists(result.PhoneID, "clip_ok.mp4"); !exists {
		t.Errorf("expected the non-evicted clip to be indexed")
	}
}
