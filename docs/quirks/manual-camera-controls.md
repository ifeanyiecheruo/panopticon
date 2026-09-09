# Manual Camera2 controls + multi-camera

Device context and the "Reconfirmed" / "Carried forward" convention: see [`README.md`](README.md).

The `/api/camera/*` slice is now implemented (`camera/CameraCapabilitiesReader.kt` /
`CameraControlApply.kt` / `CameraControlValidation.kt`). Findings from running it on real
hardware over `adb forward`:

- **`cameraIdList` is logical cameras only.** Pixel 6: `["0" back wide, "1" front]` — the
  ultrawide is a physical sub-camera of logical `0`, not a separate switchable id (reached via
  the zoom crossover, which the calibration probe already maps). BLU G5: `["0" back, "1" front]`.
  So "multi-camera switch" in practice is back↔front on both these devices.
- **`AE_MODE_OFF` (manual exposure) needs `MANUAL_SENSOR` — confirmed both ways.** Pixel 6 back
  reports `hasManualSensor=true` (`exposureTimeRangeNs` 26 µs–8.3 s, `sensitivityRange`
  44–11377); the BLU G5 reports `false`, so `POST /api/camera/state {manualExposure:true}` →
  `400 {"key":"manualExposure"}` and the controller UI hides the disclosure. The capability
  gate is `REQUEST_AVAILABLE_CAPABILITIES ∋ MANUAL_SENSOR`, read once per camera.
- **`CONTROL_ZOOM_RATIO` vs legacy `SCALER_CROP_REGION` — both live.** Pixel 6 (API 36):
  `zoomViaRatioApi=true`, range `0.67–7.0`. BLU G5 (API 28): `zoomViaRatioApi=false`, range
  `1.0–2.0` from `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`; `zoomRatio` there is applied as a centred
  `SCALER_CROP_REGION`. `CONTROL_ZOOM_RATIO_RANGE` is only ever touched through
  `calibration/ZoomRatioApi30` (the `NoSuchFieldError`-behind-a-guard trap — see
  [`calibration-zoom.md`](calibration-zoom.md)); the BLU G5 ran the capabilities read clean.
- **`SCALER_CROPPING_TYPE`:** Pixel 6 back = `CENTER_ONLY`, BLU G5 = `FREEFORM`. Validation
  does **not** reject an off-centre `cropRegionNorm` on a `CENTER_ONLY` device — whether the
  HAL honours it is what the calibration probe measures (and the Pixel 6 back honoured an
  off-centre crop last sweep despite declaring `CENTER_ONLY`).
- **A camera switch is a pipeline rebuild.** The service releases + reconstructs
  `CameraGlPipeline` on `activeCameraId` change; the release path logs a
  `JobCancellationException` at `E` from the cancelled supervisor coroutine (benign — same as
  a mode switch), then "GL + encoder up" / "capture session up" for the new camera. A switch
  mid-recording ends the current clip.
- **Physical sub-camera targeting works on the Pixel 6.** A `"0:3"` id opens logical `0` and
  builds the session with `OutputConfiguration.setPhysicalCameraId("3")` +
  `SessionConfiguration` (`camera/PhysicalCameraApi28.kt`). The Lyric HAL logs
  `Created a device session for camera 0 with 2 physical cameras: 2 3` and the encoder stream
  binds to `camera_id: RearWide, is_framework_physical_stream: 1`; the encoder input Surface
  (IMPLEMENTATION_DEFINED) is an accepted physical-stream format at 1280×720. Capabilities for
  a `"0:X"` id are read from that physical sensor's own characteristics — e.g. `0:3` (ultra-wide)
  reports `minFocusDistance 0` (fixed focus → `hasManualFocus:false`) and
  `opticalStabilizationModes:[0]`. A benign `No OisController found for physical camera` warning
  is logged when the physical sensor has no OIS.
- **Manual exposure / focus / white balance *values* ARE honoured on the Pixel 6** (contradicts
  the prototype's blanket "manual controls aren't reliably honored"). Measured via ffmpeg on
  live `.ts` frames: `SENSOR_EXPOSURE_TIME`+`SENSOR_SENSITIVITY` give a **~900× mean-luma swing**
  (1/4000 s ISO 55 → luma 0.1 vs 1/25 s ISO 4000 → luma 89); manual `COLOR_CORRECTION_GAINS`
  **flip the R/B channel ratio ~16×** between red-heavy and blue-heavy gains; `LENS_FOCUS_DISTANCE`
  measurably shifts frame sharpness between near and infinity. Re-check per device, but the
  keys are real here.
- **`COLOR_CORRECTION_MODE = TRANSFORM_MATRIX` needs BOTH gains and the 3×3 transform set.**
  Setting `AWB_MODE_OFF` + `COLOR_CORRECTION_MODE=TRANSFORM_MATRIX` + `COLOR_CORRECTION_GAINS`
  but leaving `COLOR_CORRECTION_TRANSFORM` unset produced **fully black frames** on the Pixel 6.
  `camera/CameraControlApply.kt` now always sets an identity `ColorSpaceTransform` alongside the
  gains, so the per-channel gains do the white-balance work and the transform leaves hue alone.

## Carried forward, not yet re-verified in this project

Adopted from `panopticon-prototype/QUIRKS.md`, not independently re-tested against the Pixel 6 /
BLU G5 - see [`README.md`](README.md#the-carried-forward-tag).

- ~~Manual controls aren't reliably honored at all~~ — **re-verified and mostly false on the
  Pixel 6**: exposure time, ISO, RGGB white-balance gains and focus distance are all honoured
  with large measured effects (see the section above).
  `MANUAL_SENSOR` / `MANUAL_POST_PROCESSING` capability gating is what decides whether a device
  accepts them at all; the BLU G5 has neither and returns `400`.
- `AE_MODE_OFF` (manual exposure) needs `MANUAL_SENSOR` — **confirmed** (gated in
  `CameraCapabilitiesReader`/`CameraControlValidation`; BLU G5 has no `MANUAL_SENSOR`).
- `CONTROL_AE_LOCK` is a metering freeze, not a manual-exposure dial — carried as-is; `aeLock`
  is labelled "metering freeze" in the controller UI.
- `CONTROL_MODE=USE_SCENE_MODE` silently overrides the individual 3A controls — not exercised;
  `CameraControlApply` never sets a scene mode.
- `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` reports duplicate values — **handled**:
  `camera/CameraCapabilitiesReader.kt` `.distinct()`s it (and `CONTROL_AWB_AVAILABLE_MODES`)
  before it reaches `videoStabilizationModes` / `awbModes` in the capabilities response.
