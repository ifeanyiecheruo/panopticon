package main

import (
	"context"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"io"
	"log"
	"math"
	"math/rand"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Real live streaming for the mock: a tiny physics sim rendered to RGBA frames,
// piped to `ffmpeg` which muxes rolling HLS segments that the mock serves at
// GET /live/live.m3u8 + GET /live/live-<n>.ts (behind the bearer token, same as
// the real phone). Needs `ffmpeg` on PATH; without it the live routes 503 and
// everything else still works.
//
// The scene: 1..5 balls launched from random points in the *frame* (not the
// controller's zoomed viewport) at random speed/angle/size/colour/weight, under
// gravity + drag, bouncing with energy loss until each settles on the floor.
// When all have stopped the sim pauses 1..5s, then relaunches. A grid scrolls
// diagonally the whole time so a zoomed-in viewer always has motion on screen.

const liveFPS = 15

var errNoFFmpeg = errors.New("ffmpeg not found on PATH")

// parseWxH turns "1920x1080" into (1920, 1080); anything else -> the 720p
// default. Keeps the sim + ffmpeg in lock-step with the phone's videoResolution.
func parseWxH(s string) (w, h int) {
	w, h = 1280, 720
	var pw, ph int
	if n, err := fmt.Sscanf(strings.ToLower(s), "%dx%d", &pw, &ph); err == nil && n == 2 && pw > 0 && ph > 0 {
		w, h = pw, ph
	}
	return
}

// ---- physics ----

type ball struct {
	x, y    float64
	vx, vy  float64
	r       float64
	weight  float64 // 0.5..3 — heavier balls shrug off drag
	col     color.RGBA
	stopped bool
}

type sim struct {
	rng      *rand.Rand
	w, h     float64 // frame size (== the camera resolution)
	balls    []ball
	grid     float64   // scroll offset in px, monotonically increasing
	pauseTil time.Time // zero = running; future = paused between rounds
}

func newSim(rng *rand.Rand, w, h int) *sim {
	s := &sim{rng: rng, w: float64(w), h: float64(h)}
	s.launch()
	return s
}

func (s *sim) launch() {
	n := 1 + s.rng.Intn(5)
	s.balls = s.balls[:0]
	for i := 0; i < n; i++ {
		speed := 350 + s.rng.Float64()*1150
		ang := s.rng.Float64() * 2 * math.Pi
		r := 16 + s.rng.Float64()*58
		s.balls = append(s.balls, ball{
			// spawn fully inside the frame so the whole disc is on screen
			x:      r + s.rng.Float64()*(s.w-2*r),
			y:      r + s.rng.Float64()*(s.h-2*r),
			vx:     speed * math.Cos(ang),
			vy:     speed * math.Sin(ang),
			r:      r,
			weight: 0.5 + s.rng.Float64()*2.5,
			col:    brightColor(s.rng),
		})
	}
	s.pauseTil = time.Time{}
}

func (s *sim) step(dt float64) {
	s.grid += 40 * dt // px/s

	if !s.pauseTil.IsZero() {
		if time.Now().Before(s.pauseTil) {
			return
		}
		s.launch()
	}

	const (
		gravity     = 950.0
		stopSpeed   = 20.0
		restitution = 0.6
		floorFric   = 0.84
	)
	allStopped := true
	for i := range s.balls {
		b := &s.balls[i]
		if b.stopped {
			continue
		}
		allStopped = false

		drag := 0.35 / b.weight
		b.vx -= b.vx * drag * dt
		b.vy -= b.vy * drag * dt
		b.vy += gravity * dt
		b.x += b.vx * dt
		b.y += b.vy * dt

		// Reflect off the frame bounds (== the camera resolution). The position
		// is clamped unconditionally so a ball can never leave the frame even at
		// a large timestep; velocity only flips when it's actually heading out,
		// which also stops edge jitter.
		if b.x < b.r {
			b.x = b.r
			if b.vx < 0 {
				b.vx = -b.vx * restitution
			}
		} else if b.x > s.w-b.r {
			b.x = s.w - b.r
			if b.vx > 0 {
				b.vx = -b.vx * restitution
			}
		}
		if b.y < b.r {
			b.y = b.r
			if b.vy < 0 {
				b.vy = -b.vy * restitution
			}
		}
		onFloor := false
		if b.y > s.h-b.r {
			b.y = s.h - b.r
			if b.vy > 0 {
				b.vy = -b.vy * restitution
			}
			b.vx *= floorFric
			onFloor = true
		}
		if onFloor && math.Hypot(b.vx, b.vy) < stopSpeed {
			b.stopped = true
			b.vx, b.vy = 0, 0
		}
	}
	if allStopped && s.pauseTil.IsZero() {
		s.pauseTil = time.Now().Add(time.Duration(1000+s.rng.Intn(4000)) * time.Millisecond)
	}
}

func brightColor(rng *rand.Rand) color.RGBA {
	// pick a hue on the wheel, keep it saturated + bright
	h := rng.Float64() * 6
	x := 1 - math.Abs(math.Mod(h, 2)-1)
	var r, g, bl float64
	switch int(h) {
	case 0:
		r, g, bl = 1, x, 0
	case 1:
		r, g, bl = x, 1, 0
	case 2:
		r, g, bl = 0, 1, x
	case 3:
		r, g, bl = 0, x, 1
	case 4:
		r, g, bl = x, 0, 1
	default:
		r, g, bl = 1, 0, x
	}
	return color.RGBA{
		R: uint8(60 + r*195),
		G: uint8(60 + g*195),
		B: uint8(60 + bl*195),
		A: 255,
	}
}

// ---- rendering (straight into the RGBA byte buffer ffmpeg consumes) ----

func (s *sim) render(img *image.RGBA) {
	W, H := img.Bounds().Dx(), img.Bounds().Dy()
	draw.Draw(img, img.Bounds(), &image.Uniform{color.RGBA{10, 14, 18, 255}}, image.Point{}, draw.Src)

	const cell = 84
	off := int(math.Mod(s.grid, cell))
	gridCol := color.RGBA{38, 52, 58, 255}
	for x := off - cell; x < W; x += cell {
		vline(img, x, gridCol)
	}
	for y := off - cell; y < H; y += cell {
		hline(img, y, gridCol)
	}

	for i := range s.balls {
		b := &s.balls[i]
		fillCircle(img, int(b.x), int(b.y), int(b.r), b.col)
	}
}

func vline(img *image.RGBA, x int, c color.RGBA) {
	W, H := img.Bounds().Dx(), img.Bounds().Dy()
	for w := 0; w < 2; w++ {
		xx := x + w
		if xx < 0 || xx >= W {
			continue
		}
		for y := 0; y < H; y++ {
			putPx(img, xx, y, c)
		}
	}
}

func hline(img *image.RGBA, y int, c color.RGBA) {
	W, H := img.Bounds().Dx(), img.Bounds().Dy()
	for w := 0; w < 2; w++ {
		yy := y + w
		if yy < 0 || yy >= H {
			continue
		}
		o := img.PixOffset(0, yy)
		for x := 0; x < W; x++ {
			img.Pix[o], img.Pix[o+1], img.Pix[o+2], img.Pix[o+3] = c.R, c.G, c.B, 255
			o += 4
		}
	}
}

func fillCircle(img *image.RGBA, cx, cy, r int, c color.RGBA) {
	if r < 1 {
		r = 1
	}
	W, H := img.Bounds().Dx(), img.Bounds().Dy()
	r2 := r * r
	for dy := -r; dy <= r; dy++ {
		yy := cy + dy
		if yy < 0 || yy >= H {
			continue
		}
		span2 := r2 - dy*dy
		if span2 < 0 {
			continue
		}
		half := int(math.Sqrt(float64(span2)))
		x0, x1 := cx-half, cx+half
		if x0 < 0 {
			x0 = 0
		}
		if x1 >= W {
			x1 = W - 1
		}
		if x0 > x1 {
			continue
		}
		o := img.PixOffset(x0, yy)
		for x := x0; x <= x1; x++ {
			img.Pix[o], img.Pix[o+1], img.Pix[o+2], img.Pix[o+3] = c.R, c.G, c.B, 255
			o += 4
		}
	}
}

func putPx(img *image.RGBA, x, y int, c color.RGBA) {
	o := img.PixOffset(x, y)
	img.Pix[o], img.Pix[o+1], img.Pix[o+2], img.Pix[o+3] = c.R, c.G, c.B, 255
}

// ---- HLS pipeline (ffmpeg subprocess) ----

type liveStream struct {
	mu       sync.Mutex
	running  bool
	dir      string
	cancel   context.CancelFunc
	done     chan struct{}
	lastPoll time.Time
	// armAt is when the camera finishes "arming" after entering live mode.
	// POST /api/live/start 503s with a retryAfterMs hint until now >= armAt.
	armAt time.Time
}

func (s *server) startLive() error {
	s.live.mu.Lock()
	defer s.live.mu.Unlock()
	if s.live.running {
		s.live.lastPoll = time.Now()
		return nil
	}

	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		return errNoFFmpeg
	}
	dir, err := os.MkdirTemp("", "mockphone-live-")
	if err != nil {
		return err
	}

	s.mu.Lock()
	w, h := parseWxH(s.videoResolution)
	s.mu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	args := []string{
		"-hide_banner", "-loglevel", "error",
		"-f", "rawvideo", "-pix_fmt", "rgba",
		"-s", fmt.Sprintf("%dx%d", w, h), "-r", strconv.Itoa(liveFPS),
		"-i", "pipe:0",
		"-an",
		"-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency",
		"-pix_fmt", "yuv420p",
		"-g", strconv.Itoa(liveFPS), "-keyint_min", strconv.Itoa(liveFPS),
		"-f", "hls", "-hls_time", "1", "-hls_list_size", "6",
		"-hls_flags", "delete_segments+append_list+omit_endlist",
		"-hls_segment_type", "mpegts",
		"-hls_segment_filename", filepath.Join(dir, "live-%d.ts"),
		filepath.Join(dir, "live.m3u8"),
	}
	cmd := exec.CommandContext(ctx, ffmpeg, args...)
	cmd.Stderr = os.Stderr
	stdin, err := cmd.StdinPipe()
	if err != nil {
		cancel()
		os.RemoveAll(dir)
		return err
	}
	if err := cmd.Start(); err != nil {
		cancel()
		os.RemoveAll(dir)
		return err
	}

	s.live.running = true
	s.live.dir = dir
	s.live.cancel = cancel
	s.live.done = make(chan struct{})
	s.live.lastPoll = time.Now()

	go s.pumpFrames(ctx, stdin, cmd, dir, w, h)
	log.Printf("mockphone live: streaming %dx%d@%dfps from %s", w, h, liveFPS, dir)
	return nil
}

func (s *server) pumpFrames(ctx context.Context, stdin io.WriteCloser, cmd *exec.Cmd, dir string, w, h int) {
	defer close(s.live.done)

	rng := rand.New(rand.NewSource(time.Now().UnixNano()))
	sm := newSim(rng, w, h)
	img := image.NewRGBA(image.Rect(0, 0, w, h))

	const substeps = 3
	dt := 1.0 / float64(liveFPS)
	frame := time.NewTicker(time.Second / liveFPS)
	defer frame.Stop()
	watch := time.NewTicker(5 * time.Second)
	defer watch.Stop()

	cleanup := func() {
		_ = stdin.Close()
		_ = cmd.Wait()
		os.RemoveAll(dir)
	}

	for {
		select {
		case <-ctx.Done():
			cleanup()
			return
		case <-watch.C:
			s.live.mu.Lock()
			idle := time.Since(s.live.lastPoll) > 25*time.Second
			s.live.mu.Unlock()
			if idle {
				log.Printf("mockphone live: no viewers for 25s — stopping")
				go s.stopLive()
			}
		case <-frame.C:
			for i := 0; i < substeps; i++ {
				sm.step(dt / substeps)
			}
			sm.render(img)
			if _, err := stdin.Write(img.Pix); err != nil {
				cleanup()
				return
			}
		}
	}
}

func (s *liveStream) isRunning() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.running
}

func (s *server) stopLive() {
	s.live.mu.Lock()
	if !s.live.running {
		s.live.mu.Unlock()
		return
	}
	cancel, done := s.live.cancel, s.live.done
	s.live.running = false
	s.live.cancel = nil
	s.live.dir = ""
	s.live.mu.Unlock()

	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
	}
}

// ---- HTTP handlers ----

func (s *server) handleLiveStart(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	s.mu.Lock()
	mode := s.mode
	s.mu.Unlock()
	if mode != "live" {
		writeJSON(w, http.StatusConflict, map[string]string{"error": "not in live mode"})
		return
	}
	s.live.mu.Lock()
	arming := time.Now().Before(s.live.armAt)
	s.live.mu.Unlock()
	if arming {
		w.Header().Set("Retry-After", "2")
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{
			"error":        "camera still starting",
			"retryAfterMs": 2000,
		})
		return
	}
	if err := s.startLive(); err != nil {
		if errors.Is(err, errNoFFmpeg) {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{
				"error": "live camera unavailable: install ffmpeg for mock live streaming",
			})
			return
		}
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "live camera unavailable: " + err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"started": true, "viewerCount": 1})
}

func (s *server) handleLiveStop(w http.ResponseWriter, r *http.Request) {
	s.stopLive()
	writeJSON(w, http.StatusOK, map[string]any{"stopped": true, "viewerCount": 0})
}

func (s *server) handleLiveMedia(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/live/")
	if name == "" || strings.ContainsAny(name, "/\\") || strings.Contains(name, "..") {
		http.NotFound(w, r)
		return
	}

	s.live.mu.Lock()
	dir, running := s.live.dir, s.live.running
	s.live.lastPoll = time.Now()
	s.live.mu.Unlock()

	if !running || dir == "" {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "live not started"})
		return
	}

	data, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		// segment rolled out of the window, or playlist not written yet
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "live segment unavailable"})
		return
	}
	if strings.HasSuffix(name, ".m3u8") {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	} else {
		w.Header().Set("Content-Type", "video/mp2t")
	}
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(data)
}
