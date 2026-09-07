// Command mockphone is a throwaway dev/test helper — its own top-level tool, not part of the
// shipped controller — implementing just enough of docs/implementation/phone-http-api.md to
// exercise the controller's Add-phone flow and sync loop end to end without a real Android phone
// available.
//
// Routes implemented: POST/DELETE /api/pair, GET /api/status, GET /api/device,
// GET /api/config, GET /api/build-info, GET /api/segments, GET
// /api/segments/:filename/file, GET /api/segments/:filename/thumbnail,
// POST /api/calibration/start, GET /api/calibration/status,
// DELETE /api/calibration/:runId, GET /api/calibration/result,
// GET /api/cameras, POST /api/cameras/active, GET /api/camera/capabilities,
// GET/POST /api/camera/state, POST /api/live/start, DELETE /api/live/stop,
// GET /live/live.m3u8 + GET /live/live-<n>.ts (a real HLS stream of a small
// physics sim, muxed by ffmpeg — see live.go).
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

	// Editable device config (GET/POST /api/config).
	motionSensitivity  string
	storageCapBytes    int64
	ringBufferMaxAgeMs int64
	rotationDegrees    int
	videoResolution    string // "<w>x<h>" — record/broadcast size (via /api/camera/state)

	// Calibration: a sweep is faked as a short timed "running" window, after
	// which /status reports "completed" and /result serves a canned body.
	calRunID      string
	calStartedAt  time.Time
	calSweepDurMs int64

	// Camera selection + manual controls: a fixed two-camera device; state is
	// echoed straight back so the controller's client + bindings can be
	// exercised without a real HAL.
	activeCameraID       string
	manualControlEnabled bool
	controlKeys          map[string]any

	// Live HLS stream (physics sim -> ffmpeg -> rolling .ts). See live.go.
	live liveStream
}

func main() {
	addr := flag.String("addr", ":8091", "listen address")
	invite := flag.String("invite", "TEST-INVITE-CODE", "invite code this mock phone accepts (once)")
	numSegments := flag.Int("segments", 6, "number of fake pre-existing segments to seed (as two runs split by a gap)")
	flag.Parse()

	s := &server{
		invite:         *invite,
		controllers:    make(map[string]pairedController),
		deviceName:     "Mock Porch Cam",
		manufacturer:   "Google",
		model:          "Pixel 6",
		mode:           "record",
		motionSensitivity:  "medium",
		storageCapBytes:    64_000_000_000,
		ringBufferMaxAgeMs: 604_800_000,
		videoResolution:    "1280x720",
		calSweepDurMs:  6000,
		activeCameraID: "0",
		controlKeys:    map[string]any{},
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
	mux.HandleFunc("/api/cameras", s.withAuth(s.handleCameras))
	mux.HandleFunc("/api/cameras/active", s.withAuth(s.handleCamerasActive))
	mux.HandleFunc("/api/camera/capabilities", s.withAuth(s.handleCameraCapabilities))
	mux.HandleFunc("/api/camera/state", s.withAuth(s.handleCameraState))
	mux.HandleFunc("/api/live/start", s.withAuth(s.handleLiveStart))
	mux.HandleFunc("/api/live/stop", s.withAuth(s.handleLiveStop))
	mux.HandleFunc("/live/", s.withAuth(s.handleLiveMedia))

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
	leftLive := false
	switch body.Mode {
	case "record", "standby":
		leftLive = s.mode == "live"
		s.mode = body.Mode
	case "live":
		if s.mode == "record" {
			s.mu.Unlock()
			writeJSON(w, http.StatusConflict, map[string]string{"error": "stop recording first: POST /api/mode {\"mode\":\"standby\"}"})
			return
		}
		s.mode = "live"
	default:
		s.mu.Unlock()
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "mode must be record|standby|live"})
		return
	}
	mode := s.mode
	s.mu.Unlock()

	if leftLive {
		s.stopLive()
	}
	writeJSON(w, http.StatusOK, map[string]string{"mode": mode})
}

func (s *server) handleDevice(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"manufacturer": s.manufacturer,
		"model":        s.model,
		"device":       "mockdevice",
	})
}

func (s *server) handleConfig(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if r.Method == http.MethodPost {
		var patch struct {
			DeviceName         *string `json:"deviceName"`
			MotionSensitivity  *string `json:"motionSensitivity"`
			StorageCapBytes    *int64  `json:"storageCapBytes"`
			RingBufferMaxAgeMs *int64  `json:"ringBufferMaxAgeMs"`
		}
		if err := json.NewDecoder(r.Body).Decode(&patch); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad config body"})
			return
		}
		if patch.DeviceName != nil {
			s.deviceName = *patch.DeviceName
		}
		if patch.MotionSensitivity != nil {
			s.motionSensitivity = *patch.MotionSensitivity
		}
		if patch.StorageCapBytes != nil {
			s.storageCapBytes = *patch.StorageCapBytes
		}
		if patch.RingBufferMaxAgeMs != nil {
			s.ringBufferMaxAgeMs = *patch.RingBufferMaxAgeMs
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"deviceName":         s.deviceName,
		"motionSensitivity":  s.motionSensitivity,
		"storageCapBytes":    s.storageCapBytes,
		"ringBufferMaxAgeMs": s.ringBufferMaxAgeMs,
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

// ---- camera selection + manual controls ----
//
// A fixed two-camera device ("0" wide, "2" ultra-wide). Capabilities are a
// canned MANUAL_SENSOR-capable blob; state is echoed straight back. Just enough
// to exercise the controller's phoneapi client + Wails bindings.

func (s *server) mockCameras() []map[string]any {
	s.mu.Lock()
	active := s.activeCameraID
	s.mu.Unlock()
	return []map[string]any{
		{"cameraId": "0", "facing": "back", "label": "Wide (auto)", "focalLengthMm": 6.81, "isActive": active == "0"},
		{"cameraId": "0:2", "facing": "back", "label": "Ultra-wide", "focalLengthMm": 1.55, "isActive": active == "0:2"},
		{"cameraId": "1", "facing": "front", "label": "Front (auto)", "focalLengthMm": 2.5, "isActive": active == "1"},
	}
}

func (s *server) knownCamera(id string) bool {
	return id == "0" || id == "0:2" || id == "1"
}

func (s *server) handleCameras(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"cameras": s.mockCameras()})
}

func (s *server) handleCamerasActive(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		CameraID string `json:"cameraId"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)
	if !s.knownCamera(body.CameraID) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown cameraId '" + body.CameraID + "'"})
		return
	}
	s.mu.Lock()
	s.activeCameraID = body.CameraID
	s.mu.Unlock()
	writeJSON(w, http.StatusOK, map[string]string{"activeCameraId": body.CameraID})
}

// mockOutputResolutions is the set POST /api/camera/state accepts for videoResolution.
var mockOutputResolutions = map[string]bool{"1920x1080": true, "1280x720": true, "854x480": true}

func (s *server) handleCameraCapabilities(w http.ResponseWriter, r *http.Request) {
	id := r.URL.Query().Get("cameraId")
	if id == "" {
		s.mu.Lock()
		id = s.activeCameraID
		s.mu.Unlock()
	}
	if !s.knownCamera(id) {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "unknown cameraId '" + id + "'"})
		return
	}
	physicalIDs := []string{}
	if id == "0" {
		physicalIDs = []string{"2"}
	}
	writeJSON(w, http.StatusOK, map[string]any{
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
		"maxAeRegions":              3,
		"maxAfRegions":              1,
		"outputResolutions":         []string{"1920x1080", "1280x720", "854x480"},
		"physicalCameraIds":         physicalIDs,
		"croppingType":              "FREEFORM",
		"activeArrayWidth":          4032,
		"activeArrayHeight":         3024,
	})
}

func (s *server) handleCameraState(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		var body struct {
			ManualControlEnabled *bool          `json:"manualControlEnabled"`
			RotationDegrees      *int           `json:"rotationDegrees"`
			VideoResolution      *string        `json:"videoResolution"`
			Keys                 map[string]any `json:"keys"`
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		if body.RotationDegrees != nil {
			switch *body.RotationDegrees {
			case 0, 90, 180, 270:
			default:
				writeJSON(w, http.StatusBadRequest, map[string]string{
					"error": "must be one of 0/90/180/270", "key": "rotationDegrees",
				})
				return
			}
		}
		if body.VideoResolution != nil && !mockOutputResolutions[*body.VideoResolution] {
			writeJSON(w, http.StatusBadRequest, map[string]string{
				"error": "must be one of 1920x1080 / 1280x720 / 854x480", "key": "videoResolution",
			})
			return
		}
		// Reject obviously bad keys so the controller's 400-path is exercised.
		if body.Keys != nil {
			if z, ok := body.Keys["zoomRatio"].(float64); ok && (z < 1.0 || z > 8.0) {
				writeJSON(w, http.StatusBadRequest, map[string]string{
					"error": "must be in 1.0..8.0", "key": "zoomRatio",
				})
				return
			}
			if m, ok := body.Keys["awbMode"].(float64); ok {
				allowed := map[int]bool{0: true, 1: true, 2: true, 5: true, 6: true}
				if !allowed[int(m)] {
					writeJSON(w, http.StatusBadRequest, map[string]string{
						"error": "must be one of [0 1 2 5 6]", "key": "awbMode",
					})
					return
				}
			}
			if m, ok := body.Keys["opticalStabilizationMode"].(float64); ok && m != 0 && m != 1 {
				writeJSON(w, http.StatusBadRequest, map[string]string{
					"error": "must be one of [0 1]", "key": "opticalStabilizationMode",
				})
				return
			}
		}
		s.mu.Lock()
		if body.ManualControlEnabled != nil {
			s.manualControlEnabled = *body.ManualControlEnabled
		}
		if body.RotationDegrees != nil {
			s.rotationDegrees = *body.RotationDegrees
		}
		resChanged := false
		if body.VideoResolution != nil && *body.VideoResolution != s.videoResolution {
			s.videoResolution = *body.VideoResolution
			resChanged = true
		}
		if body.Keys != nil {
			s.controlKeys = body.Keys
		}
		live := s.mode == "live"
		s.mu.Unlock()

		// A size change rebuilds the pipeline; bounce a running live stream so the
		// controller re-attaches at the new resolution (mirrors a camera switch).
		if resChanged && live && s.live.isRunning() {
			s.stopLive()
			_ = s.startLive()
		}
	} else if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	keys := s.controlKeys
	if keys == nil {
		keys = map[string]any{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"cameraId":             s.activeCameraID,
		"rotationDegrees":      s.rotationDegrees,
		"videoResolution":      s.videoResolution,
		"manualControlEnabled": s.manualControlEnabled,
		"keys":                 keys,
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
