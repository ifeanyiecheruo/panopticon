# Quirks

This is a **new** project doc, separate from `panopticon-prototype/QUIRKS.md` (the old,
abandoned prototype's findings). Same format: what we assumed → what actually happens → what we
do about it → where. Entries tagged "Reconfirmed on Pixel 6" were directly re-verified against
real hardware while building this vertical slice (`phone-app/`). Entries tagged "Carried forward"
were adopted as defensive workarounds from the old doc without being independently re-tested here
- don't mistake them for confirmed-in-this-project.

Primary test device for most of the entries below: **Google Pixel 6 (`oriole`), Android 16 (API
36), build `BP2A.250605.031.A2`**, adb serial `1C281FDF6005H0`. Encoder observed in logcat:
`ExynosC2H264EncComponent` (Exynos hardware AVC encoder) - a different SoC family from the old
prototype's Pixel 9a (Tensor), which is itself a reason some findings below don't transfer
directly. A second device, a **BLU G5, Android 9 (API 28)**, also gets used opportunistically as
a low-end/old-API check - see [`android-service.md`](android-service.md)'s foreground-service
entry for what it caught that the Pixel 6 (API 36) couldn't have.

## Domains

| File | What's in it |
| --- | --- |
| [`camera2-recording-pipeline.md`](camera2-recording-pipeline.md) | Camera2 capture sessions, the `MediaCodec` / `MediaMuxer` recording pipeline, gapless segment rotation, the GL `SurfaceTexture` fan-out, encoder-size and keyframe behaviour. |
| [`calibration-zoom.md`](calibration-zoom.md) | The empirical zoom probe (`calibration/CalibrationRunner`): `CONTROL_ZOOM_RATIO` vs legacy `SCALER_CROP_REGION`, off-centre crop honouring, digital-zoom quality collapse, per-device findings, and the ART class/field-verification trap. |
| [`manual-camera-controls.md`](manual-camera-controls.md) | The `/api/camera/*` slice: manual exposure / focus / white-balance, `MANUAL_SENSOR` gating, logical vs physical multi-camera, camera switching. |
| [`live-hls.md`](live-hls.md) | `live` mode: the plain-HLS design, sliding-window sizing, hls.js live config + stall watchdog, and the carried-forward LL-HLS / hls.js latency workarounds. |
| [`mpeg-ts.md`](mpeg-ts.md) | The hand-rolled MPEG-TS muxer (`camera/ts/TsMuxer.kt`) - why `MediaMuxer` can't be used and the 188-byte-packet invariant. |
| [`android-service.md`](android-service.md) | The foreground service and the embedded HTTP server: `startForeground` overload gating, bind-retry, and the end-to-end hardware confirmation. |
| [`dev-tooling-windows.md`](dev-tooling-windows.md) | Windows / MSYS2 `make` / Gradle / Go toolchain quirks hit while building on this dev machine (an OneDrive-rooted repo). |

## The "Carried forward" tag

Some entries carry a **Carried forward** marker: a finding from
`panopticon-prototype/QUIRKS.md` that this project adopted as a defensive workaround but has
**not** independently re-verified against the Pixel 6 / BLU G5. These were out of scope for this
vertical slice (no live HLS LL-HLS upgrade; calibration, motion-gated recording and manual
camera controls now exist but the specific carried-forward quirks still haven't been re-tested
against the Pixel 6) - see `panopticon-prototype/QUIRKS.md` for the original write-ups. Where
this slice *did* re-test a carried-forward claim the entry says so, and the result often differed
(the prototype's Pixel 9a / Tensor findings frequently don't transfer to the Pixel 6 / Exynos).
Don't cite a carried-forward entry as confirmed-here.
