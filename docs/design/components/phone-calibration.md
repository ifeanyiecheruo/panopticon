# phone-app — calibration — software design description

*Prerequisite: this description assumes the Android `Camera2` model — what
`CameraCharacteristics` declares vs what `CaptureResult` reports, `SCALER_CROP_REGION` /
`CONTROL_ZOOM_RATIO`, `ImageReader` read-back, and `@RequiresApi` class isolation.
[`../android-media-primer.md`](../android-media-primer.md) §2, §5, §7 and §8 cover it for a
generalist.*

## 1. Introduction

### 1.1 Purpose

Describe the component that empirically measures what each camera's HAL actually honours for
zoom (ratio, off-centre position, quality) versus what Camera2 declares, and persists the result
for the controller to pull.

### 1.2 Scope

Covers `calibration/CalibrationRunner.kt`, `calibration/ZoomMath.kt`,
`calibration/ZoomApiCompat.kt`, `calibration/CalibrationModels.kt`,
`calibration/CalibrationStore.kt`, `http/routes/CalibrationRoutes.kt`, and
`src/debug/DebugCalibrationReceiver.kt`. The controller-side consumer is a separate component
(see §1.6).

### 1.3 Context

Runs only from `standby` mode, because it needs exclusive camera access. Its persisted result is
what `GET /api/calibration/result` serves without a `runId`, and what the controller ingests
into its model-keyed store right after pairing.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| sweep | one full `POST /api/calibration/start` run — every camera × every output size × ~14 zoom steps |
| optical↔digital crossover | the zoom ratio at which a logical multi-camera hands off between physical sensors |
| `positionHonored` | whether an off-centre crop request actually moved the pixels (not just the metadata) |
| `qualityCollapseRatio` | the zoom ratio past which normalised sharpness drops sustainedly |
| `perResolution` | the full per-output-size list of `ZoomSample`s in the result |

System-wide term "calibration": architecture §1.3.

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[HAL](../architecture.md#acr-hal),
[API](../architecture.md#acr-api) (Android API level),
[ART](../architecture.md#acr-art),
[SDK](../architecture.md#acr-sdk),
[FBO](../architecture.md#acr-fbo),
[SIGSEGV](../architecture.md#acr-sigsegv),
[RGGB](../architecture.md#acr-rggb),
[EV](../architecture.md#acr-ev),
[ISO](../architecture.md#acr-iso),
[DTO](../architecture.md#acr-dto).

### 1.6 References

- [`../decisions/0009-calibration-model.md`](../decisions/0009-calibration-model.md),
  [`0010`](../decisions/0010-mode-state-machine.md).
- [`../../quirks/calibration-zoom.md`](../../quirks/calibration-zoom.md) — device findings and
  the weak-HAL quirks the runner works around.
- [`../http-api.md`](../http-api.md) — `/api/calibration/*`.
- Consumer: [`controller-calibration-store.md`](controller-calibration-store.md).

## 2. Design overview

A cancellable sweep engine that, for each camera and output size, issues a geometric range of
zoom requests and records what the HAL did — reading back the effective crop from a one-shot
capture, tracking the active physical camera, scoring sharpness, and (for off-centre crops)
checking both the metadata round-trip and the actual pixel shift. Results are reduced to
per-camera summaries and written to disk.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `CalibrationRunner` | sweep engine | Opens a **fresh `CameraDevice` per resolution** (some HALs disconnect the device on plain session recycling). Reads the effective crop from a one-shot capture; tracks the active physical camera; measures brightness-normalised median-of-frames sharpness; for an off-centre crop, checks the `SCALER_CROP_REGION` metadata round-trip **and** `frameShifted`. Cancellable; completed cameras keep partial results. |
| `ZoomMath` | pure helpers | `sharpness` (variance-of-Laplacian / mean-luma²), `medianSharpness`, `deriveQualityCollapse` (sustained-drop rule), `frameShifted` (grid-subsampled mean-abs-diff). Framework-free, unit-tested. |
| `ZoomApiCompat` | `@RequiresApi` objects | `ZoomRatioApi30`, `ActivePhysicalIdApi29`, `LogicalCameraApi28` — each API-gated key isolated so ART only verifies it behind the SDK check. |
| `CalibrationModels` | data | `deviceIdentity`; per camera `opticalRange` / `digitalRange` / `crossoverRatio` / `positionHonored` / `positionMetadataLiedRatios` / `qualityCollapseRatio` / `perResolution` + a thin `steps` summary. Shape matches [`../http-api.md`](../http-api.md). |
| `CalibrationStore` | persistence | Last completed result written to disk; served from `GET /api/calibration/result` without a `runId`, including after an app restart. |
| `CalibrationRoutes` | route handler | `POST /api/calibration/start` (`409` in `record`, `422` if no cameras), `GET /api/calibration/status`, `DELETE /api/calibration/:runId`, `GET /api/calibration/result`. |

### 3.2 Dependencies

- Camera2 (exclusive access — `standby` only), `ImageReader`, on-device storage.
- `PanopticonService` — enforces the `standby` precondition and the ~1.2s grace on entry from
  `record`.

### 3.3 Interfaces

- **Provided:** `/api/calibration/*`.
- **Required:** the camera stack; disk.

### 3.4 Data

- The persisted last-completed result (JSON on disk).
- Transient per-run state: `runId`, progress, partial per-camera maps.

### 3.5 Processing and behaviour

- Runs only from `standby`; a per-run ~1.2s grace covers a motion pipeline still tearing down.
- Teardown order guards an `ImageReader.close()` / in-flight-callback SIGSEGV race (Pixel 6).
- Long and rare: tens of minutes; meant to be run once. `MAX_RESOLUTIONS_PER_CAMERA` is a safety
  net.
- Wants a lit, textured scene; against a blank/dark scene `frameShifted` and
  `qualityCollapseRatio` go inconclusive.

## 4. Design rationale and decisions

- **Device-wide empirical sweep** — [`0009`](../decisions/0009-calibration-model.md):
  the risk of Camera2 lying is per-camera, not just on the default one.
- **Persisted on the phone** — so the controller can pull it without asking for a re-run.
- **Fresh `CameraDevice` per resolution, one-shot readback, `@RequiresApi` isolation** — direct
  responses to reproduced weak-HAL / ART-verification quirks; see
  [`../../quirks/calibration-zoom.md`](../../quirks/calibration-zoom.md).
- **Pixel-shift check, not metadata alone** — a HAL can echo a crop it doesn't apply; the
  `frameShifted` verdict is the authority, with the metadata mismatch recorded separately.
