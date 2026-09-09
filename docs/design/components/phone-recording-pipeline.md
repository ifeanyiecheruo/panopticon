# phone-app — recording pipeline — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that records motion-triggered video to the phone's ring buffer as gapless
~10s segments with pre-roll, on a single camera stream.

### 1.2 Scope

Covers `camera/CameraGlPipeline.kt`, `motion/MotionDetector.kt`,
`motion/RecordingPhaseController.kt`, `clips/SegmentStore.kt`, `clips/SegmentEntry.kt`, and
`src/debug/GlSoakTest.kt`. The `live` pipeline is a separate component (see §1.6).

### 1.3 Context

This is what runs while the phone is in `record` mode — the default. It is constructed and torn
down by `PanopticonService` and feeds `SegmentStore`, which the HTTP server reads for
`/api/segments…`.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| segment | one recorded `.mp4` file (system-wide term; see architecture §1.3) |
| pre-roll | encoded frames from *before* motion was detected, held in an in-RAM ring and prepended to a segment |
| fan-out | rendering one camera frame to two GL targets — a small analysis FBO and the encoder surface |
| gapless rotation | rolling to the next segment file at a keyframe with no encoder teardown, so `endMs[k] == createdAtMs[k+1]` |
| trailer tail | the interval recorded after motion stops before the muxer finalises |
| reconcile | `SegmentStore` startup pass that drops index entries whose file is gone and re-probes untracked files |

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[GL / GPU](../architecture.md#acr-gl),
[FBO](../architecture.md#acr-fbo),
[EGL](../architecture.md#acr-egl),
[OES](../architecture.md#acr-oes),
[AVC](../architecture.md#acr-avc) (H.264),
[AU](../architecture.md#acr-au),
[PTS](../architecture.md#acr-pts),
[HAL](../architecture.md#acr-hal),
[ANR](../architecture.md#acr-anr),
[RAM](../architecture.md#acr-ram),
[JSON](../architecture.md#acr-json),
[API](../architecture.md#acr-api) (Android API level).

### 1.6 References

- [`../decisions/0004-recording-pipeline.md`](../decisions/0004-recording-pipeline.md),
  [`0005`](../decisions/0005-motion-detection.md),
  [`0006`](../decisions/0006-segments-clips-and-tombstones.md).
- [`../../quirks/camera2-recording-pipeline.md`](../../quirks/camera2-recording-pipeline.md).
- [`../http-api.md`](../http-api.md) — `GET /api/segments` shape.
- Peers: [`phone-camera-control.md`](phone-camera-control.md) (control state merged into the
  repeating request), [`phone-service-and-http.md`](phone-service-and-http.md).

## 2. Design overview

One camera stream feeds a `SurfaceTexture`; a GL thread fans each frame out to a downscaled
analysis FBO and to a **continuously running** `MediaCodec` encoder. A motion verdict off the
analysis FBO gates a `MediaMuxer` that opens (primed from an in-RAM pre-roll ring), rolls
segment files at keyframes, and closes after a trailer tail. `SegmentStore` is the in-memory
authoritative index of what's on disk.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `CameraGlPipeline` | pipeline (record mode) | Camera2 → one `SurfaceTexture` → GL thread renders each frame twice: a downscaled FBO copy for `glReadPixels`, and the full frame to an EGL window surface on `MediaCodec.createInputSurface()`. Continuous H.264 encoder; a drain thread keeps an in-RAM pre-roll ring of encoded access units. |
| `MotionDetector` | analyser | Frame-difference on a 32×24 luma grid off the GL readback; verdict gates the muxer. Threshold chosen by `motionSensitivity`. |
| `RecordingPhaseController` | state machine | idle(armed) → recording → trailer-tail → idle; drives muxer open/close. |
| motion-gated `MediaMuxer` | writer | On motion, opens at the next keyframe primed from the ring back to ~`preRollMs` (default 3s); rotates by swapping muxers at a keyframe, PTS rebased per segment, `createdAtMs` chained. |
| `SegmentStore` | index | Parsed index held in memory as the authoritative copy; reads hit it directly; mutations update the map and schedule **one coalesced background flush**; `deleteAll(filenames)` = N removals + one flush; `reconcile()` on launch. Backed by `SharedPreferences` (`panopticon_clips` / `clips_index_json`) + a `clips/` directory — names unchanged for historical reasons. |

### 3.2 Dependencies

- Camera2, `MediaCodec`, `MediaMuxer`, `MediaExtractor` (keyframe-cadence logging).
- `DeviceConfig` — `videoResolution`, `motionSensitivity`.
- The camera-control component — `CameraControlApply` merges keys into the repeating request.

### 3.3 Interfaces

- **Provided:** to `PanopticonHttpServer` — the segment list/serve/delete surface and recording
  status for `/api/status`.
- **Required:** Camera2 / media stack; `DeviceConfig`; camera-control state.

### 3.4 Data

Segment entry: `{filename, createdAtMs, durationMs, endMs, sizeBytes, width, height}` — matches
`GET /api/segments`. The index is one JSON blob in `SharedPreferences`.

### 3.5 Processing and behaviour

- One capture session for the pipeline's life; only the muxer opens/closes.
- Output size (encoder/EGL surface) is decoupled from camera-source size (`SurfaceTexture`
  buffer): `pickSourceSize()` drives the source at a sensor-aspect size and a `uTexCrop` shader
  uniform centre-crops to the output aspect — otherwise >1080p 16:9 on a 4:3 sensor is
  anamorphically squashed.
- **Verified:** 10-min GL soak on the BLU G5 (24 fps camera==rendered==encoded, 0 dropped, 59
  rotations, flat memory); gapless real recording on both devices
  (`start[k+1] − start[k] − dur[k]` within ~8ms BLU / ~1ms Pixel), with pre-roll; delete-storm
  resilience on the BLU (delete a 35-segment clip, then hammer 8×, no ANR).

## 4. Design rationale and decisions

- **Single camera stream + GPU fan-out** — [`0004`](../decisions/0004-recording-pipeline.md):
  weak HALs (BLU G5's Unisoc SC9863A) reject a two-stream `[analysis + video]` config; the
  design evolved MediaRecorder → MediaCodec+MediaMuxer → this.
- **Continuous encoder + motion-gated muxer + pre-roll ring** —
  [`0004`](../decisions/0004-recording-pipeline.md): keeps rotation gapless and lets a
  segment start *before* motion.
- **Frame-difference motion, un-tuned** — [`0005`](../decisions/0005-motion-detection.md);
  on-device tuning is [`../../status/motion-detection-tuning.md`](../../status/motion-detection-tuning.md).
- **`SegmentStore` in-memory + coalesced flush** — the old per-op re-parse/re-serialise ANR'd
  the BLU on delete storms; the in-memory authoritative copy fixed it. A hard kill losing an
  unflushed mutation is harmless (`reconcile()` recovers).
- **Trade-off:** gapless rotation makes the controller's grouping gap a tight 500 ms
  ([`0006`](../decisions/0006-segments-clips-and-tombstones.md)).
- Device quirks: [`../../quirks/camera2-recording-pipeline.md`](../../quirks/camera2-recording-pipeline.md).
