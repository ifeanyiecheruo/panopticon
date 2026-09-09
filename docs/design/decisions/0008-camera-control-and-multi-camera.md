# 0008 — Camera selection and manual controls

**Status:** Accepted · implemented and verified on the Pixel 6 (both sides). No phone-side
Compose UI ([`../../status/phone-camera-control-ui.md`](../../status/phone-camera-control-ui.md)).
Component specs: [`phone-camera-control.md`](../components/phone-camera-control.md),
[`controller-live-and-camera.md`](../components/controller-live-and-camera.md).

## Context

Old phones lie about their Camera2 capabilities, and multi-camera phones expose extra sensors
only reachable through non-obvious ids. The controller needs to drive zoom / exposure / focus /
white balance / stabilization and switch cameras, safely, across a range of HALs.

## Decision

### Multi-camera is first-class

`GET /api/cameras` lists selectable cameras and which is active; `POST /api/cameras/active`
switches. `cameraIdList` reports **logical** cameras only, so the catalog also emits
`"<logical>:<physical>"` ids for each physical sub-camera of a logical multi-camera (API 28+).
Opening one opens the logical device but pins the session outputs to that physical sensor via
`OutputConfiguration.setPhysicalCameraId`. A switch is a **disruptive reconfigure** — the service
rebuilds the running pipeline; a switch mid-recording ends the current clip. `activeCameraId`
persists in `DeviceConfig`.

### Capabilities are read per (possibly physical) camera

`GET /api/camera/capabilities` returns declared per-key ranges from a pure
`CameraCharacteristics` read; for a `"0:3"` id it reads that physical sensor's own
characteristics. Optional `cameraId` previews another camera without switching.

### A concrete, validated `CameraControlKeys` set

`GET/POST /api/camera/state` carries an explicit key set (null field = leave on auto). `POST` is
**validate-then-apply** against the active camera's capabilities (`CameraControlValidation`,
framework-free + unit-tested): `400 {error, key}` naming the first offending key, applies
nothing. `POST` replaces the `keys` object wholesale.

### Exclusivity rules baked into the contract

- `zoomRatio` and off-centre `cropRegionNorm` are mutually exclusive in one request (a
  readback-corruption quirk — see
  [`../../quirks/calibration-zoom.md`](../../quirks/calibration-zoom.md)); the rect wins.
- `aeRegionNorm` / `afRegionNorm` are the *alternative* to the shutter/ISO / focus-distance
  dials — applied only alongside `manualExposure` / `manualFocus`; the controller sends exactly
  one.
- Manual white balance needs `AWB_MODE_OFF` + `COLOR_CORRECTION_GAINS` **and** an identity
  `COLOR_CORRECTION_TRANSFORM` — both, or frames go black
  ([`../../quirks/manual-camera-controls.md`](../../quirks/manual-camera-controls.md)).

### Control state spans pipelines and survives restart

The applied state persists in `DeviceConfig.cameraControls` and re-applies to whichever pipeline
(`record` **or** `live`) is running — a state dialled in on the live preview also governs
recording. `CameraControlApply` merges it into the repeating request and re-issues on change
with **no session rebuild**.

### `rotationDegrees` and `videoResolution` live on `/api/camera/state`

Both are camera-pipeline settings, not device config; `rotationDegrees` was moved off
`/api/config`. `videoResolution` is one of `capabilities.outputResolutions` (union of
MediaRecorder + SurfaceTexture ~16:9 sizes, 480p–4K); a change rebuilds the running pipeline.

## Consequences

- On the Pixel 6: physical sub-camera `0:3`, manual exposure (~900× luma swing), manual WB gains
  (~16× channel-balance flip), and manual focus all measurably honoured. On the BLU G5: no
  `MANUAL_SENSOR` / `MANUAL_POST_PROCESSING` / OIS / logical multi-cam — the matching keys `400`,
  only `0`/`1` listed.
- The controller can predict the *honoured* crop for a zoom request from stored calibration data
  (`EffectiveRect`) and overlay it under the zoom-rect picker.
