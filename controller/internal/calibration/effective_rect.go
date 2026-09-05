package calibration

import (
	"errors"
	"fmt"
	"math"
	"sort"

	"panopticon-controller/internal/dbstore"
	"panopticon-controller/internal/phoneapi"
)

// EffectiveRectResult is what the future zoom-rect picker overlays on top of
// the user's own selection: the crop the phone's HAL will actually apply for a
// requested zoom + centre, derived from the stored empirical calibration.
type EffectiveRectResult struct {
	HonoredZoom       float64           `json:"honoredZoom"`
	EffectiveRectNorm phoneapi.RectNorm `json:"effectiveRectNorm"`
	PositionHonored   bool              `json:"positionHonored"`
	ActivePhysicalID  string            `json:"activePhysicalId"`
	Note              string            `json:"note"`
}

// EffectiveRect maps a requested zoom + centre (normalised 0..1) for one
// camera at one output resolution to what will actually be honored, using the
// nearest resolution's zoom map and linear interpolation between the two
// bracketing ratio samples. No UI consumes this yet - it's the data-side of
// the deferred zoom-rect picker, unit-tested against a synthetic map.
func EffectiveRect(
	entry dbstore.Calibration,
	cameraID string,
	width, height int,
	requestedZoom float64,
	centerX, centerY float64,
) (EffectiveRectResult, error) {
	result, err := phoneapi.ParseResultSummary(entry.ResultJSON)
	if err != nil {
		return EffectiveRectResult{}, fmt.Errorf("parse stored calibration: %w", err)
	}

	cam, ok := result.Cameras[cameraID]
	if !ok {
		if cameraID == "" {
			for id := range result.Cameras {
				cam, ok = result.Cameras[id], true
				break
			}
		}
		if !ok {
			return EffectiveRectResult{}, errors.New("no calibration for that camera")
		}
	}

	zm, ok := nearestResolution(cam.PerResolution, width, height)
	if !ok || len(zm.Samples) == 0 {
		return EffectiveRectResult{}, errors.New("no zoom map for that resolution")
	}

	samples := append([]phoneapi.ZoomSample(nil), zm.Samples...)
	sort.Slice(samples, func(i, j int) bool { return samples[i].RequestedRatio < samples[j].RequestedRatio })

	lo, hi := samples[0], samples[len(samples)-1]
	z := clamp(requestedZoom, lo.RequestedRatio, hi.RequestedRatio)

	a, b := lo, hi
	for i := 0; i+1 < len(samples); i++ {
		if z >= samples[i].RequestedRatio && z <= samples[i+1].RequestedRatio {
			a, b = samples[i], samples[i+1]
			break
		}
	}
	t := 0.0
	if b.RequestedRatio > a.RequestedRatio {
		t = (z - a.RequestedRatio) / (b.RequestedRatio - a.RequestedRatio)
	}

	eff := lerpRect(a.EffectiveCropNorm, b.EffectiveCropNorm, t)
	honored := z
	if a.ReportedRatio != nil && b.ReportedRatio != nil {
		honored = lerp(*a.ReportedRatio, *b.ReportedRatio, t)
	}
	activePhysical := ""
	if a.ActivePhysicalID != nil {
		activePhysical = *a.ActivePhysicalID
	}

	out := EffectiveRectResult{
		HonoredZoom:      honored,
		PositionHonored:  cam.PositionHonored,
		ActivePhysicalID: activePhysical,
	}

	if !cam.PositionHonored {
		// The HAL recentres an off-centre crop on this model - the effective
		// view is the centred crop at the honored zoom, wherever the user drew.
		out.EffectiveRectNorm = centerRect(eff)
		out.Note = "this camera model does not honour an off-centre zoom rect — the effective view is recentred at the honoured zoom"
		return out, nil
	}

	// Position is honored: shift the (centred) effective crop to the requested
	// centre, clamped in bounds.
	out.EffectiveRectNorm = shiftRectTo(eff, clamp(centerX, 0, 1), clamp(centerY, 0, 1))
	if math.Abs(honored-requestedZoom) > 0.05*requestedZoom {
		out.Note = fmt.Sprintf("requested %.2fx, effective %.2fx (HAL quantised the zoom)", requestedZoom, honored)
	}
	return out, nil
}

// ---- helpers ----

func nearestResolution(m map[string]phoneapi.ResolutionZoomMap, w, h int) (phoneapi.ResolutionZoomMap, bool) {
	if zm, ok := m[fmt.Sprintf("%dx%d", w, h)]; ok {
		return zm, true
	}
	target := float64(w) * float64(h)
	best := phoneapi.ResolutionZoomMap{}
	bestDelta := math.MaxFloat64
	found := false
	for _, zm := range m {
		d := math.Abs(float64(zm.Width)*float64(zm.Height) - target)
		if d < bestDelta {
			best, bestDelta, found = zm, d, true
		}
	}
	return best, found
}

func clamp(v, lo, hi float64) float64 {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func lerp(a, b, t float64) float64 { return a + (b-a)*t }

func lerpRect(a, b phoneapi.RectNorm, t float64) phoneapi.RectNorm {
	return phoneapi.RectNorm{
		L: lerp(a.L, b.L, t), T: lerp(a.T, b.T, t),
		R: lerp(a.R, b.R, t), B: lerp(a.B, b.B, t),
	}
}

func centerRect(r phoneapi.RectNorm) phoneapi.RectNorm {
	w := r.R - r.L
	h := r.B - r.T
	l := 0.5 - w/2
	t := 0.5 - h/2
	return phoneapi.RectNorm{L: l, T: t, R: l + w, B: t + h}
}

func shiftRectTo(r phoneapi.RectNorm, cx, cy float64) phoneapi.RectNorm {
	w := r.R - r.L
	h := r.B - r.T
	l := clamp(cx-w/2, 0, 1-w)
	t := clamp(cy-h/2, 0, 1-h)
	return phoneapi.RectNorm{L: l, T: t, R: l + w, B: t + h}
}
