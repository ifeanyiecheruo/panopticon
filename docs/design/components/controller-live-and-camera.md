# controller — live preview & camera control — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that shows a phone's live camera in Phone detail as plain HLS and drives
its camera selection + manual controls from the same surface, with the
calibration-predicted honoured crop overlaid.

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
| predicted-crop overlay | the dashed rect from `App.ComputeEffectiveRect` under the rect picker |

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
`GET /api/camera/capabilities`, with the predicted-crop overlay from the calibration store.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `StartLivePreview` / `StopLivePreview` | `App` methods | Move a phone into/out of `live` and start/stop its broadcast; remember the prior mode; a recording phone is left alone (`outcome:"recording"`). Uses `phoneapi.LiveStartAwaitReady` — retries the hinted `503` arming response for ~20s. |
| `liveproxy` | asset-server handler | Token stays server-side; hls.js fetches same-origin. |
| `useLivePreview` + `LivePreview.tsx` | hook + view | hls.js against the local proxy with live-tuned config (`liveSyncDurationCount` / `liveMaxLatencyDurationCount`, ~20s `maxBufferLength`, patient retries) + a 2s stall watchdog. `reattach()` on a camera switch or resolution change. Bounded manifest-404 retry that resets once the first fragment buffers. |
| `CameraControls.tsx` + `phonecam/*` | UI | Camera switcher (logical + physical sub-cameras); a manual-controls master toggle; capability-gated sliders/toggles. Ported phone Preview interactions: frosted drag-rulers over the live `<video>`, a drag-scroller mode bar, categorical dropdowns, per-slider reset, "Full auto", a 2s idle-fade. One shared drag-box rect picker for Zoom/Focus/Exposure (applies on pointer-up, Esc cancels, manual-mode only). A right-aligned resolution `<select>` re-attaches the player when changed. |
| predicted-crop overlay | UI | `App.ComputeEffectiveRect` drawn as a dashed rect under the zoom-rect picker. |
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
- **Predicted-crop overlay** — uses the calibration store's `EffectiveRect` so the user sees the
  crop the HAL will actually apply, not just what was requested.
- **Deferred:** LL-HLS, adaptive bitrate, the scoped `/live/*` token —
  [`../../status/ll-hls-upgrade.md`](../../status/ll-hls-upgrade.md).
