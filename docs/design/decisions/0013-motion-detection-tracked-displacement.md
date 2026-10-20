# 0013 — Motion detection by tracked displacement

**Status:** Accepted · implemented (phone-app), 2026-10-17. Supersedes the detection algorithm
in [`0005`](0005-motion-detection.md). 0005's "motion stays out of the live API" still stands.
Follow-up: [`../../plan/motion-detection-tuning.md`](../../plan/motion-detection-tuning.md).

## Context

The 0005 detector was change detection with filters. It counted grid cells whose colour moved
against a ~1s-old frame, with noise-relative thresholds, an exposure-shift correction, and a
small-block test bolted on. Anything that changed enough pixels counted, so it could not tell
a person walking past from a TV flickering behind blinds, auto-exposure hunting at night, or
rain. Each new false trigger needed another filter, and every filter raised a threshold and
cost sensitivity.

The requirement now: count only **motion**, i.e. image structure that moves and keeps moving.
Reject change that isn't motion (flicker, lighting, noise), and reject particle motion (rain,
insects). Within that, be highly sensitive. It must still run on the BLU G5 without extra
camera streams or HAL buffers, and without draining the battery.

## Decision

### Four stages, each removing one class of false motion

`motion/MotionDetector.kt` runs per analysed frame (160×120 luma, ~15fps):

1. **Noise model** (`NoiseModel`). Per-brightness-band temporal noise and this frame's
   brightness shift, from medians of the frame difference. Every threshold below is in units of
   this noise. The shift is subtracted first, so auto-exposure steps do not read as change.
2. **Activity, then displacement** (`BlockMotion`, 8×8 blocks).
   - A block is *active* when more of its pixels changed beyond noise than its own running
     baseline. This step only decides where to look.
   - For active blocks only, it searches for the shift that maps the changed pixels' **ternary
     census codes** (`Census`) onto the previous frame. Census codes record which neighbours
     are brighter or darker, beyond a noise-wide dead band, so flicker and exposure leave them
     unchanged. A vector is kept only when it:
     - removes at least 40% of the mismatch, and at least 1.5 bits per pixel (small
       objects leave about half their changed pixels unexplainable: the background they
       uncovered);
     - is unique (repetitive texture fails);
     - is **forward-backward consistent** (disocclusion fails: the uncovered background where
       a raindrop was).
   - The search runs at full resolution (±3px), at half resolution (±8px, refined), and
     against the frame 4 back (sub-pixel speeds). Blocks with fewer than 8 changed pixels are
     not searched.
   - If most textured blocks share one vector, the camera moved and the frame is skipped.
3. **Grouping.** Neighbouring blocks with similar vectors form blobs.
4. **Tracking** (`MotionTracker`). Blobs are associated frame to frame on position *and*
   velocity. Only a **confirmed** track is motion. A track is confirmed when it has been:
   - seen for `confirmMs`, in at least a third of the analysed frames since it was born
     (distant slow objects only yield a confident vector every 2-3 frames);
   - moving at least `minPath` px along its path (not net, so **a swaying tree counts**);
   - at least `minSupport` changed pixels in size.

   **Particle rule:** a *small* track must also keep a consistent heading, get somewhere (net
   displacement), and cover less than ~0.4 of its own size per frame. Distant people pass;
   insects (erratic, or a body length per frame) and clutter fail. When many small tracks
   are born at once (rain, swarms), small tracks need twice as long.

`motionSensitivity` now selects these object-level criteria. Pixel-level thresholds follow
the noise at every level:

| | high | medium | low |
| --- | --- | --- | --- |
| `confirmMs` | 400 | 600 | 1000 |
| `minPath` (px @160×120) | 4 | 6 | 10 |
| `minSupport` (changed px) | 6 | 12 | 30 |
| small below (changed px) | 40 | 60 | 100 |
| small-track heading coherence | 0.75 | 0.8 | 0.85 |

Confirmation latency (0.4-1s) is absorbed by the recorder's 3s pre-roll ([`0004`](0004-recording-pipeline.md)):
a clip still starts before the object appeared.

### Pixel thresholds stay low because confirmation is strict

Pixel thresholds stay low (3σ change, 2σ census dead band) and nothing is tuned to a scene.
A false positive has to survive the displacement tests, the consistency check *and* sustained,
coherent tracking. Noise, flicker and particles each fail at least one.

### Off the GL thread, luma only, asynchronous readback, lazy census

- **GPU side** (`camera/MotionReadback.kt`):
  - A shader renders the analysis view as luma, packed 4 pixels per RGBA texel (19KB instead
    of 76KB). Each pixel averages a 3×3 tap grid, so fine texture does not alias into shimmer.
  - On a GLES 3 context (both test phones), readback goes into a pair of pixel-pack buffers
    and is mapped one analysed frame later. The synchronous `glReadPixels` pipeline flush that
    starved the camera's buffer queue is gone. GLES 2 falls back to a synchronous read.
- **Threading:** analysis runs on its own thread from a pool of 3 buffers. When that thread is
  busy, frames are dropped and counted, never waited for.
- **Rate:** 15fps (every 2nd camera frame).
- **CPU side:** census codes are computed lazily, per 8×8 tile, only around active blocks. A
  quiet scene costs one luma pass. The first, full-frame implementation spent two thirds of its
  time on census codes nobody compared.
- No new camera streams, HAL buffers or native libraries.

### The camera-disturbance gate is kept

`MotionGate`/`CameraDisturbance`/`MotionAnalyzer` describe changes *we* make to the camera.
That is knowledge no detector can infer, so it carries over unchanged. `MotionAnalyzer.reset`
now defers the detector reset to the analysis thread.

## Consequences

- **A colour or brightness change with no displacement is no longer motion.** That includes
  the night-time drawn-blinds case that drove 0005's per-channel design. This is intended.
- **Visible screen content is real image motion.** A TV in view, not behind blinds, will
  produce real tracks. Exclusion zones are out of scope.
- **Fixed-camera assumption.** A camera that pans continuously would be suppressed as global
  motion.
- **Debug trace format** is now `PMTR2` (160×120 luma at the analysis rate, track counts per
  frame). `MotionReplayTest` replays traces and recorded clips through the shipping code on the
  JVM, against the old detector (`LegacyChangeDetector`, test-only).
- **Health gauges:** `motion.readback` (async/sync), `motion.analysisMeanMs`,
  `motion.analysisMaxMs`, `motion.framesDropped`.
- **Tuning** so far is synthetic scenes (`MotionDetectorTest`) plus the replay results recorded
  in the status doc. The thresholds above are starting points to refit on labelled traces.
