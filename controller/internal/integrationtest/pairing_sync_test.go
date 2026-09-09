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

type fakeSegment struct {
	filename    string
	createdAtMs int64
	durationMs  int64 // defaults to 1000 when zero
	data        []byte
}

type fakePhone struct {
	mu          sync.Mutex
	invite      string
	inviteUsed  bool
	tokens      map[string]bool
	segments    []fakeSegment
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

	// mode: "record" (default) blocks calibration; "standby" allows it.
	mode string

	// Camera selection + manual controls. activeCameraID defaults to "0"; the
	// device has cameras "0" (wide) and "2" (ultra-wide). cameraKeys echoes the
	// last POST /api/camera/state keys.
	activeCameraID       string
	manualControlEnabled bool
	cameraKeys           map[string]any
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
	mux.HandleFunc("/api/mode", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		if r.Method == http.MethodPost {
			var b struct {
				Mode string `json:"mode"`
			}
			_ = json.NewDecoder(r.Body).Decode(&b)
			fp.mode = b.Mode
		}
		m := fp.mode
		if m == "" {
			m = "record"
		}
		writeJSON(w, map[string]string{"mode": m})
	}))
	mux.HandleFunc("/api/calibration/start", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		if fp.mode == "" || fp.mode == "record" {
			w.WriteHeader(http.StatusConflict)
			writeJSON(w, map[string]string{"error": "stop recording on the phone before calibrating"})
			return
		}
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
	mux.HandleFunc("/api/segments", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		since, _ := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
		fp.mu.Lock()
		defer fp.mu.Unlock()
		var out []map[string]any
		for _, s := range fp.segments {
			if s.createdAtMs <= since {
				continue
			}
			dur := s.durationMs
			if dur == 0 {
				dur = 1000
			}
			out = append(out, map[string]any{
				"filename": s.filename, "url": "/api/segments/" + s.filename + "/file",
				"createdAtMs": s.createdAtMs, "durationMs": dur, "endMs": s.createdAtMs + dur,
				"sizeBytes": len(s.data), "width": 100, "height": 100,
			})
		}
		writeJSON(w, map[string]any{"segments": out})
	}))
	mux.HandleFunc("/api/segments/", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		rest := strings.TrimPrefix(r.URL.Path, "/api/segments/")
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
		var found *fakeSegment
		for i := range fp.segments {
			if fp.segments[i].filename == filename {
				found = &fp.segments[i]
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

	mux.HandleFunc("/api/cameras", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		fp.mu.Lock()
		active := fp.activeCameraID
		if active == "" {
			active = "0"
		}
		fp.mu.Unlock()
		writeJSON(w, map[string]any{"cameras": []map[string]any{
			{"cameraId": "0", "facing": "back", "label": "Wide (auto)", "focalLengthMm": 6.81, "isActive": active == "0"},
			{"cameraId": "0:2", "facing": "back", "label": "Ultra-wide", "focalLengthMm": 1.55, "isActive": active == "0:2"},
			{"cameraId": "1", "facing": "front", "label": "Front (auto)", "focalLengthMm": 2.5, "isActive": active == "1"},
		}})
	}))
	mux.HandleFunc("/api/cameras/active", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		var b struct {
			CameraID string `json:"cameraId"`
		}
		_ = json.NewDecoder(r.Body).Decode(&b)
		if !knownCam(b.CameraID) {
			w.WriteHeader(http.StatusNotFound)
			writeJSON(w, map[string]string{"error": "unknown cameraId '" + b.CameraID + "'"})
			return
		}
		fp.mu.Lock()
		fp.activeCameraID = b.CameraID
		fp.mu.Unlock()
		writeJSON(w, map[string]string{"activeCameraId": b.CameraID})
	}))
	mux.HandleFunc("/api/camera/capabilities", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		id := r.URL.Query().Get("cameraId")
		if id == "" {
			fp.mu.Lock()
			id = fp.activeCameraID
			fp.mu.Unlock()
			if id == "" {
				id = "0"
			}
		}
		if !knownCam(id) {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		physicalIDs := []string{}
		if id == "0" {
			physicalIDs = []string{"2"}
		}
		writeJSON(w, map[string]any{
			"cameraId":                  id,
			"zoomRatioRange":            map[string]any{"lo": 1.0, "hi": 8.0},
			"zoomViaRatioApi":           true,
			"aeCompensationRange":       map[string]any{"lo": -24, "hi": 24},
			"aeCompensationStepMilliEv": 166,
			"exposureTimeRangeNs":       map[string]any{"lo": 12000, "hi": 100000000},
			"sensitivityRange":          map[string]any{"lo": 50, "hi": 6400},
			"minFocusDistanceDiopters":  10.0,
			"hasManualSensor":           true,
			"hasManualFocus":            true,
			"hasManualWhiteBalance":     true,
			"wbGainRange":               map[string]any{"lo": 1.0, "hi": 8.0},
			"awbModes":                  []int{0, 1, 2, 5, 6},
			"videoStabilizationModes":   []int{0, 1},
			"opticalStabilizationModes": []int{0, 1},
			"physicalCameraIds":         physicalIDs,
			"croppingType":              "FREEFORM",
			"activeArrayWidth":          4032,
			"activeArrayHeight":         3024,
		})
	}))
	mux.HandleFunc("/api/camera/state", authed(fp, func(w http.ResponseWriter, r *http.Request) {
		fp.mu.Lock()
		defer fp.mu.Unlock()
		if r.Method == http.MethodPost {
			var b struct {
				ManualControlEnabled *bool          `json:"manualControlEnabled"`
				Keys                 map[string]any `json:"keys"`
			}
			_ = json.NewDecoder(r.Body).Decode(&b)
			if b.Keys != nil {
				if z, ok := b.Keys["zoomRatio"].(float64); ok && (z < 1.0 || z > 8.0) {
					w.WriteHeader(http.StatusBadRequest)
					writeJSON(w, map[string]string{"error": "must be in 1.0..8.0", "key": "zoomRatio"})
					return
				}
				if m, ok := b.Keys["awbMode"].(float64); ok {
					allowed := map[int]bool{0: true, 1: true, 2: true, 5: true, 6: true}
					if !allowed[int(m)] {
						w.WriteHeader(http.StatusBadRequest)
						writeJSON(w, map[string]string{"error": "must be one of [0 1 2 5 6]", "key": "awbMode"})
						return
					}
				}
				if m, ok := b.Keys["opticalStabilizationMode"].(float64); ok && m != 0 && m != 1 {
					w.WriteHeader(http.StatusBadRequest)
					writeJSON(w, map[string]string{"error": "must be one of [0 1]", "key": "opticalStabilizationMode"})
					return
				}
			}
			if b.ManualControlEnabled != nil {
				fp.manualControlEnabled = *b.ManualControlEnabled
			}
			if b.Keys != nil {
				fp.cameraKeys = b.Keys
			}
		}
		id := fp.activeCameraID
		if id == "" {
			id = "0"
		}
		keys := fp.cameraKeys
		if keys == nil {
			keys = map[string]any{}
		}
		writeJSON(w, map[string]any{
			"cameraId":             id,
			"rotationDegrees":      0,
			"manualControlEnabled": fp.manualControlEnabled,
			"keys":                 keys,
		})
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

// knownCam mirrors the fake phone's fixed camera set: logical "0" (+ its physical
// sub-camera "0:2") and front "1".
func knownCam(id string) bool { return id == "0" || id == "0:2" || id == "1" }

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

// waitForSyncedSegments blocks until at least want segments (across all of the
// phone's active clips) have been indexed, or fails the test on timeout.
func waitForSyncedSegments(t *testing.T, store *dbstore.Store, phoneID string, want int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		clips, err := store.ListClips(phoneID, dbstore.ClipActive)
		if err != nil {
			t.Fatalf("ListClips: %v", err)
		}
		total := 0
		for _, c := range clips {
			total += c.SegmentCount
		}
		if total >= want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for sync; got %d/%d segments", total, want)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// TestSyncLoop_GroupsContiguousSegments is the end-to-end sync verification for
// the segments→clips grouping: the mock phone is seeded with three gaplessly-
// rolled segments (each starting ~100ms after the previous one's end, well
// within GroupingGapMs), and after the syncer runs they must collapse into a
// single clip whose segment files + thumbnails are all on disk, with the cursor
// advanced so a second run re-downloads nothing.
func TestSyncLoop_GroupsContiguousSegments(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, dirs := newTestStore(t)

	now := time.Now().UnixMilli()
	// Each fake segment is 1000ms long; starting them 1100ms apart is a ~100ms
	// inter-segment gap.
	fp.segments = []fakeSegment{
		{filename: "seg_1.mp4", createdAtMs: now - 20000, data: []byte("A")},
		{filename: "seg_2.mp4", createdAtMs: now - 18900, data: []byte("BB")},
		{filename: "seg_3.mp4", createdAtMs: now - 17800, data: []byte("CCC")},
	}

	address := strings.TrimPrefix(srv.URL, "http://")
	result, err := pairing.AddPhone(context.Background(), store, address, "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone failed: %v", err)
	}

	mgr := syncer.NewManager(store, dirs, 50*time.Millisecond, dbstore.GroupingGapMs)
	mgr.Start()
	defer mgr.Stop()

	waitForSyncedSegments(t, store, result.PhoneID, 3)

	clips, err := store.ListClips(result.PhoneID, dbstore.ClipActive)
	if err != nil {
		t.Fatalf("ListClips: %v", err)
	}
	if len(clips) != 1 {
		t.Fatalf("expected the 3 contiguous segments to form 1 clip, got %d clips", len(clips))
	}
	if clips[0].SegmentCount != 3 {
		t.Errorf("clip.SegmentCount = %d, want 3", clips[0].SegmentCount)
	}

	segs, err := store.ListSegmentsForClip(clips[0].ID)
	if err != nil {
		t.Fatalf("ListSegmentsForClip: %v", err)
	}
	if len(segs) != 3 {
		t.Fatalf("expected 3 segments in the clip, got %d", len(segs))
	}
	for _, s := range segs {
		if _, statErr := os.Stat(s.LocalPath); statErr != nil {
			t.Errorf("expected segment file on disk at %s: %v", s.LocalPath, statErr)
		}
		if s.ThumbnailPath == "" {
			t.Errorf("expected a thumbnail path for %s", s.Filename)
		} else if _, statErr := os.Stat(s.ThumbnailPath); statErr != nil {
			t.Errorf("expected thumbnail file on disk at %s: %v", s.ThumbnailPath, statErr)
		}
	}

	phone, err := store.GetPhone(result.PhoneID)
	if err != nil {
		t.Fatalf("GetPhone: %v", err)
	}
	if phone.SyncCursorMs < fp.segments[2].createdAtMs {
		t.Errorf("expected cursor to advance past the last segment; cursor=%d want>=%d", phone.SyncCursorMs, fp.segments[2].createdAtMs)
	}
}

// TestSyncLoop_SplitsOnGap is the counterpart: two runs of two segments each,
// separated by a gap well over the 3s threshold, must produce two distinct
// clips.
func TestSyncLoop_SplitsOnGap(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, dirs := newTestStore(t)

	now := time.Now().UnixMilli()
	// Two runs of two gaplessly-rolled segments (~100ms apart); a2 ends at
	// now-27900 and b1 starts at now-20000, a ~7.9s motion-stop gap between them.
	fp.segments = []fakeSegment{
		{filename: "seg_a1.mp4", createdAtMs: now - 30000, data: []byte("A")},
		{filename: "seg_a2.mp4", createdAtMs: now - 28900, data: []byte("BB")},
		{filename: "seg_b1.mp4", createdAtMs: now - 20000, data: []byte("CCC")},
		{filename: "seg_b2.mp4", createdAtMs: now - 18900, data: []byte("DDDD")},
	}

	address := strings.TrimPrefix(srv.URL, "http://")
	result, err := pairing.AddPhone(context.Background(), store, address, "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone failed: %v", err)
	}

	mgr := syncer.NewManager(store, dirs, 50*time.Millisecond, dbstore.GroupingGapMs)
	mgr.Start()
	defer mgr.Stop()

	waitForSyncedSegments(t, store, result.PhoneID, 4)

	clips, err := store.ListClips(result.PhoneID, dbstore.ClipActive)
	if err != nil {
		t.Fatalf("ListClips: %v", err)
	}
	if len(clips) != 2 {
		t.Fatalf("expected the gap to split into 2 clips, got %d", len(clips))
	}
	for _, c := range clips {
		if c.SegmentCount != 2 {
			t.Errorf("clip %s: SegmentCount = %d, want 2", c.ID, c.SegmentCount)
		}
	}
}

// TestSyncLoop_TreatsEvictedSegmentAsSkipNotError verifies the "404 on
// download == already evicted, normal skip" rule from docs/design/http-api.md and
// the old prototype's identical lesson: a segment that 404s on file download
// must be skipped (never indexed, never retried forever) while the OTHER
// listed segment still syncs normally.
func TestSyncLoop_TreatsEvictedSegmentAsSkipNotError(t *testing.T) {
	srv, fp := newFakePhoneServer(t, "GOOD-CODE")
	store, dirs := newTestStore(t)

	now := time.Now().UnixMilli()
	fp.segments = []fakeSegment{
		{filename: "seg_evicted.mp4", createdAtMs: now - 5000, data: []byte("GONE")},
		{filename: "seg_ok.mp4", createdAtMs: now - 3000, data: []byte("STILL-HERE")},
	}
	fp.evictedName = "seg_evicted.mp4"

	address := strings.TrimPrefix(srv.URL, "http://")
	result, err := pairing.AddPhone(context.Background(), store, address, "GOOD-CODE")
	if err != nil {
		t.Fatalf("AddPhone failed: %v", err)
	}

	mgr := syncer.NewManager(store, dirs, 50*time.Millisecond, dbstore.GroupingGapMs)
	mgr.Start()
	defer mgr.Stop()

	deadline := time.Now().Add(5 * time.Second)
	for {
		phone, err := store.GetPhone(result.PhoneID)
		if err != nil {
			t.Fatalf("GetPhone: %v", err)
		}
		if phone.SyncCursorMs >= fp.segments[1].createdAtMs {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for cursor to pass the evicted+ok segments; cursor=%d", phone.SyncCursorMs)
		}
		time.Sleep(20 * time.Millisecond)
	}

	if exists, _ := store.SegmentExists(result.PhoneID, "seg_evicted.mp4"); exists {
		t.Errorf("evicted segment should never be indexed")
	}
	if exists, _ := store.SegmentExists(result.PhoneID, "seg_ok.mp4"); !exists {
		t.Errorf("expected the non-evicted segment to be indexed")
	}
}
