# 0015 — Stills while recording, and one camera view

**Status:** Accepted · implemented (phone-app, controller), 2026-10-20. Build- and unit-tested;
not yet run against a real phone.
Component specs: [`phone-recording-pipeline.md`](../components/phone-recording-pipeline.md),
[`controller-live-and-camera.md`](../components/controller-live-and-camera.md).

## Context

The only way to see what a phone was aiming at was the live preview, and live and record are
mutually exclusive ([`0007`](0007-live-preview-plain-hls.md), [`0010`](0010-mode-state-machine.md)):
low-end phones don't run two encoders well, and a second H.264 stream while recording costs
encoder time and heat on a phone that already records all day. So a recording phone was a black
box. Phone detail showed "stop recording to watch live", and zoom was frozen while recording
because there was nothing to frame it against. To adjust the shot you had to stop recording,
watch, adjust and restart, losing footage the whole time.

A view of a scene that is mostly still does not need 30fps video. A JPEG every couple of seconds
is enough to frame, focus and expose against, and it costs one extra draw and readback of a frame
the recording pipeline is already rendering.

## Decision

### Stills come from the recording pipeline's own GL thread, on demand

`GET /api/camera/snapshot` returns a JPEG of exactly what RECORD is encoding: the same view rect
(zoom, aspect trim) and the same rotated quad as the encoder draw, scaled to at most 960px on its
longer edge. `camera/SnapshotReadback.kt` draws the next camera frame once more into a small FBO,
reads it back, and JPEG-encodes it on a thread of its own.

It is **pull-only**. Nothing is rendered until a request arrives, so a phone nobody is looking at
does no work at all. Concurrent requests share one grab, and a still under 1s old is handed back
as is, so any number of controllers watching one phone cost at most one grab a second between
them. The readback is synchronous (`glReadPixels` into client memory). That is the GL-thread stall
[`MotionReadback`](../components/phone-recording-pipeline.md) avoids at frame rate, but at once
every second or two it is a few milliseconds the camera's buffer queue absorbs.

Only RECORD serves stills. LIVE has real video, and STANDBY has no camera open (`409`). While the
camera is restarting the route answers `503` with `retryAfterMs`, the same shape as
`POST /api/live/start`'s arming contract.

### The controller polls only while someone is looking

Phone detail polls `/still/<phoneId>` (a same-origin proxy, like `/live/`) every 2s, and only while
it is open on a recording phone and the window is visible. It starts the next request only once
the last has finished. After a control change it looks again sooner (1.2s: just past the phone's
1s still cache, so the "fresh" still isn't the one from before the change).

### One camera view, two feeds

Phone detail has one camera viewport with the same controls in both modes: stills while
recording, LL-HLS video in live mode. Rulers, the shared rect picker (zoom, focus, exposure), the
camera switcher and rotation all work over either, in the same `0..1` viewer coordinates. The
still is sized to its own aspect ratio exactly like the `<video>`. The phone maps a rect drawn
over a still with the recording pipeline's aspect trim, as it maps one drawn over the video with
the live pipeline's.

Live mode stays. It is still the only full-motion view, and `record` → `live` is still a `409`.

### Zoom is no longer frozen while recording; resolution still is

The zoom freeze existed because there was no way to see the frame while recording. A zoom is a
repeating-request re-issue plus a GL crop, with no rebuild and no hole in the footage, and the
motion gate already treats it as our own disturbance, so it does not start a clip. A clip that is
recording simply reframes.

`videoResolution` stays frozen (`409`). It fixes the encoder's size, so changing it rebuilds the
pipeline: a gap in the footage, and segments of two sizes inside one clip.

`applyControls` now begins the `CONTROLS` disturbance **before** publishing the new spec. The GL
thread reads it every frame, and a zoom that is all GL crop moves the picture on the very next
one. Publishing first left a frame in which the view had jumped and the gate was still open.

## Consequences

- A recording phone can be framed, focused, exposed and zoomed without stopping it.
- A clip may now contain a reframe, where a zoom was applied mid-clip. That is the trade the
  user accepts by zooming while recording.
- Stills cost nothing when nobody is looking, and about one frame readback plus one small JPEG
  encode per second per phone when someone is.
- Not yet verified on a device: still orientation and colour on both test phones, the GL-thread
  cost of the synchronous readback on the BLU G5, and that a mid-recording zoom on the Pixel 6
  does not produce a clip.
