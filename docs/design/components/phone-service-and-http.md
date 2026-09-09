# phone-app — service & HTTP server — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that owns the phone's process lifecycle and camera, exposes the HTTP
contract to paired controllers, and holds the pairing state that gates it.

### 1.2 Scope

Covers `service/PanopticonService.kt`, `http/PanopticonHttpServer.kt`, `http/AuthPlugin.kt`,
`http/routes/*`, `pairing/InviteManager.kt`, `pairing/ControllerRegistry.kt`. The route
*payloads* are specified in [`../http-api.md`](../http-api.md); the pipelines the service drives
have their own descriptions (see §1.6).

### 1.3 Context

`PanopticonService` is the phone app's single long-lived component. Everything else in
`phone-app` is either something it constructs (a pipeline, the HTTP server) or something the
Compose UI drives directly. The controller reaches the phone only through this component's HTTP
server.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| mode | the phone's top-level camera state — `record`, `standby`, `live` (see [`../decisions/0010-mode-state-machine.md`](../decisions/0010-mode-state-machine.md)) |
| route group | one `http/routes/*` file registering a related set of endpoints |
| pairing | redeeming an invite to issue a per-controller bearer token |
| controller registry | the on-device record of paired controllers and their tokens |

System-wide terms (segment, clip, controller, calibration, prototype) are defined in
[`../architecture.md`](../architecture.md) §1.3.

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[HTTP](../architecture.md#acr-http),
[API](../architecture.md#acr-api) (both the network sense and "Android API level"),
[CORS](../architecture.md#acr-cors),
[JSON](../architecture.md#acr-json),
[LAN](../architecture.md#acr-lan),
[UI](../architecture.md#acr-ui),
[DTO](../architecture.md#acr-dto).

### 1.6 References

- [`../http-api.md`](../http-api.md) — the wire contract this component serves.
- [`../decisions/0002-http-api-surface-and-auth.md`](../decisions/0002-http-api-surface-and-auth.md),
  [`0010`](../decisions/0010-mode-state-machine.md).
- [`../../quirks/android-service.md`](../../quirks/android-service.md) — foreground-service and
  bind-retry findings.
- Peer components: [`phone-recording-pipeline.md`](phone-recording-pipeline.md),
  [`phone-live-pipeline.md`](phone-live-pipeline.md),
  [`phone-calibration.md`](phone-calibration.md),
  [`phone-camera-control.md`](phone-camera-control.md).

## 2. Design overview

A foreground Android service that is the mode owner and the HTTP host. It constructs exactly one
camera-using pipeline at a time, wires an embedded Ktor server with a bearer-token gate in front
of every route but `POST /api/pair`, and keeps the pairing/invite state the gate consults.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `PanopticonService` | foreground `START_STICKY` service | Holds the current mode; constructs/tears down `CameraGlPipeline` (record) or `LivePipeline` (live); rebuilds the running pipeline on a camera switch or `videoResolution` change; starts the HTTP server. |
| `PanopticonHttpServer` | embedded Ktor/Netty server (port 8080) | Wires the route groups; retries `embeddedServer(...).start()` on `BindException` (~20 attempts / ~30s). |
| `AuthPlugin` | Ktor plugin | Bearer-token gate on every route except `POST /api/pair`; `401` on missing/unknown/revoked. |
| `http/routes/*` | route handlers | `DeviceRoutes`, `ModeRoutes`, `SegmentRoutes`, `CameraRoutes`, `CalibrationRoutes`, `LiveRoutes`, pairing. |
| `InviteManager` | local library | `createInvite()` / `listPendingInvites()` / `revokeInvite(id)` — **not** HTTP routes. |
| `ControllerRegistry` | local library | Per-controller `{controllerId, publicKey, name, kind, token}`; `list()` / `revoke(id)` local-only; token lookup for `AuthPlugin`. `POST /api/pair` adds; `DELETE /api/pair` revokes the calling token. |

### 3.2 Dependencies

- `SegmentStore` — segment listing / serving / deletion for `/api/segments…`.
- `CameraGlPipeline` / `LivePipeline` — the mode implementations the service builds.
- `CalibrationRunner` — for `/api/calibration/*`.
- The camera-control components — for `/api/cameras*` and `/api/camera/*`.
- `DeviceConfig` persistence (`SharedPreferences`).
- Android `Service` / `startForeground` platform API.

### 3.3 Interfaces

- **Provided:** the full HTTP contract of [`../http-api.md`](../http-api.md); a local
  invite/registry API to the Compose UI.
- **Required:** the pipeline constructors and the persistence listed in §3.2.

### 3.4 Data

- Pairings: `ControllerRegistry` entries `{controllerId, publicKey, name, kind, token}`.
- Pending invites: `InviteManager` entries.
- No footage or config data of its own — it delegates to `SegmentStore` and `DeviceConfig`.

### 3.5 Processing and behaviour

- **Mode machine** — `record` (sticky) / `standby` / `live`; transitions and error codes per
  [`0010`](../decisions/0010-mode-state-machine.md). `live` from `record` is a `409`;
  calibration in `record` is a `409`.
- **Foreground service** — API-gated `startForeground` overload: 2-arg below API 29, 3-arg with
  `foregroundServiceType` on API 29+ (calling the 3-arg form unconditionally is a
  `NoSuchMethodError` on API 28 — see [`../../quirks/android-service.md`](../../quirks/android-service.md)).
- **Bind-retry** — retries a `BindException` crash-loop socket-not-released race (carried
  forward from the prototype; not stress-tested here).
- **Auth** — every request enters through `AuthPlugin`; `POST /api/pair` is the sole
  unauthenticated route.

## 4. Design rationale and decisions

- **Which actions are routes vs local library functions** —
  [`0002`](../decisions/0002-http-api-surface-and-auth.md). Invite and
  controller-registry admin are owner-at-the-phone tasks with no remote party, so they are
  library calls; `DELETE /api/pair` (self-unpair) is the single exception.
- **Per-controller bearer token, no permission tiers** —
  [`0002`](../decisions/0002-http-api-surface-and-auth.md).
- **Mode exclusivity and stickiness** —
  [`0010`](../decisions/0010-mode-state-machine.md): the camera is one exclusive
  resource; recording must not be interrupted by a page load.
- **Trade-off accepted:** the bind-retry and the CORS header exposure are defensive
  carry-overs, not re-verified on this hardware — see
  [`../../quirks/android-service.md`](../../quirks/android-service.md).
