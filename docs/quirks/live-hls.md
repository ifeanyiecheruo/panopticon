# Live view (plain HLS)

Device context and the "Reconfirmed" / "Carried forward" convention: see [`README.md`](README.md).
The hand-rolled MPEG-TS muxer this feed depends on has its own page:
[`mpeg-ts.md`](mpeg-ts.md).

## Live view is plain HLS, single-stream, hand-rolled MPEG-TS
**Context:** `live` mode (`POST /api/mode {"mode":"live"}`) needed a real HLS feed, not the flag
flip it used to be. Decisions taken this slice:

- **Plain HLS, not LL-HLS.** Whole `.ts` segments (~1s target — see keyframe cadence below), a
  **16-deep** sliding window (~16s of DVR), `#EXT-X-VERSION:3`, and `#EXT-X-START:TIME-OFFSET=-4`
  so a joining player sits ~4s behind the live edge. Glass-to-glass ≈ 4–6s. The prototype built
  full LL-HLS (`EXT-X-PART`/`PRELOAD-HINT` byte-range parts, blocking playlist reloads) and paid
  for a long list of hls.js latency workarounds — all deferred here (see "Carried forward").
- **A deep window is load-bearing, not luxury.** The first cut used a 6-segment / ~2s window
  (~12s of DVR). hls.js sitting its default ~3 target-durations behind the live edge then had
  only a few seconds of margin, so any hiccup (a slow fetch, a GC pause, hls.js's *own*
  post-stall latency ratchet) walked it into fetching a just-evicted segment → 404 → stall →
  ratchet → more 404s → a permanent "still frame + endless rebuffering" spiral on the **Pixel 6**
  (the *faster* device). Fix: ~1s segments (finer granularity, a slow fetch costs ~1s of buffer
  not ~2–3s) + a 16-deep window (~16s recovery headroom) + real hls.js live config + a client
  stall watchdog (see `LivePreview.tsx`). After that both devices play smoothly for minutes with
  zero segment 404s.
- **`MediaMuxer` cannot emit MPEG-TS** (only MP4/WebM/3GP/OGG), so the muxer is hand-rolled:
  `phone-app`'s `camera/ts/TsMuxer.kt` (one PAT, one PMT, PCR on the video PID, PES-wrapped
  Annex-B AUs), ported verbatim from the prototype. `TsMuxerTest` guards the one invariant that
  bit it there — every TS packet is exactly 188 bytes, including padded PAT/PMT writes. Full
  write-up: [`mpeg-ts.md`](mpeg-ts.md).
- **No GL / no `SurfaceTexture` for live.** Unlike `CameraGlPipeline` (which fans one stream out
  to motion analysis *and* the encoder), live preview does no motion analysis
  (docs/design/http-api.md: "motion detection stays out of live preview"), so the camera renders
  straight into `MediaCodec.createInputSurface()` — one stream, no GL thread, no EGL. Also means
  the BLU G5's two-concurrent-stream rejection is a non-issue here by construction.
- **Keyframe cadence: `KEY_I_FRAME_INTERVAL = 1s` *and* an explicit `REQUEST_SYNC_FRAME` timer**
  (at half the segment target). Relying on `I_FRAME_INTERVAL=1` alone gave ~2s (not ~1s)
  segments on the Pixel 6 and looser, more variable segments on the BLU G5 — the hint isn't
  honoured as tightly for this direct camera→encoder-surface path as it is for
  `CameraGlPipeline`'s GL-fed encoder. Adding the prototype's explicit sync-frame timer on top
  pins both devices to dead-regular ~0.96–1.0s segments. Also pins the capture rate via
  `CONTROL_AE_TARGET_FPS_RANGE` so the encoder gets a steady frame cadence for hls.js's buffer
  maths (a HAL dropping to 15 fps for exposure makes the buffer lumpy).
- **armed-idle vs broadcasting.** Entering `live` mode opens the camera + configures the session
  but sets no repeating request — zero encoding cost until `POST /api/live/start`. A 15s
  no-`GET` inactivity watchdog drops back to armed-idle.
- **`/live/*` uses the full bearer token.** The prototype minted a separate GET-only scoped
  token so a browser could fetch segments without the credential that can also delete footage.
  Deferred: the controller proxies `/live/*` server-side (`controller/liveproxy.go`), so the
  webview never talks to the phone directly and no scoped token is needed yet.
- **hls.js needs real live config + a stall watchdog** (in `LivePreview.tsx`), not defaults:
  `liveSyncDurationCount` / `liveMaxLatencyDurationCount`, a ~20s `maxBufferLength`, patient
  fragment/manifest retries, plus a 2s-interval watchdog that — on ~6s of no playback progress,
  or a `BUFFER_STALLED_ERROR`, or a fatal network error — calls `hls.startLoad()` and seeks to
  `hls.liveSyncPosition` (or the buffer's leading edge). This is the concrete, minimal subset of
  the prototype's "stuck load queue after a stall" + "latency ratchet never recovers" workarounds
  that plain HLS actually needs. The startup manifest-404 retry budget resets once the first
  fragment buffers, so a mid-stream blip gets a fresh allowance.

**Where:** `phone-app` `camera/LivePipeline.kt`, `camera/LiveHlsRelay.kt`, `camera/ts/TsMuxer.kt`,
`http/routes/LiveRoutes.kt`; `controller/liveproxy.go`, `frontend/src/components/LivePreview.tsx`.
**Verified end to end:** `src/debug/DebugLiveReceiver.kt` (a broadcast probe) confirmed both the
Pixel 6 and BLU G5 produce a rolling playlist of valid `mpegts`/`h264` 1280×720 that `ffmpeg`
decodes + concatenates cleanly; then, through a real `wails dev` controller (hls.js in the
webview against `liveproxy.go`), the Pixel 6 played **2.5+ minutes continuously, ~50 segments,
zero segment 404s, no stalls**, and the BLU produced dead-regular ~0.96s segments at real time.

## Carried forward, not yet re-verified in this project

Adopted from `panopticon-prototype/QUIRKS.md`, not independently re-tested here - see
[`README.md`](README.md#the-carried-forward-tag). The live slice ships **plain HLS**, so the
LL-HLS-specific items below are not implemented yet; adopt them if/when the plain-HLS latency
drives an LL-HLS upgrade (see "Live view is plain HLS" above).

- CORS needs explicit header exposure for hls.js (adopted defensively in `PanopticonHttpServer.kt`
  for ranged clip downloads generally - `exposeHeader(Content-Range/Content-Length)`). The live
  slice sidesteps CORS entirely - `controller/liveproxy.go` serves the playlist/segments
  same-origin to the webview - so this still isn't exercised against a real hls.js client.
- **hls.js LL-HLS + latency workarounds** - the whole `Browser / hls.js` section of
  `panopticon-prototype/QUIRKS.md`:
  - hls.js's load queue can get **permanently stuck after a stall** with no error event
    (video-dev/hls.js#5716, #6350) - needs a zero-progress watchdog that forces `startLoad()`/reload.
  - hls.js's **target latency ratchets up ~1s after every stall and never comes back down**
    (#6350) - needs a manual `hls.targetLatency` reset back to `partHoldBack` after a stall-free window.
  - Setting `liveSyncDurationCount` silently **defeats LL-HLS** (forces whole-segment sync) -
    leave `liveSyncDuration`/`liveSyncDurationCount` unset.
  - The **first playlist load routinely 404s** (encoder hasn't produced segment 0) and hls.js
    treats a playlist 404 as fatal-non-retryable - needs a custom `manifestLoadPolicy` fast-retry.
    *(A minimal bounded-retry version of just this one is in `LivePreview.tsx` already, since it
    bites even plain HLS.)*
  - **Safari** has native HLS, loads no hls.js, and can't attach an `Authorization` header - it
    would always need the server-proxied path. Not relevant while the controller is a WebView2
    (Chromium) app, but relevant if a real browser client is ever added.
- `Server / Node` section: not applicable - the controller is Go/Wails, not the prototype's Node
  server. The live proxy is `controller/liveproxy.go`, ~80 lines, no streaming-error-crash or
  single-instance concerns of the Node original.
