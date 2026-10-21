package main

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"math"
	"net/http"
	"strconv"
	"time"
)

// Stills while recording (docs/design/http-api.md "Stills while recording").
//
// The mock has no camera, so a still is a test pattern - a grid with a ball
// circling the frame - cropped to the current zoomViewNorm, which is enough to
// see the controller's still view refresh and a zoom land on it.

const (
	stillW = 640
	stillH = 360
)

// handleCameraSnapshot serves GET /api/camera/snapshot: 409 outside record
// mode, 503 while overheated, otherwise a JPEG.
func (s *server) handleCameraSnapshot(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	s.mu.Lock()
	mode, overheated := s.mode, s.overheated
	view := fullView()
	if s.manualControlEnabled {
		if v, ok := rectFromAny(s.controlKeys["zoomViewNorm"]); ok {
			view = v
		}
	}
	s.mu.Unlock()

	if overheated {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{
			"error": "phone is overheated; stills are unavailable until it cools",
		})
		return
	}
	if mode != "record" {
		writeJSON(w, http.StatusConflict, map[string]string{
			"error": "not recording: stills are served in record mode only (use /live/* in live mode)",
		})
		return
	}

	now := time.Now()
	var buf bytes.Buffer
	_ = jpeg.Encode(&buf, renderStill(view, now), &jpeg.Options{Quality: 75})
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Captured-At-Ms", strconv.FormatInt(now.UnixMilli(), 10))
	w.Write(buf.Bytes())
}

// renderStill draws the part of the un-zoomed scene inside view (0..1 over
// the full frame), so a zoom crops and magnifies it the way the phone's would.
func renderStill(view rectNorm, now time.Time) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, stillW, stillH))
	// The ball circles the frame once every 20s, so successive stills differ.
	angle := float64(now.UnixMilli()%20000) / 20000 * 2 * math.Pi
	ballX, ballY := 0.5+0.3*math.Cos(angle), 0.5+0.3*math.Sin(angle)
	const ballR = 0.04
	for py := 0; py < stillH; py++ {
		for px := 0; px < stillW; px++ {
			// Scene coordinates of this pixel, 0..1 over the un-zoomed frame.
			x := view.L + (float64(px)+0.5)/stillW*(view.R-view.L)
			y := view.T + (float64(py)+0.5)/stillH*(view.B-view.T)
			c := color.RGBA{R: uint8(20 + 60*x), G: uint8(40 + 80*y), B: 70, A: 255}
			if math.Mod(x*16, 1) < 0.04 || math.Mod(y*9, 1) < 0.04 {
				c = color.RGBA{R: 90, G: 200, B: 190, A: 255}
			}
			// Aspect-corrected distance, so the ball stays round.
			dx, dy := (x-ballX)*16/9, y-ballY
			if dx*dx+dy*dy < ballR*ballR {
				c = color.RGBA{R: 255, G: 95, B: 86, A: 255}
			}
			img.SetRGBA(px, py, c)
		}
	}
	return img
}
