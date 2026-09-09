# Calibration zoom probe

Device context and the "Reconfirmed" / "Carried forward" convention: see [`README.md`](README.md).

The empirical zoom probe (`phone-app`'s `calibration/CalibrationRunner`) was built and run to
completion on both the **Pixel 6 (API 36)** — `CONTROL_ZOOM_RATIO` + logical-multi-camera path —
and the **BLU G5 (API 28)** — legacy `SCALER_CROP_REGION` path. Findings below.

## An API-gated `CaptureResult`/`CaptureRequest` key throws `NoSuchFieldError` even behind a runtime `SDK_INT` guard
**What we assumed:** wrapping a use of `CaptureResult.CONTROL_ZOOM_RATIO` (API 30) in
`if (Build.VERSION.SDK_INT >= R) { ... }` is enough to keep it off older devices.
**What actually happens (BLU G5, API 28):** ART verifies the whole method when it's first run
and resolves *every* field reference in it, guard or not — so the first zoom sample threw
`java.lang.NoSuchFieldError: No static field CONTROL_ZOOM_RATIO ... in class CaptureResult`.
Same trap for `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29), `CONTROL_ZOOM_RATIO_RANGE`
(API 30), and `getPhysicalCameraIds()` / `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`
(API 28) on anything below their level.
**What we do:** every such key lives in its own `@RequiresApi` object
(`calibration/ZoomApiCompat.kt`: `ZoomRatioApi30`, `ActivePhysicalIdApi29`, `LogicalCameraApi28`).
A separate class is only loaded/verified when it's actually referenced, which only happens
inside the SDK check — so older devices never touch the missing field.
(Same class of ART-verification trap as `android-service.md`'s `startForeground` overload
`NoSuchMethodError` — a method/field reference resolved at verify time, not call time.)
**Where:** `calibration/ZoomApiCompat.kt`, `calibration/CalibrationRunner.kt` (`hasZoomRatio` /
`hasActivePhysicalId`).

## Releasing an `ImageReader` while a frame callback is in flight is a native `SIGSEGV` (Pixel 6)
**What we assumed:** wrapping the `onImageAvailable` body in `try/catch` + a lock is enough to
release the reader safely from another thread when a resolution's probe finishes.
**What actually happens (Pixel 6, mid-sweep, ~resolution 16/24):** `reader.close()` on the
sweep coroutine thread unmaps the native `YUV_420_888` plane buffer while an `onImageAvailable`
callback on the reader's Handler thread is still inside `buffer.get(bytes)` → `Fatal signal 11
(SIGSEGV), code 1 (SEGV_MAPERR) ... in tid PanopticonCalib`. A `catch` can't save you from a
native memory fault; the process just dies (and `START_STICKY` restarts the service, losing the
run). Not seen on the BLU G5 — its slower frame rate made the window much smaller.
**What we do:** teardown order is now `stopRepeating()` → set a `readerClosing` flag under the
frame lock → `setOnImageAvailableListener(null)` → post a no-op to the callback Handler and
await it (guarantees any running callback has returned, since that thread is serial) → only
then `session.close()` / `reader.close()`.
**Where:** `calibration/CalibrationRunner.kt` (`probeResolution`, the `finally` block).

## Closing a `CameraCaptureSession` and opening the next one on the same still-open `CameraDevice` disconnects the device entirely (BLU G5)
**What we assumed:** keep one `CameraDevice` open for a camera and cycle
`ImageReader` + `CameraCaptureSession` per output resolution.
**What actually happens (BLU G5):** the *first* resolution probes fine; creating the *second*
session's request throws `IllegalStateException: CameraDevice was already closed` /
`ServiceSpecificException: The camera device has been disconnected (code 4)` — the HAL dropped
the whole device on the session teardown, not just the session. The old prototype flagged the
adjacent "two concurrent record + analysis surfaces never configure" case; this is a related
weak-HAL failure on plain session recycling.
**What we do:** the probe opens a **fresh `CameraDevice` per resolution** (open → one session →
sweep the zoom steps → close → ~600 ms settle → next). Slower, but every resolution is actually
probed. `openCameraDeviceWithRetry` also retries `ERROR_MAX_CAMERAS_IN_USE` (a just-closed
camera reports busy briefly on this device), and a per-run 1.2 s grace delay covers the motion
pipeline's still-in-flight teardown when calibration is entered straight from RECORD.
**Where:** `calibration/CalibrationRunner.kt` (`probeCamera`, `openCameraDeviceWithRetry`).

## Interleaving `CONTROL_ZOOM_RATIO` and `SCALER_CROP_REGION` in one session's repeating request corrupts the readback (Pixel 6 front camera)
**What we did first:** the per-zoom "position honoured?" sub-probe swapped the session's
*repeating* request to an off-centre `SCALER_CROP_REGION`, then the next step swapped it back to
`CONTROL_ZOOM_RATIO`.
**What actually happens (Pixel 6, camera 1 / front):** the primary readback went junk —
`requested 1.19× → reported 1.0×`, `requested 1.43× → reported 1.19×` (lagging one step),
`ratioHonored = false` almost everywhere, `positionHonored = false` with six fail ratios. The
back camera (0) was fine; the front camera can't cleanly alternate the two zoom controls on the
repeating stream.
**What we do:** the repeating request now holds *one* control for the whole resolution, and
**both the primary readback and the position sub-probe are one-shot `session.capture()` calls**
(`captureOneShot`) whose `TotalCaptureResult` is guaranteed to belong to that request. With
that, the front camera reads back clean (`reported ≈ requested` across 1.0–10.0×,
`positionHonored = true`). Lesson for any future Camera2 probing: don't infer a value from
"latest result of a churning repeating request" — capture the exact request and read *its*
result.
**Where:** `calibration/CalibrationRunner.kt` (`captureOneShot`, `probeResolution`).

## "Position honoured?" can't be answered from `SCALER_CROP_REGION` metadata alone — the HAL can echo a crop it doesn't apply
**What we did first:** `positionHonored` = "did the reported `SCALER_CROP_REGION` centre match
the off-centre request (within tolerance)". On the Pixel 6 back camera (`SCALER_CROPPING_TYPE =
CENTER_ONLY`) that came back `true` — which is impossible if the device really can't do an
off-centre crop.
**What we do now:** the off-centre probe also **grabs a frame** and compares its pixels to the
centred frame at the same zoom (`ZoomMath.frameShifted` — grid-subsampled mean-abs-diff,
luma-normalised, null verdict below a min-brightness). `positionHonored` is now the *pixel*
verdict; `positionMetadataMatch` is recorded separately, and a ratio where the metadata matched
but the pixels didn't move is added to `positionMetadataLiedRatios` — that's the old prototype's
"the device lies about it", now measured directly rather than trusted-by-metadata.
**Where:** `calibration/ZoomMath.frameShifted`, `probeResolution`, `summariseCamera`.

## Frame-sharpness is normalised now, but still wants a lit, textured target
`ZoomMath.sharpness` is variance-of-Laplacian **divided by mean-luma²** (the raw Laplacian
scales with luma amplitude), taken as the **median of ~4 frames** per zoom step, with the
baseline = the sharpest of the first four samples. `deriveQualityCollapse` now requires a
**sustained** drop (below 0.5× baseline and staying there), so a single noisy frame no longer
fires it. That removes most of the lighting sensitivity, but a genuinely blank / dark scene
still can't tell blur from "nothing to focus on" — for an absolute `qualityCollapseRatio`,
point the camera at a resolution chart. The *shape* (digital zoom softens past ~2–3× on a main
sensor, faster on a fixed-focus front camera) is the trustworthy part.
**Where:** `calibration/ZoomMath.sharpness` / `medianSharpness` / `deriveQualityCollapse`.

## Device findings

All from the current probe (frame-content position check + normalised sharpness), against a
lit indoor scene.

**Pixel 6 (API 36), camera 0 / back — logical multi-camera, physicals `2` + `3`:**
`CONTROL_ZOOM_RATIO_RANGE = 0.67–7.0`, `SCALER_CROPPING_TYPE = CENTER_ONLY`.
- **Optical→digital handoff at 1.15×**, empirically: `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`
  is `3` (ultrawide, `LENS_FOCAL_LENGTH` 2.35 mm) for requested ratios ≤ 0.96×, flips to `2`
  (main wide, 6.81 mm) at 1.15× and stays to 7.0×. `opticalRange 0.67–1.15`, `digitalRange 1.15–7.0`.
- **Every requested ratio honoured** across 0.67–7.0×, 24/24 output sizes.
- **Off-centre position IS honoured** — the pixels actually shift (`positionFrameShifted = true`
  at the majority of probed ratios), and the metadata agrees, so `positionMetadataLiedRatios`
  is empty. `SCALER_CROPPING_TYPE = CENTER_ONLY` did *not* stop an off-centre `SCALER_CROP_REGION`
  request from taking effect on API 36.
- **`qualityCollapseRatio ≈ 2.8×`** — the normalised sharpness holds through ~2× then drops
  sustainedly (rel-to-best falls to ~0.6 at 2.8×, ~0.25 at 4×, ~0.10 at 7×). With the lit scene
  + median-of-frames + sustained-drop rule this is a defensible number: digital zoom on the
  main sensor visibly softens past ~2.8×. (The ultrawide segment, 0.67–0.96×, also reads softer
  than the 1.15× main-sensor baseline — real, the ultrawide is a lower-grade lens.)

**Pixel 6, camera 1 / front — single sensor:** `CONTROL_ZOOM_RATIO_RANGE = 1.0–10.0`,
`CENTER_ONLY`, all-digital. Every ratio honoured 1.0–10.0×, 24/24 sizes. **Off-centre position
is NOT honoured** (`positionFrameShifted = false` at every probe) — but the metadata *also*
reports a centred crop, so it's honest, not a lie (`positionMetadataLiedRatios` empty). Sharpness
collapses hard and early (fixed-focus tiny sensor); the exact `qualityCollapseRatio` is
resolution-sensitive on this camera and shouldn't be quoted precisely.

**BLU G5 (API 28), both cameras — single physical sensor, legacy `SCALER_CROP_REGION` path:**
`crossoverMethod = "single-camera"`, `SCALER_AVAILABLE_MAX_DIGITAL_ZOOM = 2.0`, no
`CONTROL_ZOOM_RATIO_RANGE`, `SCALER_CROPPING_TYPE = FREEFORM`. Ratio (crop-area) honouring clean
across all 24 sizes. **Position: inconclusive** — with only a 2.0× max, an off-centre shift
clears the "visible fraction of the frame" gate at just the top ~1.7–2.0× band, too few probes
(and too noisy a scene) to vote confidently.

**Net:** ratio honouring and the optical/digital crossover are solid on both devices. On the
Pixel 6, an off-centre zoom rect **is** honoured on the back camera and **is not** on the front
camera — and in neither case did the device lie about it in metadata. The old prototype's
"`SCALER_CROP_REGION` position isn't honoured, and the device lies about it" did **not
reproduce** here; re-check on the specific hardware it came from if it matters.

## Carried forward, not yet re-verified in this project

Adopted from `panopticon-prototype/QUIRKS.md`, not independently re-tested against the Pixel 6 /
BLU G5 - see [`README.md`](README.md#the-carried-forward-tag).

- `SCALER_CROP_REGION` position isn't honored, and the device lies about it — the calibration
  probe measures this by frame content (`positionHonored` / `positionFailRatios` /
  `positionMetadataLiedRatios` per camera). **Did not reproduce on the Pixel 6:** back camera
  honours an off-centre crop, front camera doesn't, and neither lies about it in metadata (see
  "Device findings"). Re-check on the exact hardware the old finding came from if it matters.
- Digital zoom quality collapses well below the declared max, invisible to crop-region metadata —
  **confirmed on the Pixel 6 back camera**: `qualityCollapseRatio ≈ 2.8×` against a declared 7×
  max (normalised sharpness, lit scene). Crop-region metadata reports the ratio as fully
  honoured throughout - the softening is only visible in the pixels.
- Crop readback is unreliable — the probe reads `SCALER_CROP_REGION` back from a one-shot
  capture of the exact request; clean on both devices once it stopped reading stale
  repeating-request results.
