# Panopticon — architecture description

*Structured after ISO/IEC/IEEE 42010. This is the system-level view; per-subsystem detail is in
[`components/`](components/), and the reasoning behind the choices recorded here
is in [`decisions/`](decisions/).*

---

## 1. Introduction

### 1.1 Purpose and scope

Panopticon turns spare Android phones into standalone, cloud-free security cameras. A phone
records motion-triggered video to a local ring buffer; a desktop **controller** pairs with one
or more phones over the LAN, continuously syncs their footage into a local aggregate gallery,
and can view live, configure, calibrate, and pull recordings.

This document describes the architecture of the system as built: two applications
(`phone-app/`, `controller/`) plus shared build/codegen tooling (`tools/`), in one monorepo.

### 1.2 Status

Both applications exist as working, cross-verified vertical slices — pair → record → sync a
clip → view it — plus feature slices layered on since (camera control, calibration, motion-gated
recording, plain-HLS live view, the controller UX overhaul). See
[`../status/README.md`](../status/README.md) for the current build/verify status
and the remaining-work plans.

### 1.3 Definitions

| Term | Meaning |
|---|---|
| **segment** | one recorded `.mp4` file in the phone's ring buffer; the phone's only unit of footage |
| **clip** | a contiguous run of segments recorded back-to-back during one motion event; the user-facing gallery item, assembled **by the controller** on sync (the phone has no notion of it) |
| **controller** | the desktop app; pairs with phones, syncs and presents their footage |
| **mode** | the phone's top-level camera state: `record`, `standby`, or `live` |
| **calibration** | an empirical device-wide sweep of what the Camera2 API *actually* honours, versus what it declares |
| **prototype** | `../panopticon-prototype/` — an earlier, abandoned attempt on a Node/TS + Kotlin stack; mined for lessons, no code reused |

These terms are used unqualified throughout the component design descriptions in
[`components/`](components/); each of those adds only its own local terms.

### 1.4 Acronyms and abbreviations

Every component design description in [`components/`](components/) links the acronyms in its
§1.5 back to a row here. For a narrative walk-through of the Android camera / media / GL / HLS
machinery these expand to — aimed at a reader who is *not* an Android camera-stack specialist —
see [`android-media-primer.md`](android-media-primer.md).

- <a id="acr-adr"></a>**ADR** — architecture decision record ([`decisions/`](decisions/))
- <a id="acr-sdd"></a>**SDD** — software design description (ISO/IEC/IEEE 1016)
- <a id="acr-api"></a>**API** — application programming interface; also **API level**, the Android SDK version a device runs (e.g. API 28, API 36)
- <a id="acr-hal"></a>**HAL** — hardware abstraction layer (Android camera / media)
- <a id="acr-art"></a>**ART** — Android Runtime; verifies every referenced field/method when a method is first run, guard or not
- <a id="acr-sdk"></a>**SDK** — software development kit (here, the Android SDK)
- <a id="acr-http"></a>**HTTP / HTTPS** — Hypertext Transfer Protocol (Secure)
- <a id="acr-json"></a>**JSON** — JavaScript Object Notation
- <a id="acr-cors"></a>**CORS** — cross-origin resource sharing
- <a id="acr-url"></a>**URL** — uniform resource locator
- <a id="acr-lan"></a>**LAN** — local area network
- <a id="acr-dto"></a>**DTO** — data transfer object (a plain wire/serialisation type)
- <a id="acr-ui"></a>**UI / UX** — user interface / user experience
- <a id="acr-spa"></a>**SPA** — single-page application (the controller frontend)
- <a id="acr-jsx"></a>**JSX** — the XML-in-JavaScript syntax Preact components use
- <a id="acr-css"></a>**CSS** — Cascading Style Sheets
- <a id="acr-svg"></a>**SVG** — Scalable Vector Graphics
- <a id="acr-db"></a>**DB** — database (the controller's embedded SQLite)
- <a id="acr-sql"></a>**SQL** — Structured Query Language
- <a id="acr-cgo"></a>**CGO** — the Go↔C foreign-function bridge (deliberately avoided)
- <a id="acr-os"></a>**OS** — operating system
- <a id="acr-pid"></a>**PID** — process identifier
- <a id="acr-cwd"></a>**cwd** — current working directory
- <a id="acr-ed25519"></a>**Ed25519** — the elliptic-curve signature scheme used for controller identity
- <a id="acr-hls"></a>**HLS** — HTTP Live Streaming
- <a id="acr-ll-hls"></a>**LL-HLS** — Low-Latency HLS
- <a id="acr-ts"></a>**TS / MPEG-TS** — MPEG transport stream (`.ts` segments)
- <a id="acr-pat"></a>**PAT / PMT / PCR / PES** — MPEG-TS program association table / program map table / program clock reference / packetised elementary stream
- <a id="acr-dvr"></a>**DVR** — the seek-back buffer a live player retains
- <a id="acr-avc"></a>**AVC** — Advanced Video Coding (H.264)
- <a id="acr-au"></a>**AU** — access unit (one coded frame's worth of bitstream)
- <a id="acr-pts"></a>**PTS** — presentation timestamp
- <a id="acr-gl"></a>**GL / GPU** — OpenGL ES / graphics processing unit
- <a id="acr-egl"></a>**EGL** — the native platform layer between OpenGL ES and the windowing system
- <a id="acr-fbo"></a>**FBO** — framebuffer object (an off-screen GL render target)
- <a id="acr-oes"></a>**OES** — OpenGL ES extension namespace; here the external-texture sampler a `SurfaceTexture` feeds
- <a id="acr-ae"></a>**AE / AF / AWB** — auto-exposure / auto-focus / auto-white-balance (Camera2)
- <a id="acr-ois"></a>**OIS** — optical image stabilization
- <a id="acr-iso"></a>**ISO** — sensor sensitivity (film-speed equivalent)
- <a id="acr-ev"></a>**EV** — exposure value (exposure-compensation steps)
- <a id="acr-rggb"></a>**RGGB** — the red/green/green/blue Bayer channel order for manual white-balance gains
- <a id="acr-anr"></a>**ANR** — "Application Not Responding" (an Android main-thread stall)
- <a id="acr-ram"></a>**RAM** — random-access (working) memory
- <a id="acr-sigsegv"></a>**SIGSEGV** — the segmentation-fault signal (a native memory fault)
- <a id="acr-qr"></a>**QR** — Quick Response (2-D barcode)

### 1.5 References

- [`decisions/`](decisions/) — the architecture decision records.
- [`android-media-primer.md`](android-media-primer.md) — background on the Android camera /
  media / GL / HLS APIs the `phone-*` and live components assume, for a non-specialist.
- [`http-api.md`](http-api.md) — the phone HTTP contract.
- [`components/`](components/) — per-subsystem design descriptions.
- [`../status/README.md`](../status/README.md) — build/verify status and remaining-work plans.
- [`../quirks/`](../quirks/) — reproduced device / library / OS / toolchain misbehaviour.
- [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) — build, run, test.

---

## 2. Stakeholders and concerns

| Stakeholder | Concerns |
|---|---|
| **Owner / operator** (runs the fleet) | footage is captured and retained locally; no cloud, no account; a phone's identity to the controller is a public key, never an IP or location; the gallery is "always almost up to date" without having to open a window |
| **Developer / maintainer** | two disparate stacks (Android/Kotlin, Go/Wails) buildable from one entry point; device-specific Camera2/HAL behaviour is documented, not rediscovered; decisions are recorded so they aren't re-litigated |
| **The phone as a constrained host** | old/low-end hardware: one hardware encoder, weak HALs that reject multi-stream configs, limited RAM/thermal headroom; the app must not assume flagship behaviour |
| **The controller as an always-on background process** | must run without a window; must not lose footage that the phone's ring buffer will eventually evict; must survive crashes and restarts without corrupting local state |

Concern → where addressed:

- *No cloud / local-only* → §4 deployment; every component is on-LAN or on-disk.
- *Identity by public key* → [`decisions/0003-pairing-and-unpairing.md`](decisions/0003-pairing-and-unpairing.md).
- *Gallery always current* → the controller's background syncer (§5.2), independent of the UI.
- *Don't lose evictable footage* → the segment/clip/tombstone model
  ([`decisions/0006-segments-clips-and-tombstones.md`](decisions/0006-segments-clips-and-tombstones.md)).
- *Constrained encoder/HAL* → single-stream GPU fan-out recording pipeline
  ([`decisions/0004-recording-pipeline.md`](decisions/0004-recording-pipeline.md)),
  and [`../quirks/`](../quirks/).
- *One build entry point* → the root `Makefile`; see [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md).

---

## 3. Context

```mermaid
flowchart LR
    subgraph LAN
        P1["phone-app\n(Android, Kotlin/Compose)\nCamera2 · ring buffer · Ktor HTTP"]
        P2["phone-app\n(another phone)"]
        C["controller\n(Go/Wails + Preact)\ntray app · embedded SQLite"]
    end
    Owner(["Owner / operator"])
    Disk[("controller archive/\n+ data/ (SQLite)")]

    Owner -->|"launches, configures,\nviews gallery"| C
    Owner -->|"physically holds phone,\ngenerates invite"| P1
    C <-->|"HTTPS + Bearer token\n(pair · status · config · mode ·\nsegments · live · calibration · camera)"| P1
    C <-->|"same contract"| P2
    C -->|"downloaded segments +\nthumbnails, DB rows"| Disk
```

The phone is the server; the controller is the client. Pairing is bootstrapped out of band —
the owner holds the phone, reads an invite (code + address, or a QR encoding both), and gives it
to a controller they also hold. There is no discovery protocol and no third party.

---

## 4. Deployment view

- **phone-app** — a foreground `START_STICKY` Android service (`PanopticonService`) owns the
  camera and an embedded Ktor/Netty HTTP server on port 8080. State (config, pairings, segment
  index) is on-device: `SharedPreferences` + a `clips/` directory in app storage. No outbound
  network calls; it only answers the controller.
- **controller** — a single Go binary, no bundled runtime. A system-tray presence (model:
  Syncthing/Tailscale) keeps a background sync loop running whether or not a window is open. The
  window is an OS-native embedded webview (Wails: WebView2 / WebKitGTK / WKWebView) rendering a
  Preact frontend. Local state is one embedded SQLite DB (`data/panopticon.db`, pure-Go
  `modernc.org/sqlite` driver — no CGO); synced footage lands in `archive/<phoneId>/`.
- **tools/** — build-time only: `mock-phone` (a fake phone for controller dev/tests), `dbstore`
  and `go-deps` (sqlc/goose codegen plumbing). Each is its own Go module; `go.work` ties them
  together. Not shipped.

Rationale: [`decisions/0011-controller-runtime-and-state.md`](decisions/0011-controller-runtime-and-state.md),
[`decisions/0001-repository-and-build.md`](decisions/0001-repository-and-build.md).

---

## 5. Logical / component view

### 5.1 phone-app

*The `Camera2 → SurfaceTexture → GL fan-out → MediaCodec → MediaMuxer` and
`Camera2 → MediaCodec → TsMuxer` chains below are walked through, API by API, in
[`android-media-primer.md`](android-media-primer.md) §9.*

```mermaid
flowchart TB
    Svc["PanopticonService\n(foreground service, mode owner)"]
    HTTP["PanopticonHttpServer\n(Ktor: auth gate + route groups)"]
    Rec["CameraGlPipeline (record)\nCamera2 → SurfaceTexture → GL fan-out\n→ continuous MediaCodec\n→ motion-gated MediaMuxer + pre-roll ring"]
    Motion["MotionDetector\n(frame-difference on GL readback)"]
    Live["LivePipeline (live)\nCamera2 → MediaCodec → LiveHlsRelay\n→ hand-rolled TsMuxer"]
    Cal["CalibrationRunner\n(empirical per-camera zoom sweep)"]
    CamCtl["Camera control\nCatalog · CapabilitiesReader ·\nControlApply · ControlValidation"]
    Store["SegmentStore\n(in-memory index + coalesced flush)"]
    Pair["InviteManager · ControllerRegistry\n(local library, not routes)"]
    UI["Compose UI\nHome · Connect · Gallery · Calibrate"]

    Svc --> Rec & Live & Cal
    Rec --> Motion & Store
    HTTP --> Svc & Store & CamCtl & Cal & Pair & Live
    UI --> Svc & Store & Pair
```

`record`, `standby`, and `live` are mutually exclusive; `record` is sticky and must be left for
`standby` explicitly before calibration or live can take the camera
([`decisions/0010-mode-state-machine.md`](decisions/0010-mode-state-machine.md)). Component
detail: [`components/`](components/) `phone-*` files.

### 5.2 controller

```mermaid
flowchart TB
    App["App (Wails bindings)\nmain.go · app.go"]
    Tray["trayapp · singleinstance · appdirs"]
    Sync["syncer\nper-phone poll loop · segment→clip grouping"]
    Store["dbstore / Store\nidentity · phones · segments · clips · calibration\n(sqlc + goose, pure-Go SQLite)"]
    PhoneAPI["phoneapi\nHTTP client + typed sentinels"]
    Pairing["pairing · unpair · identity"]
    Cal["calibration\nmodel-keyed store · Lookup · EffectiveRect"]
    Live["liveproxy\n/live/<phoneID>/* → phone, token server-side"]
    FE["frontend (Preact/Vite)\nFleet · PhoneDetail · Gallery · Trash · AddPhone\nLivePreview (hls.js) · CameraControls"]

    App --> Sync & Pairing & Cal & PhoneAPI
    Sync --> Store & PhoneAPI
    Pairing --> Store & PhoneAPI
    Cal --> Store & PhoneAPI
    FE --> App
    FE -->|"same-origin HLS"| Live
    Live --> PhoneAPI
```

The UI is a **read view over the SQLite DB**, not a live re-derivation from phone calls
([`decisions/0011-controller-runtime-and-state.md`](decisions/0011-controller-runtime-and-state.md)).
Component detail: [`components/`](components/) `controller-*` files.

### 5.3 Key interaction: sync + grouping

```mermaid
sequenceDiagram
    participant S as syncer (per phone)
    participant P as phone /api/segments
    participant DB as Store (SQLite)

    loop every syncPollInterval (30s)
        S->>P: GET /api/segments?since=<cursor>
        P-->>S: [{filename, createdAtMs, endMs, ...}, ...]
        loop each new segment
            S->>P: GET /api/segments/:f/file  (+ /thumbnail)
            alt 404
                P-->>S: 404 → treat as already-evicted, skip
            else 200
                P-->>S: bytes
                S->>DB: write file to archive/<phoneId>/, insert segment row
                S->>DB: extend open clip if gap ≤ GroupingGapMs (500ms), else open new clip
                S->>DB: advance sync cursor (only after durable + assigned)
            end
        end
    end
```

Cursor advancement is idempotent and crash-safe: a segment is only "done" once written,
assigned to a clip, and indexed. Detail:
[`components/controller-sync.md`](components/controller-sync.md).

---

## 6. Data view

### 6.1 phone-app

Persisted on device, no schema/migration framework:

- **`DeviceConfig`** — `deviceName`, `motionSensitivity`, `storageCapBytes`, `ringBufferMaxAgeMs`,
  `activeCameraId`, `cameraControls` (`CameraControlKeys` + `rotationDegrees` + `videoResolution`).
- **Segment index** — one JSON blob in `SharedPreferences`, held in memory by `SegmentStore` as
  the authoritative copy; `reconcile()` on launch drops entries whose file is gone and re-probes
  untracked files. (Dir/keys still literally say `clip` for historical reasons — renaming would
  orphan every recorded file for no behavioural gain.)
- **Pairings** — `ControllerRegistry`: per-controller `{controllerId, publicKey, name, kind,
  token}`. Pending invites in `InviteManager`.
- **Calibration result** — last completed run, written to disk, served from
  `GET /api/calibration/result` without a `runId`.

### 6.2 controller

One SQLite DB, goose migrations applied on every `Open()`, sqlc-generated query code:

| Table | Holds |
|---|---|
| `identity` | the controller's own Ed25519 keypair |
| `phones` | paired phones: id, name, base URL, bearer token, last-seen, sync cursor |
| `segments` | one row per synced file, each carrying a `clip_id` |
| `clips` | the user-facing gallery item: a contiguous run of segments, with the `active`/`trashed`/`purged` lifecycle and aggregate span/size/count |
| `calibration` | `manufacturer+model` → last result JSON + source phone + timestamp (a **shared** resource, not per-phone) |

The `clips` lifecycle and why a purged row keeps its tombstone:
[`decisions/0006-segments-clips-and-tombstones.md`](decisions/0006-segments-clips-and-tombstones.md).
Calibration keyed by model:
[`decisions/0009-calibration-model.md`](decisions/0009-calibration-model.md).

---

## 7. Cross-cutting

- **Auth** — pairing exchanges a controller public key for a per-controller bearer token (not a
  shared secret), so any one controller can be revoked without touching the others. Every route
  but `POST /api/pair` requires `Authorization: Bearer <token>`. No permission tiers.
  ([`decisions/0002-http-api-surface-and-auth.md`](decisions/0002-http-api-surface-and-auth.md).)
- **The HTTP contract** — [`http-api.md`](http-api.md) is authoritative for wire
  shapes and status codes; both sides implement against it and `tools/mock-phone` fakes it.
- **Device quirks** — [`../quirks/`](../quirks/) records reproduced Camera2 / MediaCodec / HAL /
  hls.js / Windows-toolchain misbehaviour and the workarounds. Read it before "cleaning up"
  odd-looking code.
- **Visual language** — the dark/teal theme shared by both UIs is an inherited **placeholder**,
  not a settled decision; a visual-language pass is deferred
  ([`../status/visual-language-pass.md`](../status/visual-language-pass.md)).

---

## 8. Architecture decisions

The thematic decision records in [`decisions/`](decisions/) are the rationale of record:

| ADR | Covers |
|---|---|
| [0001](decisions/0001-repository-and-build.md) | Monorepo + linear history, `tools/` as separate modules + `go.work`, sqlc/goose, generated-code layout, private tool isolation, `Makefile` entry point |
| [0002](decisions/0002-http-api-surface-and-auth.md) | Per-controller bearer token, no permission tiers, no pairing confirmation, which actions are routes vs local library functions |
| [0003](decisions/0003-pairing-and-unpairing.md) | Phone-only invite generation, address+code (QR carries both), Unpair vs Force unpair, public-key-only identity |
| [0004](decisions/0004-recording-pipeline.md) | Single camera stream + GPU fan-out, continuous encoder + motion-gated muxer, pre-roll ring, gapless rotation, no audio |
| [0005](decisions/0005-motion-detection.md) | Frame-difference only, un-tuned thresholds, motion kept out of the live API |
| [0006](decisions/0006-segments-clips-and-tombstones.md) | Phone speaks segments; controller assembles clips; three-state lifecycle; purge-keeps-tombstone; no favorites |
| [0007](decisions/0007-live-preview-plain-hls.md) | Plain HLS not LL-HLS, hand-rolled TS muxer, vendored hls.js, RECORD/LIVE exclusive, arming-retry contract |
| [0008](decisions/0008-camera-control-and-multi-camera.md) | Multi-camera first-class, logical+physical ids, `CameraControlKeys`, validate-then-apply, control state spans pipelines |
| [0009](decisions/0009-calibration-model.md) | Device-wide empirical sweep, persisted on phone, controller-side store keyed by `manufacturer+model` |
| [0010](decisions/0010-mode-state-machine.md) | `record` (sticky) / `standby` / `live`; transitions and their error codes |
| [0011](decisions/0011-controller-runtime-and-state.md) | Tray app not window-first, single binary + embedded webview, SQLite as source of truth |
| [0012](decisions/0012-ux-shape.md) | Fresh Compose start, phone Preview-screen interaction patterns, controller master-detail screens, placeholder theme |
