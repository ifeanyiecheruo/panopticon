# Component design descriptions

One design description per subsystem, structured after ISO/IEC/IEEE 1016. The system-level view
is [`../architecture.md`](../architecture.md); the wire contract several of these implement is
[`../http-api.md`](../http-api.md); the reasoning behind the choices is in
[`../decisions/`](../decisions/); reproduced device/toolchain misbehaviour and its workarounds
are in [`../../quirks/`](../../quirks/).

## Outline

Every file follows the same shape:

1. **Introduction** — 1.1 Purpose · 1.2 Scope · 1.3 Context · 1.4 Definitions · 1.5 Acronyms and
   abbreviations · 1.6 References
2. **Design overview**
3. **Detailed design** — 3.1 Design entities · 3.2 Dependencies · 3.3 Interfaces · 3.4 Data ·
   3.5 Processing and behaviour
4. **Design rationale and decisions** — the governing ADRs, trade-offs, and links to quirks and
   the [`../../status/`](../../status/) plans

System-wide terms (segment, clip, controller, mode, calibration, prototype) are defined once in
[`../architecture.md`](../architecture.md) §1.3, and acronyms in §1.4; each component's §1.4 /
§1.5 add only what is local to it and link shared acronyms back to that glossary.

The Android `Camera2`, `MediaCodec`, `MediaMuxer` / `MediaExtractor`, `SurfaceTexture` /
OpenGL ES, and MPEG-TS / HLS / hls.js APIs that the `phone-*` files and
[`controller-live-and-camera.md`](controller-live-and-camera.md) are written against are
introduced for a non-specialist in [`../android-media-primer.md`](../android-media-primer.md);
those files carry a prerequisite note pointing at the relevant section.

## Index

### phone-app

| Component | Subsystem |
|---|---|
| [`phone-service-and-http.md`](phone-service-and-http.md) | `PanopticonService` lifecycle, the Ktor server + auth gate, pairing internals |
| [`phone-recording-pipeline.md`](phone-recording-pipeline.md) | `CameraGlPipeline`: GL fan-out, continuous encoder, motion gate, pre-roll, gapless rotation, `SegmentStore` |
| [`phone-live-pipeline.md`](phone-live-pipeline.md) | `LivePipeline` + `LiveHlsRelay` + `TsMuxer`: arming/broadcast, plain-HLS output |
| [`phone-calibration.md`](phone-calibration.md) | `CalibrationRunner` + `ZoomMath` + persistence |
| [`phone-camera-control.md`](phone-camera-control.md) | catalog, capabilities read, control apply/validation, physical-camera targeting |
| [`phone-ui.md`](phone-ui.md) | Compose UI: Home, Connect, Gallery, Calibrate |

### controller

| Component | Subsystem |
|---|---|
| [`controller-runtime.md`](controller-runtime.md) | `App` bindings, tray, single-instance, app dirs, live proxy |
| [`controller-state-store.md`](controller-state-store.md) | `dbstore`/`Store`: schema, migrations, segments/clips/tombstones |
| [`controller-sync.md`](controller-sync.md) | per-phone poll loop, segment→clip grouping, cursor advancement |
| [`controller-pairing.md`](controller-pairing.md) | identity, pairing, unpair/force-unpair, the `phoneapi` client |
| [`controller-calibration-store.md`](controller-calibration-store.md) | model-keyed calibration store, `Lookup`, `EffectiveRect` |
| [`controller-live-and-camera.md`](controller-live-and-camera.md) | live preview (hls.js), camera-control UI, the rect picker |
| [`controller-ui.md`](controller-ui.md) | Preact frontend: router/state, screens, shared components |
