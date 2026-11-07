# 0016 — Live video while recording, off the recording encoder

**Status:** Accepted · implemented (phone-app, controller, mock phone), 2026-11-06. Measured on
the Pixel 7 Pro. Partly supersedes [`0015`](0015-recording-stills.md): stills stay, as the
fallback. Planned next: LIVE mode on the same pipeline, so moving between record and live no
longer reopens the camera.
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
relay drops samples until that keyframe. The live routes serve a `LiveSource`, which `LivePipeline`
(LIVE mode) and `LiveTap` (RECORD) both implement. The same 15s no-fetch watchdog ends it.

The view is the recording itself: the same view rect, size and bitrate (4Mbps, 30fps) - not the
2Mbps/24fps of LIVE's own encoder.

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
- Live while recording is at the recording's resolution and bitrate: up to 4K at 4Mbps. Fine on the
  LAN, heavier over Tailscale.
