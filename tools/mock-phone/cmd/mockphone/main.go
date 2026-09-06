// Command mockphone is a throwaway dev/test helper — its own top-level tool, not part of the
// shipped controller — implementing just enough of docs/implementation/phone-http-api.md to
// exercise the controller's Add-phone flow and sync loop end to end without a real Android phone
// available.
//
// Routes implemented: POST/DELETE /api/pair, GET /api/status, GET /api/device,
// GET /api/config, GET /api/build-info, GET /api/segments, GET
// /api/segments/:filename/file, GET /api/segments/:filename/thumbnail,
// POST /api/calibration/start, GET /api/calibration/status,
// DELETE /api/calibration/:runId, GET /api/calibration/result.
//
// Usage: go run ./cmd/mockphone [-addr :8091] [-invite XYZF-EBDO-ORMS]
package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type pairedController struct {
	controllerID string
	token        string
	publicKey    string
	name         string
}

type segment struct {
	filename    string
	createdAtMs int64
	durationMs  int64
	sizeBytes   int64
	width       int
	height      int
	data        []byte
}

type server struct {
	mu           sync.Mutex
	invite       string
	inviteUsed   bool
	controllers  map[string]pairedController // token -> controller
	segments     []segment
	deviceName   string
	manufacturer string
	model        string
	mode         string // "record" | "standby" | "live" - RECORD blocks calibration

	// Calibration: a sweep is faked as a short timed "running" window, after
	// which /status reports "completed" and /result serves a canned body.
	calRunID      string
	calStartedAt  time.Time
	calSweepDurMs int64
}

func main() {
	addr := flag.String("addr", ":8091", "listen address")
	invite := flag.String("invite", "TEST-INVITE-CODE", "invite code this mock phone accepts (once)")
	numSegments := flag.Int("segments", 6, "number of fake pre-existing segments to seed (as two runs split by a gap)")
	flag.Parse()

	s := &server{
		invite:        *invite,
		controllers:   make(map[string]pairedController),
		deviceName:    "Mock Porch Cam",
		manufacturer:  "Google",
		model:         "Pixel 6",
		mode:          "record",
		calSweepDurMs: 6000,
	}
	s.seedSegments(*numSegments)

	mux := http.NewServeMux()
	mux.HandleFunc("/api/pair", s.handlePair)
	mux.HandleFunc("/api/status", s.withAuth(s.handleStatus))
	mux.HandleFunc("/api/device", s.withAuth(s.handleDevice))
	mux.HandleFunc("/api/config", s.withAuth(s.handleConfig))
	mux.HandleFunc("/api/build-info", s.withAuth(s.handleBuildInfo))
	mux.HandleFunc("/api/mode", s.withAuth(s.handleMode))
	mux.HandleFunc("/api/segments", s.withAuth(s.handleSegmentsList))
	mux.HandleFunc("/api/segments/", s.withAuth(s.handleSegmentFileOrThumb))
	mux.HandleFunc("/api/calibration/start", s.withAuth(s.handleCalibrationStart))
	mux.HandleFunc("/api/calibration/status", s.withAuth(s.handleCalibrationStatus))
	mux.HandleFunc("/api/calibration/result", s.withAuth(s.handleCalibrationResult))
	mux.HandleFunc("/api/calibration/", s.withAuth(s.handleCalibrationCancel)) // DELETE /api/calibration/:runId

	log.Printf("mockphone listening on %s (invite=%s, manufacturer=%s model=%s, %d seeded segments)",
		*addr, *invite, s.manufacturer, s.model, *numSegments)
	log.Fatal(http.ListenAndServe(*addr, mux))
}

// seedSegments lays n fake segments out as TWO back-to-back runs (~10s apart,
// i.e. within the controller's 3s grouping gap once the ~2s rotation loss is
// accounted for) separated by a multi-minute gap, so the controller's
// segment→clip grouping has something real to collapse: n segments in, 2 clips
// out.
func (s *server) seedSegments(n int) {
	if n < 2 {
		n = 2
	}
	now := time.Now().UnixMilli()
	const segLenMs int64 = 10_000
	firstRun := (n + 1) / 2

	// Walk a cursor backwards from "now" so the newest segment is ~now.
	// Segments within a run start segLenMs apart; the run boundary inserts a
	// 10-minute gap.
	starts := make([]int64, n)
	cursor := now
	for i := n - 1; i >= 0; i-- {
		starts[i] = cursor - segLenMs
		cursor = starts[i]
		if i == firstRun {
			cursor -= 10 * 60_000 // gap between run 1 and run 2
		}
	}

	for i := 0; i < n; i++ {
		data := makeFakeMp4(fmt.Sprintf("segment %d", i))
		s.segments = append(s.segments, segment{
			filename:    fmt.Sprintf("clip_%07d.mp4", i+1),
			createdAtMs: starts[i],
			durationMs:  segLenMs,
			sizeBytes:   int64(len(data)),
			width:       1920,
			height:      1080,
			data:        data,
		})
	}
}

// makeFakeMp4 fabricates a small, deterministic byte blob standing in for a
// real .mp4 — this mock never actually decodes video, so any bytes suffice
// to exercise download/persist/dedupe logic.
func makeFakeMp4(tag string) []byte {
	return []byte("FAKEMP4:" + tag + strings.Repeat("x", 512))
}

func (s *server) withAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		authz := r.Header.Get("Authorization")
		token := strings.TrimPrefix(authz, "Bearer ")
		s.mu.Lock()
		_, ok := s.controllers[token]
		s.mu.Unlock()
		if token == "" || !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r)
	}
}

func (s *server) handlePair(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
		invite := r.URL.Query().Get("invite")
		var body struct {
			PublicKey string `json:"publicKey"`
			Name      string `json:"name"`
			Kind      string `json:"kind"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)

		s.mu.Lock()
		defer s.mu.Unlock()
		if invite != s.invite {
			writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown invite code"})
			return
		}
		if s.inviteUsed {
			writeJSON(w, http.StatusGone, map[string]string{"error": "invite already used"})
			return
		}
		s.inviteUsed = true

		token := randHex(16)
		controllerID := "ctl_" + randHex(4)
		s.controllers[token] = pairedController{controllerID: controllerID, token: token, publicKey: body.PublicKey, name: body.Name}

		resp := map[string]any{
			"controllerId": controllerID,
			"token":        token,
			"phone": map[string]string{
				"phoneId": "ph_mock0001",
				"name":    s.deviceName,
			},
		}
		writeJSON(w, http.StatusOK, resp)

	case http.MethodDelete:
		authz := r.Header.Get("Authorization")
		token := strings.TrimPrefix(authz, "Bearer ")
		s.mu.Lock()
		_, ok := s.controllers[token]
		delete(s.controllers, token)
		s.mu.Unlock()
		if !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"unpaired": true})

	default:
		w.WriteHeader(http.StatusMethodNotAllowed)
	}
}

func (s *server) handleStatus(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	mode := s.mode
	s.mu.Unlock()
	recStatus := "recording"
	if mode != "record" {
		recStatus = "stopped"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"mode":             mode,
		"status":           recStatus,
		"cameraHealthy":    true,
		"liveViewers":      0,
		"storageUsedBytes": 4_200_000_000,
		"storageCapBytes":  64_000_000_000,
		"batteryPercent":   81,
		"charging":         true,
		"serverTimeMs":     time.Now().UnixMilli(),
	})
}

// handleMode: GET returns the current mode; POST switches it. `standby` is the
// explicit stop that frees the camera; `live` from `record` is a 409.
func (s *server) handleMode(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		s.mu.Lock()
		mode := s.mode
		s.mu.Unlock()
		writeJSON(w, http.StatusOK, map[string]string{"mode": mode})
		return
	}
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		Mode string `json:"mode"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	s.mu.Lock()
	defer s.mu.Unlock()
	switch body.Mode {
	case "record", "standby":
		s.mode = body.Mode
	case "live":
		if s.mode == "record" {
			writeJSON(w, http.StatusConflict, map[string]string{"error": "stop recording first: POST /api/mode {\"mode\":\"standby\"}"})
			return
		}
		s.mode = "live"
	default:
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "mode must be record|standby|live"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"mode": s.mode})
}

func (s *server) handleDevice(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"manufacturer": s.manufacturer,
		"model":        s.model,
		"device":       "mockdevice",
	})
}

func (s *server) handleConfig(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"deviceName":         s.deviceName,
		"motionSensitivity":  "medium",
		"storageCapBytes":    64_000_000_000,
		"ringBufferMaxAgeMs": 604_800_000,
		"rotationDegrees":    0,
	})
}

func (s *server) handleBuildInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"appVersionName": "0.1.0-mock",
		"appVersionCode": 1,
		"buildType":      "mock",
		"gitSha":         "mock0000",
	})
}

func (s *server) handleSegmentsList(w http.ResponseWriter, r *http.Request) {
	sinceStr := r.URL.Query().Get("since")
	since, _ := strconv.ParseInt(sinceStr, 10, 64)

	s.mu.Lock()
	defer s.mu.Unlock()
	var out []map[string]any
	for _, c := range s.segments {
		if c.createdAtMs <= since {
			continue
		}
		out = append(out, map[string]any{
			"filename":    c.filename,
			"url":         "/api/segments/" + c.filename + "/file",
			"createdAtMs": c.createdAtMs,
			"durationMs":  c.durationMs,
			"endMs":       c.createdAtMs + c.durationMs,
			"sizeBytes":   c.sizeBytes,
			"width":       c.width,
			"height":      c.height,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"segments": out})
}

func (s *server) handleSegmentFileOrThumb(w http.ResponseWriter, r *http.Request) {
	// Path shape: /api/segments/<filename>/file or /api/segments/<filename>/thumbnail
	rest := strings.TrimPrefix(r.URL.Path, "/api/segments/")
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) != 2 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	filename, kind := parts[0], parts[1]

	s.mu.Lock()
	var found *segment
	for i := range s.segments {
		if s.segments[i].filename == filename {
			found = &s.segments[i]
			break
		}
	}
	s.mu.Unlock()

	if found == nil {
		w.WriteHeader(http.StatusNotFound) // evicted / never existed
		return
	}

	switch kind {
	case "file":
		w.Header().Set("Content-Type", "video/mp4")
		w.Write(found.data)
	case "thumbnail":
		w.Header().Set("Content-Type", "image/jpeg")
		w.Write(makeFakeThumbnail())
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

// ---- Calibration (faked as a short timed sweep) ----

func (s *server) handleCalibrationStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.mode == "record" {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "stop recording on the phone before calibrating"})
		return
	}
	if s.calRunID != "" && time.Since(s.calStartedAt).Milliseconds() < s.calSweepDurMs {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "calibration already running"})
		return
	}
	s.calRunID = "cal-" + randHex(4)
	s.calStartedAt = time.Now()
	writeJSON(w, http.StatusOK, map[string]any{
		"runId":       s.calRunID,
		"status":      "running",
		"startedAtMs": s.calStartedAt.UnixMilli(),
		"cameraIds":   []string{"0", "1"},
	})
}

func (s *server) handleCalibrationStatus(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.calRunID == "" {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	elapsed := time.Since(s.calStartedAt).Milliseconds()
	done := elapsed >= s.calSweepDurMs
	status := "running"
	if done {
		status = "completed"
	}
	// Two cameras, two steps each, 15 within-step checks - progress scales with elapsed time.
	frac := float64(elapsed) / float64(s.calSweepDurMs)
	if frac > 1 {
		frac = 1
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"runId":            s.calRunID,
		"status":           status,
		"currentCameraId":  "0",
		"camerasCompleted": int(frac * 2),
		"camerasTotal":     2,
		"currentStep":      "zoom-quality",
		"stepsCompleted":   int(frac*4) % 2,
		"stepsTotal":       2,
		"progressWithinStep": map[string]int{
			"index": int(frac*15) % 16,
			"total": 15,
		},
		"startedAtMs": s.calStartedAt.UnixMilli(),
	})
}

func (s *server) handleCalibrationCancel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.calRunID == "" {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	// Force the sweep to look finished so /status stops reporting "running".
	s.calStartedAt = time.Now().Add(-time.Duration(s.calSweepDurMs) * time.Millisecond)
	writeJSON(w, http.StatusOK, map[string]bool{"cancelled": true})
}

func (s *server) handleCalibrationResult(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	runAt := s.calStartedAt
	s.mu.Unlock()
	if runAt.IsZero() {
		runAt = time.Now().Add(-time.Hour) // pretend this mock phone was calibrated an hour ago
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"runId":          "cal-mock",
		"runAtMs":        runAt.UnixMilli(),
		"deviceIdentity": map[string]any{"manufacturer": s.manufacturer, "model": s.model, "device": "mockdevice", "appVersionName": "0.1.0-mock"},
		"cameras": map[string]any{
			"0": mockCameraResult("0", "back", true, 2.0, false, 6.0),
			"1": mockCameraResult("1", "front", false, 0, false, 0),
		},
	})
}

// mockCameraResult fabricates a rich per-camera zoom map: crop shrinks
// linearly with the requested ratio across two resolutions.
func mockCameraResult(id, facing string, logical bool, crossover float64, positionHonored bool, qualityCollapse float64) map[string]any {
	sample := func(ratio, l float64) map[string]any {
		return map[string]any{
			"requestedRatio": ratio, "reportedRatio": ratio, "ratioHonored": true,
			"requestedCropNorm": map[string]float64{"l": l, "t": l, "r": 1 - l, "b": 1 - l},
			"effectiveCropNorm": map[string]float64{"l": l, "t": l, "r": 1 - l, "b": 1 - l},
			"sharpness":         1000.0 / ratio, "sharpnessRelToBaseline": 1.0 / ratio,
		}
	}
	res := func(w, h int) map[string]any {
		return map[string]any{"width": w, "height": h, "samples": []any{
			sample(1.0, 0.0), sample(2.0, 0.25), sample(4.0, 0.375), sample(8.0, 0.4375),
		}}
	}
	var crossoverPtr any
	if crossover > 0 {
		crossoverPtr = crossover
	}
	var collapsePtr any
	if qualityCollapse > 0 {
		collapsePtr = qualityCollapse
	}
	return map[string]any{
		"deviceIdentity": map[string]any{
			"cameraId": id, "facing": facing, "isLogicalMultiCam": logical,
			"physicalIds": []string{}, "activeArrayWidth": 4032, "activeArrayHeight": 3024,
			"croppingType": "FREEFORM", "focalLengthsMm": []float64{4.38},
		},
		"opticalRange":         map[string]float64{"lo": 1.0, "hi": 2.0},
		"digitalRange":         map[string]float64{"lo": 2.0, "hi": 8.0},
		"crossoverRatio":       crossoverPtr,
		"crossoverMethod":      "active-physical-id",
		"positionHonored":      positionHonored,
		"positionFailRatios":   []float64{4.0, 8.0},
		"qualityCollapseRatio": collapsePtr,
		"perResolution": map[string]any{
			"1920x1080": res(1920, 1080),
			"1280x720":  res(1280, 720),
		},
		"steps": map[string]any{
			"zoom-map":    map[string]int{"checksTotal": 8, "checksPassed": 8},
			"crop-region": map[string]int{"checksTotal": 2, "checksPassed": 0},
		},
	}
}

func makeFakeThumbnail() []byte {
	img := image.NewRGBA(image.Rect(0, 0, 32, 24))
	for y := 0; y < 24; y++ {
		for x := 0; x < 32; x++ {
			img.Set(x, y, color.RGBA{R: 79, G: 227, B: 201, A: 255})
		}
	}
	// Encoding failures are impossible for this fixed in-memory image, so
	// the error is intentionally ignored.
	var buf sizeWriter
	_ = jpeg.Encode(&buf, img, nil)
	return buf.data
}

type sizeWriter struct{ data []byte }

func (w *sizeWriter) Write(p []byte) (int, error) {
	w.data = append(w.data, p...)
	return len(p), nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
