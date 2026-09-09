# Camera2 / recording pipeline

Device context and the "Reconfirmed" / "Carried forward" convention: see [`README.md`](README.md).

## Reconfirmed on Pixel 6

### `CameraCaptureSession` teardown+recreate does NOT reproduce the old "configure-then-fail-async" quirk here
**Old prototype's claim (Pixel 9a):** a session can report `onConfigured` successfully and then
fail at the very first capture request submission, with nothing catchable at the call site.
**What we did:** the original clip-rotation design fully tore down and recreated the
`CameraCaptureSession` + encoder on every ~10s rotation - effectively a repeated stress test of
session (re)configuration. (Gapless rotation later removed the per-rotation teardown; with the
GPU-fan-out pipeline the session is rebuilt only on an error or a mode change.) We kept the old
workaround anyway (configure → wait 500ms → check for an async failure signal, see
`CameraGlPipeline.kt`).
**Actually observed (original per-rotation design):** over roughly 20 consecutive rotations (~3.5
minutes of continuous recording) plus 2 full stop/restart cycles (triggered via `POST /api/mode`
switching to `live` and back), **zero** async configure failures were logged - every session
configured and started cleanly on the first attempt. The retry path never fired.
**Conclusion:** not reproduced on this device/encoder combination. We're keeping the defensive
retry (it's cheap and a `CameraCaptureSession` is documented as capable of this failure mode in
general), but this specific Pixel 6 + Exynos encoder pairing didn't exhibit it under normal
conditions. Worth retesting under thermal/memory pressure if it ever becomes suspect again.
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/camera/CameraGlPipeline.kt`
(`openSessionAndRequest()`).

### Camera2 calls did not throw synchronously here, but the defensive wrapping stayed
**Old prototype's claim:** `createCaptureSession`/`setRepeatingRequest`/etc. observed throwing
`CameraAccessException` synchronously under HAL stress, not just via callbacks.
**What we did:** every Camera2 call in `CameraGlPipeline` is wrapped in try/catch regardless
(`openCameraDevice`, `createCaptureSession`, `openSessionAndRequest`'s `setRepeatingRequest`).
**Actually observed:** no synchronous throws seen in this slice's testing - the only exception
logged during the whole session was an expected `kotlinx.coroutines.JobCancellationException`
when `stop()` cancelled the run loop's coroutine job during a mode switch (working as intended,
not a HAL failure).
**Conclusion:** not reproduced here either, same caveat as above - kept as defensive wrapping
since it costs nothing and the old finding was itself real (just device-specific).
**Where:** `CameraGlPipeline.kt`.

### `KEY_I_FRAME_INTERVAL` IS honored, tightly, on this device (and on-demand sync frames work)
**Old prototype's claim (Pixel 9a):** `MediaFormat.KEY_I_FRAME_INTERVAL` was honored wildly
irregularly (~1.6s-4.7s) when targeting ~2s, breaking a live-HLS segmenter's timeline
assumptions.
**What we do now:** the gapless-rotation pipeline (see below) configures a `MediaCodec` AVC
encoder with `KEY_I_FRAME_INTERVAL = 1` and, at each rotation boundary, additionally requests an
immediate sync frame via `PARAMETER_KEY_REQUEST_SYNC_FRAME` - so this *is* a direct re-test of
the old claim.
**What we measured** (`CameraGlPipeline.logKeyframeCadence()` reads real keyframe timestamps from
each finished segment via `MediaExtractor`): across many 10s segments the deltas were
`[1027, 997, 997, 997, ...]` (ms) essentially every time, run after run - the Pixel 6's
`ExynosC2H264EncComponent` places a keyframe within ~1ms of once per second, and the on-demand
sync-frame request lands the rotation keyframe within a few ms of the deadline.
**Conclusion:** not reproduced here. `KEY_I_FRAME_INTERVAL` is reliable enough on this
device/encoder to drive segment boundaries directly. Still budget for the old finding on other
SoCs - which is exactly why rotation also falls back to "next natural keyframe" if the sync-frame
request is ignored.
**Where:** `CameraGlPipeline.kt` (`createEncoder()`, `drainLoop()`, `logKeyframeCadence()`).

### Gapless segment rotation: MediaRecorder can't, MediaCodec+MediaMuxer can (everywhere the camera feeds an encoder at all)
**Goal:** one encoder alive for a whole motion event, rolling its output `.mp4` with no
teardown, so consecutive ~10s segments of continuous motion have no ~1-2s session-rebuild gap.

**MediaRecorder dead ends (Pixel 6, `oriole`, API 36):**
- `setMaxDuration(N)` + `setNextOutputFile` → the duration limit **stops** the encoder at ~N ms
  (no `NEXT_OUTPUT_FILE_STARTED`, no roll). `setMaxDuration` is a hard stop, not a rollover
  trigger.
- `setMaxFileSize(bytes)` + `MAX_FILESIZE_APPROACHING` → arm `setNextOutputFile` → **does** roll
  gaplessly on the Pixel 6 (`start[k+1] == start[k] + dur[k]`). But on the **BLU G5**
  (Spreadtrum, API 28) `setMaxFileSize` on a SURFACE H264 recorder makes the encoder write only
  the ~32-byte container header then stall (`MediaRecorder error extra=-1007`).

**MediaCodec + MediaMuxer, encoder surface input (superseded):** one `MediaCodec` AVC encoder
whose input surface is a camera stream, running untouched; the `MediaMuxer` rotates at a
keyframe. Gapless on the Pixel 6 (`start[k+1] ≈ start[k] + dur[k]`, ±3ms). But it needs the
camera session to target **both** an analysis `ImageReader` (`YUV_420_888` 320x240) and the
encoder-surface video stream, and **the BLU G5's Unisoc SC9863A HAL rejects two concurrent
streams**: `Camera3-Device: sendRequestsBatch: Unable to submit capture request N to HAL
device: Function not implemented (-38)` → the device errors out and disconnects, encoder never
gets a frame. (Same root cause as MediaRecorder failing there - it's the two-stream config, not
the encoder.) A video-only session records fine there; ARMED (analysis-only) and still-image
calibration work too - it's *only* the two concurrent streams.

**GPU texture fan-out (the shipped approach):** **one** camera stream → a `SurfaceTexture`. A GL
thread samples that external-OES texture per frame and renders it twice: a small downscaled copy
to an FBO for `glReadPixels` (frame-difference motion detection) *and* the full frame to an EGL
window surface on `MediaCodec.createInputSurface()`. The encoder runs continuously; a
motion-gated `MediaMuxer` (primed from an in-RAM pre-roll ring of encoded access units) is what
writes to disk, rotating at a keyframe. **Verified sustained on both devices** - a 10-min soak
(`GlSoakTest`) on the BLU G5 ran clean: 24 fps camera==rendered==encoded with zero dropped
frames, 59 MediaMuxer rotations, 0 GL errors, flat memory; and the real pipeline records
gapless (`start[k+1] - start[k] - dur[k]` within ~8ms on the BLU, ~1ms on the Pixel), with
pre-roll, on both. Single stream = the BLU's two-stream rejection is simply avoided.
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/camera/CameraGlPipeline.kt`;
`app/src/debug/.../GlSoakTest.kt`.

### Unsupported-encoder-size guard ran successfully, but its failure mode (black frames) was not reproduced
**Old prototype's claim:** requesting a recording size the AVC encoder can't actually handle
(despite the camera declaring it) silently produces a black output surface, no error.
**What we did:** `CameraGlPipeline.pickRecordingSize()` cross-references the camera's
`StreamConfigurationMap` sizes against `MediaCodecInfo.VideoCapabilities.isSizeSupported()` before
ever recording, same approach as the old `recordingSizes()`.
**Actually observed:** on this Pixel 6, the intersection logic ran on every camera open and always
resolved to 1280x720 (both camera and encoder agree it's supported) - we never hit a case where
the two capability sets disagreed, so **the actual black-frame failure mode was not reproduced or
exercised** here. The guard is present but untested against a real disagreement.
**Update (commit `e6de4cf`):** `pickRecordingSize()` now honours a controller-selected
`videoResolution` (up to 4K) when both capability sets support it, so it no longer always lands on
720p. That exposed a *different* failure at >1080p — an anamorphic squash, not black frames — see
"The GL `SurfaceTexture` record path anamorphically squashes…" below.
**Where:** `CameraGlPipeline.kt` (`pickRecordingSize()`).

### A thumbnail came back black - explained by test-environment lighting, not a codec bug
While verifying `GET /api/clips/:filename/thumbnail` end-to-end, the extracted JPEG frame was
essentially solid black. Logcat showed `[BandingDetection] Input environment_lux: ~0.07-0.08`
consistently throughout the entire test session (from the phone's own flicker/ambient-light
sensor), meaning the physical test environment was genuinely near-zero light (phone lens
covered/in a dark space), not a rendering bug. **Not treated as a reproduction of any known
quirk** - flagged here only so a future reader doesn't mistake "we saw a black thumbnail" for "we
found the black-frame bug." Re-verify visually in normal lighting if this ever needs re-checking.
**Where:** observed via `ClipStore.thumbnailFor()`'s output during manual testing, not a code change.

### The GL `SurfaceTexture` record path anamorphically squashes the frame when the buffer aspect ≠ the sensor aspect (>1080p 16:9 on the 4:3 main sensor)
**What we assumed:** feeding the camera into a `SurfaceTexture` sized with
`setDefaultBufferSize(W,H)` and drawing it through `SurfaceTexture.getTransformMatrix()` yields a
correctly-proportioned frame at any `(W,H)` the camera lists for `SurfaceTexture` output — the
matrix carries whatever crop the HAL applied.
**Actually observed (Pixel 6 back camera):** at a 16:9 size **above ~1080p** (`3840×2160`), the
HAL fills the buffer with the **whole 4:3 sensor frame scaled anamorphically to 16:9** — no
centre-crop, and `getTransformMatrix()` comes back ≈ identity (no crop encoded). The full-quad GL
blit then stretches that 4:3 content across the 16:9 encoder surface → recordings are **visibly
vertically squashed** (circles → wide ellipses). At `1280×720` the HAL *does* centre-crop, so it
was invisible until `pickRecordingSize()` began honouring a UI-selected resolution (commit
`e6de4cf`). The **live** path is unaffected: `LivePipeline` targets a concrete `MediaCodec` input
`Surface`, which gets Camera2's guaranteed aspect-preserving centre-crop — so live looked right
while a recording of the same scene was squashed.
**What we do about it:** `CameraGlPipeline` now separates the *output* size (`recordingSize` — the
encoder/EGL surface) from the *camera source* size (`sourceSize` — the `SurfaceTexture` buffer).
`pickSourceSize()` drives the `SurfaceTexture` at a **sensor-aspect** size (so the HAL fills it
1:1, no squash) and a `uTexCrop` uniform in the vertex shader centre-crops the sampled region to
the output aspect *before* `uSTMatrix`. When the sensor already matches the output aspect,
`sourceSize == recordingSize` and `uTexCrop` is `(1,1)` (no-op). If the camera exposes no
sensor-aspect `SurfaceTexture` size it logs and falls back to the old (squashed) behaviour.
**Verified on the Pixel 6:** a fresh `3840×2160` recording is correctly proportioned and its
framing matches the live preview (both a 16:9 centre-crop of the sensor).
**Where:** `phone-app/.../camera/CameraGlPipeline.kt` (`pickSourceSize()`, `computeTexCrop()`,
`VERTEX_SHADER`'s `uTexCrop`). Commit `e3bb2fa`.

## Carried forward, not yet re-verified in this project

Adopted from `panopticon-prototype/QUIRKS.md` as defensive workarounds, not independently
re-tested against the Pixel 6 / BLU G5 - see [`README.md`](README.md#the-carried-forward-tag).

- The HAL only honors roughly every other explicit sync-frame request.
- The automatic keyframe timer and explicit requests fight each other.
- In-place bitrate changes are silently ignored.
- Concurrent `MediaCodec` access crashes natively, and a decoder that's thrown once throws forever.
- **Two concurrent camera surfaces for record + motion sampling** - the old prototype's claim
  (Pixel 9a / Tensor): they never configure. **Device-dependent, confirmed both ways** - the
  Pixel 6 co-configures `[YUV analysis ImageReader + encoder surface]` fine indefinitely; the
  BLU G5 (Unisoc SC9863A) rejects it (`sendRequestsBatch` → `-ENOSYS`, device errors out). The
  shipping pipeline (`CameraGlPipeline`) sidesteps this entirely by using **one** camera stream
  (a `SurfaceTexture`) and fanning it out to the analysis FBO + the encoder on the GPU - see
  "GPU texture fan-out" above.
- A `CaptureRequest` template mismatch broke calibration carry-through between modes - both
  `CameraGlPipeline` (record) and `LivePipeline` (live) consistently build from `TEMPLATE_RECORD`
  at the same resolution (following the old lesson proactively). Not stress-tested against a
  manual-control value calibrated in one mode and expected to hold in the other, since manual
  Camera2 controls aren't implemented yet.
