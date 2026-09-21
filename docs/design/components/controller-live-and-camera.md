# controller — live preview & camera control — software design description

*Prerequisite: this description assumes the HLS delivery model and how hls.js plays it —
playlist / sliding window / live edge / DVR, Media Source Extensions, LL-HLS, and the live-config
knobs (`liveSyncDurationCount` etc.). [`../android-media-primer.md`](../android-media-primer.md)
§6 covers it for a generalist; the phone side it consumes is
[`phone-live-pipeline.md`](phone-live-pipeline.md).*

## 1. Introduction

### 1.1 Purpose

Describe the component that shows a phone's live camera in Phone detail as plain HLS and drives
its camera selection + manual controls from the same surface, including the zoom adjuster whose
selections are sent in viewer coordinates for the phone to resolve.

### 1.2 Scope

Covers `liveproxy.go` (see also [`controller-runtime.md`](controller-runtime.md)),
`app.go`'s `StartLivePreview` / `StopLivePreview`, `internal/phoneapi/camera.go`, and the
frontend pieces `components/LivePreview.tsx`, `components/CameraControls.tsx`,
`components/phonecam/*`, `vendor/hlsjs/`.

### 1.3 Context

Lives inside Phone detail. Reads camera capabilities + calibration from the phone and the
model-keyed store; plays the live feed through the controller's own server-side proxy so hls.js
never holds the bearer token.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| server-side proxy | `liveproxy` fetches the playlist/segments with the stored token and serves them same-origin |
| stall watchdog | a 2s poll that kicks hls.js (`startLoad()` + seek to `liveSyncPosition`) on stalled progress / buffer-stall / fatal network error |
| `reattach` | re-initialising the player on a camera switch or resolution change |
| rect picker | one shared drag-box over the live `<video>` for Zoom/Focus/Exposure |
| viewer space | `0..1` over the frame on screen — the only coordinate space the controller sends rects in |
| fitted box | the dashed second box during a zoom drag: the smallest rect at the video's aspect ratio *containing* the drag, and exactly what the viewport shows on release |

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[HLS](../architecture.md#acr-hls),
[LL-HLS](../architecture.md#acr-ll-hls),
[CORS](../architecture.md#acr-cors),
[UI](../architecture.md#acr-ui),
[SPA](../architecture.md#acr-spa).

### 1.6 References

- [`../decisions/0007-live-preview-plain-hls.md`](../decisions/0007-live-preview-plain-hls.md),
  [`0008`](../decisions/0008-camera-control-and-multi-camera.md),
  [`0012`](../decisions/0012-ux-shape.md).
- [`../../quirks/live-hls.md`](../../quirks/live-hls.md) — the hls.js workarounds.
- [`../http-api.md`](../http-api.md) — `/api/live/*`, `/api/cameras*`, `/api/camera/*`.
- [`controller-calibration-store.md`](controller-calibration-store.md) — `EffectiveRect`.
- LL-HLS upgrade: [`../../status/ll-hls-upgrade.md`](../../status/ll-hls-upgrade.md).

## 2. Design overview

`App` moves a phone into/out of `live` (leaving a recording phone alone) with a retry that
tolerates the cold-camera arming `503`. `liveproxy` serves the feed same-origin.
`LivePreview.tsx` plays it with live-tuned hls.js config + a stall watchdog.
`CameraControls.tsx` is the ported phone Preview-screen UI, capability-gated to
`GET /api/camera/capabilities`. Rects it collects (zoom, focus, exposure) are sent exactly as
drawn, in viewer coordinates; the phone resolves them.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `StartLivePreview` / `StopLivePreview` | `App` methods | Move a phone into/out of `live` and start/stop its broadcast; remember the prior mode; a recording phone is left alone (`outcome:"recording"`). Uses `phoneapi.LiveStartAwaitReady` — retries the hinted `503` arming response for ~20s. |
| `liveproxy` | asset-server handler | Token stays server-side; hls.js fetches same-origin. |
| `useLivePreview` + `LivePreview.tsx` | hook + view | hls.js against the local proxy with live-tuned config (`liveSyncDurationCount` / `liveMaxLatencyDurationCount`, ~20s `maxBufferLength`, patient retries) + a 2s stall watchdog. `reattach()` on a camera switch or resolution change. Bounded manifest-404 retry that resets once the first fragment buffers. |
| `CameraControls.tsx` + `phonecam/*` | UI | Camera switcher (logical + physical sub-cameras); a manual-controls master toggle; capability-gated sliders/toggles. Ported phone Preview interactions: frosted drag-rulers over the live `<video>`, a drag-scroller mode bar, categorical dropdowns, per-slider reset, "Full auto", a 2s idle-fade. One shared drag-box rect picker for Zoom/Focus/Exposure (applies on pointer-up, Esc cancels, manual-mode only). A right-aligned resolution `<select>` re-attaches the player when changed. |
| zoom adjuster | UI | A ruler bound to `zoomRatio` (magnifies about the *current* view's centre) plus the shared rect picker targeting `zoomSelectNorm`. During a zoom drag two boxes are drawn: the drag itself, and the **fitted box**. Because the overlay is `0..1` over the video with no `object-fit` crop, the fitted box is just the smallest *square* containing the drag — the aspect ratio falls out of percentage sizing, with no aspect arithmetic anywhere in the component or the CSS. |
| zoom readback | UI | `glResidual` from the phone drives a "zoom is upscaling, not resolving" note above ~1.5×. |
| `phoneapi/camera.go` | client | Sentinels `ErrUnknownCamera` (404 on switch), `*InvalidControlKeyError{Key,Reason}` (400 naming the rejected key). |

### 3.2 Dependencies

- `phoneapi` (camera + live routes).
- `internal/calibration` (`EffectiveRect`) + the model-keyed store.
- The vendored hls.js (`vendor/hlsjs/`, 1.7.2, Apache-2.0 — not an npm dependency).

### 3.3 Interfaces

- **Provided:** the Phone-detail live section; `App.ListCameras` / `SetActiveCamera` /
  `GetCameraControls` (bundles capabilities + state + the model's calibration cameras) /
  `SetCameraControls`.
- **Required:** `phoneapi`, `internal/calibration`, the vendored hls.js.

### 3.4 Data

- No persisted data of its own. Camera state is written through `/api/camera/state` (persisted on
  the phone); calibration comes from the model-keyed store.

### 3.5 Processing and behaviour

- Live preview is gated behind an explicit action — never auto-started on page open (live and
  record are mutually exclusive).
- The stall watchdog is the minimal subset of the prototype's hls.js workarounds that plain HLS
  needs to not spiral into permanent rebuffering.
- **Verified end to end** against the Pixel 6 (front + back live connect through the arming
  retry; camera state set from the controller round-trips).

## 4. Design rationale and decisions

- **Plain HLS + server-side proxy + vendored hls.js** —
  [`0007`](../decisions/0007-live-preview-plain-hls.md).
- **Camera control on the live surface** —
  [`0008`](../decisions/0008-camera-control-and-multi-camera.md),
  [`0012`](../decisions/0012-ux-shape.md): the ported phone Preview interactions
  (rulers, drag-scroller, one shared rect picker) are load-bearing UX, not stock widgets.
- **The controller sends viewer coordinates and nothing else** — every rect (zoom, focus,
  exposure) travels as drawn over the preview; the phone owns rotation, zoom composition and the
  hardware/GL split ([`../http-api.md`](../http-api.md), "Coordinate spaces";
  [`phone-camera-control.md`](phone-camera-control.md)). The controller therefore needs no sensor
  geometry and no calibration data to draw a correct zoom overlay: the fitted box *is* the
  contract, because the phone compensates in GL for whatever the hardware won't do. That is why
  the older `App.ComputeEffectiveRect` predicted-crop overlay is not what shipped — predicting the
  HAL's crop only matters when the HAL's crop is what you see, and it no longer is.
- **Selections are sent, never remembered** — `applyKeys` strips the `*SelectNorm` fields from
  local state. Retaining one would replay it on the next unrelated patch, and since a zoom
  selection composes onto the current view, replaying it would zoom again every time.
- **A committed change adopts the phone's resulting state** — `SetCameraControls` returns it, so
  the zoom ruler reflects the ratio a *rect* selection produced. Skipped mid-drag, where a late
  response would fight the user's hand.
- **Deferred:** LL-HLS, adaptive bitrate, the scoped `/live/*` token —
  [`../../status/ll-hls-upgrade.md`](../../status/ll-hls-upgrade.md).
