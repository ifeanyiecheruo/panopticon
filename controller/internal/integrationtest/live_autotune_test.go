package integrationtest

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/jpeg"
	"math"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"panopticon-controller/internal/phoneapi"
)

// TestLiveAutoTune checks a real phone fine-tuning its own auto for an area of
// interest - with no controller driving it, which is the point
// (docs/design/decisions/0017-area-of-interest-fine-tuning.md). It runs only
// when pointed at one:
//
//	PANOPTICON_LIVE_PHONE_URL=http://<phone>:8080
//	PANOPTICON_LIVE_TOKEN_FILE=<file holding a paired controller's bearer token>
//	PANOPTICON_LIVE_AREA=l,t,r,b   (frame coordinates, 0..1 top down)
//
//	PANOPTICON_LIVE_WATCH=2m       (how long to watch it tune; optional)
//
// The phone must be recording or live. The test draws the area, checks that
// auto refuses a manual control, watches auto settle and the phone nudge it -
// failing on any jump in exposure or focus - checks the result against a still
// of its own, and puts the camera back as it found it.
func TestLiveAutoTune(t *testing.T) {
	base := os.Getenv("PANOPTICON_LIVE_PHONE_URL")
	tokFile := os.Getenv("PANOPTICON_LIVE_TOKEN_FILE")
	if base == "" || tokFile == "" {
		t.Skip("set PANOPTICON_LIVE_PHONE_URL and PANOPTICON_LIVE_TOKEN_FILE to test a live phone")
	}
	tok, err := os.ReadFile(tokFile)
	if err != nil {
		t.Fatal(err)
	}
	area := liveArea(t, os.Getenv("PANOPTICON_LIVE_AREA"))
	ctx := context.Background()
	client := phoneapi.New(base, strings.TrimSpace(string(tok)))

	orig, err := client.CameraState(ctx)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		k, m := orig.Keys, orig.ManualControlEnabled
		if _, err := client.SetCameraState(context.Background(), phoneapi.CameraStatePatch{ManualControlEnabled: &m, Keys: &k}); err != nil {
			t.Errorf("restoring the camera: %v", err)
		}
	})

	// Draw the area in auto.
	auto := false
	keys := phoneapi.CameraControlKeys{ZoomRatio: orig.Keys.ZoomRatio, ZoomViewNorm: orig.Keys.ZoomViewNorm, AESelectNorm: &area, AFSelectNorm: &area}
	if _, err := client.SetCameraState(ctx, phoneapi.CameraStatePatch{ManualControlEnabled: &auto, Keys: &keys}); err != nil {
		t.Fatal(err)
	}

	// Auto refuses a manual control.
	st, err := client.CameraState(ctx)
	if err != nil {
		t.Fatal(err)
	}
	bad := st.Keys
	iso := 400
	bad.SensorSensitivityISO = &iso
	_, err = client.SetCameraState(ctx, phoneapi.CameraStatePatch{Keys: &bad})
	var conflict *phoneapi.ControlConflictError
	if !errors.As(err, &conflict) || conflict.Key != "sensorSensitivityIso" {
		t.Errorf("a manual control in auto: want a 409 naming sensorSensitivityIso, got %v", err)
	}

	watch := 2 * time.Minute
	if v := os.Getenv("PANOPTICON_LIVE_WATCH"); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			watch = d
		}
	}
	res := watchTuning(t, client, watch)
	if res == nil {
		return
	}
	logStatus(t, "after "+watch.String(), res)
	if res.Contrast != nil && res.PhoneContrast != nil && *res.Contrast+0.05 < *res.PhoneContrast {
		t.Errorf("less contrast than auto alone gave: %.2f < %.2f", *res.Contrast, *res.PhoneContrast)
	}
	if res.ExposureBiasEV != nil && math.Abs(*res.ExposureBiasEV) > 1.01 {
		t.Errorf("exposure biased %.2f EV from auto's: a fine-tune is at most 1 EV", *res.ExposureBiasEV)
	}

	// An independent look: the area's tones in a still now, against what the phone reported.
	st, err = client.CameraState(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if st.AreaNorm != nil && res.Contrast != nil {
		snap, err := client.CameraFreshSnapshot(ctx)
		if err != nil {
			t.Fatal(err)
		}
		bits := areaEntropy(t, snap.JPEG, *st.AreaNorm)
		t.Logf("a still now: %.2f bits in the area (phone reported %.2f)", bits, *res.Contrast)
		if math.Abs(bits-*res.Contrast) > 0.4 {
			t.Errorf("the still's area contrast %.2f bits is far from the reported %.2f", bits, *res.Contrast)
		}
	}

	// Start again from auto on demand.
	if err := client.RetuneArea(ctx); err != nil {
		t.Fatalf("RetuneArea: %v", err)
	}
	if res := watchTuning(t, client, 30*time.Second); res != nil {
		logStatus(t, "restarted", res)
	}
}

// The most the camera's own exposure and focus may move between one-second
// samples while tuning: tuning nudges, it never jumps.
const (
	maxExposureJumpStops = 0.5
	maxFocusJumpDiopters = 0.25
)

// watchTuning waits for the phone to settle auto on the area and start tuning,
// then watches it for [watch], failing on any jump in what the camera reports.
// Returns the last status.
func watchTuning(t *testing.T, client *phoneapi.Client, watch time.Duration) *phoneapi.AutoTuningStatus {
	t.Helper()
	began := time.Now()
	seen := ""
	var tuningSince time.Time
	var last *phoneapi.AutoTuningStatus
	var prev *phoneapi.CaptureReport
	nudges := 0
	for {
		st, err := client.CameraState(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		at := st.AutoTuning
		if at == nil {
			if time.Since(began) > 15*time.Second {
				t.Skip("the phone reports no autoTuning: its app predates on-phone tuning")
			}
			time.Sleep(time.Second)
			continue
		}
		if at.State != seen {
			t.Logf("%5.1fs %s %s", time.Since(began).Seconds(), at.State, at.Message)
			seen = at.State
		}
		switch at.State {
		case "failed", "unsupported":
			t.Fatalf("tuning %s: %s", at.State, at.Message)
		case "tuning":
			if tuningSince.IsZero() {
				tuningSince = time.Now()
				logStatus(t, "baseline", at)
			}
			if last != nil && at.TunedAtMs != nil && (last.TunedAtMs == nil || *at.TunedAtMs != *last.TunedAtMs) {
				nudges++
				logStatus(t, "kept a nudge", at)
			}
			last = at
			if r := st.Reported; r != nil && prev != nil {
				if s0, s1 := stops(prev), stops(r); s0 != 0 && s1 != 0 && math.Abs(s1-s0) > maxExposureJumpStops {
					t.Errorf("exposure jumped %.2f stops in a second while tuning", s1-s0)
				}
				if prev.FocusDistanceDiopters != nil && r.FocusDistanceDiopters != nil &&
					math.Abs(*r.FocusDistanceDiopters-*prev.FocusDistanceDiopters) > maxFocusJumpDiopters {
					t.Errorf("focus jumped %.3f -> %.3f D in a second while tuning", *prev.FocusDistanceDiopters, *r.FocusDistanceDiopters)
				}
			}
			if st.Reported != nil {
				prev = st.Reported
			}
			if time.Since(tuningSince) >= watch {
				t.Logf("%d nudges kept in %v", nudges, watch)
				return last
			}
		}
		if tuningSince.IsZero() && time.Since(began) > time.Minute {
			t.Fatalf("auto didn't settle on the area within a minute (state %q)", seen)
		}
		time.Sleep(time.Second)
	}
}

// stops is the light a capture gathered, as log2(exposure time x ISO); 0 when unreported.
func stops(r *phoneapi.CaptureReport) float64 {
	if r.ExposureTimeNs == nil || r.SensitivityISO == nil || *r.ExposureTimeNs <= 0 || *r.SensitivityISO <= 0 {
		return 0
	}
	return math.Log2(float64(*r.ExposureTimeNs) * float64(*r.SensitivityISO))
}

func logStatus(t *testing.T, what string, s *phoneapi.AutoTuningStatus) {
	f := func(p *float64) string {
		if p == nil {
			return "-"
		}
		return strconv.FormatFloat(*p, 'f', 2, 64)
	}
	focus := "autofocus"
	if s.FocusDistanceDiopters != nil {
		focus = strconv.FormatFloat(*s.FocusDistanceDiopters, 'f', 3, 64) + "D"
	}
	t.Logf("%s: exposure bias %s EV, contrast %s bits (auto alone %s); focus %s, sharpness %s (autofocus %s)",
		what, f(s.ExposureBiasEV), f(s.Contrast), f(s.PhoneContrast), focus, f(s.Sharpness), f(s.PhoneSharpness))
}

// areaEntropy is the entropy of the area's luma histogram in a JPEG still.
func areaEntropy(t *testing.T, jpg []byte, a phoneapi.RectNorm) float64 {
	img, err := jpeg.Decode(bytes.NewReader(jpg))
	if err != nil {
		t.Fatal(err)
	}
	yc, ok := img.(*image.YCbCr)
	if !ok {
		t.Fatalf("still is %T, want YCbCr", img)
	}
	b := yc.Bounds()
	w, h := b.Dx(), b.Dy()
	var hist [256]int
	n := 0
	for y := int(a.T * float64(h)); y < int(a.B*float64(h)); y++ {
		for x := int(a.L * float64(w)); x < int(a.R*float64(w)); x++ {
			hist[yc.Y[yc.YOffset(b.Min.X+x, b.Min.Y+y)]]++
			n++
		}
	}
	var bits float64
	for _, c := range hist {
		if c > 0 {
			q := float64(c) / float64(n)
			bits -= q * math.Log2(q)
		}
	}
	return bits
}

func liveArea(t *testing.T, s string) phoneapi.RectNorm {
	if s == "" {
		return phoneapi.RectNorm{L: 0.35, T: 0.35, R: 0.65, B: 0.65}
	}
	f := strings.Split(s, ",")
	if len(f) != 4 {
		t.Fatalf("PANOPTICON_LIVE_AREA wants l,t,r,b; got %q", s)
	}
	var v [4]float64
	for i := range f {
		x, err := strconv.ParseFloat(strings.TrimSpace(f[i]), 64)
		if err != nil {
			t.Fatal(err)
		}
		v[i] = x
	}
	return phoneapi.RectNorm{L: v[0], T: v[1], R: v[2], B: v[3]}
}
