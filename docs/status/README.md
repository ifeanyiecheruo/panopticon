# Status and remaining work

The current build/verify status, and a plan per remaining work item. The design context those
plans build on is in [`../design/`](../design/).

## Status

Both applications are working, **cross-verified** vertical slices — the controller has paired
with the real Pixel 6, synced real clips, driven its camera, and self-unpaired; this is real
interop, not two halves built against a paper spec. Feature slices layered on since the initial
pair→record→sync→view slice: camera selection + manual controls, calibration (with a real
empirical zoom probe), motion-gated recording with GPU fan-out + pre-roll, the segment/clip
model, plain-HLS live view, the controller UX overhaul + selectable resolution + live-start
arming, unpair / force-unpair.

### Built & verified

| Area | State |
|---|---|
| Pair → record → sync → view | done, cross-verified against the Pixel 6 |
| Motion-gated recording (GPU fan-out, pre-roll, gapless rotation) | done (phone); verified on Pixel 6 + BLU G5; **motion thresholds un-tuned** |
| Segment/clip model + tombstones | done, except the eviction-probe loop |
| Calibration (empirical zoom probe, model-keyed controller store) | done, verified on Pixel 6 (lit scene) + BLU G5 |
| Camera selection + manual controls (both sides) | done, verified on Pixel 6; no phone-side Compose UI |
| Live view (plain HLS, both sides) | done, verified end to end; LL-HLS deferred |
| Controller master-detail Fleet / Gallery / Trash / Add-phone | done |
| Unpair / force-unpair (controller) | done, integration-tested |

### Device coverage

- **Google Pixel 6** (`oriole`, Android 16 / API 36) — primary test device.
- **BLU G5** (Android 9 / API 28) — low-end / old-API check.

See [`../quirks/README.md`](../quirks/README.md) for the device context and what reproduced on
which.

## Remaining work

| Plan | Side | Size |
|---|---|---|
| [`eviction-probe-loop.md`](eviction-probe-loop.md) | controller | medium |
| [`ll-hls-upgrade.md`](ll-hls-upgrade.md) | both | large |
| [`motion-detection-tuning.md`](motion-detection-tuning.md) | phone | medium |
| [`fleet-bulk-actions.md`](fleet-bulk-actions.md) | controller | small–medium |
| [`qr-pairing.md`](qr-pairing.md) | both | medium |
| [`phone-camera-control-ui.md`](phone-camera-control-ui.md) | phone | large |
| [`phone-remaining-screens.md`](phone-remaining-screens.md) | phone | medium |
| [`sync-cadence-and-backoff.md`](sync-cadence-and-backoff.md) | controller | small |
| [`controller-app-data-dir.md`](controller-app-data-dir.md) | controller | small |
| [`visual-language-pass.md`](visual-language-pass.md) | both | medium |

No hard ordering. The cross-cutting ones (`ll-hls-upgrade`, `qr-pairing`) need coordinated
phone + controller work; the rest are single-side.

## Plan format

Each plan is: **Goal · Context · Approach · Affected files · Testing · Acceptance · Not in
scope**. Keep them to a page. When a plan is delivered, fold its substance into the relevant
component spec / ADR and cut the plan down to a one-line "done in `<commit>`" entry (or delete
it and note it under Status above).
