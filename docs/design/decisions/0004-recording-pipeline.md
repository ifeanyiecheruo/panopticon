# 0004 — Recording pipeline architecture

**Status:** Accepted · implemented (phone-app). Component spec:
[`../components/phone-recording-pipeline.md`](../components/phone-recording-pipeline.md).

## Context

The record path must run on old/low-end phones with **one** hardware encoder and HALs that
reject multi-stream capture configs — concretely, the BLU G5's Unisoc SC9863A HAL fails a
session targeting `[analysis stream + video stream]` (`sendRequestsBatch` → `-ENOSYS`, device
errors out). It also needs pre-roll (footage from *before* motion was detected) and gapless
~10s segment rotation so a motion event isn't chopped by session rebuilds.

The design evolved: `MediaRecorder` → `MediaCodec` + `MediaMuxer` (encoder-surface input) →
the current GPU-texture-fan-out. The first two both required the rejected two-stream config.

## Decision

### One camera stream, fanned out on the GPU

Camera2 feeds **one** `SurfaceTexture`. A GL thread samples that external-OES texture per frame
and renders it twice: a small downscaled copy to an FBO (`glReadPixels` → motion analysis) and
the full frame to an EGL window surface on `MediaCodec.createInputSurface()`. No second camera
stream, so weak HALs never see a multi-stream config.

### The encoder runs continuously; the muxer is motion-gated

The `MediaCodec` H.264 encoder is never stopped. A drain thread keeps an in-RAM **pre-roll
ring** of recent encoded access units. On motion, at the next keyframe a `MediaMuxer` opens,
is primed from the ring back to ~`preRollMs` (default 3s) before the motion, and writes forward.
After motion stops plus a `trailerMs` tail, the muxer finalises.

### Gapless rotation via muxer swap

At the segment interval a sync frame is requested; the muxer rolls on the next
`BUFFER_FLAG_KEY_FRAME`, PTS rebased per segment, `createdAtMs` chained so
`endMs[k] == createdAtMs[k+1]`. One capture session lives for the whole pipeline; only the muxer
opens and closes.

### No audio track

Video only — avoids the `RECORD_AUDIO` permission entirely.

## Consequences

- Verified on both devices: a 10-min GL soak on the BLU (24 fps camera==rendered==encoded, 0
  dropped frames, 59 rotations, flat memory) and gapless real recording
  (`start[k+1] − start[k] − dur[k]` within ~8 ms BLU / ~1 ms Pixel), with pre-roll.
- With rotation gapless, the controller's segment→clip grouping gap is a tight **500 ms**
  (`GroupingGapMs`).
- Motion analysis rides the GL readback, so its resolution/rate is a GL concern, not a second
  Camera2 stream — see [`0005`](0005-motion-detection.md).
- A `SurfaceTexture` buffer whose aspect ≠ the sensor aspect gets anamorphically squashed above
  ~1080p; fixed with a sensor-aspect source size + a GL centre-crop uniform (see
  [`../../quirks/camera2-recording-pipeline.md`](../../quirks/camera2-recording-pipeline.md)).
