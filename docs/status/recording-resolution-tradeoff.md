# Plan (investigation): pick the recording resolution from calibration, not by hand

**Side:** phone (+ controller display) · **Size:** investigate first, then medium

## Goal

Let the phone choose the cheapest stream/encode resolution that still delivers the sharpness a
given zoom can deliver, instead of the user finding it by trial and error.

## Context

- Recording briefly reduced its size from the zoom (`RecordingSizeSelection.recordingSizeFor`),
  assuming all zoom was a digital crop. That was wrong once most zoom moved into the camera
  (`CONTROL_ZOOM_RATIO` crops the sensor and reads the crop out at the *stream* size), and clips
  came out far softer than the live preview. Removed 2026-09-29: RECORD now streams and encodes at
  `videoResolution`, exactly like LIVE.
- On 2026-09-29 the user then lowered `videoResolution` to 1024x576 **by hand, for heat**: by eye
  it was the lowest size that didn't look blurry at their zoom (7.9x = 3.9x hardware + 2.0x GL).
  That is exactly the power/quality trade-off the phone should be making itself.
- The real detail across the view is bounded by several things the phone can measure or already
  knows: the sensor pixels the hardware crop covers (and whether the HAL remosaics at that ratio),
  the stream size the ISP reads that crop out at, the GL residual that magnifies it further, and
  the optics. Above the tightest bound, extra resolution is heat for nothing.

## Approach (to investigate)

1. **Measure delivered detail per zoom.** Extend the calibration sweep (`calibration/`) to capture
   a textured target at each hardware ratio and several stream sizes, and score sharpness (e.g.
   high-frequency energy / MTF50 on edges) - how many real pixels does each size deliver at each
   ratio? Find where more resolution stops adding detail.
2. **Measure cost.** Thermal headroom / encoder load per stream size (the pipeline already logs
   `thermal.*` gauges and measured fps).
3. **Decide.** With a view's split known at pipeline start, choose the smallest size whose
   delivered detail after the GL residual matches the largest size's. `videoResolution` becomes a
   ceiling again, but the decision is backed by measurement rather than the crop arithmetic that
   failed before.
4. Report the chosen size (`recordingResolution` already exists in `/api/camera/state`) and why,
   so the controller can show it.

## Open questions

Does the Pixel 6 remosaic (50MP) at some hardware ratios, making the curve step-shaped? Does the
answer depend on light (night noise swamps fine detail)? Is heat dominated by the ISP stream size,
the encoder size, or both - i.e. could stream at full size and encode smaller (they are separate
since `sourceSize` / `outputSize` exist)?

## Not in scope

Changing the zoom split itself (off-centre hardware crop via `SCALER_CROP_REGION`), or the bitrate.
