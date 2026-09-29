# Plan: on-device motion-threshold tuning + background model

**Side:** phone · **Size:** medium

## Goal

Replace the reasoned-but-unmeasured per-sensitivity thresholds with values validated against a
real phone in real lighting, and evaluate whether a background-subtraction model is worth it.

## Context

[`../design/decisions/0005-motion-detection.md`](../design/decisions/0005-motion-detection.md):
`MotionDetector` is frame-difference on a 32×24 grid, no background model.

**Done 2026-09-28 (partial):** a night-time scene where a window's blinds were drawn went
undetected on `high`. Replaying 72 recorded Pixel 6 clips offline showed the change was colour
(green −4, blue −12), gradual, and in ~30 of 768 cells - invisible to luma against the previous
frame at any threshold. The detector now compares cell means on every RGB channel against a
~30-frame-old reference, with per-level (cell delta, fraction): high (10, 1.5%), medium
(26, 4%), low (22, 8%). Fit to recordings, not the live readback, and to one scene; steps 1-3
below (live instrumentation, more scenes, background model) are still open.

**Done 2026-09-28/29:** step 1 exists - `debug/DebugMotionTraceReceiver` records the detector's
exact input (80x60 RGB at 10fps, plus its per-frame verdict) for offline replay. From those
traces:

- `high` gained a fine 80x60 grid that fires on any 2x2 block of changed cells, for small
  movements (a head behind a laptop was ~1 coarse cell). The block rejects sub-pixel camera shake,
  which only lights thin lines along edges.
- Every threshold on `high` is now relative to each cell's own running noise (fine 6x, coarse
  5x): pixel noise over 1s is ~2 by day and ~12 at night, so no fixed delta suits both - the
  fixed one fired on 90% of night frames.
- The frame-wide brightness shift (median over cells, capped at 24) is removed first: night
  auto-exposure hunting, worse with exposure compensation raised, moved the whole frame 6-18
  levels in steps. Coarse cells also need a changed neighbour to count.

Result on the 2026-09-29 night: from recording 50-100% of the time to ~0.2-4% of frames, the
remainder being lit windows (a TV behind blinds, lamps). Still one scene; medium/low unchanged.

## Approach

1. **Instrument** — a debug mode (extend `DebugCalibrationReceiver`-style `adb` triggers) that
   logs per-frame changed-cell counts + the verdict to a file over a fixed scene, so real
   sequences (empty room, person walking, light switch, cloud shadow, TV on) can be replayed
   and scored.
2. **Tune** — pick low/medium/high thresholds from the logged distributions: minimise false
   triggers on light-switch / drift while keeping true-positive latency low. Record the numbers
   and the scenes they were fit to.
3. **Evaluate a background model** — a running per-cell mean/variance (single-Gaussian) or a
   lightweight MOG; compare false-trigger rate vs the frame-difference baseline on the same
   logged sequences. Only adopt if it clearly wins and stays cheap enough for the BLU G5.

## Affected files

`phone-app/.../motion/MotionDetector.kt` (thresholds, optional background model),
`motion/RecordingPhaseController.kt` (if the verdict shape changes), a debug receiver +
`src/debug/AndroidManifest.xml`, `MotionDetectorTest`.

## Testing

`MotionDetectorTest` gains fixture-sequence cases (recorded luma-grid traces → expected
verdicts). Manual: run the tuned thresholds on the Pixel 6 across the scene set.

## Acceptance

Documented thresholds fit to named real sequences; false triggers on a light-switch and on slow
cloud drift are eliminated (or explicitly accepted) at the default sensitivity; no regression in
true-positive latency; BLU G5 still holds frame rate.

## Not in scope

Any ML model needing a training pipeline or a native CV library; motion signal in the live API
(kept out by [`0005`](../design/decisions/0005-motion-detection.md)).
