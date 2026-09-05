// Command mockphone is a throwaway dev/test helper — its own top-level tool, not part of the
// shipped controller — implementing just enough of docs/implementation/phone-http-api.md to
// exercise the controller's Add-phone flow and sync loop end to end without a real Android phone
// available.
//
// Routes implemented: POST/DELETE /api/pair, GET /api/status, GET /api/device,
// GET /api/config, GET /api/build-info, GET /api/clips, GET
// /api/clips/:filename/file, GET /api/clips/:filename/thumbnail,
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

type clip struct {
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
	clips        []clip
	deviceName   string
	manufacturer string
	model        string

	// Calibration: a sweep is faked as a short timed "running" window, after
	// which /status reports "completed" and /result serves a canned body.
	calRunID      string
	calStartedAt  time.Time
	calSweepDurMs int64
}

func main() {
	addr := flag.String("addr", ":8091", "listen address")
	invite := flag.String("invite", "TEST-INVITE-CODE", "invite code this mock phone accepts (once)")
	numClips := flag.Int("clips", 3, "number of fake pre-existing clips to seed")
	flag.Parse()

	s := &server{
		invite:        *invite,
		controllers:   make(map[string]pairedController),
		deviceName:    "Mock Porch Cam",
		manufacturer:  "Google",
		model:         "Pixel 6",
		calSweepDurMs: 6000,
	}
	s.seedClips(*numClips)

	mux := http.NewServeMux()
	mux.HandleFunc("/api/pair", s.handlePair)
	mux.HandleFunc("/api/status", s.withAuth(s.handleStatus))
	mux.HandleFunc("/api/device", s.withAuth(s.handleDevice))
	mux.HandleFunc("/api/config", s.withAuth(s.handleConfig))
	mux.HandleFunc("/api/build-info", s.withAuth(s.handleBuildInfo))
	mux.HandleFunc("/api/clips", s.withAuth(s.handleClipsList))
	mux.HandleFunc("/api/clips/", s.withAuth(s.handleClipFileOrThumb))
	mux.HandleFunc("/api/calibration/start", s.withAuth(s.handleCalibrationStart))
	mux.HandleFunc("/api/calibration/status", s.withAuth(s.handleCalibrationStatus))
	mux.HandleFunc("/api/calibration/result", s.withAuth(s.handleCalibrationResult))
	mux.HandleFunc("/api/calibration/", s.withAuth(s.handleCalibrationCancel)) // DELETE /api/calibration/:runId

	log.Printf("mockphone listening on %s (invite=%s, manufacturer=%s model=%s, %d seeded clips)",
		*addr, *invite, s.manufacturer, s.model, *numClips)
	log.Fatal(http.ListenAndServe(*addr, mux))
}

func (s *server) seedClips(n int) {
	now := time.Now().UnixMilli()
	for i := 0; i < n; i++ {
		data := makeFakeMp4(fmt.Sprintf("clip %d", i))
		s.clips = append(s.clips, clip{
			filename:    fmt.Sprintf("clip_%07d.mp4", i+1),
			createdAtMs: now - int64(n-i)*60_000,
			durationMs:  8000,
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
	writeJSON(w, http.StatusOK, map[string]any{
		"mode":             "record",
		"status":           "recording",
		"cameraHealthy":    true,
		"liveViewers":      0,
		"storageUsedBytes": 4_200_000_000,
		"storageCapBytes":  64_000_000_000,
		"batteryPercent":   81,
		"charging":         true,
		"serverTimeMs":     time.Now().UnixMilli(),
	})
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

func (s *server) handleClipsList(w http.ResponseWriter, r *http.Request) {
	sinceStr := r.URL.Query().Get("since")
	since, _ := strconv.ParseInt(sinceStr, 10, 64)

	s.mu.Lock()
	defer s.mu.Unlock()
	var out []map[string]any
	for _, c := range s.clips {
		if c.createdAtMs <= since {
			continue
		}
		out = append(out, map[string]any{
			"filename":    c.filename,
			"url":         "/api/clips/" + c.filename + "/file",
			"createdAtMs": c.createdAtMs,
			"durationMs":  c.durationMs,
			"endMs":       c.createdAtMs + c.durationMs,
			"sizeBytes":   c.sizeBytes,
			"width":       c.width,
			"height":      c.height,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"clips": out})
}

func (s *server) handleClipFileOrThumb(w http.ResponseWriter, r *http.Request) {
	// Path shape: /api/clips/<filename>/file or /api/clips/<filename>/thumbnail
	rest := strings.TrimPrefix(r.URL.Path, "/api/clips/")
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) != 2 {
		w.WriteHeader(http.StatusNotFound)
		return
	}
	filename, kind := parts[0], parts[1]

	s.mu.Lock()
	var found *clip
	for i := range s.clips {
		if s.clips[i].filename == filename {
			found = &s.clips[i]
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
		"runId":   "cal-mock",
		"runAtMs": runAt.UnixMilli(),
		"cameras": map[string]any{
			"0": map[string]any{
				"deviceIdentity": map[string]any{"cameraId": "0", "facing": "back", "focalLengthMm": 6.81},
				"steps": map[string]any{
					"crop-region":  map[string]int{"checksTotal": 6, "checksPassed": 6},
					"zoom-quality": map[string]int{"checksTotal": 15, "checksPassed": 15},
				},
			},
			"1": map[string]any{
				"deviceIdentity": map[string]any{"cameraId": "1", "facing": "front", "focalLengthMm": 2.74},
				"steps": map[string]any{
					"crop-region":  map[string]int{"checksTotal": 6, "checksPassed": 6},
					"zoom-quality": map[string]int{"checksTotal": 15, "checksPassed": 13},
				},
			},
		},
	})
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
