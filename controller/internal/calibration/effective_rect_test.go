package calibration

import (
	"math"
	"testing"

	"panopticon-controller/internal/dbstore"
)

// A synthetic two-resolution zoom map for camera "0": crop shrinks linearly
// with the requested ratio, ratio always honoured, and (unless overridden)
// position honoured.
const syntheticZoomResult = `{
  "runId": "cal-x", "runAtMs": 1,
  "deviceIdentity": {"manufacturer": "Google", "model": "Pixel 6", "device": "oriole", "appVersionName": "0.1.0"},
  "cameras": {
    "0": {
      "deviceIdentity": {"cameraId": "0", "facing": "back"},
      "opticalRange": {"lo": 1.0, "hi": 2.0}, "digitalRange": {"lo": 2.0, "hi": 8.0},
      "crossoverRatio": 2.0, "crossoverMethod": "active-physical-id",
      "positionHonored": true, "positionFailRatios": [],
      "qualityCollapseRatio": 6.0,
      "perResolution": {
        "1920x1080": {"width": 1920, "height": 1080, "samples": [
          {"requestedRatio": 1.0, "reportedRatio": 1.0, "ratioHonored": true,
           "requestedCropNorm": {"l":0,"t":0,"r":1,"b":1}, "effectiveCropNorm": {"l":0,"t":0,"r":1,"b":1}},
          {"requestedRatio": 2.0, "reportedRatio": 2.0, "ratioHonored": true,
           "requestedCropNorm": {"l":0.25,"t":0.25,"r":0.75,"b":0.75}, "effectiveCropNorm": {"l":0.25,"t":0.25,"r":0.75,"b":0.75}},
          {"requestedRatio": 4.0, "reportedRatio": 4.0, "ratioHonored": true,
           "requestedCropNorm": {"l":0.375,"t":0.375,"r":0.625,"b":0.625}, "effectiveCropNorm": {"l":0.375,"t":0.375,"r":0.625,"b":0.625}}
        ]},
        "1280x720": {"width": 1280, "height": 720, "samples": [
          {"requestedRatio": 1.0, "reportedRatio": 1.0, "ratioHonored": true,
           "requestedCropNorm": {"l":0,"t":0,"r":1,"b":1}, "effectiveCropNorm": {"l":0,"t":0,"r":1,"b":1}},
          {"requestedRatio": 4.0, "reportedRatio": 4.0, "ratioHonored": true,
           "requestedCropNorm": {"l":0.375,"t":0.375,"r":0.625,"b":0.625}, "effectiveCropNorm": {"l":0.375,"t":0.375,"r":0.625,"b":0.625}}
        ]}
      },
      "steps": {"zoom-map": {"checksTotal": 3, "checksPassed": 3}}
    }
  }
}`

func entry(json string) dbstore.Calibration {
	return dbstore.Calibration{ManufacturerModel: "Google|Pixel 6", ResultJSON: json}
}

func almost(a, b float64) bool { return math.Abs(a-b) < 1e-6 }

func TestEffectiveRect_InterpolatesBetweenBracketingSamples(t *testing.T) {
	// Requested 3.0x sits halfway between the 2.0x and 4.0x samples at 1920x1080.
	got, err := EffectiveRect(entry(syntheticZoomResult), "0", 1920, 1080, 3.0, 0.5, 0.5)
	if err != nil {
		t.Fatalf("EffectiveRect: %v", err)
	}
	if !almost(got.HonoredZoom, 3.0) {
		t.Errorf("honoredZoom = %v, want 3.0", got.HonoredZoom)
	}
	// halfway between l=0.25 and l=0.375 => 0.3125
	if !almost(got.EffectiveRectNorm.L, 0.3125) || !almost(got.EffectiveRectNorm.R, 0.6875) {
		t.Errorf("effective rect = %+v, want L≈0.3125 R≈0.6875", got.EffectiveRectNorm)
	}
	if !got.PositionHonored {
		t.Errorf("expected positionHonored true")
	}
}

func TestEffectiveRect_HonoredPositionShiftsToRequestedCentre(t *testing.T) {
	// At 4.0x the crop is 0.25 wide; centre it near the top-left.
	got, err := EffectiveRect(entry(syntheticZoomResult), "0", 1920, 1080, 4.0, 0.2, 0.2)
	if err != nil {
		t.Fatalf("EffectiveRect: %v", err)
	}
	w := got.EffectiveRectNorm.R - got.EffectiveRectNorm.L
	if !almost(w, 0.25) {
		t.Errorf("width changed: %v", w)
	}
	cx := (got.EffectiveRectNorm.L + got.EffectiveRectNorm.R) / 2
	if !almost(cx, 0.2) {
		t.Errorf("centre x = %v, want 0.2", cx)
	}
}

func TestEffectiveRect_RecentresWhenPositionNotHonored(t *testing.T) {
	notHonored := replaceOnce(syntheticZoomResult, `"positionHonored": true`, `"positionHonored": false`)
	got, err := EffectiveRect(entry(notHonored), "0", 1920, 1080, 4.0, 0.1, 0.1)
	if err != nil {
		t.Fatalf("EffectiveRect: %v", err)
	}
	cx := (got.EffectiveRectNorm.L + got.EffectiveRectNorm.R) / 2
	cy := (got.EffectiveRectNorm.T + got.EffectiveRectNorm.B) / 2
	if !almost(cx, 0.5) || !almost(cy, 0.5) {
		t.Errorf("expected recentred to (0.5,0.5), got (%v,%v)", cx, cy)
	}
	if got.Note == "" {
		t.Errorf("expected an explanatory note when position isn't honoured")
	}
}

func TestEffectiveRect_FallsBackToNearestResolution(t *testing.T) {
	// 1000x562 isn't in the map; nearest by area is 1280x720.
	got, err := EffectiveRect(entry(syntheticZoomResult), "0", 1000, 562, 4.0, 0.5, 0.5)
	if err != nil {
		t.Fatalf("EffectiveRect: %v", err)
	}
	if !almost(got.EffectiveRectNorm.L, 0.375) {
		t.Errorf("expected the 1280x720 sample, got L=%v", got.EffectiveRectNorm.L)
	}
}

func TestEffectiveRect_ClampsOutOfRangeZoom(t *testing.T) {
	got, err := EffectiveRect(entry(syntheticZoomResult), "0", 1920, 1080, 99.0, 0.5, 0.5)
	if err != nil {
		t.Fatalf("EffectiveRect: %v", err)
	}
	// Clamped to the 4.0x sample.
	if !almost(got.EffectiveRectNorm.L, 0.375) {
		t.Errorf("expected clamp to max sample, got L=%v", got.EffectiveRectNorm.L)
	}
}

// replaceOnce is strings.Replace with n=1 without importing strings into the test.
func replaceOnce(s, old, new string) string {
	i := indexOf(s, old)
	if i < 0 {
		return s
	}
	return s[:i] + new + s[i+len(old):]
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}
