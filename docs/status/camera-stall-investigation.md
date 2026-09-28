# Camera stall investigation, and the false clips it caused

**Side:** phone · **Size:** medium · **Status:** cause of the false clips found and fixed; cause
of the *stalls* still open, instrumentation landed to close it

## What was seen

Watching the gallery, most clips were not motion. A clip would open with the viewport in the
wrong place and, a fraction of a second in, snap to the correct one — the snap itself being the
"motion" that caused the recording.

## What the clips proved

Measured against the controller's own database and the archived `.mp4`s (Pixel 6, `ph_bd13a7ad`,
3840x2160, 21–23 Sep 2026):

- **509 of 816 clips since the zoom rebuild (`c5b94e5`) were this.** Sampling twelve at random,
  nine had the signature: one frame in which 58–78% of the grid changed, 0.17–0.37s into the
  clip, with the largest change anywhere else in the clip being **0.001**. A dead-still scene
  with exactly one instantaneous whole-frame event.
- **Every one had ~0 pre-roll.** `segmentFileName()` stamps wall-clock at muxer-open, so
  `filename time − clip.startedAtMs` recovers what `openSegmentFromRing` actually got. Clips
  split cleanly in two: 509 with under 2s (against a `preRollMs` of 3000, i.e. the ring was
  empty) and 307 with the full ~3s. The empty ring dates the encoder to under half a second old
  — **the pipeline had just restarted.**
- **The snap coincides with a dropped-frame gap.** Inter-frame timing is a flat 33ms everywhere,
  in every clip, with one exception per false clip: a 67–133ms hole at the snap. That hole is the
  camera reconfiguring for a new zoom ratio.

Before and after the snap: the earlier frames show a different region of the scene, visibly soft
and over-magnified; the later ones the intended region, sharp.

## Why (the false clips)

`CameraGlPipeline.onFrameAvailable` finalises the aspect trim on the first frame of a run, calls
`recomputeZoomSplit()` + `reissueRepeatingRequest()` — and **from that same frame** rendered with
`ZoomGeometry.texRectUniform(..., deliveredCrop())`, where `deliveredCrop` came from the split
just *requested*.

The HAL needs ~10 frames to reach a new `CONTROL_ZOOM_RATIO`. For those frames the buffer still
held the full field of view while the shader normalised the view against a much smaller crop — so
it sampled a sub-rect of a sub-rect: wrong region, magnified further, soft. When the hardware
landed, the picture snapped, and `MotionDetector` recorded the snap.

The code anticipated the opposite hazard:

> *Reading a stale split for a frame or two after a change is harmless: the hardware takes a few
> frames to act on a new zoom anyway.*

GL being **late** is harmless. GL being **early** is this bug. And nothing observed when the zoom
actually arrived — all three `setRepeatingRequest` calls passed a `null` `CaptureCallback`.

## What was changed

1. **The shader follows the camera instead of the request.** `ZoomGeometry.splitFor` computes the
   split for a ratio the hardware is *actually at*; `ZoomFeedback` records `CONTROL_ZOOM_RATIO`
   (or `SCALER_CROP_REGION`, per device, matching what `CameraControlApply.applyZoom` drives)
   from each `CaptureResult` against its sensor timestamp, which is the same clock and value as
   `SurfaceTexture.getTimestamp()`. Every frame is therefore rendered against what its own buffer
   holds. The view no longer moves at all during a zoom — it just sharpens. Both pipelines.
   - Fallbacks, in order: nothing read back yet → `1.0`, i.e. GL does all of it (correct framing,
     merely soft); a device that reports neither key for 30 results → trust the request, as
     before, since permanently refusing the hardware zoom would be the worse regression.
   - Zooming *out* is the one transition that cannot be exact throughout: the buffer does not
     contain the wider field of view yet. The region grows into it rather than jumping, which is
     the best available behaviour and is pinned by a test.

2. **Motion detection is told when a change is ours.** `MotionGate` is an interface the motion
   side implements and the camera side calls, bracketing pipeline start, zoom transitions,
   control re-applies and reconfigures (`CameraDisturbance`). Frames inside a disturbance are
   dropped *as a reference*, not merely reported as no-motion, so the first settled frame is not
   compared against a disturbed one. Chosen over an event/subscription shape because it has to be
   synchronous — the whole failure happens inside ~300ms — and because a bracket can express an
   end that is genuinely known (a `CaptureResult`) rather than a guessed timeout.
   - A disturbance that is never ended expires after `maxHoldMs` (3s). Without that cap a missed
     `endDisturbance` would leave a security camera silently not recording — a far worse failure
     than the false clips this removes.

3. **`MotionDetector` keeps its reference frame across a sensitivity change.** It was being
   rebuilt wholesale every 5s to pick up a possibly-unchanged setting, discarding the reference
   and re-running the 3-frame warm-up each time: blind for ~100ms out of every 5s. Sensitivity is
   now a settable property.

4. **`GET /api/camera/health`** — loosely-typed per-camera counters (see below). Not surfaced in
   any UI.

## Retracted: thermal does NOT drive the stalls

**The section below overstated its case and the conclusion in its heading was wrong.** It is kept
because the measurements in it are real and the reasoning error is worth not repeating.

The overnight control run (2026-09-24, 114 min, cold start into 4K, no sun, controller closed)
settles it. Restarts, with the thermal headroom at each:

| Run began | +min | headroom | level |
|---|---|---|---|
| 22:40:21 | 0.3 | **0.468** | none |
| 22:42:32 | 2.4 | **0.636** | none |
| 22:45:07 | 5.0 | 0.794 | none |
| 22:47:50 | 7.7 | 0.886 | light |
| 22:51:14 | 11.1 | 0.945 | moderate |
| 23:02:33 | 22.5 | 1.001 | severe |

The pipeline stalls **every ~150–165s from stone cold**, at headroom 0.47–0.64, hundreds of
milli-units below the throttling threshold and at severity `none`. Over the whole run: 40
restarts at 21.0/hr, `stall` mean uptime 176s — statistically indistinguishable from the hot
evening run's 22.7/hr and 182s, and from the historical database's 184s.

**Where the earlier reasoning went wrong.** The afternoon "hot" phase showed 5–30s uptimes, which
looked like heat making everything worse. Those were overwhelmingly `stopped` restarts - the
controller's live preview seizing the camera - not stalls. Comparing a contaminated hot window
against a clean cool one produced a correlation that isn't there. The lesson is narrow and
specific: **the end reason had to be read before the rate meant anything**, and it was not.

Thermal shutdowns are real and worth guarding against, but they are a *separate* failure of
running 4K on this device, not the cause of the ~160s stall cycle. The consumer-side fix is
therefore **required**, not one option among several - there is no thermal policy that makes this
go away.

## Measured 2026-09-23 (superseded - see the retraction above)

With the instrumentation on a Pixel 6 recording 4K by a window in afternoon sun, the samples log
split cleanly in two when the device cooled:

| Phase | Thermal | Restarts | Run uptime |
|---|---|---|---|
| 18:04–18:47, direct sun | critical → **emergency**, headroom 1.11–1.30 | 18 in 42 min (**~26/hr**) | **5–30 s** |
| 19:19–19:28, after cooling | none → moderate, headroom 0.80–0.94 | 1 in 9 min | 83 s+ |

`headroom` is `PowerManager.getThermalHeadroom()`, where 1.0 is the throttling threshold. Above
it the pipeline stalls constantly; below it, largely not. The device reached actual thermal
**shutdown** twice during the day — the second time mid-run, visible in the log as the app's
uptime jumping 3764s → 16s at 19:19:03.

So the load dependence measured from the clip database (4K 19.6/hr vs 720p 5.1/hr) is thermal,
not some fixed 40.2s timer. The earlier quantisation is better read as a symptom of a governor
stepping through mitigation levels than as a scheduler.

**Confound, and why a re-run matters:** ambient sun on the phone is doing much of this. The same
measurement out of direct light is the control, and is what separates "4K is too much for this
SoC" from "4K plus solar gain is too much". Use `--window control` for it.

## A restart could open a clip without detecting any motion

Found while checking whether the guards held. `motionEver` and `lastMotionMs` were **never reset
in `teardown()`**, so a pipeline that stalled within `trailerMs` (5s) of the last motion came back
up, found `motionEver` still true and `lastMotionMs` still recent, and opened a segment on its
first keyframe — having analysed no frames at all.

That path never consults `MotionGate`, so the guards could hold perfectly and a restart still
produced a clip. With restarts taking 4–6s against a 5s trailer, the window was open exactly when
the device was hottest and restarting most.

Confirmed on `clip_20260923_184651_da9f.mp4`: 26 frames, 0.7s, one 84.5%-of-frame change at
t=0.23s and a second-largest change of 0.003 — the zoom-snap signature and nothing else, during
thermal emergency with camera frames arriving 200–250ms apart. Fixed by re-arming both in
`teardown()`: motion seen by the previous camera session says nothing about the new one.

**Pre-roll alone is not a sufficient discriminator** when the pipeline is restarting every few
seconds, because a genuine event then has a short ring too. The reliable test is the clip itself:
one whole-frame change with everything else near-still. `tools/collect-health.py --verdict` uses
pre-roll to *flag* candidates; confirming one means pulling it off the phone and measuring it.

## Partly wrong: this was one stall out of 29, and the rarer kind

Read on for the correction in *Answered 2026-09-24*. Classifying every failure in the same run
rather than the first one caught shows **25 of 29 stalls had fresh camera frames** at the moment
of death - the opposite signature to the one below, which occurred 4 times. The reading here was
not mis-measured, it was generalised from an unrepresentative sample, and it sent the next day's
work at the consumer-side readback instead of at the encoder.

Caught in full at 20:20:25, at 4K, thermal `critical`, headroom 1.046:

| Witness | Age at failure | Reads as |
|---|---|---|
| `captureResultAgeMs` | **23 ms** | the camera is *still capturing*, results still arriving |
| `cameraFrameAgeMs` | **3455 ms** | but buffers stopped reaching our `SurfaceTexture` 3.5s ago |
| `encodedOutputAgeMs` | 4160 ms | encoder starved as a consequence, tripping the stall timeout |
| `measuredFps` | 29.90 | full rate right up to the cut - no decay, no warning |

That disagreement is the whole answer. A HAL that had died, a camera the thermal governor had
shut down, or an encoder that had wedged would all stop the capture results too. Results arriving
23ms before the failure while frames are 3.5s stale means the camera is healthy and **the
camera → `SurfaceTexture` buffer path is what fails**.

Corroborated by two signals that were invisible before the `CaptureCallback` was attached:

- `updateTexImage: Unable to update texture contents` as its own failure reason - the same fault
  throwing outright rather than merely starving.
- `captureBufferLost` ticking up, and a standing gap between counters: 4241 capture results
  against 4136 frames delivered to GL in that run, ~2.5% simply never arriving.

So heat is the **trigger**, not the mechanism. The GL thread is a slow consumer by construction -
one thread doing a synchronous `glReadPixels` *and* a 4K `eglSwapBuffers` per frame - and when
thermal throttling slows the GPU, it falls behind far enough for the camera's buffer queue to
drain. That reframes the fix: it is ours to make on the consumer side (buffer count, moving the
readback off the critical path, decoupling analysis from encode), not merely a thermal limit to
be accepted or a resolution to be capped.

**Not yet established:** whether the same mechanism explains the historical ~40.2s quantisation,
and whether a cool phone at 4K stalls at all. The control run still owes us that.

## Answered 2026-09-24: the SoC suspends out from under the hardware encoder

The 40.2s grid was the answer all along, not a detail left over after it. **It is this device's
suspend cadence.** With the screen off the application processor attempts suspend every ~40.3s,
and after several of those in a row the Exynos MFC encoder comes back unusable.

Caught whole in `logcat`, 2026-09-24 07:23, cold phone, thermal `moderate`:

```
07:23:17.382  AidlSensorManager: aidl_ssvc_poll: spurious wake up, back to work
07:23:17.400  libc: Access denied finding property "suspend.debug.wakestats_log.enabled"
07:23:17.675  libexynosv4l2: failed to ioctl: VIDIOC_QBUF (22 - Invalid argument)
07:23:17.675  ExynosVideoEncoder: MFC_Encoder_Enqueue_Inbuf: Failed to enqueue input buffer
07:23:17.675  ExynosVideoCodecEnc-H264Enc: [srcEnqueue] inbuf : ExtensionEnqueue() is failed
07:23:17.675  ExynosVideoCodec-H264Enc: [doInputEnqueue] srcEnqueue() is failed
              ^ then once per frame, at 30Hz, never recovering
07:23:21.419  CameraGlPipeline: encoder output stalled for 4000ms
```

293ms after the suspend/wake transition the kernel driver begins rejecting **every** input buffer
with `EINVAL`, and keeps rejecting them until the pipeline is torn down and rebuilt. Nothing else
is broken: `eglSwapBuffers` still succeeds, the camera still delivers, capture results still
arrive. That is precisely why the failure looked like a healthy camera feeding a dead encoder.

The suspend attempts, measured off the same capture:

| Wake | Gap |
|---|---|
| 07:21:15.759 | — |
| 07:21:56.339 | 40.58s |
| 07:22:36.427 | 40.09s |
| 07:23:17.382 | 40.96s ← this one killed the encoder |
| 07:23:57.633 | 40.25s |

And the uptimes it produces, from the 114-minute 4K run of 2026-09-23 evening (n=25 encoder
stalls, the tight clustering being the point):

```
159.4 159.6 159.6 159.7 160.0 160.0 160.1 160.2 160.3 160.4 160.5 160.6 160.6 160.7
200.0 200.2 200.3 200.8 201.4  221.8  240.0 240.1 240.2 240.7  285.0
```

Four, five, six and seven suspend cycles, minus the 4s stall timeout. Every earlier observation
falls out of this:

- **The ~40.2s quantisation** is the suspend period. It was never a thermal governor stepping.
- **No warning before the cut** - a suspend transition is instantaneous, not a degradation.
- **Not light-dependent** - the screen is off around the clock.
- **Worse at 4K** - more MFC state to lose, and 720p was measured on a phone in use.
- **Unrelated to temperature** - reproduced at thermal `moderate` on a cold device.

### The fix

The app declared `android.permission.WAKE_LOCK` in the manifest and never took one out. A
`camera` foreground service keeps the *process* from being killed; it does nothing whatsoever to
keep the AP awake, and the two are easy to conflate. `PanopticonService` now holds a
`PARTIAL_WAKE_LOCK` (`panopticon:camera-pipeline`) for its whole lifetime, released in
`onDestroy`.

### What this retires

- **The `glReadPixels` consumer-side theory.** An isolation run with analysis disabled entirely
  (`ANALYSIS_FRAME_INTERVAL = 0` - no FBO draw, no readback, no motion detection) changed stall
  uptime not at all: 188.1s mean over 11 stalls against 176s for the baseline. The readback is
  not what starves the pipeline. Decimating it, or moving it to an async PBO, would have bought
  nothing against this failure.
- **The buffer-starvation reading** as the general case. It is real - 4 of 29 stalls, plus the
  `updateTexImage` failures - but it is the minority mode, and it rides on top of the suspend
  failure rather than causing it.

### Verified 2026-09-24

| | baseline (no wake lock) | with the wake lock |
|---|---|---|
| Config | 4K, screen off, analysis every frame | *identical* |
| Longest run | 285s | **10013s (2h 47m) and counting** |
| Mean uptime, `stall` | 176s (n=69) | no stalls |
| Restarts in 2h 47m | ~57 predicted | **0** |
| `VIDIOC_QBUF` EINVAL | once per frame at each wedge | **0** |
| Suspend attempts (`spurious wake up`) | one every ~40.3s | **0** |
| `captureBufferLost` | 1.72/min | 1 total |

2h 47m on a single run against a 176s mean and a 285s record - 57x the mean, 35x the record.
Thermal climbed back to
`moderate` (headroom 0.93) during the soak without provoking a stall, which independently
re-confirms the thermal retraction.

The suspend attempts disappearing entirely is the direct confirmation that the mechanism was
understood correctly and not merely correlated with: a `PARTIAL_WAKE_LOCK` does exactly one
thing, and that one thing removed both the wakeups and the stalls.

### Still owed

- An overnight soak. Under three hours clears the failure by 57x, but a rarer mode would not
  have shown up yet.
- Measuring this from the host is unreliable: poller processes and `adb logcat` captures on the
  Windows machine stop emitting after a few minutes. The phone's own cumulative counters are
  unaffected and are what every number here rests on.
- Whether the residual `updateTexImage` failures (~370 capture results lost per occurrence,
  against ~3 for a suspend-killed encoder) have a cause of their own.
- Battery cost. A held partial wake lock on a mains-powered fixed camera is cheap; on battery it
  is not free, and nothing measures it yet.

### Instrumentation added while closing this

`eglSwapBuffers`' return value was discarded at both call sites. A silent swap failure is
indistinguishable from a wedged encoder from the outside - `lastFrameMs` is stamped *before* the
draw, so it stays fresh either way. It is now checked, counted (`run.swaps`, `run.swapFailures`,
and the matching `lastFailure.*` gauges) and the EGL error logged. It reported zero failures
across the diagnosis, which is what ruled the EGL path out and left the driver.

Not changed, deliberately: **`CONTROL_AE_TARGET_FPS_RANGE` is still unpinned in the record path**
(`LivePipeline` pins it, `CameraGlPipeline` does not). Pinning it would stop the HAL lengthening
exposure at night, making night footage darker, and it is not implicated in any of this.

### How to close it

Leave a phone recording for an hour with the screen off, then `GET /api/camera/health`:

| Field | Reads as |
|---|---|
| `meanUpMsByEndReason.stall` | absent is the pass; anything near the ~160s floor is the regression |
| `gauges.lastFailure.cameraFrameAgeMs` vs `…encodedOutputAgeMs` | fresh frames + stale output = the suspend failure |
| `gauges.lastFailure.swapFailuresThisRun` | non-zero moves the fault to EGL, not the codec |
| `counters.captureBufferLost` | the separate buffer-delivery fault, not this one |
| `gauges.motion.disturbancesExpired` | non-zero means a `MotionGate` pairing bug, not a camera fault |

In `logcat`, `VIDIOC_QBUF (22 - Invalid argument)` is the signature to grep for, and
`adb shell dumpsys power | grep panopticon` confirms the lock is actually held.

## Affected files

`phone-app/.../camera/ZoomGeometry.kt` (`splitFor`), `camera/ZoomFeedback.kt` *(new)*,
`camera/CameraHealth.kt` *(new)*, `camera/CameraGlPipeline.kt`, `camera/LivePipeline.kt`,
`motion/CameraDisturbance.kt` *(new)*, `motion/MotionAnalyzer.kt` *(new)*,
`motion/MotionDetector.kt`, `http/routes/HealthRoutes.kt` *(new)*, `http/PanopticonHttpServer.kt`,
`PanopticonApplication.kt`, `service/PanopticonService.kt`.

Tests: `ZoomTransitionTest`, `MotionAnalyzerTest`, `CameraHealthTest`, additions to
`MotionDetectorTest`.

## Acceptance

- A zoom change, from the slider or a drawn box, produces no visible jump in the recording or the
  live preview — the framing holds and the image sharpens.
- Over an hour of a still scene, no clips are recorded.
- `GET /api/camera/health` reports a mean uptime per end reason after a soak.
