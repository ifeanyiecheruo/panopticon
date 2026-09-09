# Plan: LL-HLS upgrade for live view

**Side:** both · **Size:** large

## Goal

Cut live glass-to-glass latency below the current plain-HLS ~4–6s by moving to LL-HLS, only if
the plain-HLS latency proves too high in practice.

## Context

[`../design/decisions/0007-live-preview-plain-hls.md`](../design/decisions/0007-live-preview-plain-hls.md)
ships **plain HLS** deliberately — the prototype built full LL-HLS and paid for a long list of
hls.js latency workarounds, all of which are catalogued (ready to adopt) in the carried-forward
section of [`../quirks/live-hls.md`](../quirks/live-hls.md). This plan is that adoption.

## Approach

**Phone (`camera/LiveHlsRelay.kt`, `camera/ts/TsMuxer.kt`, `http/routes/LiveRoutes.kt`):**

- Emit `EXT-X-PART` partial segments with byte-range `PRELOAD-HINT`; support blocking playlist
  reloads (`_HLS_msn` / `_HLS_part` query params) with a long-poll hold.
- Keep the hand-rolled TS muxer; parts are byte ranges into the same `.ts`.

**Controller (`frontend/src/components/LivePreview.tsx`):**

- Turn on hls.js LL-HLS; port each carried-forward workaround from
  [`../quirks/live-hls.md`](../quirks/live-hls.md):
  - zero-progress watchdog that forces `startLoad()`/reload (load queue stuck after a stall);
  - manual `hls.targetLatency` reset to `partHoldBack` after a stall-free window (latency
    ratchet);
  - leave `liveSyncDuration`/`liveSyncDurationCount` unset (setting them defeats LL-HLS);
  - keep the bounded manifest-404 fast-retry (already present).

**Adjacent deferred items to fold in here:**

- **Scoped `/live/*` GET-only token** — mint a token that can fetch segments but not delete
  footage; the phone issues it, the controller stops proxying with the full bearer token (or
  keeps the proxy and drops the token requirement). See
  [`http-api.md` open questions](../design/http-api.md#4-open-contract-questions).
- **Adaptive bitrate** — multiple `EXT-X-STREAM-INF` renditions off the one encoder, or a
  single rendition with a bitrate step on sustained rebuffering.

## Affected files

Phone: `camera/LiveHlsRelay.kt`, `camera/ts/TsMuxer.kt` (+ test), `http/routes/LiveRoutes.kt`.
Controller: `frontend/src/components/LivePreview.tsx`, `liveproxy.go` (range/long-poll
pass-through), `tools/mock-phone` (its HLS stream grows LL-HLS output).

## Testing

`DebugLiveReceiver` verdict extended for parts; `TsMuxerTest` still green; a sustained on-device
soak on both devices (the plain-HLS run was 2.5+ min — LL-HLS needs at least the same).

## Acceptance

Measured glass-to-glass latency materially below plain HLS on the Pixel 6, with no stall spiral
over a multi-minute soak on both devices.

## Not in scope

Changing `record`/`live` exclusivity; WebRTC or any non-HLS transport.
