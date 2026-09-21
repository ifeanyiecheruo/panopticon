package main

import "math"

// Zoom resolution, faking the phone's camera/ZoomGeometry.kt + CameraRoutes.resolveSelections
// closely enough to drive the controller's zoom UI without a device.
//
// The contract (docs/design/http-api.md, "Coordinate spaces, and why some keys are request-only"):
// the controller sends what the user drew, in VIEWER coordinates, in a request-only
// zoomSelectNorm; the phone composes it onto the current view and answers with the absolute
// zoomViewNorm plus the hardware/GL split. Selections are relative, so sending the same box twice
// zooms twice - the behaviour most worth being able to click through here.
//
// This mock has no camera, so the split is the uncalibrated case: no hardware zoom, GL does all
// of it. That is the same fallback a real but uncalibrated phone uses.

type rectNorm struct {
	L float64 `json:"l"`
	T float64 `json:"t"`
	R float64 `json:"r"`
	B float64 `json:"b"`
}

func fullView() rectNorm { return rectNorm{0, 0, 1, 1} }

// rectFromAny reads a {l,t,r,b} rect out of the keys map. It has to accept BOTH shapes the map
// can hold: a map[string]any when the value was just decoded from a request, and a rectNorm when
// it is one this code stored on an earlier request (the keys map is kept in memory between calls,
// not re-decoded). Handling only the map form silently loses the stored view, which reads as
// "zoom selections stop compounding".
func rectFromAny(v any) (rectNorm, bool) {
	if r, ok := v.(rectNorm); ok {
		if r.R <= r.L || r.B <= r.T {
			return rectNorm{}, false
		}
		return r, true
	}
	m, ok := v.(map[string]any)
	if !ok {
		return rectNorm{}, false
	}
	get := func(k string) (float64, bool) {
		f, ok := m[k].(float64)
		return f, ok
	}
	l, okL := get("l")
	t, okT := get("t")
	r, okR := get("r")
	b, okB := get("b")
	if !okL || !okT || !okR || !okB || r <= l || b <= t {
		return rectNorm{}, false
	}
	return rectNorm{l, t, r, b}, true
}

// recentreWithin01 is a w x h rect centred on (cx, cy), shifted (never shrunk) back inside 0..1.
func recentreWithin01(cx, cy, w, h float64) rectNorm {
	cw := math.Min(math.Max(w, 1e-4), 1)
	ch := math.Min(math.Max(h, 1e-4), 1)
	l := math.Min(math.Max(cx-cw/2, 0), 1-cw)
	t := math.Min(math.Max(cy-ch/2, 0), 1-ch)
	return rectNorm{l, t, l + cw, t + ch}
}

// fitToAspect grows r to the output aspect ratio - which in these normalised coordinates means
// growing it to a square, since the frame itself is the unit square. It CONTAINS r rather than
// fitting inside it, so nothing the user boxed is cropped away.
func fitToAspect(r rectNorm) rectNorm {
	side := math.Min(1, math.Max(r.R-r.L, r.B-r.T))
	return recentreWithin01((r.L+r.R)/2, (r.T+r.B)/2, side, side)
}

// inverseRotateRect maps a rect from the rotated on-screen frame back to the pre-rotation frame.
func inverseRotateRect(r rectNorm, deg int) rectNorm {
	pt := func(x, y float64) (float64, float64) {
		switch ((deg%360)+360)%360 {
		case 90:
			return y, 1 - x
		case 180:
			return 1 - x, 1 - y
		case 270:
			return 1 - y, x
		}
		return x, y
	}
	xs := make([]float64, 0, 4)
	ys := make([]float64, 0, 4)
	for _, c := range [][2]float64{{r.L, r.T}, {r.R, r.T}, {r.L, r.B}, {r.R, r.B}} {
		x, y := pt(c[0], c[1])
		xs = append(xs, x)
		ys = append(ys, y)
	}
	return rectNorm{
		L: min4(xs), T: min4(ys), R: max4(xs), B: max4(ys),
	}
}

func min4(v []float64) float64 {
	m := v[0]
	for _, x := range v {
		if x < m {
			m = x
		}
	}
	return m
}

func max4(v []float64) float64 {
	m := v[0]
	for _, x := range v {
		if x > m {
			m = x
		}
	}
	return m
}

// composeView places a viewer-space selection inside the current view, giving the new absolute
// view. Composing a square into a square keeps it square, so the aspect ratio never drifts
// however many times the user zooms in.
func composeView(current, selection rectNorm, rotationDegrees int) rectNorm {
	s := inverseRotateRect(fitToAspect(selection), rotationDegrees)
	vw := current.R - current.L
	vh := current.B - current.T
	clamp01 := func(f float64) float64 { return math.Min(math.Max(f, 0), 1) }
	return rectNorm{
		L: clamp01(current.L + s.L*vw),
		T: clamp01(current.T + s.T*vh),
		R: clamp01(current.L + s.R*vw),
		B: clamp01(current.T + s.B*vh),
	}
}

// viewForRatio is the slider: magnify about the CURRENT view's centre, so a slider nudge never
// discards the framing a rect selection set up.
func viewForRatio(current rectNorm, ratio float64) rectNorm {
	side := 1 / math.Max(1, ratio)
	return recentreWithin01((current.L+current.R)/2, (current.T+current.B)/2, side, side)
}

func ratioOf(v rectNorm) float64 {
	w := math.Max(v.R-v.L, 1e-6)
	h := math.Max(v.B-v.T, 1e-6)
	return 2 / (w + h)
}

// resolveZoomKeys applies the request-only selection/slider in keys to the stored view, mutating
// keys into the absolute state the phone would return. Mirrors CameraRoutes.resolveSelections:
// a fresh selection wins, then the slider, then an absolute view sent outright.
func resolveZoomKeys(keys map[string]any, stored map[string]any, rotationDegrees int) {
	current := fullView()
	if stored != nil {
		if v, ok := rectFromAny(stored["zoomViewNorm"]); ok {
			current = v
		}
	}

	var next rectNorm
	switch {
	case hasRect(keys, "zoomSelectNorm"):
		sel, _ := rectFromAny(keys["zoomSelectNorm"])
		next = composeView(current, sel, rotationDegrees)
	case hasFloat(keys, "zoomRatio"):
		next = viewForRatio(current, keys["zoomRatio"].(float64))
	default:
		if v, ok := rectFromAny(keys["zoomViewNorm"]); ok {
			next = v
		} else {
			next = fullView()
		}
	}

	// A selection is an instruction, not state: consumed here, never echoed back.
	delete(keys, "zoomSelectNorm")
	delete(keys, "aeSelectNorm")
	delete(keys, "afSelectNorm")
	keys["zoomViewNorm"] = next
	keys["zoomRatio"] = ratioOf(next)
}

func hasRect(m map[string]any, k string) bool {
	_, ok := rectFromAny(m[k])
	return ok
}

func hasFloat(m map[string]any, k string) bool {
	_, ok := m[k].(float64)
	return ok
}

// zoomSplit reports where the magnification is coming from. Uncalibrated, so the hardware does
// none of it and GL upscales the lot - which is what a real phone falls back to as well.
func zoomSplit(keys map[string]any, manual bool) (hwRatio, glResidual float64) {
	if !manual {
		return 1, 1
	}
	v, ok := rectFromAny(keys["zoomViewNorm"])
	if !ok {
		return 1, 1
	}
	return 1, math.Min(8, ratioOf(v))
}
