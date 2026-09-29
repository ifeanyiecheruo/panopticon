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

### `getTransformMatrix()` can SWAP the axes, so the size Camera2 reports for a buffer is not the size you see
**What we assumed:** the entry above says the matrix "comes back ≈ identity (no crop encoded)",
and the crop math that grew out of it assumed the matrix is at most a flip — i.e. that
`sourceSize`'s reported `width × height` are also the *displayed* width and height, so
`computeTexCrop()` could compare that aspect against the recording target directly.
**Actually observed (Pixel 6 back camera, `sourceSize` `4000×3000`):** the matrix is a genuine
**axis swap**. Logged verbatim (column-major, via a one-off `Log.i` of the array):
`[0,-1,0,0, -1,0,0,0, 0,0,1,0, 1,1,0,1]`, which maps `(x,y) → (1-y, 1-x)`. The OES buffer is
stored **transposed** relative to the `4000×3000` Camera2 reports for it, and the matrix undoes
that transpose during sampling. So the image you actually see is **`3000×4000` — portrait** —
even though every size the API hands you says `4000×3000` landscape. (This is also why the frame
still comes out upright with `rotationDegrees = 0`: the swap is corrective, not a visible
rotation, so nothing *looks* rotated to tip you off.)
**Consequence:** `computeTexCrop(4000×3000 → 3840×2160)` returned `(1.0, 0.75)`, but applied to a
display that is really `3000×4000` that selects a `3000×3000` **square** region, which is then
stretched across the 16:9 encoder surface — a **1.78× horizontal stretch** on every recorded and
live frame.
**What we do about it:** `CameraFraming.axesSwapped()` reads the swap straight off the matrix
(compares `stMatrix[0]`, how much natural-X moves per unit raw-X, against `stMatrix[4]`, the same
for raw-Y) and `naturalSourceSize()` transposes the size when it fires. The crop is then computed
against *that*, giving `(1.0, 0.421875)`. The matrix isn't known until the first frame arrives, so
both pipelines recompute the crop once, on first frame (`texCropFinalized`).
**Do not "fix" the crop ordering:** `uSTMatrix` maps **quad/display coords → buffer coords**, so
`aTex` is already in display space and `uTexCrop` must be applied to it **before** the matrix.
Moving the crop after the matrix (an attractive-looking change, since the crop is "about" the
buffer) puts it in buffer space and re-breaks this on exactly the devices this entry is about —
it was tried, and measured *worse* than the original bug (window h/w 0.644 → 0.464).
**How it was verified — don't eyeball this class of bug:** an anamorphic stretch is genuinely hard
to see by inspection, and several rounds of "looks fine to me" on screenshots got it wrong in both
directions. What settled it was rendering the camera's full natural image into a **letterboxed
viewport whose aspect equals the natural size** (`3000×4000` into a centred `1620×2160` box inside
the normal `3840×2160` surface). That mapping is a uniform scale on both axes *by construction*,
so it is a guaranteed-undistorted picture of whatever the camera is pointed at — ground truth, on
the spot, with no reference object needed. Measuring the same windows in both framings:

| framing | window height/width |
| --- | --- |
| before the fix | 0.644 |
| letterboxed reference (ground truth) | 1.129 |
| after the fix | 1.121 (0.7% off truth) |

The model also predicts the broken value independently — a square region stretched to 16:9 gives
`1.129 / 1.778 = 0.635` vs the 0.644 measured, within 1.4%.
**Where:** `phone-app/.../camera/CameraFraming.kt` (`axesSwapped()`, `naturalSourceSize()`),
`GlBlit.kt` (`VERTEX_SHADER`), `LivePipeline.kt` / `CameraGlPipeline.kt` (`onFrameAvailable()`'s
`texCropFinalized` block). Pinned by `CameraFramingTest`, which asserts the cropped region's
aspect ratio equals the recording target's for both swapping and non-swapping matrices.

### A `CONTROL_ZOOM_RATIO` change lands ~10 frames late, via a reconfigure that drops a frame — and a shader that assumes otherwise reads as motion
**What we assumed:** that the lag between requesting a hardware zoom and getting it is worth at
most a comment. `CameraGlPipeline`/`LivePipeline` both said so in as many words: *"Reading a
stale split for a frame or two after a change is harmless: the hardware takes a few frames to act
on a new zoom anyway."*
**Actually observed (Pixel 6 back camera, 3840x2160):** measured off the recorded `.mp4`s, the
handover takes ~10 frames and includes a **67–133ms hole in an otherwise flat 33ms cadence** —
the HAL reconfiguring. Inter-frame timing across whole clips is `{33ms: 152, 67ms: 1, 133ms: 1}`,
with both outliers at the zoom transition and nowhere else.
**Why the assumption was backwards:** the hazard is not the shader being *late*, which is
harmless. It is the shader being **early**. `ZoomGeometry.split` returns the crop the buffer
*will* hold, and the shader adopted it on the next frame, normalising the view against a crop
that was not there yet — sampling a sub-rect of a sub-rect. Wrong region, over-magnified, soft,
for ten frames, then a snap to the correct framing. `MotionDetector` cannot tell that snap from a
person walking past: it is one frame in which 58–78% of the grid changes. **509 of 816 clips**
recorded over three days were this and nothing else — a dead-still scene whose second-largest
inter-frame change was 0.001.
**What we do about it:** the shader renders against what the camera *reports*, not what was
requested. `ZoomGeometry.splitFor(hwRatio)` computes the split for a ratio the hardware is
actually at; `ZoomFeedback` records `CONTROL_ZOOM_RATIO` (or `SCALER_CROP_REGION` on devices
driven that way) out of each `CaptureResult`, keyed by `SENSOR_TIMESTAMP` — the same clock and
value as `SurfaceTexture.getTimestamp()`, so each frame is matched to its own reading rather than
to the newest one. Every intermediate frame is then geometrically correct and the view simply
sharpens. Separately, `MotionGate` brackets the transition so the detector drops those frames as
a reference either way.
**The enabling mistake, worth naming on its own:** all three `setRepeatingRequest` calls passed a
`null` `CaptureCallback`. With no result listener there was no way to know where the zoom was, and
no way to see `onCaptureFailed` / `onCaptureBufferLost` / `onCaptureSequenceAborted` at all.
**Where:** `phone-app/.../camera/ZoomFeedback.kt`, `ZoomGeometry.splitFor()`,
`CameraGlPipeline.deliveredCrop()`, `LivePipeline.deliveredCrop()`.

### The pipeline's encoder stops dead after ~155/195/235s of flawless 30fps (unexplained)
**Observed (Pixel 6, `oriole`, 3840x2160 @ 30fps, `ENCODER_BIT_RATE` 4Mbps):** the supervisor's
`OUTPUT_STALL_TIMEOUT_MS` fires every few minutes, around the clock, ~20–43 times an hour. The
cadence is **quantised on a ~40.2s grid** — restart gaps cluster at 161.1s (n=292), 201.4s
(n=100), 241.9s (n=19), 362.0s (n=5) with nothing between — and stays phase-locked to it for 7+
hours at a stretch.
**What it is not:** not a slow decay. A 130s clip holds 30.0fps in every 10s bucket and a 33ms
inter-frame gap right up to its last frame, then output stops. Not light- or scene-dependent
(same rate at 03:00 as at noon). Not an exception: the 4–6s hole before the next run matches the
stall timeout plus teardown plus the 500ms retry, so nothing threw — frames simply stopped.
**It tracks capture load, and the periodicity is 4K-only.** Restarts per hour of covered
recording: Pixel 6 @ 3840x2160 **19.6/hr, mean uptime 184s** (43.7h sampled); the same Pixel @
1280x720 **5.1/hr, 708s** (3.7h); BLU G5 @ 1280x720 **4.3/hr, 828s** (9.0h). At 720p the restarts
are irregular — the ~40.2s grid appears only at 4K.
**Status: SOLVED 2026-09-24 — see the entry below.** The ~40.2s grid is this device's suspend
cadence, and the failure is in the MFC encoder driver, not in the camera or in our GL consumer.
Everything from here to the end of this entry is the reasoning that did *not* pan out, kept
because it shows how a single unrepresentative forensic sample steered two days of work.

Thermal is the best-corroborated suspect (sustained 4K on a Tensor is a
well-documented overheater, with frame drops reported at 4K30 specifically) but does not account
for a seven-hour phase lock; camera buffer starvation fits the load dependence and the silence
(one GL thread doing a synchronous `glReadPixels` *and* a 4K `eglSwapBuffers` per frame is a slow
consumer). Instrumentation to separate them landed with the entry above — `GET /api/camera/health`
records which of {camera frame, capture result, encoded output} stopped first, the measured fps at
the moment of death, and the capture-failure counters.
**Not changed on a guess:** `CONTROL_AE_TARGET_FPS_RANGE` is pinned in `LivePipeline` but not in
`CameraGlPipeline`. Pinning it is a plausible fix *and* a plausible regression (it stops the HAL
lengthening exposure at night), so the capture rate is measured rather than the behaviour changed
until the cause is known.
**Where:** `CameraGlPipeline.superviseUntilError()`, `recordStallForensics()`.

### A foreground service does not keep the SoC awake, and the hardware encoder dies when it sleeps

**Symptom:** at 4K, the recording pipeline restarts every 160/200/240/285 seconds — multiples of
~40.3s and never less than 160s. At the moment of death the camera looks perfectly healthy:
`cameraFrameAgeMs` ~12ms, `captureResultAgeMs` ~10ms, `measuredFps` 29.9, and only
`encodedOutputAgeMs` is stale at ~4.1s. `eglSwapBuffers` returns `true` throughout.

**Cause:** with the screen off, the application processor attempts suspend every ~40.3s. After
several such cycles the Exynos MFC encoder comes back unusable and the kernel driver rejects
every input buffer:

```
E libexynosv4l2: failed to ioctl: VIDIOC_QBUF (22 - Invalid argument)
E ExynosVideoEncoder: MFC_Encoder_Enqueue_Inbuf: Failed to enqueue input buffer
```

once per frame, at 30Hz, for as long as the codec instance lives. `MediaCodec` reports no error
and throws nothing — it simply stops producing output, so only an output-staleness watchdog
notices.

**Why it was missed:** `android:foregroundServiceType="camera"` plus a `startForeground` call is
what keeps the *process* alive, and it is easy to read that as "the system will leave my
recording alone". It is a different guarantee entirely. Only a wake lock keeps the AP out of
suspend, and `android.permission.WAKE_LOCK` had been declared in the manifest since the first
commit without anything ever taking one out.

**Fix:** hold a `PARTIAL_WAKE_LOCK` for the lifetime of the service, released in `onDestroy`.
Verified: 2h 47m on a single run against a 176s mean and a 285s record, with the suspend
attempts gone entirely and zero `VIDIOC_QBUF` failures.

**How to recognise it:** run uptimes clustering on a grid of a few tens of seconds, with a floor
several multiples up, is the tell — a resource being lost to a periodic system transition, not a
degradation. Grep `logcat` for `VIDIOC_QBUF`, and check `adb shell dumpsys power | grep <tag>`.

**What it is not:** not thermal (reproduced at `moderate` on a cold device), not the synchronous
`glReadPixels` (disabling analysis entirely left mean uptime at 188s against a 176s baseline),
not backpressure from the encoder input surface (the swaps succeed).

**Where:** `PanopticonService.acquireWakeLock()`. `GET /api/camera/health` (see
[`../design/http-api.md`](../design/http-api.md)) is how to check it stays fixed.

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
