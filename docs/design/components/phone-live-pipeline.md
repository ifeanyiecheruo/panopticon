# phone-app — live pipeline — software design description

*Prerequisite: this description assumes the Android `Camera2` and `MediaCodec` APIs and the
MPEG-TS / HLS delivery model (PAT / PMT / PCR / PES, 188-byte packets, sliding-window playlist,
live edge, LL-HLS). If any of those are unfamiliar, read
[`../android-media-primer.md`](../android-media-primer.md) first — §4 and §6 cover exactly this
path.*

## 1. Introduction

### 1.1 Purpose

Describe the component that serves a live view of the camera as LL-HLS, single-stream, with a
cold-camera arming contract.

### 1.2 Scope

Covers `camera/LivePipeline.kt`, `camera/LiveHlsRelay.kt`, `camera/ts/TsMuxer.kt` (+
`TsMuxerTest`), `http/routes/LiveRoutes.kt`, and `src/debug/DebugLiveReceiver.kt`. The
hand-rolled MPEG-TS muxing rules are also covered by
[`../../quirks/mpeg-ts.md`](../../quirks/mpeg-ts.md).

### 1.3 Context

Runs while the phone is in `live` mode. Mutually exclusive with the recording pipeline;
constructed/torn down by `PanopticonService`. The controller never plays it directly — it goes
through the controller's server-side proxy (see
[`controller-live-and-camera.md`](controller-live-and-camera.md)).

### 1.4 Definitions

| Term | Meaning |
|---|---|
| armed-idle | camera open and session configured, but no repeating request — zero encoding cost |
| broadcasting | actively encoding and producing HLS segments (after `POST /api/live/start`) |
| sliding window | the fixed-count set of most-recent `.ts` segments the playlist advertises |
| arming contract | the `503` + `Retry-After` response `POST /api/live/start` gives while the camera is still coming up |
| `STILL_ARMING` | the internal `-1` sentinel `startBroadcasting()` returns vs `0` for a genuine failure |

System-wide terms: architecture §1.3.

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[HLS](../architecture.md#acr-hls),
[LL-HLS](../architecture.md#acr-ll-hls),
[TS / MPEG-TS](../architecture.md#acr-ts),
[PAT / PMT / PCR / PES](../architecture.md#acr-pat),
[DVR](../architecture.md#acr-dvr),
[AVC](../architecture.md#acr-avc) (H.264),
[AU](../architecture.md#acr-au),
[GL](../architecture.md#acr-gl),
[EGL](../architecture.md#acr-egl),
[HAL](../architecture.md#acr-hal),
[DTO](../architecture.md#acr-dto).

### 1.6 References

- [`../decisions/0007-live-preview-plain-hls.md`](../decisions/0007-live-preview-plain-hls.md),
  [`0010`](../decisions/0010-mode-state-machine.md).
- [`../../quirks/live-hls.md`](../../quirks/live-hls.md),
  [`../../quirks/mpeg-ts.md`](../../quirks/mpeg-ts.md).
- [`../http-api.md`](../http-api.md) — the `/api/live/*` + `/live/*` routes.
- LL-HLS upgrade plan: [`../../status/ll-hls-upgrade.md`](../../status/ll-hls-upgrade.md).

## 2. Design overview

One camera stream renders straight into a `MediaCodec` input surface (no GL, no motion analysis).
Encoded access units are drained to `LiveHlsRelay`, which muxes them to MPEG-TS with a hand-rolled
`TsMuxer` and maintains a rolling LL-HLS playlist (whole segments plus in-progress `EXT-X-PART`
byte ranges) plus segment files in cache. `LiveRoutes` serves the playlist/segments — including
blocking playlist reloads and in-progress byte-range part fetches — and enforces the armed-idle /
broadcasting lifecycle.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `LivePipeline` | pipeline (live mode) | One camera stream into a `MediaCodec` encoder input surface. Keyframes pinned to ~1s by `KEY_I_FRAME_INTERVAL=1` **plus** an explicit `REQUEST_SYNC_FRAME` timer; capture rate pinned via `CONTROL_AE_TARGET_FPS_RANGE`. Arms idle on `live` entry; `startBroadcasting()` waits ~10s for the camera, returning `STILL_ARMING` (-1) vs `0`. |
| `LiveHlsRelay` | relay | Drains encoded AUs → hand-rolled TS → rolling LL-HLS playlist (in-memory) + `live-<n>.ts` files in cache (not the ring buffer, not counted against the storage cap). ~1s segments, 16-deep window (~16s DVR), ~333ms `EXT-X-PART` byte-range parts, `EXT-X-SERVER-CONTROL:PART-HOLD-BACK` ≈ 1s. `awaitAtLeast(msn, part)` backs blocking playlist reloads; `readInProgressRange` serves a part/preload-hint fetch against the segment still being muxed. Non-blocking `feed()` via a bounded drop-on-full queue. |
| `TsMuxer` | muxer | PAT / PMT / PCR on the video PID / PES-wrapped Annex-B AUs. Ported verbatim from the prototype. `TsMuxerTest` guards the 188-byte-packet invariant (parts are plain byte ranges into this same output, so no muxer changes were needed for LL-HLS). |
| `LiveRoutes` | route handler | `POST /api/live/start` (+ the `503` arming contract), `DELETE /api/live/stop`, `GET /live/live.m3u8` (blocking reload via `_HLS_msn`/`_HLS_part`), `GET /live/live-<n>.ts` (finalized file, or a `Range` fetch against the in-progress segment → `206`/`416`); 15s no-`GET` inactivity watchdog → armed-idle. |

### 3.2 Dependencies

- Camera2, `MediaCodec`.
- `DeviceConfig` — `videoResolution`, camera controls.
- `PanopticonService` — constructs it on the `record`→`standby`→`live` transition.

### 3.3 Interfaces

- **Provided:** `/api/live/*` and `/live/*` per [`../http-api.md`](../http-api.md).
- **Required:** the camera / media stack; `DeviceConfig`.

### 3.4 Data

- In-memory HLS playlist text; `live-<n>.ts` segment files in the app cache dir.
- No persisted state; nothing survives leaving `live`.

### 3.5 Processing and behaviour

- `live` and `record` are mutually exclusive; `live` from `record` is a `409` (go via
  `standby`).
- Cold-camera contract: `503 {error:"camera still starting", retryAfterMs:2000}` + `Retry-After`
  while arming; bare `503 {error:"live camera unavailable"}` is a hard failure. The controller
  retries the hinted form for ~20s.
- **Verified (plain HLS):** `DebugLiveReceiver` confirmed valid `mpegts`/`h264` 1280×720 on both
  devices; Pixel 6 played 2.5+ min continuously via the real controller (~50 segments, zero
  404s); BLU produced dead-regular ~0.96s segments.
- **LL-HLS:** implemented, build- and unit-test-verified (JVM unit suite, `TsMuxerTest`
  unaffected); the same kind of multi-minute on-device soak against a real phone emitting parts
  is still outstanding — see [`../../quirks/live-hls.md`](../../quirks/live-hls.md).

## 4. Design rationale and decisions

- **Plain HLS first, LL-HLS once that latency was judged worth cutting** —
  [`0007`](../decisions/0007-live-preview-plain-hls.md): shipped the smallest thing that held up
  first (a 16-deep window + a client stall watchdog), then ported the prototype's LL-HLS
  machinery and hls.js workarounds ([`../../status/ll-hls-upgrade.md`](../../status/ll-hls-upgrade.md),
  [`../../quirks/live-hls.md`](../../quirks/live-hls.md)) once that plan was executed — keeping
  this project's own ~1s/16-deep segment tuning rather than reverting to the prototype's
  2s/6-deep one.
- **Hand-rolled `TsMuxer`** — `MediaMuxer` cannot emit MPEG-TS; ported verbatim with the
  188-byte-packet regression guard ([`../../quirks/mpeg-ts.md`](../../quirks/mpeg-ts.md)). Needed
  no changes for LL-HLS parts, which are just byte ranges into the same output.
- **Single stream, no GL for live** — live does no motion analysis, so the BLU G5 two-stream
  rejection is a non-issue here by construction.
- **Armed-idle + arming contract** — a cold front camera takes several seconds; the `503`
  hint + controller retry lets it connect instead of erroring.
- **Deferred:** adaptive bitrate, the scoped `/live/*` token —
  [`../../status/ll-hls-upgrade.md`](../../status/ll-hls-upgrade.md).
