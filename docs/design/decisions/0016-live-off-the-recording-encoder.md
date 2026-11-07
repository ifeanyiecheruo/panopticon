# 0016 — Live video while recording, off the recording encoder

**Status:** Accepted · implemented (phone-app, controller, mock phone), 2026-11-06. Measured on
the Pixel 7 Pro. Partly supersedes [`0015`](0015-recording-stills.md) (stills stay, as the
fallback), and [`0007`](0007-live-preview-plain-hls.md)'s and [`0010`](0010-mode-state-machine.md)'s
"RECORD and LIVE are mutually exclusive": LIVE is now the recording pipeline with recording off.
Component specs: [`phone-recording-pipeline.md`](../components/phone-recording-pipeline.md),
[`controller-live-and-camera.md`](../components/controller-live-and-camera.md).

## Context

[`0015`](0015-recording-stills.md) gave a recording phone a view, as JPEG stills, because live and
record were mutually exclusive: a second H.264 encoder running alongside the recording one costs
encoder time and heat on a phone that records all day.

But the recording encoder doesn't need a second one beside it. It already runs continuously in
RECORD, whether or not a clip is being written (the muxer is what the motion gate opens and
closes, see [`0004`](0004-recording-pipeline.md)), and it produces exactly what the LL-HLS relay
consumes: H.264 access units with 1s keyframes, against the relay's 1s segment target.

## Decision

### Tap the recording encoder

`CameraGlPipeline`'s drain thread hands every encoded access unit to a `LiveTap`. Idle, that is one
volatile read per frame. `POST /api/live/start` in RECORD starts a `LiveHlsRelay` on it, with no
mode change, and asks the encoder for a keyframe so the viewer isn't kept waiting for the GOP; the
relay drops samples until that keyframe. The live routes serve the tap as a `LiveSource`. The same
15s no-fetch watchdog ends it.

The view is the recording itself: the same view rect, size and bitrate (4Mbps, 30fps) - not the
2Mbps/24fps of the separate LIVE encoder it replaced (below).

### LIVE is the same pipeline, with recording off

`LivePipeline` - LIVE's own camera, encoder and relay - is gone. LIVE is `CameraGlPipeline` with
`setRecording(false)`: the drain thread opens no segment (finalizing one that is open), and
everything else runs as in RECORD, motion analysis included, so the detector and the pre-roll
ring are already warm when recording comes back on. RECORD ⇄ LIVE is that one flag: the camera,
its converged exposure and focus, the encoder and a viewer's stream all carry on, and
`POST /api/mode` allows `live` from `record`. The controller relies on that. It doesn't reconnect
the player when a recording starts or stops, and stopping a recording someone is watching goes to
`live` (`StopRecordingToLive`), not `standby`; turning the preview off then rests the phone in
standby.

LIVE used to sit armed-idle (camera open, nothing encoding) until a viewer arrived. Now it runs the
whole pipeline, which costs about what recording does, so LIVE with no viewer for 2 minutes drops
to STANDBY on its own: a controller that goes away without a word can't leave the camera running.

Camera switches, resolution and rotation changes still rebuild the pipeline (a new frame size),
and so does STANDBY; those are the remaining reasons a viewer reconnects. LIVE now uses the record
request as it is, so it no longer pins `CONTROL_AE_TARGET_FPS_RANGE` (see
[`../../quirks/camera2-recording-pipeline.md`](../../quirks/camera2-recording-pipeline.md)): in low
light the frame rate can drop, as it can while recording.

### Live segments in memory

The relay holds finished segments in memory (16 × ~0.5MB at 4Mbps) instead of writing them to the
cache directory and reading them back. Writing them cost ~1.8GB of flash writes per hour of
watching, for something nobody keeps. It also closes the window in which the playlist named a
segment whose file wasn't written yet.

### A warm phone declines, and the viewer gets stills

From thermal status `moderate` the tap refuses to start (`409` with the reason) and stops one
already running. That is one step before the thermal guard's default `severe` shutdown
([`0014`](0014-thermal-safety-shutdown.md)). A phone build that predates this also answers `409`
in RECORD. Either way the controller shows stills, and asks again every 60s.

### The controller

`StartLivePreview` on a recording phone only asks it to stream; `StopLivePreview` only stops the
stream while the phone records, and never changes its mode. Phone detail's preview is live video in
both modes, with stills only for a phone that declines. A hidden window drops the player (the phone
stops on its own 15s later) and comes back with it.

## Consequences

Measured on the Pixel 7 Pro, recording at 1280×720, 150s per phase, power rails read with Perfetto:

| | Recording | Recording + one viewer | Change |
|---|---|---|---|
| Wi-Fi (`VSYS_PWR_WLAN_BT`) | 66mW | 287mW | +220mW |
| CPU, all clusters | 268mW | 286mW | +18mW |
| Camera, GPU, memory, storage | — | — | ≈ +7mW |
| All rails | 1957mW | 2152mW | +195mW (+260mW without an unrelated modem swing) |

- Watching costs ~10–13% on top of recording, almost all of it Wi-Fi sending ~3–4Mbps. Camera,
  GPU and encoder don't move: nothing new is captured or encoded.
- No thermal effect was visible over the 150s (headroom improved in both phases while the phone
  cooled from earlier tests); a long soak, and a weaker phone, are still to be measured.
- A recording phone can be framed and adjusted against real video. Stills remain for older builds
  and warm phones.
- Starting or stopping a recording while watching doesn't interrupt the stream or reopen the
  camera. On the Pixel 7 Pro, a viewer pulling continuously through record → live → record got
  every segment, 30fps with no timestamp gaps, and the camera pipeline's run count didn't move.
- Live while recording is at the recording's resolution and bitrate: up to 4K at 4Mbps. Fine on the
  LAN, heavier over Tailscale.
