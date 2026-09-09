# 0007 — Live preview: plain HLS

**Status:** Accepted · implemented (both sides). LL-HLS + scoped token + adaptive bitrate
deferred ([`../../status/ll-hls-upgrade.md`](../../status/ll-hls-upgrade.md)).
Component specs: [`phone-live-pipeline.md`](../components/phone-live-pipeline.md),
[`controller-live-and-camera.md`](../components/controller-live-and-camera.md).

## Context

`live` mode needed a real feed, not a stub. The prototype built full LL-HLS
(`EXT-X-PART`/`PRELOAD-HINT` parts, blocking playlist reloads) and paid for a long list of
hls.js latency workarounds. This project wanted the smallest thing that holds up.

## Decision

### Plain HLS, not LL-HLS

Whole ~1s `.ts` segments, a **16-deep** sliding window (~16s DVR), `#EXT-X-VERSION:3`,
`#EXT-X-START:TIME-OFFSET=-4`. Glass-to-glass ≈ 4–6s. A deep window is load-bearing: a shallow
one let any hiccup walk hls.js into fetching an evicted segment → 404 → stall spiral. The fix is
~1s segments + the 16-deep window + real hls.js live config + a client stall watchdog
(`hls.startLoad()` + seek-to-`liveSyncPosition` on stalled progress / buffer-stall / fatal
network error) — the minimal subset of the prototype's workarounds that plain HLS needs.

### Hand-rolled MPEG-TS muxer

`MediaMuxer` can't emit MPEG-TS, so `camera/ts/TsMuxer.kt` is hand-rolled (PAT/PMT/PCR/PES),
ported verbatim from the prototype, with `TsMuxerTest` guarding the 188-byte-packet invariant.
See [`../../quirks/mpeg-ts.md`](../../quirks/mpeg-ts.md).

### Single stream, no GL for live

Live does no motion analysis, so the camera renders straight into the encoder input surface —
one stream, no GL thread. The BLU G5 two-stream rejection is a non-issue here by construction.

### RECORD and LIVE are mutually exclusive

Deliberate — low-end phones don't multi-task encoders well. `live` from `record` is a `409` (go
via `standby`; see [`0010`](0010-mode-state-machine.md)).

### Armed-idle vs broadcasting, with a cold-camera arming contract

Entering `live` opens the camera but encodes nothing until `POST /api/live/start`; a 15s no-`GET`
watchdog returns it to armed-idle. `POST /api/live/start` answers
`503 {error:"camera still starting", retryAfterMs:2000}` + `Retry-After` while the camera is
still coming up (a cold front camera takes several seconds); a bare
`503 {error:"live camera unavailable"}` is a hard, no-retry failure. The controller retries the
hinted form for ~20s.

### `/live/*` behind the normal bearer token; controller proxies server-side

`controller/liveproxy.go` fetches the playlist/segments with the stored token and serves them
same-origin to the webview, so hls.js never needs the credential and CORS is sidestepped. The
prototype's separate GET-only scoped token is deferred.

### hls.js is vendored, not an npm dependency

hls.js 1.7.2 (Apache-2.0) lives under `controller/frontend/src/vendor/hlsjs/` as built ESM (full
+ minified) + `.d.ts` + LICENSE + update steps. The frontend build does no registry fetch for it.

## Consequences

- Verified end to end: Pixel 6 played 2.5+ min continuously (~50 segments, zero 404s, no
  stalls); BLU produced dead-regular ~0.96s segments at real time.
- Deferred: LL-HLS and the rest of its hls.js workarounds (catalogued in
  [`../../quirks/live-hls.md`](../../quirks/live-hls.md)), adaptive bitrate, the scoped
  `/live/*` token, and a sustained on-device soak.
