# Design

Design intent and rationale for Panopticon. Current build status and remaining-work plans are
in [`../status/`](../status/); reproduced device/toolchain misbehaviour is in
[`../quirks/`](../quirks/); build/run/test is in [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md).

| | |
|---|---|
| [`architecture.md`](architecture.md) | The system-level view — context, deployment, component and data views, diagrams. Structured after ISO/IEC/IEEE 42010. Start here. Its §1.3 / §1.4 are the shared glossary the rest of this folder defers to. |
| [`android-media-primer.md`](android-media-primer.md) | Background reading for a generalist — the Android `Camera2`, `MediaCodec`, `MediaMuxer` / `MediaExtractor`, `SurfaceTexture` / OpenGL ES, and MPEG-TS / HLS / hls.js APIs the `phone-*` and live components are written against. Not a design doc; those docs link to it as a prerequisite. |
| [`decisions/`](decisions/) | Architecture decision records (Nygard style: **Status · Context · Decision · Consequences**), grouped thematically — the rationale of record for *why* the system is the way it is. |
| [`components/`](components/) | One IEEE 1016 design description per subsystem (6 phone, 7 controller) — what each subsystem is, its entities, interfaces, data, behaviour, and the ADRs that govern it. See [`components/README.md`](components/README.md) for the index and the shared outline. |
| [`http-api.md`](http-api.md) | The phone HTTP API contract — authoritative for wire shapes and status codes. `phone-app` implements it, `controller` calls it, `tools/mock-phone` fakes it. |
| [`storyboards/`](storyboards/) | The original numbered screen sketches. |
| [`ux-mocks/`](ux-mocks/) | The clickable HTML/CSS/JS UX mocks (`phone-ux-mock.html`, `controller-ux-mock.html`) the two UIs were prototyped from. |

## Keeping it current

- A new cross-cutting design choice → a sub-section in the closest [`decisions/`](decisions/)
  file (or a new numbered file), linked from that file's index.
- A subsystem changes shape → update its [`components/`](components/) file.
- The wire contract changes → [`http-api.md`](http-api.md) is authoritative; update it first.
- A new acronym → add an anchored row to [`architecture.md`](architecture.md) §1.4 and link it
  from the relevant component's §1.5.
- A component starts leaning on an Android / media / streaming API the
  [primer](android-media-primer.md) doesn't yet cover → add a short subsection there and link it
  from that component's prerequisite note, same principle as a new acronym.
