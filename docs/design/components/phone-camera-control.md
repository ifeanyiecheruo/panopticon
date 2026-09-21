# phone-app — camera control & selection — software design description

*Prerequisite: this description assumes the Android `Camera2` model — `CameraCharacteristics`,
capture requests, the 3A (AE / AF / AWB) controls and their manual overrides, `SCALER_CROP_REGION`
vs `CONTROL_ZOOM_RATIO`, RGGB colour gains, logical vs physical multi-camera, and `@RequiresApi`
class isolation. [`../android-media-primer.md`](../android-media-primer.md) §2 and §8 introduce
all of it for a generalist.*

## 1. Introduction

### 1.1 Purpose

Describe the component that enumerates selectable cameras (including physical sub-cameras),
reports each one's declared capabilities, and validates + applies a manual-control key set to
whichever pipeline is running.

### 1.2 Scope

Covers `camera/CameraCatalog.kt`, `camera/CameraLabels.kt`, `camera/CameraCapabilitiesReader.kt`,
`camera/CameraControlApply.kt`, `camera/CameraControlValidation.kt`, `camera/PhysicalCameraApi28.kt`,
`camera/CameraModels.kt`, `camera/ViewportRect.kt`, `camera/ZoomGeometry.kt`,
`http/routes/CameraRoutes.kt`.

### 1.3 Context

Sits behind `/api/cameras*` and `/api/camera/*`. It doesn't own a pipeline; it hands applied
state to whichever of `CameraGlPipeline` / `LivePipeline` is running, and a switch or
`videoResolution` change asks `PanopticonService` to rebuild that pipeline.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| logical camera | an id `CameraManager` reports directly |
| physical sub-camera | `"<logical>:<physical>"` — a physical sensor of a logical multi-camera, reachable via `setPhysicalCameraId` (API 28+) |
| `CameraControlKeys` | the concrete manual-control key set; a null field = leave that control on auto |
| validate-then-apply | reject the whole request on the first offending key (`400 {error, key}`), apply nothing |
| capability gate | the declared range/flag a key is checked against |

System-wide terms: architecture §1.3.

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[AE / AF / AWB](../architecture.md#acr-ae),
[OIS](../architecture.md#acr-ois),
[ISO](../architecture.md#acr-iso),
[EV](../architecture.md#acr-ev),
[RGGB](../architecture.md#acr-rggb),
[HAL](../architecture.md#acr-hal),
[API](../architecture.md#acr-api) (Android API level),
[ART](../architecture.md#acr-art),
[SDK](../architecture.md#acr-sdk),
[DTO](../architecture.md#acr-dto).

### 1.6 References

- [`../decisions/0008-camera-control-and-multi-camera.md`](../decisions/0008-camera-control-and-multi-camera.md).
- [`../../quirks/manual-camera-controls.md`](../../quirks/manual-camera-controls.md),
  [`../../quirks/calibration-zoom.md`](../../quirks/calibration-zoom.md).
- [`../http-api.md`](../http-api.md) — the full `keys` set and capability gates.
- Consumer: [`controller-live-and-camera.md`](controller-live-and-camera.md).

## 2. Design overview

A read side (catalog + capabilities from pure `CameraCharacteristics`) and a write side
(framework-free validation, then a merge into the live repeating request). Applied state persists
in `DeviceConfig` and re-applies to whichever pipeline runs next, so a state dialled in on the
live preview also governs recording.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `CameraCatalog` | enumerator | Logical ids from `CameraManager` **plus** `"<logical>:<physical>"` entries per physical sub-camera (API 28+). `isActive`, `facing`, `focalLengthMm`. |
| `CameraLabels` | pure heuristic | wide / ultra-wide / tele / front label from relative focal length; "(auto)" suffix for a bare logical id. Framework-free. |
| `CameraCapabilitiesReader` | reader | Pure `CameraCharacteristics` read → declared per-key ranges; reads the *physical* sensor's characteristics for a `"0:X"` id. Dedupes `awbModes` / `videoStabilizationModes` / `opticalStabilizationModes`. API-gated keys funnelled through `ZoomApiCompat`. Emits `outputResolutions` (union of MediaRecorder + SurfaceTexture ~16:9 sizes). |
| `CameraControlValidation` | pure validator | Validate-then-apply: `400 {error, key}` on the first offending key. Framework-free, unit-tested. Does **not** reject an off-centre crop on a `CENTER_ONLY` device — that's the calibration probe's job. |
| `CameraControlApply` | applier | Merges `CameraControlKeys` into the repeating request and re-issues on change — **no session rebuild**. Invariant: always an identity `COLOR_CORRECTION_TRANSFORM` alongside manual WB gains. Carries **no** zoom — see `ZoomGeometry`. |
| `ZoomGeometry` | pure geometry | Fits a drawn rect to the output aspect, composes selections onto the current view (so they compound), maps the slider about the current centre, and splits the result into a centred hardware magnification + a GL crop, interpolating the measured curve. Frame space makes an output-aspect rect a *square*, so the aspect can't drift across repeated zooms. Framework-free, unit-tested. |
| `ZoomCalibrationLut` | pure reader | Reduces the persisted calibration sweep to "what magnification did this ratio actually deliver". Prefers the reported ratio over the reported crop — on API 30+ the crop stays at the full array at every ratio, and believing it would drive the hardware to maximum zoom at any zoom level. Framework-free, unit-tested. |
| `ViewportRect` | pure geometry | Untransforms a viewer-space AE/AF rect back to the sensor through the rotation and the current zoom view — the same view `ZoomGeometry` renders, so a rect lands where the user drew it. |
| `PhysicalCameraApi28` | isolated API-28 path | `OutputConfiguration.setPhysicalCameraId` + `SessionConfiguration`; its own class so ART only verifies it behind the SDK check. |
| `CameraModels` | data | `CameraControlKeys`, `RectNorm`, capability DTOs. Field names match [`../http-api.md`](../http-api.md). |

### 3.2 Dependencies

- Camera2 (`CameraCharacteristics`, `CameraManager`, capture request builders).
- `DeviceConfig` — `activeCameraId`, `cameraControls`, `rotationDegrees`, `videoResolution`.
- `PanopticonService` — a switch or `videoResolution` change triggers a pipeline rebuild.

### 3.3 Interfaces

- **Provided:** `/api/cameras`, `/api/cameras/active`, `/api/camera/capabilities`,
  `/api/camera/state`.
- **Required:** Camera2; `DeviceConfig`; the pipeline-rebuild hook.

### 3.4 Data

- `DeviceConfig.cameraControls` — `CameraControlSpec` (`manualControlEnabled` + `CameraControlKeys`),
  plus `rotationDegrees` and `videoResolution`. Persisted; kept whole across mode switches and
  restarts.

### 3.5 Processing and behaviour

- A camera switch is a disruptive reconfigure; a switch mid-recording ends the current clip.
- Zoom is split between a **centred** hardware magnification and a GL crop, decided against this
  phone's own calibration sweep (`ZoomCalibrationLut` → `ZoomGeometry.split`). A centred zoom uses
  the camera for all of it (measured: 1x–7x at `glResidual` 1.00 on the Pixel 6); a selection
  hugging an edge gets none, because no centred field of view containing it is zoomed, and GL
  does the work instead. The hardware is never asked to crop past the selection.
- `rotationDegrees` (0/90/180/270) and `videoResolution` are accepted + persisted even in
  `standby`, applied when a pipeline next starts.
- On the Pixel 6: physical sub-camera `0:3`, manual exposure (~900× luma swing), manual WB gains
  (~16× channel-balance flip), manual focus — all measurably honoured. On the BLU G5: the
  unsupported keys `400`, only `0`/`1` listed.

## 4. Design rationale and decisions

- **Multi-camera first-class; logical + physical ids** —
  [`0008`](../decisions/0008-camera-control-and-multi-camera.md): `cameraIdList` hides
  physical sensors, so the catalog synthesises `"<logical>:<physical>"` ids.
- **Validate-then-apply, framework-free** — the validator is pure so it can be unit-tested
  exhaustively against capability sets without a device.
- **Exclusivity rules in the contract** — a zoom rect vs the zoom slider, region vs dial for
  AE/AF, and the identity `COLOR_CORRECTION_TRANSFORM` requirement are all reproduced-quirk
  driven ([`../../quirks/manual-camera-controls.md`](../../quirks/manual-camera-controls.md),
  [`../../quirks/calibration-zoom.md`](../../quirks/calibration-zoom.md)).
- **Rects are viewer-space on the wire, absolute on the phone** — the controller sends what the
  user drew over the preview and nothing else; `ViewportRect` + `ZoomGeometry` own every
  transform. A viewer-space rect is a *relative* instruction, so it travels in a request-only
  `*SelectNorm` field, separate from the absolute state it produces
  ([`../http-api.md`](../http-api.md), "Coordinate spaces").
- **Zoom is not a capture key** — it is split between a hardware crop and a GL crop by
  `ZoomGeometry`, so it lives with the pipelines' shader rect rather than in
  `CameraControlApply`. The split never lets the hardware crop past the selection; GL makes up the
  rest by upscaling, which adds no detail (the capture resolution is deliberately not raised).
- **State spans pipelines** — one persisted `CameraControlSpec` governs both `record` and
  `live`, so a value tuned live also applies to recording.
