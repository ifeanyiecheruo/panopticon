# Plan: zoom rebuild (viewer-space selection, phone-side solver, GL residual)

**Side:** both · **Size:** large · **Done** (all three stages; verified on the Pixel 6)

## Goal

A zoom adjuster in the live view — a slider *and* a drag-a-box zoom rect — whose result also
governs recording, where the viewport after a selection shows exactly the region selected, on
hardware that may refuse an off-centre crop.

## Context

Zoom had been torn out of `CameraControlApply` entirely (neither `zoomRatio` nor the old
`cropRegionNorm` reached the HAL) after a half-working attempt: the Pixel 6 back camera declares
`SCALER_CROPPING_TYPE = CENTER_ONLY`, and a GL-side pan/zoom introduced an on-device-only bug.
This rebuild starts from the measured behaviour instead —
[`../quirks/calibration-zoom.md`](../quirks/calibration-zoom.md) records that the Pixel 6 back
camera *does* honour an off-centre crop while the front camera does not, and that neither lies
about it in metadata.

## The model

Settled with the user; the wire half is authoritative in
[`../design/http-api.md`](../design/http-api.md) ("Coordinate spaces, and why some keys are
request-only").

- **The controller only ever sends viewer-space rects** — `0..1` over the frame on screen. The
  phone owns every transform (`ViewportRect`, `ZoomGeometry`).
- **Selections are request-only, results are response-only.** `zoomSelectNorm` / `afSelectNorm` /
  `aeSelectNorm` in; `zoomViewNorm` / `afRegionNorm` / `aeRegionNorm` + `hwZoomRatio` /
  `glResidual` out. A viewer-space rect is a *relative* instruction, so drawing the same box twice
  must zoom twice — which the old "is this rect different from the stored one?" heuristic could
  not express, and which made read-modify-write re-send a sensor-space rect as a viewer-space one.
- **Frame space** (`0..1` over the un-zoomed view) is where zoom state lives, because in it *a
  rect at the output aspect ratio is a square* — so aspect-fitting is "grow to a square", and a
  square composed into a square stays square, giving zero aspect drift across repeated zooms.
- **The slider magnifies about the current view's centre**, so it never discards the framing a
  rect selection set up.
- **The hardware never crops past the selection.** `ZoomGeometry.split` picks the largest
  calibrated ratio whose *measured* delivered crop still contains the target, and GL crops the
  rest. On a recentring camera an edge selection therefore gets little or no hardware zoom.
- **The GL residual upscales only** — the capture resolution and the camera→`SurfaceTexture`
  buffer size are deliberately *not* raised to feed it. Heavy zoom is allowed to look soft rather
  than cost bandwidth and thermals.

## Stage 1 — wire contract, phone solver, GL crop ✅

- `camera/ZoomGeometry.kt` (new, pure, 23 unit tests): aspect fit, compounding composition,
  slider-about-centre, frame↔sensor mapping, and the hardware/GL split against a calibration LUT.
- `GlBlit` takes a crop **rect** (`uTexRect`) instead of a centred `uTexCrop`. The fixed
  output-aspect trim is now just the *default* view rect, so there is still exactly one GL crop
  (keeping commit `c32688f`'s "single owner" true) rather than a second stage bolted on.
- Both pipelines render `ZoomGeometry.shaderRect(zoomViewNorm, texCropX, texCropY)`.
- `ViewportRect` untransforms AE/AF rects through that same view, so a focus rect drawn on zoomed
  content lands correctly.
- `CameraRoutes.resolveSelections` is the single place viewer space becomes absolute state.
- Go + TS types updated; the frontend's `applyKeys` deliberately does **not** retain
  `*SelectNorm` fields (retaining one would replay the selection on the next unrelated patch, and
  a zoom selection would compound every time).

**Not yet:** no hardware zoom. `split` is called with an empty LUT, so all zoom is GL. Framing is
already exactly right; sharpness is what stage 3 buys back.

## Stage 2 — controller UI ✅

- Zoom adjuster in `CameraControls.tsx`, first in the mode bar and the default open one: a ruler
  bound to `zoomRatio` (1×…`zoomRatioRange.hi`, per-ruler reset gives 1×), plus the shared rect
  picker targeting `zoomSelectNorm`.
- **Two overlays during the drag**: the raw drag box, and the dashed fitted box — the smallest
  output-aspect rect *containing* it, which in viewer-normalised coordinates is simply the
  smallest square, so it renders as percentages with no aspect math. That box is the promise: it
  is exactly what the viewport shows on release, and the controller can promise it without any
  calibration data because the phone compensates in GL.
- A zoom drag is judged on its fitted size, so a deliberately wide-and-short box is a valid
  selection rather than being rejected by the both-axes minimum AE/AF uses.
- `SetCameraControls` now returns the phone's resulting state, so the ruler reflects the ratio a
  rect selection produced without a follow-up read. Adopted only on a committed change.
- `glResidual` above ~1.5× shows a "zoom is upscaling rather than resolving detail" note.
- Zoom respects `manualControlEnabled` in both pipelines and in the route, like every other
  control — "Full auto" means no zoom.

`tools/mock-phone` implements the same zoom resolution (`cmd/mockphone/zoom.go`), so the whole
adjuster — both overlays, compounding, the slider keeping its centre, the `glResidual` note — can
be clicked through without a device:

```bash
go run ./cmd/mockphone -addr :8091 -invite TESTCODE1234 -segments 0   # from tools/mock-phone
```

Verified against it: repeating one box zooms 2× → 4× → 8×; a corner selection keeps its framing
through a slider change (clamped at the edge, not recentred); a wide-and-short drag resolves to
1.67× (its fitted square) rather than 3.33×; "Full auto" drops the zoom.

## Stage 3 — calibration-driven hardware/GL split ✅

- `camera/ZoomCalibrationLut.kt` (new, pure, unit-tested) turns this phone's own persisted
  calibration sweep into the table the solver uses, picking the camera entry (a pinned
  `"<logical>:<physical>"` id falls back to its logical parent, which is never swept separately)
  and the nearest probed output size (the Pixel 6 records 3840x2160; the sweep topped out at
  2688x1512).
- **The crop rect is not the signal.** On API 30+ hardware driving zoom through
  `CONTROL_ZOOM_RATIO`, `SCALER_CROP_REGION` keeps reporting the *full* active array at every
  ratio — measured `effectiveCropNorm ~= (1.0, 1.0)` from 0.67x to 7x on the Pixel 6. A
  crop-driven solver concludes every ratio still shows the whole frame and slams the hardware to
  its maximum at any zoom level. `ZoomCalibrationLut` prefers the **reported ratio**, falling
  back to the crop only where there is no reported ratio (the BLU G5's legacy path).
- `ZoomGeometry.split` now solves against measured **delivered magnification**, interpolating
  between samples so the dial is smooth and so a non-linear optical handover is followed as
  measured rather than assumed to be `1/ratio`.
- `CameraControlApply.applyZoom` applies it: `CONTROL_ZOOM_RATIO` via the API-30-isolated
  `ZoomRatioApi30`, else a centred `SCALER_CROP_REGION` — never both in one request.
- The pipelines recompute the split on every control change *and* on the first frame, where the
  aspect trim finally exists, re-issuing the repeating request if the hardware half changed.

**The hardware zoom is always centred, deliberately.** `CONTROL_ZOOM_RATIO` is a scalar and a
centred crop is isotropic, so it denotes the same region whether or not the camera's buffer is
transposed relative to the sensor (`CameraFraming.axesSwapped` — true on the Pixel 6, which is
why its aspect trim is `0.421875` rather than `0.75`). An off-centre `SCALER_CROP_REGION` would
need a sensor-coordinate mapping that has to get the transpose right, which is where the previous
zoom attempt came unstuck, so all off-centre movement stays in GL. The cost is measured and
accepted: a selection hugging an edge gets no hardware help, because the largest centred field of
view containing it is barely zoomed.

Measured on the Pixel 6 back camera after wiring:

| request | hardware | GL residual |
|---|---|---|
| centred 1x…7x | does all of it | 1.00 |
| near-centre 2x selection | 2.00 | 1.00 |
| corner 2x selection | 1.00 (cannot help) | 2.00 |

Frame capture confirms the framing is unchanged and the image is visibly sharper than the
equivalent digital crop.

### Known follow-ups

- **Off-centre hardware crop** for cameras where calibration reports `positionHonored` (the Pixel
  6 back camera does). Needs the sensor-coordinate mapping above, including the axis transpose.
- **A few frames of stale framing when the hardware zoom changes.** The shader starts placing the
  view inside the *new* delivered crop as soon as the split is recomputed, but the HAL takes a
  few frames to act on the new ratio. Invisible in practice behind ~4-6s of HLS latency; the fix
  is to drive the shader from the zoom actually reported in `CaptureResult` rather than the
  predicted one.
- **A pinned physical sub-camera borrows its logical parent's table.** Safe (the split only ever
  asks for a magnification the view can absorb) but approximate: pinning removes the logical
  camera's optical handover, so the parent's sub-crossover samples describe a lens that session
  cannot reach. Real optical zoom wants the logical camera ("Wide (auto)"), not `0:2`.

## Testing

Unit tests cover the geometry on a plain JVM. On device, the three cases that matter are the
Pixel 6 **back** camera (honours an off-centre crop), the Pixel 6 **front** camera (does not, so
an edge selection should fall back to GL and still frame correctly), and the **BLU G5** (legacy
`SCALER_CROP_REGION`, 2.0× max). The check in each case: draw a box, and confirm the viewport
afterwards matches the second overlay exactly — then confirm a recording made after it has the
same framing.

## Acceptance

A zoom rect and a zoom slider in the live view; the viewport after a selection is exactly the
aspect-fitted box that was drawn; zooms compound; a focus rect drawn on zoomed content lands on
the right part of the scene; and the same framing appears in recorded segments.
