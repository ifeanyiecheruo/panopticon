# phone-app — camera control & selection — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that enumerates selectable cameras (including physical sub-cameras),
reports each one's declared capabilities, and validates + applies a manual-control key set to
whichever pipeline is running.

### 1.2 Scope

Covers `camera/CameraCatalog.kt`, `camera/CameraLabels.kt`, `camera/CameraCapabilitiesReader.kt`,
`camera/CameraControlApply.kt`, `camera/CameraControlValidation.kt`, `camera/PhysicalCameraApi28.kt`,
`camera/CameraModels.kt`, `http/routes/CameraRoutes.kt`.

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
| `CameraControlApply` | applier | Merges `CameraControlKeys` into the repeating request and re-issues on change — **no session rebuild**. Invariants: never `SCALER_CROP_REGION` + `CONTROL_ZOOM_RATIO` in one request; always an identity `COLOR_CORRECTION_TRANSFORM` alongside manual WB gains. |
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
- **Exclusivity rules in the contract** — `zoomRatio` vs `cropRegionNorm`, region vs dial for
  AE/AF, and the identity `COLOR_CORRECTION_TRANSFORM` requirement are all reproduced-quirk
  driven ([`../../quirks/manual-camera-controls.md`](../../quirks/manual-camera-controls.md),
  [`../../quirks/calibration-zoom.md`](../../quirks/calibration-zoom.md)).
- **State spans pipelines** — one persisted `CameraControlSpec` governs both `record` and
  `live`, so a value tuned live also applies to recording.
