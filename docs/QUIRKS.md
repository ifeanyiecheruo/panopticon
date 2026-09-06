# Quirks

This is a **new** project doc, separate from `panopticon-prototype/QUIRKS.md` (the old,
abandoned prototype's findings). Same format: what we assumed → what actually happens → what we
do about it → where. Entries under "Reconfirmed on Pixel 6" were directly re-verified against
real hardware while building this vertical slice (`phone-app/`). Entries under "Carried forward"
were adopted as defensive workarounds from the old doc without being independently re-tested here
- don't mistake them for confirmed-in-this-project.

Primary test device for most of the entries below: **Google Pixel 6 (`oriole`), Android 16 (API
36), build `BP2A.250605.031.A2`**, adb serial `1C281FDF6005H0`. Encoder observed in logcat:
`ExynosC2H264EncComponent` (Exynos hardware AVC encoder) - a different SoC family from the old
prototype's Pixel 9a (Tensor), which is itself a reason some findings below don't transfer
directly. A second device, a **BLU G5, Android 9 (API 28)**, also gets used opportunistically as
a low-end/old-API check - see "Foreground service" below for what it caught that the Pixel 6
(API 36) couldn't have.

## Contents

- [Camera2 / recording pipeline](#camera2--recording-pipeline)
  - [Reconfirmed on Pixel 6](#reconfirmed-on-pixel-6)
  - [Carried forward, not yet re-verified in this project](#carried-forward-not-yet-re-verified-in-this-project)
- [Foreground service](#foreground-service)
- [HTTP server](#http-server)
- [Development tooling (Windows) - new findings this project](#development-tooling-windows---new-findings-this-project)

## Camera2 / recording pipeline

### Reconfirmed on Pixel 6

#### `CameraCaptureSession` teardown+recreate does NOT reproduce the old "configure-then-fail-async" quirk here
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

#### Camera2 calls did not throw synchronously here, but the defensive wrapping stayed
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

#### `KEY_I_FRAME_INTERVAL` IS honored, tightly, on this device (and on-demand sync frames work)
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

#### Gapless segment rotation: MediaRecorder can't, MediaCodec+MediaMuxer can (everywhere the camera feeds an encoder at all)
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

#### Unsupported-encoder-size guard ran successfully, but its failure mode (black frames) was not reproduced
**Old prototype's claim:** requesting a recording size the AVC encoder can't actually handle
(despite the camera declaring it) silently produces a black output surface, no error.
**What we did:** `CameraGlPipeline.pickRecordingSize()` cross-references the camera's
`StreamConfigurationMap` sizes against `MediaCodecInfo.VideoCapabilities.isSizeSupported()` before
ever recording, same approach as the old `recordingSizes()`.
**Actually observed:** on this Pixel 6, the intersection logic ran on every camera open and always
resolved to 1280x720 (both camera and encoder agree it's supported) - we never hit a case where
the two capability sets disagreed, so **the actual black-frame failure mode was not reproduced or
exercised** here. The guard is present but untested against a real disagreement.
**Where:** `CameraGlPipeline.kt` (`pickRecordingSize()`).

#### A thumbnail came back black - explained by test-environment lighting, not a codec bug
While verifying `GET /api/clips/:filename/thumbnail` end-to-end, the extracted JPEG frame was
essentially solid black. Logcat showed `[BandingDetection] Input environment_lux: ~0.07-0.08`
consistently throughout the entire test session (from the phone's own flicker/ambient-light
sensor), meaning the physical test environment was genuinely near-zero light (phone lens
covered/in a dark space), not a rendering bug. **Not treated as a reproduction of any known
quirk** - flagged here only so a future reader doesn't mistake "we saw a black thumbnail" for "we
found the black-frame bug." Re-verify visually in normal lighting if this ever needs re-checking.
**Where:** observed via `ClipStore.thumbnailFor()`'s output during manual testing, not a code change.

#### Live view is plain HLS, single-stream, hand-rolled MPEG-TS
**Context:** `live` mode (`POST /api/mode {"mode":"live"}`) needed a real HLS feed, not the flag
flip it used to be. Decisions taken this slice:

- **Plain HLS, not LL-HLS.** Whole `.ts` segments (~1s target — see keyframe cadence below), a
  **16-deep** sliding window (~16s of DVR), `#EXT-X-VERSION:3`, and `#EXT-X-START:TIME-OFFSET=-4`
  so a joining player sits ~4s behind the live edge. Glass-to-glass ≈ 4–6s. The prototype built
  full LL-HLS (`EXT-X-PART`/`PRELOAD-HINT` byte-range parts, blocking playlist reloads) and paid
  for a long list of hls.js latency workarounds — all deferred here (see "Carried forward").
- **A deep window is load-bearing, not luxury.** The first cut used a 6-segment / ~2s window
  (~12s of DVR). hls.js sitting its default ~3 target-durations behind the live edge then had
  only a few seconds of margin, so any hiccup (a slow fetch, a GC pause, hls.js's *own*
  post-stall latency ratchet) walked it into fetching a just-evicted segment → 404 → stall →
  ratchet → more 404s → a permanent "still frame + endless rebuffering" spiral on the **Pixel 6**
  (the *faster* device). Fix: ~1s segments (finer granularity, a slow fetch costs ~1s of buffer
  not ~2–3s) + a 16-deep window (~16s recovery headroom) + real hls.js live config + a client
  stall watchdog (see `LivePreview.tsx`). After that both devices play smoothly for minutes with
  zero segment 404s.
- **`MediaMuxer` cannot emit MPEG-TS** (only MP4/WebM/3GP/OGG), so the muxer is hand-rolled:
  `phone-app`'s `camera/ts/TsMuxer.kt` (one PAT, one PMT, PCR on the video PID, PES-wrapped
  Annex-B AUs), ported verbatim from the prototype. `TsMuxerTest` guards the one invariant that
  bit it there — every TS packet is exactly 188 bytes, including padded PAT/PMT writes.
- **No GL / no `SurfaceTexture` for live.** Unlike `CameraGlPipeline` (which fans one stream out
  to motion analysis *and* the encoder), live preview does no motion analysis
  (phone-http-api.md: "motion detection stays out of live preview"), so the camera renders
  straight into `MediaCodec.createInputSurface()` — one stream, no GL thread, no EGL. Also means
  the BLU G5's two-concurrent-stream rejection is a non-issue here by construction.
- **Keyframe cadence: `KEY_I_FRAME_INTERVAL = 1s` *and* an explicit `REQUEST_SYNC_FRAME` timer**
  (at half the segment target). Relying on `I_FRAME_INTERVAL=1` alone gave ~2s (not ~1s)
  segments on the Pixel 6 and looser, more variable segments on the BLU G5 — the hint isn't
  honoured as tightly for this direct camera→encoder-surface path as it is for
  `CameraGlPipeline`'s GL-fed encoder. Adding the prototype's explicit sync-frame timer on top
  pins both devices to dead-regular ~0.96–1.0s segments. Also pins the capture rate via
  `CONTROL_AE_TARGET_FPS_RANGE` so the encoder gets a steady frame cadence for hls.js's buffer
  maths (a HAL dropping to 15 fps for exposure makes the buffer lumpy).
- **armed-idle vs broadcasting.** Entering `live` mode opens the camera + configures the session
  but sets no repeating request — zero encoding cost until `POST /api/live/start`. A 15s
  no-`GET` inactivity watchdog drops back to armed-idle.
- **`/live/*` uses the full bearer token.** The prototype minted a separate GET-only scoped
  token so a browser could fetch segments without the credential that can also delete footage.
  Deferred: the controller proxies `/live/*` server-side (`controller/liveproxy.go`), so the
  webview never talks to the phone directly and no scoped token is needed yet.
- **hls.js needs real live config + a stall watchdog** (in `LivePreview.tsx`), not defaults:
  `liveSyncDurationCount` / `liveMaxLatencyDurationCount`, a ~20s `maxBufferLength`, patient
  fragment/manifest retries, plus a 2s-interval watchdog that — on ~6s of no playback progress,
  or a `BUFFER_STALLED_ERROR`, or a fatal network error — calls `hls.startLoad()` and seeks to
  `hls.liveSyncPosition` (or the buffer's leading edge). This is the concrete, minimal subset of
  the prototype's "stuck load queue after a stall" + "latency ratchet never recovers" workarounds
  that plain HLS actually needs. The startup manifest-404 retry budget resets once the first
  fragment buffers, so a mid-stream blip gets a fresh allowance.

**Where:** `phone-app` `camera/LivePipeline.kt`, `camera/LiveHlsRelay.kt`, `camera/ts/TsMuxer.kt`,
`http/routes/LiveRoutes.kt`; `controller/liveproxy.go`, `frontend/src/components/LivePreview.tsx`.
**Verified end to end:** `src/debug/DebugLiveReceiver.kt` (a broadcast probe) confirmed both the
Pixel 6 and BLU G5 produce a rolling playlist of valid `mpegts`/`h264` 1280×720 that `ffmpeg`
decodes + concatenates cleanly; then, through a real `wails dev` controller (hls.js in the
webview against `liveproxy.go`), the Pixel 6 played **2.5+ minutes continuously, ~50 segments,
zero segment 404s, no stalls**, and the BLU produced dead-regular ~0.96s segments at real time.

### Calibration zoom probe

The empirical zoom probe (`phone-app`'s `calibration/CalibrationRunner`) was built and run to
completion on both the **Pixel 6 (API 36)** — `CONTROL_ZOOM_RATIO` + logical-multi-camera path —
and the **BLU G5 (API 28)** — legacy `SCALER_CROP_REGION` path. Findings below.

#### An API-gated `CaptureResult`/`CaptureRequest` key throws `NoSuchFieldError` even behind a runtime `SDK_INT` guard
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
**Where:** `calibration/ZoomApiCompat.kt`, `calibration/CalibrationRunner.kt` (`hasZoomRatio` /
`hasActivePhysicalId`).

#### Releasing an `ImageReader` while a frame callback is in flight is a native `SIGSEGV` (Pixel 6)
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

#### Closing a `CameraCaptureSession` and opening the next one on the same still-open `CameraDevice` disconnects the device entirely (BLU G5)
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

#### Interleaving `CONTROL_ZOOM_RATIO` and `SCALER_CROP_REGION` in one session's repeating request corrupts the readback (Pixel 6 front camera)
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

#### "Position honoured?" can't be answered from `SCALER_CROP_REGION` metadata alone — the HAL can echo a crop it doesn't apply
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

#### Frame-sharpness is normalised now, but still wants a lit, textured target
`ZoomMath.sharpness` is variance-of-Laplacian **divided by mean-luma²** (the raw Laplacian
scales with luma amplitude), taken as the **median of ~4 frames** per zoom step, with the
baseline = the sharpest of the first four samples. `deriveQualityCollapse` now requires a
**sustained** drop (below 0.5× baseline and staying there), so a single noisy frame no longer
fires it. That removes most of the lighting sensitivity, but a genuinely blank / dark scene
still can't tell blur from "nothing to focus on" — for an absolute `qualityCollapseRatio`,
point the camera at a resolution chart. The *shape* (digital zoom softens past ~2–3× on a main
sensor, faster on a fixed-focus front camera) is the trustworthy part.
**Where:** `calibration/ZoomMath.sharpness` / `medianSharpness` / `deriveQualityCollapse`.

#### Device findings

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

### Carried forward, not yet re-verified in this project

Out of scope for this vertical slice (no manual Camera2 controls, no live HLS pipeline, no
multi-camera; calibration and motion-gated recording now exist but the specific quirks below
still haven't been re-tested against the Pixel 6) - see `panopticon-prototype/QUIRKS.md` for the
original write-ups:

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
- Manual controls aren't reliably honored at all
- `AE_MODE_OFF` (manual exposure) needs `MANUAL_SENSOR`, despite being independently selectable
- `CONTROL_AE_LOCK` is a metering freeze, not a manual-exposure dial
- `CONTROL_MODE=USE_SCENE_MODE` silently overrides the individual 3A controls
- `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES` reports duplicate values
- A `CaptureRequest` template mismatch broke calibration carry-through between modes - both
  `CameraGlPipeline` (record) and `LivePipeline` (live) consistently build from `TEMPLATE_RECORD`
  at the same resolution (following the old lesson proactively). Not stress-tested against a
  manual-control value calibrated in one mode and expected to hold in the other, since manual
  Camera2 controls aren't implemented yet.
- The HAL only honors roughly every other explicit sync-frame request
- The automatic keyframe timer and explicit requests fight each other
- In-place bitrate changes are silently ignored
- Concurrent `MediaCodec` access crashes natively, and a decoder that's thrown once throws forever
- **Two concurrent camera surfaces for record + motion sampling** - the old prototype's claim
  (Pixel 9a / Tensor): they never configure. **Device-dependent, confirmed both ways** - the
  Pixel 6 co-configures `[YUV analysis ImageReader + encoder surface]` fine indefinitely; the
  BLU G5 (Unisoc SC9863A) rejects it (`sendRequestsBatch` → `-ENOSYS`, device errors out). The
  shipping pipeline (`CameraGlPipeline`) sidesteps this entirely by using **one** camera stream
  (a `SurfaceTexture`) and fanning it out to the analysis FBO + the encoder on the GPU - see
  "GPU texture fan-out" above.
- CORS needs explicit header exposure for hls.js (adopted defensively in `PanopticonHttpServer.kt`
  for ranged clip downloads generally - `exposeHeader(Content-Range/Content-Length)`). The live
  slice sidesteps CORS entirely - `controller/liveproxy.go` serves the playlist/segments
  same-origin to the webview - so this still isn't exercised against a real hls.js client.
- **hls.js LL-HLS + latency workarounds (the whole `Browser / hls.js` section of the prototype's
  QUIRKS.md).** The live slice ships **plain HLS**, so none of these are implemented yet; adopt
  them if/when the plain-HLS latency drives an LL-HLS upgrade (see "Live view is plain HLS" above):
  - hls.js's load queue can get **permanently stuck after a stall** with no error event
    (video-dev/hls.js#5716, #6350) - needs a zero-progress watchdog that forces `startLoad()`/reload.
  - hls.js's **target latency ratchets up ~1s after every stall and never comes back down**
    (#6350) - needs a manual `hls.targetLatency` reset back to `partHoldBack` after a stall-free window.
  - Setting `liveSyncDurationCount` silently **defeats LL-HLS** (forces whole-segment sync) -
    leave `liveSyncDuration`/`liveSyncDurationCount` unset.
  - The **first playlist load routinely 404s** (encoder hasn't produced segment 0) and hls.js
    treats a playlist 404 as fatal-non-retryable - needs a custom `manifestLoadPolicy` fast-retry.
    *(A minimal bounded-retry version of just this one is in `LivePreview.tsx` already, since it
    bites even plain HLS.)*
  - **Safari** has native HLS, loads no hls.js, and can't attach an `Authorization` header - it
    would always need the server-proxied path. Not relevant while the controller is a WebView2
    (Chromium) app, but relevant if a real browser client is ever added.
- `Server / Node` section: not applicable - the controller is Go/Wails, not the prototype's Node
  server. The live proxy is `controller/liveproxy.go`, ~80 lines, no streaming-error-crash or
  single-instance concerns of the Node original.

## Foreground service

### `startForeground`'s 3-arg (foregroundServiceType) overload doesn't exist before API 29
**Assumed:** calling the 3-argument `startForeground(id, notification, foregroundServiceType)`
overload is safe on any API level as long as the *type value itself* is only supplied on API 29+
(`if (SDK_INT >= Q) FOREGROUND_SERVICE_TYPE_CAMERA else 0`) - i.e. that the guard only needed to
protect the *value*, not the *method call*.
**Actually:** confirmed via a real crash on a BLU G5 (Android 9, API 28): the 3-arg overload
doesn't exist at all in the `Service` class before API 29 (Q) - calling it unconditionally throws
`java.lang.NoSuchMethodError: No virtual method startForeground(ILandroid/app/Notification;I)V`
immediately on `onStartCommand`, and since the service is `START_STICKY`, Android just kept
restarting and re-crashing it in a loop.
**Workaround:** branch on the overload itself, not just the argument value - call the 2-arg
`startForeground(id, notification)` below API 29, and only use the 3-arg
`foregroundServiceType`-carrying overload on API 29+.
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/service/PanopticonService.kt`
(`onStartCommand`).

## HTTP server

### Bind-retry-on-crash-loop - implemented, not stress-tested via an induced crash
**Old prototype's claim:** a crashed process's socket isn't released fast enough for Android's
service auto-restart, causing a repeating `BindException` crash loop without a generous retry
budget.
**What we did:** `PanopticonHttpServer.start()` retries `embeddedServer(...).start()` on
`BindException` up to 20 attempts / ~30s total, same budget as the old prototype.
**Not tested:** we didn't deliberately crash the service mid-session to confirm the retry budget
is actually sufficient on this device - the server bound successfully on its very first attempt
every time we started/restarted the app during this project. Treat as "implemented defensively,
carried forward," not "reconfirmed."
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/http/PanopticonHttpServer.kt`.

### End-to-end verified on real hardware (not a quirk, a confirmation)
Via `adb forward tcp:8080 tcp:8080` + `curl` against the running Pixel 6: bearer-token auth gate
(401 on missing/invalid token, every route but `POST /api/pair`), invite redemption
(`POST /api/pair?invite=...`), `GET /api/device`, `/api/build-info`, `/api/status`, `/api/config`,
`/api/mode` (both directions, including the recording pipeline correctly stopping/restarting
around a `live`->`record` round trip), `GET /api/clips` (delta list), full-file and byte-`Range`
downloads of `/api/clips/:filename/file` (`206 Partial Content` with a correct `Content-Range`),
`/api/clips/:filename/thumbnail`, `DELETE /api/clips/:filename` (with a following `404`), and
self-unpair via `DELETE /api/pair` (with a following `401` on the now-revoked token). All matched
`phone-http-api.md`'s documented shapes and status codes.

## Development tooling (Windows) - new findings this project

### `gradlew.bat` invoked bare through `cmd.exe //c` isn't found, even from the right cwd
**Assumed:** once `cd /d <path> && gradlew.bat ...` has changed into the project directory (which
it genuinely does - confirmed via a bare `cd` printout), a bare `gradlew.bat` invocation would be
found the same way Explorer/PowerShell would find it.
**Actually:** `cmd.exe //c "cd /d <path> && gradlew.bat ..."` (invoked from this environment's
Bash tool, itself calling `cmd.exe` explicitly per the old prototype's Makefile convention)
reliably printed `'gradlew.bat' is not recognized as an internal or external command` - even
though `dir`/`where` in the same session found the file, and a bare `cd` (no `%cd%` var
substitution - see next entry) confirmed the cwd was correct.
**Workaround:** prefix with `.\`: `.\gradlew.bat ...` resolved and ran correctly every time.
**Where:** used throughout manual testing in this session; worth carrying into `phone-app/Makefile`'s
`GRADLEW` variable (explicit `./gradlew.bat`, not a bare `gradlew.bat`, when routed through `cmd.exe`).

### `%cd%` inside a `cmd.exe //c "cd /d X && echo %cd%"` one-liner reports the *pre-cd* directory
**Assumed:** `%cd%` expands to the current directory at the point the `echo` command actually
runs, i.e. after `cd /d X` has taken effect earlier on the same line.
**Actually:** cmd.exe expands `%variables%` once, at parse time, before executing *any* command on
the line (without delayed expansion, `!var!`) - so `%cd%` in a `cd /d X && echo %cd%` one-liner
shows the directory the shell was in *before* the line ran, not after the `cd`. This produced a
confusing false lead while debugging the entry above (the cwd looked "one directory too shallow").
**Workaround:** use a bare `cd` (no arguments) to print the actual current directory instead of
`echo %cd%`, or enable delayed expansion (`setlocal enabledelayedexpansion` + `!cd!`) if a variable
is really needed.
**Where:** debugging notes only, not encoded in any script - worth remembering if `Makefile` ever
needs to introspect cwd from a `cmd.exe`-routed recipe.

### adb can mark a still-connected device "offline" until `adb kill-server`/`start-server`
**Assumed:** `adb devices` reliably reflects a physically connected, USB-debugging-enabled
device's real state.
**Actually:** with two devices attached (this Pixel 6, serial `1C281FDF6005H0`, plus a second
unrelated device), the Pixel 6 showed as `offline` in `adb devices` at the start of this session
despite being physically connected and previously working - `adb -s 1C281FDF6005H0 shell ...`
failed with `device offline` for every command.
**Workaround:** `adb kill-server && adb start-server` (then re-run `adb devices`) recovered it
immediately, no physical reconnection needed.
**Where:** dev workflow note - worth adding to `phone-app/Makefile`'s `install:phone`/`run:phone`
troubleshooting output if this recurs.

### This MSYS2 `make` also strips `GOPATH`/`USERPROFILE`/`TMP`/`TEMP`, breaking `go` inside recipes
**Assumed:** the old prototype's finding (`LOCALAPPDATA`/`USERPROFILE`/`APPDATA`/`ANDROID_HOME`
stripped from every process this `make` spawns) was a closed, fully-enumerated list scoped to
Android tooling.
**Actually:** building a unified root `Makefile` that also drives the Go-based controller hit the
same stripping for Go's toolchain env vars, in two stages: first `go test`/`go build` inside a
recipe failed with `neither GOMODCACHE nor GOPATH is set` (Go's Windows build computes a default
`GOPATH` from `%USERPROFILE%`, which is empty here); after pinning `GOPATH` explicitly, it failed
again with `build cache is required, but could not be located: GOCACHE is not defined and
%LocalAppData% is not defined`, and separately `go: creating work dir: mkdir
C:\Windows\go-build...: Access is denied` (Go's temp-dir fallback walks `TMP`/`TEMP`/`USERPROFILE`,
all empty here, and lands on `C:\Windows` via a last-resort Windows API call, which isn't
writable). Also confirmed this `make`'s own `$HOME`/`PATH` inside a recipe are **not** the
invoking interactive shell's values either (`$HOME` resolved to this `make`'s own
`/home/<user>`, not `/c/Users/<user>`; `$PATH` started with `/home/<user>/bin`, not the real
`PATH`) - a broader form of the same isolation than the original entry described.
**Workaround:** same fix as `ADB` above, extended to Go: `export GOPATH := $(firstword $(wildcard
/c/Users/*/go))`, plus explicit `export TMP`/`TEMP`/`GOCACHE` pointed at scratch directories under
that resolved `GOPATH` (created via `$(shell mkdir -p ...)` at Makefile-parse time), so nothing
Go-related depends on an env var this `make` might strip.
**Where:** `Makefile` (root), the `ifneq (,$(findstring MINGW,...))` Windows block.

### This `make`'s recipe shell eats a literal backslash before a letter (`\g` → `g`)
**Assumed:** a Makefile variable holding a literal Windows-style relative path prefix (`.\gradlew.bat`)
would reach `cmd.exe` unchanged when interpolated into a recipe line, the same way it reads in the
Makefile source.
**Actually:** the recipe line is first evaluated by `make`'s own shell (`sh`, POSIX-like) before
`cmd.exe` ever sees it, and POSIX shells treat a backslash before an ordinary character as "take
the next character literally" (dropping the backslash) - so `.\gradlew.bat` reached `cmd.exe` as
`.gradlew.bat` (no backslash, no separator between `.` and `gradlew`), which `cmd.exe` correctly
reported as not found. This is distinct from - and adds a further wrinkle on top of - the older
"bare `gradlew.bat` isn't found" entry above; the fix for that one (`.\gradlew.bat` instead of a
bare name) is exactly what tripped this new issue when moved from a one-off interactive command
into a `Makefile` recipe.
**Workaround:** double the backslash in the Makefile source (`.\\gradlew.bat`) so that after the
recipe shell's escape processing, `cmd.exe` still receives a single `\`.
**Where:** `Makefile` (root), the `GRADLEW` variable.

### This `make`'s own `$(CURDIR)` resolves through the wrong mount alias for an OneDrive-rooted repo
**Assumed:** GNU Make's built-in `$(CURDIR)` variable reflects the same working directory a
recipe's own shell would report via `pwd`.
**Actually:** for this repo specifically (its real path is under `C:\Users\<user>\OneDrive\...`),
`$(CURDIR)` resolved to `/home/<user>/OneDrive/...` - a path that doesn't exist at all in this
`make`'s own recipe shell (`mkdir -p` on it failed trying to create `/home` itself, permission
denied), even though a bare `pwd` run as a recipe command correctly printed
`/c/Users/<user>/OneDrive/...`. Root cause not fully diagnosed, but consistent with this
`make`/MSYS2 build having its own internal mount-alias table it consults for `$(CURDIR)`
specifically, separate from (and less complete than) whatever its spawned recipe shells actually
use for real filesystem paths.
**Workaround:** never use `$(CURDIR)` in this Makefile; use `$(shell pwd)` instead (confirmed
correct) for anything that needs the repo root as an absolute path (`ROOT` in the Makefile).
**Where:** `Makefile` (root), the `ROOT` variable.

### This `make`'s `command -v <tool>` can resolve through a *working-but-different* path alias too
**Assumed:** if `command -v nvm` resolves and the resulting path passes a `[ -f ... ]` existence
check inside a recipe, that same path string is safe to bake into a generated script for later use
outside `make`.
**Actually:** `command -v nvm` inside a recipe returned `/home/<user>/AppData/Roaming/nvm/nvm` -
unlike the `$(CURDIR)` case above, this path *does* resolve inside this `make`'s own recipe shells
(nvm-windows' install directory apparently being one of a small set of user-profile paths this
MSYS2 build's `/home/<user>` mount aliases to), which made it easy to mistake for a genuinely
portable path. A shim script written with this path baked in worked when run via `make`, then
failed with "No such file or directory" run directly from an ordinary interactive Git Bash prompt,
where `/home/<user>/AppData/...` isn't a valid path at all.
**Workaround:** don't trust `command -v`'s literal output for anything that needs to be portable
outside this one `make`'s own shells - construct the path independently instead. Here: nvm-windows'
root is always `%APPDATA%\nvm` by its own fixed convention, so `NVM_ROOT` is built from
`/c/Users/$(shell whoami)/AppData/Roaming/nvm` rather than `dirname` of `command -v nvm`'s output;
`command -v nvm` is still used, but only as an existence check (installed vs. not), never as a path
source.
**Where:** `Makefile` (root), the `NVM_ROOT` variable.

### Gradle's unit-test task needs `local.properties`' `sdk.dir` even when `assembleDebug` doesn't
**Assumed:** since `ANDROID_HOME` being stripped from this `make`'s recipes never broke
`assembleDebug` in practice, Gradle must be resolving the SDK location some other reliable way
that `test` would share.
**Actually:** `assembleDebug` kept succeeding only because its relevant tasks were already
`UP-TO-DATE` from a previous (IDE- or manually-configured) run and never actually needed to
re-resolve the SDK location; `./gradlew test`, run fresh after the monorepo restructuring
recreated `phone-app/` from git history (which never tracked the gitignored `local.properties`),
failed immediately with "SDK location not found" - the one thing that reliably tells Gradle where
the SDK is regardless of environment variables is `local.properties`' `sdk.dir` line (normally
auto-written by Android Studio, silently relied upon rather than actually understood).
**Workaround:** a `phone-app-local-properties` Make target unconditionally (re)writes
`phone-app/local.properties` from the same `ANDROID_SDK_ROOT_WIN`/`_POSIX` this Makefile already
resolves for `install-tools-android-sdk`, and both `build-phone` and `test-phone` depend on it -
cheap enough (one line) to just always rewrite rather than track staleness.
**Where:** `Makefile` (root), the `phone-app-local-properties` target.

### `go run <relative-path>` refuses to cross a module boundary, even to a directory with its own go.mod
**Assumed:** `go run ../some/other/dir` (a plain filesystem path, not an import path) builds and
runs whatever package lives at that path, the same way it would if that path were a subdirectory
of the calling module.
**Actually:** when the *target* directory has its own `go.mod` (i.e. it's a different module, as
every `tools/*` module here deliberately is - see the "tools/ as separate modules" convention),
`go run`/`go build` refuse outright: `directory ...\tools\dbstore outside main module or its
selected dependencies`. This is true even for a path that's a perfectly valid, buildable module on
its own - the restriction is specifically about crossing a module boundary via a bare relative
path, and it bit both `controller/internal/dbstore/queries.gen.json`'s declared generator command
(`go run ../../../tools/dbstore ...`) and `tools/mock-phone`'s own documented usage from
`controller/README.md` (`go run ../tools/mock-phone/cmd/mockphone`) - the latter had apparently
never actually been run exactly as documented until this was diagnosed.
**Workaround:** a `go.work` file at the repo root listing every module (`controller`,
`tools/dbstore`, `tools/go-deps`, `tools/mock-phone`) makes `go run`/`go build` workspace-aware,
resolving relative paths across any module the workspace lists rather than just the calling
module's own tree. Committed (not gitignored, unlike most projects' `go.work`) since `make
generate` genuinely depends on it existing, not just as a per-developer convenience.
**Where:** `go.work` (root); `controller/internal/dbstore/queries.gen.json`;
`tools/dbstore/README.md`, `tools/go-deps/README.md`.

### A parse-time `$(shell go ...)` call doesn't see this make's own `export`s, even though every recipe does
**Assumed:** once a Makefile variable is `export`ed, every subsequent `$(shell ...)` call in that
same Makefile sees it in its environment - recipe-time or parse-time, no difference.
**Actually:** confirmed the opposite empirically: a `$(shell go ...)` call used to compute a target's
prerequisite list (i.e. evaluated while Make is still reading the Makefile, via `$(eval $(call
...))`) fails with the by-now-familiar `neither GOMODCACHE nor GOPATH is set`, even though the very
same `export GOPATH := ...` line reliably reaches every ordinary recipe body elsewhere in this same
file (extensively verified throughout the rest of this Makefile). A minimal repro nailed it down
further: `$(info $(shell echo $$GOPATH))` placed *after* the `export GOPATH := ...` line still
prints empty. Whatever this `make` does to apply `export` to a `$(shell)` call's environment, it
isn't available yet during the file's own top-to-bottom parse pass, only once recipes start running.
**Workaround:** don't rely on the `export` for a parse-time `$(shell ...)` call - pass the already-
computed Makefile variables explicitly as an inline environment prefix instead: `$(shell
GOPATH="$(GOPATH)" TMP="$(TMP)" TEMP="$(TEMP)" GOCACHE="$(GOCACHE)" go run ...)`. The values are
already known as ordinary Make variables regardless of whether their `export` has "taken" yet, so
this sidesteps the question entirely.
**Where:** `Makefile` (root), the `gen-stamp-rule` define (used to compute each `.gen.json.stamp`
target's prerequisites via `go-deps get`).

### Piping a real command's output into `awk` inside a recipe's `$$(...)` can break, same as `head`
**Assumed:** the earlier-documented "piping a glob through `head` inside `$(shell)` intermittently
breaks" quirk (see `panopticon-prototype/QUIRKS.md`) was specific to `$(shell)` (Makefile-parse-time
execution) and to `head`.
**Actually:** hit the same failure mode (`awk: ... fatal: error reading input file '-': Broken
pipe`) from a completely ordinary *recipe* (build-time, not parse-time) piping real `adb devices`
output into `awk` via `count=$$("$(ADB)" devices | awk '...')` - neither `$(shell)` nor `head` were
involved this time, just this `make`'s pipe handling in general being unreliable under this
specific MSYS2 build.
**Workaround:** avoid the pipe entirely - redirect the producer's output to a temp file, then have
the consumer read that file instead of stdin: `"$(ADB)" devices > "$$devices_list"; count=$$(awk
'...' "$$devices_list")`. Slightly more verbose, but never touches a pipe, so there's nothing left
for this bug to trigger on.
**Where:** `Makefile` (root), the `check-adb-devices` target.
