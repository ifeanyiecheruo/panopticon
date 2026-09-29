# Plan: LL-HLS upgrade for live view

**Side:** both · **Size:** large · **Status:** implemented; glass-to-glass latency manually
confirmed on device at ~1s (2026-09-29). Scoped-token and adaptive-bitrate adjacent items below
stay deferred. **Outstanding:** a real multi-minute on-device soak on both devices (see
Acceptance), and the follow-up latency work below, which was tried and reverted. The plain-HLS
bar is documented in
[`../design/decisions/0007-live-preview-plain-hls.md`](../design/decisions/0007-live-preview-plain-hls.md)
and [`../quirks/live-hls.md`](../quirks/live-hls.md), which both describe the shipped LL-HLS
design.

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

## Follow-up: further latency cuts (tried 2026-09-29, reverted)

With ~1s confirmed, four changes were built together to cut further. They passed the phone-app
unit suite (a new `LiveHlsRelayTest`) but the **Pixel 6 glitched roughly once a second** in live
view, so all four were reverted without finding the cause. The code is in a local git stash on
the dev machine (`latency: 200ms parts, held preload hints, no B-frames, finished-segment parts`)
(local only, never pushed). Redo them **one at a time**, soaking each on the Pixel 6
before the next, in this order:

1. **No-B-frame encoder** (`LivePipeline.createEncoder`): `KEY_MAX_B_FRAMES=0` (API 29+),
   `KEY_LATENCY=1`, `KEY_PRIORITY=0`; if `configure()` throws, `reset()` and configure without
   them. Also a correctness guard: `TsMuxer` assumes PTS == DTS, which B-frames would break.
   Check the BLU G5's logcat for the "encoder rejected low-latency hints" fallback.
2. **Range handling in `LiveRoutes` / `LiveHlsRelay`:**
   - **Real bug, fix regardless:** a `Range` request for a segment that has already finalized
     gets the *whole file* with `200`, not the requested range - hls.js then takes the full
     segment as the part. Serve the range (`206`) whether the segment is finalized or in
     progress.
   - Accept the open-ended `bytes=N-` a preload hint produces (today it fails to parse → `404`)
     and hold it until that part is muxed (`awaitRange`, same waiter list as blocking reloads);
     hold a not-yet-muxed closed range instead of `416`. Keep the newest finalized segment in
     memory so it's servable before its off-lock disk write lands.
   - **Won't lower latency for us today:** vendored hls.js 1.7.2 parses `EXT-X-PRELOAD-HINT` but
     never fetches it. The hold is spec-correct groundwork (Safari/AVPlayer, later hls.js).
   - Size the hold timeout at 3× target duration (spec bound), not 4× part - the current
     4 × 333ms is shorter than a whole-segment blocking reload can legitimately wait.
3. **200ms parts** (`DEFAULT_PART_TARGET_DURATION_US`, `PART-HOLD-BACK` 1s → 0.6s), cutting
   *before* the access unit that reaches the target (5ms tolerance) so parts are exactly 6
   frames at 30fps. Today's cut-after overshoots by a frame (a "333ms" part is ~367ms) and
   advertises the part as one frame shorter than it is.
4. **Keep finalized segments' parts listed** for 3 target durations ahead of their `#EXTINF`
   (the LL-HLS spec's window; today they vanish the moment the segment finalizes, which may make
   hls.js refetch whole segments), flushing the trailing part at rotation so parts cover every
   byte. This came with two dependent changes: `#EXTINF` measured to the next segment's first
   frame (today it's first-to-last-frame, ~1 frame / ~3% short per segment), and
   `TARGETDURATION` rounded to nearest instead of up (spec rule; needed because segments then
   always measure just over 1s). **Prime suspect for the ~1s glitch** - it touches exactly the
   segment boundary, and changes the timeline hls.js sees.

## Not in scope

Changing `record`/`live` exclusivity; WebRTC or any non-HLS transport.
