# Phone HTTP API — contract

## 1. Introduction

### 1.1 Purpose

Specify the HTTP interface the phone app exposes to paired controllers. This is the
**authoritative** contract for wire shapes and status codes: `phone-app` implements it,
`controller` calls it, and `tools/mock-phone` fakes it.

### 1.2 Scope

All routes under `/api/*` and `/live/*`, plus the auth model. Modeled on the prototype's
`shared/api-contract.md`, reworked for this project's pairing model, multi-camera support, and
device-wide calibration. Component-level design of the code behind these routes is in
[`components/`](components/); this document is the wire contract only.

### 1.3 Definitions

| Term | Meaning |
|---|---|
| segment | one recorded file in the phone's ring buffer — the phone's only unit of footage |
| clip | a contiguous run of segments; assembled **by the controller**, not part of this API |
| mode | `record` / `standby` / `live` — the phone's top-level camera state |
| logical / physical camera | an id `CameraManager` reports / a `"<logical>:<physical>"` sub-sensor of a logical multi-camera |
| capability gate | the declared range or flag a camera-control key is validated against |
| arming | the interval after entering `live` before the camera can broadcast |

### 1.4 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](architecture.md#14-acronyms-and-abbreviations):
[HTTP](architecture.md#acr-http),
[JSON](architecture.md#acr-json),
[HLS](architecture.md#acr-hls),
[LL-HLS](architecture.md#acr-ll-hls),
[TS](architecture.md#acr-ts),
[AE / AF / AWB](architecture.md#acr-ae),
[OIS](architecture.md#acr-ois),
[ISO](architecture.md#acr-iso),
[EV](architecture.md#acr-ev),
[RGGB](architecture.md#acr-rggb),
[API](architecture.md#acr-api) (both senses),
[DVR](architecture.md#acr-dvr).

### 1.5 References

Design rationale is in the decision records, not here:
[0002](decisions/0002-http-api-surface-and-auth.md) (surface + auth),
[0003](decisions/0003-pairing-and-unpairing.md) (pairing),
[0006](decisions/0006-segments-clips-and-tombstones.md) (segments vs clips),
[0007](decisions/0007-live-preview-plain-hls.md) (live),
[0008](decisions/0008-camera-control-and-multi-camera.md) (camera control),
[0009](decisions/0009-calibration-model.md) (calibration),
[0010](decisions/0010-mode-state-machine.md) (mode).
Related quirks: [`../quirks/live-hls.md`](../quirks/live-hls.md),
[`../quirks/manual-camera-controls.md`](../quirks/manual-camera-controls.md),
[`../quirks/calibration-zoom.md`](../quirks/calibration-zoom.md).

## 2. Auth model

Pairing exchanges a controller's public key for a **per-controller bearer token** — not a single
shared secret. Each controller can be revoked without invalidating the others.

```
Authorization: Bearer <per-controller-token>
```

Missing/unknown/revoked token → `401`. No permission tiers: every paired controller has full
access to every route below. `POST /api/pair` is the only unauthenticated route.

Invite generation and controller-registry management (list/revoke other controllers) are **not
HTTP routes** — they are local library functions called by the phone's own UI
(`InviteManager.createInvite()/listPendingInvites()/revokeInvite()`,
`ControllerRegistry.list()/revoke()`). `DELETE /api/pair` (self-unpair) is the single exception,
because the remote party is the one who needs it.

## 3. Route table

### Pairing

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| POST | `/api/pair` | `invite=<code>` | `{ "publicKey": "MCowBQYDK2VwAyEA...", "name": "Ifeanyi's Desktop", "kind": "desktop" }` | `{ "controllerId": "ctl_9b02", "token": "8f2a1c...e91b", "phone": { "phoneId": "ph_a81d", "name": "Garage cam" } }` | Redeems an invite: registers the controller's public key and issues a bearer token. `404` unknown code, `410` expired/already used. |
| DELETE | `/api/pair` | — | — | `{ "unpaired": true }` | Self-unpair: revokes the calling controller's own token. The bearer token *is* the identity being revoked — there's no `controllerId` param, so a controller can never unpair anyone but itself. `401` if the token's already invalid/revoked. |

### Device identity & status

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/device` | — | — | `{ "manufacturer": "Google", "model": "Pixel 9a", "device": "tegu" }` | Hardware identity used to key the controller's device-capability/calibration store. |
| GET | `/api/build-info` | — | — | `{ "appVersionName": "1.4.2", "appVersionCode": 47, "buildType": "release", "gitSha": "a3f9c21" }` | Phone app's own build identity, for compatibility gating. |
| GET | `/api/status` | — | — | `{ "mode": "live", "status": "idle", "cameraHealthy": true, "liveViewers": 0, "storageUsedBytes": 40200000000, "storageCapBytes": 64000000000, "batteryPercent": 78, "charging": true, "thermal": { "supported": true, "severity": 1, "level": "light", "headroom": 0.62 }, "serverTimeMs": 1755270015231 }` | One-shot snapshot: mode, recording/broadcast state, storage, battery, thermal, phone clock. `thermal` is **not a temperature** — degrees are privileged on Android and are not the useful signal. `severity` is `PowerManager`s own 0..6 scale (`none`/`light`/`moderate`/`severe`/`critical`/`emergency`/`shutdown`), mirrored as `level`; `headroom` is a 0..1+ forecast to the throttling threshold (Android 11+, `null` otherwise). `supported` is `false` on Android < 29, which cannot report thermal state at all — the controller draws no thermal icon for those, since "cannot say" is not "cool". |
| GET | `/api/config` | — | — | `{ "deviceName": "Garage cam", "motionSensitivity": "medium", "storageCapBytes": 64000000000, "ringBufferMaxAgeMs": 604800000 }` | Reads persisted device configuration. (`rotationDegrees` moved to `/api/camera/*` — it's a camera-pipeline setting.) |
| POST | `/api/config` | — | `{ "deviceName": "Garage cam", "motionSensitivity": "high" }` | *(full resulting config document)* | Batch-updates any subset of device config. |

### Mode

Modes: `record` (motion-gated recording pipeline owns the camera), `standby` (camera released —
the only state calibration / live preview can take it from), `live` (live-preview pipeline —
implemented as **LL-HLS**; see Live view below). **`record` is sticky**: it takes precedence
over every other camera-using feature, and you must move to `standby` *explicitly* before any of
them can run.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/mode` | — | — | `{ "mode": "record" }` | Current top-level mode. |
| POST | `/api/mode` | — | `{ "mode": "standby" }` | `{ "mode": "standby" }` | Switches mode. `standby` and `record` are always allowed. `live` from `record` is a **`409`** (`{"error":"stop recording first: POST /api/mode {\"mode\":\"standby\"}"}`) — go via `standby`. No-op `200` if already there. |

### Cameras (selection)

`cameraIdList` returns *logical* cameras only, so the catalog also emits `"<logical>:<physical>"`
ids for each physical sub-camera of a logical multi-camera (API 28+). Opening one opens the
*logical* device but pins the session's outputs to that physical sensor via
`OutputConfiguration.setPhysicalCameraId`. On the Pixel 6 this exposes `0:2` (wide) and `0:3`
(ultra-wide); the BLU G5 (single sensor per facing) just lists `0`/`1`.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/cameras` | — | — | `{ "cameras": [ { "cameraId": "0:3", "facing": "back", "label": "Ultra-wide", "focalLengthMm": 2.35, "isActive": false }, { "cameraId": "0", "facing": "back", "label": "Wide (auto)", "focalLengthMm": 6.81, "isActive": true }, { "cameraId": "1", "facing": "front", "label": "Front (auto)", "focalLengthMm": 2.51, "isActive": false } ] }` | Lists selectable cameras (logical ids + `"<logical>:<physical>"` sub-cameras) and which is active. `label` is a wide/ultra-wide/tele/front heuristic from relative focal length; a bare logical id gets a "(auto)" suffix. |
| POST | `/api/cameras/active` | — | `{ "cameraId": "0:3" }` | `{ "activeCameraId": "0:3" }` | Switches active camera. Disruptive reconfigure — the service rebuilds the running pipeline (a switch mid-recording ends the current clip). Persisted in `DeviceConfig.activeCameraId`. `404` unknown `cameraId`. |

### Camera control

The applied state persists in `DeviceConfig.cameraControls` and is re-applied to whichever
pipeline (`record` or `live`) is running — a state dialled in while watching the live preview
also governs recording. Verified on the Pixel 6 (API 36) and BLU G5 (API 28).

**The concrete `keys` set** (`CameraControlKeys`; a null/absent field = leave that control on
auto). `POST` replaces the `keys` object wholesale — send the full desired set.

#### Coordinate spaces, and why some keys are request-only

Every rect the controller sends is in **viewer space**: `0..1` over the frame the user is
actually looking at in the live view — already rotated by `rotationDegrees`, already cropped by
whatever zoom is in effect. The controller never converts to sensor coordinates and never needs
to know the sensor's geometry; the phone owns every transform
(`camera/ViewportRect.kt`, `camera/ZoomSolver.kt`).

That makes a viewer-space rect a **relative** instruction ("zoom into *this* part of what I'm
seeing now"), which cannot share a field with the absolute state it produces — drawing the same
box twice must zoom twice, and a read-modify-write of the absolute state must not be mistaken
for a fresh draw. So the rect keys are split:

| | field | space | meaning |
|---|---|---|---|
| **request-only** (`POST`) | `zoomSelectNorm`, `afSelectNorm`, `aeSelectNorm` | viewer | a **fresh selection**, always. Present ⇒ the user just drew it. Never returned by `GET`. |
| **response-only** (`GET`) | `zoomViewNorm` | frame (`0..1` over the *un-zoomed* view, pre-rotation) | the resulting **absolute** state. Echoing one back in a `POST` is ignored. |
| **response-only** (`GET`) | `afRegionNorm`, `aeRegionNorm` | sensor active array | as above; these are metering rects the HAL consumes directly, so they stay in its coordinates. |

`zoomSelectNorm` is composed onto the current view, so selections compound. The phone is free to
widen a selection to the output aspect ratio (it fits the smallest output-aspect rect that
*contains* the selection, so nothing the user boxed is ever cropped away) — the controller draws
that same fitted rect as a second overlay, and it is exactly what the viewport shows afterwards.

The phone decides how much of the zoom the **camera** can do and how much GL must finish, against
its own calibration sweep. The camera is only ever asked for a **centred** magnification whose
field of view still fully contains the requested view, so it can never crop past the selection;
everything off-centre, and any remainder, is made up by cropping and upscaling the texture in GL.
That upscale adds no detail — the capture resolution and the camera→`SurfaceTexture` buffer size
are deliberately left alone, so a heavy GL residual reads as soft rather than costing bandwidth
and thermals. `GET` reports both `hwZoomRatio` and `glResidual`, so the UI can say where the zoom
came from. An uncalibrated phone simply gets `hwZoomRatio: 1` and does it all in GL.

| key | Camera2 mapping | capability gate |
|---|---|---|
| `zoomRatio` (float) | magnification relative to the un-zoomed view, **about the current view's centre** (the slider). Resolved by `ZoomSolver` into a hardware request + a GL residual, exactly as a rect is — it is not passed to the HAL directly. | `zoomRatioRange` |
| `zoomSelectNorm` `{l,t,r,b}` 0..1 *(request-only)* | a freshly-drawn zoom rect in **viewer** space. Fitted to the output aspect ratio, composed onto the current view, then resolved by `ZoomSolver`. **Exclusive** with `zoomRatio` in one request; the rect wins. | must be a sane sub-rect of 0..1 |
| `zoomViewNorm` `{l,t,r,b}` 0..1 *(response-only)* | the resulting absolute view, as a fraction of the **un-zoomed** view rather than of the sensor array — so it is answerable in `standby`, when no pipeline is running and the output-aspect trim isn't known yet. Alongside it `GET` reports `hwZoomRatio` (what the HAL was actually asked for) and `glResidual` (the leftover magnification GL makes up), so the UI can show where the zoom is coming from. | — |
| `aeExposureCompensation` (int) | `CONTROL_AE_EXPOSURE_COMPENSATION` | `aeCompensationRange` |
| `aeLock` (bool) | `CONTROL_AE_LOCK` (metering freeze, not a manual-exposure dial) | — |
| `manualExposure` (bool) + `sensorExposureTimeNs` + `sensorSensitivityIso` | `CONTROL_AE_MODE=OFF` + `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` | `hasManualSensor` (`REQUEST_AVAILABLE_CAPABILITIES` ∋ `MANUAL_SENSOR`) |
| `aeRegionNorm` `{l,t,r,b}` 0..1 | `CONTROL_AE_REGIONS` (one max-weight `MeteringRectangle`, active-array coords) — spot metering. Applied **only alongside `manualExposure`**, as the alternative to the shutter/ISO dials (region → AE stays metering, biased to the box; dials → `AE_MODE=OFF`). The controller sends exactly one. | `maxAeRegions > 0`; sane sub-rect of 0..1 |
| `manualFocus` (bool) + `lensFocusDistanceDiopters` | `CONTROL_AF_MODE=OFF` + `LENS_FOCUS_DISTANCE` | `hasManualFocus` (`LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` + `AF_MODE_OFF`) |
| `afRegionNorm` `{l,t,r,b}` 0..1 | `CONTROL_AF_MODE=CONTINUOUS_VIDEO` + `CONTROL_AF_REGIONS` (one max-weight `MeteringRectangle`) — drag-to-focus. Applied **only alongside `manualFocus`**, as the alternative to the distance dial (region → continuous AF on the box; dial → `AF_MODE=OFF` + fixed distance). The controller sends exactly one. | `maxAfRegions > 0`; sane sub-rect of 0..1 |
| `awbMode` (int) | `CONTROL_AWB_MODE` (0 off / 1 auto / 2 incandescent / … / 8 shade) | must be in `awbModes` (`CONTROL_AWB_AVAILABLE_MODES`, deduped) |
| `manualWhiteBalance` (bool) + `wbRedGain` / `wbGreenGain` / `wbBlueGain` | `AWB_MODE_OFF` + `COLOR_CORRECTION_MODE=TRANSFORM_MATRIX` + identity `COLOR_CORRECTION_TRANSFORM` + `COLOR_CORRECTION_GAINS` (RGGB, green used for both G channels). Wins over `awbMode`. | `hasManualWhiteBalance` (`REQUEST_AVAILABLE_CAPABILITIES` ∋ `MANUAL_POST_PROCESSING`); gains in `wbGainRange` (fixed `1.0..8.0` — Camera2 declares none) |
| `videoStabilizationMode` (int) | `CONTROL_VIDEO_STABILIZATION_MODE` (0 off, 1 on, 2 preview-stabilization API 33+) | must be in `videoStabilizationModes` (`CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`, deduped) |
| `opticalStabilizationMode` (int) | `LENS_OPTICAL_STABILIZATION_MODE` (0 off, 1 on) | must be in `opticalStabilizationModes` (`LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`, deduped) |

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/camera/capabilities` | `cameraId=1` *(optional, defaults to active)* | — | `{ "cameraId": "0", "zoomRatioRange": {"lo":0.67,"hi":7.0}, "zoomViaRatioApi": true, "aeCompensationRange": {"lo":-24,"hi":24}, "aeCompensationStepMilliEv": 166, "exposureTimeRangeNs": {"lo":26503,"hi":8310343667}, "sensitivityRange": {"lo":44,"hi":11377}, "minFocusDistanceDiopters": 9.52, "hasManualSensor": true, "hasManualFocus": true, "hasManualWhiteBalance": true, "wbGainRange": {"lo":1.0,"hi":8.0}, "awbModes": [0,1,2,3,4,5,6,7,8], "videoStabilizationModes": [0,1,2], "opticalStabilizationModes": [0,1], "maxAeRegions": 3, "maxAfRegions": 1, "outputResolutions": ["1920x1080","1280x720","854x480"], "physicalCameraIds": ["2","3"], "croppingType": "CENTER_ONLY", "activeArrayWidth": 4080, "activeArrayHeight": 3072 }` | Declared per-key ranges for a camera; pure `CameraCharacteristics` read, works in any mode. Optional `cameraId` previews another camera without switching. `404` unknown `cameraId`. |
| GET | `/api/camera/state` | — | — | `{ "cameraId": "0", "rotationDegrees": 0, "videoResolution": "3840x2160", "recordingResolution": "1024x576", "manualControlEnabled": false, "keys": { "zoomRatio": null, "...": null } }` | Current manual-control state (from `DeviceConfig`), including `rotationDegrees` (0/90/180/270), `videoResolution` (the viewing/broadcast size, one of `capabilities.outputResolutions`) and `recordingResolution` (**derived, read-only** — see below). |
| POST | `/api/camera/state` | — | `{ "manualControlEnabled": true, "rotationDegrees": 90, "videoResolution": "1920x1080", "keys": { "zoomRatio": 2.0, "aeExposureCompensation": -2 } }` | *(resulting state, same shape as GET)* | Validate-then-apply against the **active** camera's capabilities: `400 {"error": "...", "key": "zoomRatio"}` on the first offending key, applies nothing. Any subset of `manualControlEnabled` / `rotationDegrees` / `videoResolution` / `keys` may be sent; omitted fields are left unchanged. `rotationDegrees` must be one of 0/90/180/270; `videoResolution` must be one of the camera's `outputResolutions`. `recordingResolution` is derived and **cannot be set**. A `videoResolution` change rebuilds the running pipeline. **`409 {"error": "frozen while recording", "key": "..."}`** for `videoResolution` or any zoom key while the mode is `record` — see below. Accepted + persisted even in `standby` (applied when a pipeline next starts). |


### Viewing vs recording resolution

`videoResolution` is what both RECORD and LIVE capture and encode at. `recordingResolution` is
reported alongside it, read-only, and is always the same size.

RECORD used to reduce it for zoom (to `1024x576` at 4.2x on a Pixel 6), on the reasoning that
digital zoom is a crop-and-upscale. That stopped holding once most of the zoom moved into the
camera (`CONTROL_ZOOM_RATIO`): the ISP crops the sensor and reads the crop out at the *stream*
size, so a smaller stream discarded real detail, and recordings came out visibly softer than the
live preview (2026-09-29: ~640 real pixels across the view against ~2400). The recording bitrate
is fixed, so full size costs encoder work, not storage.

### What is frozen while recording

`videoResolution` and the zoom keys (`zoomRatio`, `zoomSelectNorm`, `zoomViewNorm`) are rejected
with `409` while the mode is `record`. Resolution fixes the recording size when the pipeline
starts, and a hardware zoom change reconfigures the camera and reframes the shot; honouring either
mid-recording would mean a real hole in the footage, and segments of two sizes or framings inside
one clip.

Everything else stays live. Exposure, focus and white balance do not change how many real pixels
the frame carries and apply through the light repeating-request path, so a badly-exposed camera
can still be corrected without stopping the recording.

To re-frame: `POST /api/mode {"mode":"standby"}`, adjust, then back to `record`. That is already
the only way to see what you are aiming at — `record` → `live` is refused with `409` for the same
reason.

### Calibration (device-wide)

A **real empirical zoom probe**: for every camera, at every `StreamConfigurationMap` output
size, it applies a geometric range of zoom requests (`CONTROL_ZOOM_RATIO` on API 30+,
`SCALER_CROP_REGION` on every API) and records what the HAL actually did — the effective crop
rect (`effectiveCropNorm`), whether the requested ratio/position was honoured, which physical
camera was active (optical↔digital crossover), and a frame-sharpness score. Result body carries
`deviceIdentity`, and per camera `opticalRange` / `digitalRange` / `crossoverRatio` /
`positionHonored` / `qualityCollapseRatio` / `perResolution` (the full `ZoomSample` list) plus a
thin `steps` summary; the exact shape is `phone-app`'s `calibration/CalibrationModels.kt` ↔
`controller`'s `internal/phoneapi/calibration.go`. Needs exclusive camera access, so it only
runs from `standby`.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| POST | `/api/calibration/start` | — | `{}` | `{ "runId": "cal-8f2a1c", "status": "running", "startedAtMs": 1755270000000, "cameraIds": ["0", "2", "1"] }` | Sweeps every camera the device reports. `409` if already running, **`409` if the phone is in `record` mode** (`{"error":"stop recording on the phone before calibrating"}`), `422` if the device reports no cameras. |
| GET | `/api/calibration/status` | `runId=` | — | `{ "runId": "cal-8f2a1c", "status": "running", "currentCameraId": "2", "camerasCompleted": 1, "camerasTotal": 3, "currentStep": "1920x1080", "stepsCompleted": 4, "stepsTotal": 24, "progressWithinStep": { "index": 9, "total": 14 } }` | Poll target. `currentStep` is the output size being swept; `progressWithinStep` is the zoom step. |
| DELETE | `/api/calibration/:runId` | — | — | `{ "cancelled": true }` | Cooperative stop; completed cameras keep partial results. |
| GET | `/api/calibration/result` | `runId=` *(optional)* | — | *(see the note above — `runId`, `runAtMs`, `deviceIdentity`, `cameras.{id}.{opticalRange,digitalRange,crossoverRatio,positionHonored,qualityCollapseRatio,perResolution,steps}`)* | With `runId`, that specific run (`409` if not completed). **Without it, the phone's last persisted result** — written to disk, so an already-calibrated phone serves it on every request without re-running, including after an app restart. `404` if this phone has never completed a calibration. |

### Camera health (diagnostics)

**Not a stable contract, and nothing renders it yet.** A loosely-typed dump of per-camera
pipeline counters, added to characterise the recording pipeline's restart cycle from a phone that
has been running for hours rather than from a `logcat` session that happens to be attached at the
right moment — see [`../status/camera-stall-investigation.md`](../status/camera-stall-investigation.md).

Only the envelope (`generatedAtMs`, `processUptimeMs`, `cameras`) is fixed. Inside `cameras`, each
key is `"<role>:<cameraId>"` (`role` being `record` or `live`, since the same physical camera
behaves differently under the two pipelines) and the value's fields are free-form: a new probe is
a one-line change on the phone with no wire type and no controller model to keep in step. Counters
are process-lifetime and reset when the app restarts — what they measure is *pipeline* restarts,
which happen many times per hour inside one process.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/camera/health` | — | — | `{ "generatedAtMs": 1758300000000, "processUptimeMs": 3600000, "cameras": { "record:0": { "cameraId": "0", "role": "record", "runs": 23, "runsEnded": 22, "currentUpMs": 41000, "lastUpMs": 155000, "meanUpMs": 158000, "upMsMin": 152000, "upMsMax": 235000, "meanUpMsByEndReason": { "stall": {"count": 21, "meanUpMs": 161000, "totalUpMs": 3381000}, "stopped": {"count": 1, "meanUpMs": 9000, "totalUpMs": 9000} }, "counters": { "captureFailed": 0, "captureBufferLost": 0, "segmentPublished": 23 }, "gauges": { "recordingSize": "3840x2160", "measuredFps": "29.98", "lastFailure.reason": "stall", "lastFailure.cameraFrameAgeMs": 4102, "lastFailure.captureResultAgeMs": 4098, "lastFailure.encodedOutputAgeMs": 4001, "motion.disturbancesExpired": 0 }, "recent": [ { "atMs": 1758299840000, "event": "runEnded:stall", "detail": "encoder output stalled for 4000ms" } ] } } }` | Per-camera pipeline health. Read-only; same bearer auth as every other route. |

### Live view

**LL-HLS** (~1s `.ts` segments, 16-segment sliding window, ~333ms `EXT-X-PART` byte-range parts,
`#EXT-X-VERSION:9`, `EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,PART-HOLD-BACK≈1s`). Glass-to-glass
latency close to `PART-HOLD-BACK` (down from plain HLS's ~4–6s — see
[`../design/decisions/0007-live-preview-plain-hls.md`](decisions/0007-live-preview-plain-hls.md)).
The phone runs a dedicated single-stream `camera → MediaCodec → TsMuxer` pipeline; `MediaMuxer`
can't emit MPEG-TS so the muxer is hand-rolled. Entering `live` mode arms the pipeline (camera
warm, nothing encoding); `POST /api/live/start` begins broadcasting; a 15s no-request inactivity
watchdog returns it to armed-idle. `/live/*` is behind the normal bearer token — the controller
proxies these server-side, so hls.js fetches same-origin (the prototype's separate GET-only
scoped token is deferred). See [`../quirks/live-hls.md`](../quirks/live-hls.md).

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| POST | `/api/live/start` | — | `{}` | `{ "started": true, "viewerCount": 1 }` | Idempotently begins broadcasting; `409` unless the phone is in `live` mode. `503 { "error": "camera still starting", "retryAfterMs": 2000 }` (plus a `Retry-After` header) while the camera is still arming — a cold front-facing camera can take several seconds; the caller should retry until it succeeds. `503 { "error": "live camera unavailable" }` (no `retryAfterMs`) is a hard failure — do not retry. |
| DELETE | `/api/live/stop` | — | — | `{ "stopped": true, "viewerCount": 0 }` | Explicit stop (usually unnecessary — 15s inactivity watchdog handles it). |
| GET | `/live/live.m3u8` | `_HLS_msn=`, `_HLS_part=` *(both optional; LL-HLS blocking reload)* | — | *(text `application/vnd.apple.mpegurl`)* | Rolling LL-HLS playlist. With `_HLS_msn` (optionally `_HLS_part`), blocks up to ~4× the part target duration until that part/segment exists instead of returning immediately. `404` before broadcasting starts, `409` when not in `live` mode. |
| GET | `/live/live-<n>.ts` | — (a `Range: bytes=<start>-<end>` request header, not a query param, selects an in-progress part) | — | *(binary `video/mp2t`)* | A finalized segment: full body, `200`. Not yet finalized: with a `Range` header matching a byte range this segment's playlist entry advertised (`EXT-X-PART`/`EXT-X-PRELOAD-HINT`), `206` + `Content-Range` for whatever's been muxed so far, or `416` if that range isn't there yet. `404` once fully rolled out of the window. |

### Segments (sync)

A **segment** is one recorded file in the ring buffer. Grouping contiguous segments into a
user-facing **clip** is a controller/UX concern (the controller does it on sync, by time gap) —
**not part of this API**. The phone only ever lists, serves, and deletes individual segments.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/segments` | `since=<epochMs>` | — | `{ "segments": [ { "filename": "clip_0004123.mp4", "url": "/api/segments/clip_0004123.mp4/file", "createdAtMs": 1755270012000, "durationMs": 8000, "endMs": 1755270020000, "sizeBytes": 2100000, "width": 1920, "height": 1080 } ] }` | Delta-pull of segments created after `since` (default `0` = everything on disk). |
| GET | `/api/segments/:filename/file` | — | — | *(binary `video/mp4`, supports `Range`)* | Downloads one segment; `404` if evicted. |
| GET | `/api/segments/:filename/thumbnail` | — | — | *(binary `image/jpeg`)* | Single extracted frame, for the Gallery filmstrip. |
| DELETE | `/api/segments/:filename` | — | — | `{ "deleted": true }` | Explicit early eviction. |

(The `filename` still carries a historical `clip_` prefix — it's an opaque on-disk name, not a
statement that the file is a "clip" in the grouped sense.)

## 4. Open contract questions

Tracked as implementation work:

- Controller-side sync cadence / backoff against an unreachable phone, and the eviction-probe
  loop's cadence — [`../status/sync-cadence-and-backoff.md`](../status/sync-cadence-and-backoff.md).
- A scoped GET-only `/live/*` token (currently the full bearer token, mitigated by the
  server-side proxy) — [`../status/ll-hls-upgrade.md`](../status/ll-hls-upgrade.md).
- Multi-phone fleet concerns beyond the single-phone API surface — covered by
  [`architecture.md`](architecture.md) §5.2 and the controller specs.
