# 0005 — Motion detection scope

**Status:** Accepted · implemented (phone-app), thresholds fit to recorded Pixel 6 footage (2026-09-28). Follow-up:
[`../../status/motion-detection-tuning.md`](../../status/motion-detection-tuning.md).

## Context

Recording is motion-gated (see [`0004`](0004-recording-pipeline.md)). The gate signal comes off
the GL readback FBO, on-device, every frame — it has to be cheap.

## Decision

### Frame-difference only, no background model, no CV library

`MotionDetector` averages the RGBA readback into a 32×24 grid and counts cells where any colour
channel moved beyond a per-cell delta against the frame ~1s (30 frames) earlier; a cell-count
threshold fires the gate. `motionSensitivity` from `/api/config` picks both the per-cell delta
and the cell-count threshold. A warmup guard (whose frames never become the reference) and the
per-cell delta absorb small global auto-exposure/gain drift.

Per-channel and lagged, rather than luma against the previous frame, because a window's blinds
being drawn at night went undetected even on `high`: the change was one of colour (a screen's
blue glow gone, green −4 vs blue −12) and gradual. Cell means rather than one pixel per cell keep
the extra sensor noise a lagged reference sees below the lower thresholds.

### Thresholds are fit to recorded footage

The per-sensitivity thresholds were fit by replaying 72 recorded Pixel 6 clips (a fixed night
scene) through the detector offline: `high` (Δ10, 1.5%) is the only level that catches the drawn
blinds, and no level misses anything the old luma/previous-frame version at the same level
caught. They are fit to recordings (encoded, already cropped) rather than the live readback, and
to one scene. It is still knowingly naive about lighting steps (a light switching on trips it).

### Motion detection stays out of the live API

No live "motion currently detected" signal or badge — Preview only runs `live` mode, where the
motion-gated recording pipeline isn't active. Motion is purely the `motionSensitivity` field in
`/api/config`.

## Consequences

- Cheap enough to run per-frame on the BLU G5.
- On-device threshold tuning against real lighting, and any move to a real
  background-subtraction model, are explicit follow-up work.
- A controller cannot show "motion now" for a phone in live preview — there is no such signal.
