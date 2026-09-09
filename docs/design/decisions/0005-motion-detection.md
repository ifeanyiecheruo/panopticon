# 0005 — Motion detection scope

**Status:** Accepted · implemented (phone-app), thresholds un-tuned. Follow-up:
[`../../status/motion-detection-tuning.md`](../../status/motion-detection-tuning.md).

## Context

Recording is motion-gated (see [`0004`](0004-recording-pipeline.md)). The gate signal comes off
the GL readback FBO, on-device, every frame — it has to be cheap.

## Decision

### Frame-difference only, no background model, no CV library

`MotionDetector` subsamples the luma plane on a 32×24 grid and counts cells whose brightness
changed beyond a per-cell delta since the last frame; a cell-count threshold (chosen by
`motionSensitivity` from `/api/config`) fires the gate. A warmup guard and the per-cell
threshold absorb small global auto-exposure/gain drift.

### Thresholds are reasoned starting points, not measured

The per-sensitivity thresholds in the code were chosen by reasoning, **not** validated against a
real phone in real lighting. It is knowingly naive about lighting steps (a light switching on
trips it) and slow scene drift.

### Motion detection stays out of the live API

No live "motion currently detected" signal or badge — Preview only runs `live` mode, where the
motion-gated recording pipeline isn't active. Motion is purely the `motionSensitivity` field in
`/api/config`.

## Consequences

- Cheap enough to run per-frame on the BLU G5.
- On-device threshold tuning against real lighting, and any move to a real
  background-subtraction model, are explicit follow-up work.
- A controller cannot show "motion now" for a phone in live preview — there is no such signal.
