# Plan: on-device motion-threshold tuning + background model

**Side:** phone · **Size:** medium

## Goal

Replace the reasoned-but-unmeasured per-sensitivity thresholds with values validated against a
real phone in real lighting, and evaluate whether a background-subtraction model is worth it.

## Context

[`../design/decisions/0005-motion-detection.md`](../design/decisions/0005-motion-detection.md):
`MotionDetector` is frame-difference on a 32×24 luma grid, no background model. The
`motionSensitivity` → threshold mapping in the code was chosen by reasoning and **never measured
against the Pixel 6 in real lighting**. It is knowingly naive about lighting steps and slow
drift.

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
