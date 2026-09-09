# Panopticon phone HTTP API — design summary

Status as of 2026-09-04: this is the settled design for the HTTP interface the phone app
exposes to paired controllers, worked out in a design session (no Kotlin/Ktor code written
yet). Modeled on `../panopticon-prototype/shared/api-contract.md` (the old prototype's
contract) but reworked for this project's pairing model, multi-camera support, and
device-wide calibration. Use this doc as the starting point for the controller-side design
session and, eventually, as the phone/server implementation contract.

## Auth model

Pairing exchanges a controller's public key for a per-controller bearer token — not a single
shared secret like the old prototype. This lets each controller be individually revoked
without invalidating the others.

```
Authorization: Bearer <per-controller-token>
```

Missing/unknown/revoked token → `401`. No permission tiers: every paired controller has full
access to every route below (no read-only/view-only notion).

## Decisions made this session (worth not re-litigating)

- **Invite generation and controller-registry management of *other* controllers (list/revoke
  by `controllerId`) are NOT HTTP routes.** Invites are created on a phone physically in the
  user's possession and presented to a controller also in the user's possession (not a
  public-internet or Bluetooth-broadcast scenario), so there's no remote party that needs
  those actions over HTTP — they're plain internal library functions called by the phone's
  own local UI:
  ```
  InviteManager.createInvite(): Invite
  InviteManager.listPendingInvites(): List<Invite>
  InviteManager.revokeInvite(inviteId: String)

  ControllerRegistry.list(): List<PairedController>
  ControllerRegistry.revoke(controllerId: String)
  ```
  **Self-unpair is the one exception** — added during the controller-design session as
  `DELETE /api/pair` above. Unlike the admin actions listed here, the remote party *is* the
  one who needs it (a controller unpairing itself), so it has to be a route. It reuses the
  bearer token as the target identity rather than taking a `controllerId`, so it can't be
  repurposed to revoke a different controller.
- **No human-confirmation step on pairing.** Considered and dropped — `POST /api/pair`
  grants a fully active token immediately. The invite-in-both-hands trust model means the
  mitigations that confirmation would add (against a leaked/broadcast invite) aren't needed.
- **The set of paired controllers is not exposed over HTTP at all** — not even read-only to
  other controllers. Purely a local/owner concern, hence `ControllerRegistry.list()` above
  being a library function, not a route.
- **Favoriting is removed entirely** — no favorite field on segments, no favorite route.
- **This API speaks in "segments"** — one segment is one recorded file in the phone's ring
  buffer. The user-facing "clip" (a contiguous run of segments recorded back-to-back during one
  motion event) is assembled *by the controller* from the segments it syncs; the phone has no
  notion of it and there is no clip route here. (An earlier draft of this doc used "clips" for
  the individual files; that's now "segments", and "clip" is the group.)
- **`/api/control/*` renamed to `/api/camera/*`.**
- **Multi-camera is a first-class concept**: `/api/cameras` lists physical cameras (id,
  facing, label, focal length, which is active); `/api/cameras/active` switches, which is a
  disruptive reconfigure like a mode switch. `/api/camera/capabilities` and
  `/api/camera/state` operate on "whichever camera is active" (capabilities takes an optional
  `cameraId` to preview another camera's declared ranges without switching to it; state does
  not, since live capture state only exists for the camera actually running).
- **Calibration is device-wide, not per-camera-on-demand** — one `POST
  /api/calibration/start` sweeps *every* camera the device reports, because calibration's
  whole purpose is empirically catching a device's Camera2 API lying about its own
  capabilities, and that risk exists per-camera, not just on the default one. Status reports
  both which camera and which step; results are keyed by `cameraId`.
- **Motion detection stays out of live preview** — no live "motion currently detected"
  signal/badge in the API; it remains purely the `motionSensitivity` field in `/api/config`,
  since Preview only runs `live` mode where the motion-gated recording pipeline isn't active.
- **Calibration results are persisted on the phone**, not just held for the lifetime of a run —
  `GET /api/calibration/result` without `runId` serves the last completed result straight from
  disk. This is what lets the controller-side device-capability database (see
  [HANDOFF-controller-ux.md](HANDOFF-controller-ux.md)) pull a phone's results opportunistically
  — e.g. right after pairing — without asking that phone to actually run calibration.
- Added along the way: `batteryPercent`/`charging`/`serverTimeMs` on `/api/status` (battery
  display + clock-skew correction for a controller's segment timeline), and a new
  `GET /api/build-info` endpoint so a controller/server can gate on the phone app's version
  before calling a route it might not support.

## Full route table

### Pairing

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| POST | `/api/pair` | `invite=<code>` | `{ "publicKey": "MCowBQYDK2VwAyEA...", "name": "Ifeanyi's Desktop", "kind": "desktop" }` | `{ "controllerId": "ctl_9b02", "token": "8f2a1c...e91b", "phone": { "phoneId": "ph_a81d", "name": "Garage cam" } }` | Redeems an invite: registers the controller's public key and issues a bearer token. `404` unknown code, `410` expired/already used. |
| DELETE | `/api/pair` | — | — | `{ "unpaired": true }` | Self-unpair: revokes the calling controller's own token. The bearer token *is* the identity being revoked — there's no `controllerId` param, so a controller can never unpair anyone but itself. `401` if the token's already invalid/revoked. |

### Device identity & status

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/device` | — | — | `{ "manufacturer": "Google", "model": "Pixel 9a", "device": "tegu" }` | Hardware identity used to key the server's device-capability/quirks database. |
| GET | `/api/build-info` | — | — | `{ "appVersionName": "1.4.2", "appVersionCode": 47, "buildType": "release", "gitSha": "a3f9c21" }` | Phone app's own build identity, for compatibility gating. |
| GET | `/api/status` | — | — | `{ "mode": "live", "status": "idle", "cameraHealthy": true, "liveViewers": 0, "storageUsedBytes": 40200000000, "storageCapBytes": 64000000000, "batteryPercent": 78, "charging": true, "serverTimeMs": 1755270015231 }` | One-shot snapshot: mode, recording/broadcast state, storage, battery, phone clock. |
| GET | `/api/config` | — | — | `{ "deviceName": "Garage cam", "motionSensitivity": "medium", "storageCapBytes": 64000000000, "ringBufferMaxAgeMs": 604800000 }` | Reads persisted device configuration. (`rotationDegrees` moved to `/api/camera/*` — it's a camera-pipeline setting.) |
| POST | `/api/config` | — | `{ "deviceName": "Garage cam", "motionSensitivity": "high" }` | *(full resulting config document)* | Batch-updates any subset of device config. |

### Mode

Modes: `record` (motion-gated recording pipeline owns the camera), `standby` (camera released —
the only state calibration / live preview can take it from), `live` (live-preview pipeline —
implemented as **plain HLS**; see Live view below). **`record` is sticky**: it takes precedence
over every other camera-using feature, and you must move to `standby` *explicitly* before any of
them can run.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/mode` | — | — | `{ "mode": "record" }` | Current top-level mode. |
| POST | `/api/mode` | — | `{ "mode": "standby" }` | `{ "mode": "standby" }` | Switches mode. `standby` and `record` are always allowed. `live` from `record` is a **`409`** (`{"error":"stop recording first: POST /api/mode {\"mode\":\"standby\"}"}`) — go via `standby`. No-op `200` if already there. |

### Cameras (selection)

> **Implemented** — phone-app `http/routes/CameraRoutes.kt` + `camera/CameraCatalog.kt`,
> controller `internal/phoneapi/camera.go` + `App.ListCameras` / `SetActiveCamera` +
> `frontend/src/components/CameraControls.tsx`. `cameraIdList` returns *logical* cameras only, so
> the catalog also emits `"<logical>:<physical>"` ids for each physical sub-camera of a logical
> multi-camera (API 28+). Opening one opens the *logical* device but pins the session's outputs
> to that physical sensor via `OutputConfiguration.setPhysicalCameraId` (`camera/PhysicalCameraApi28.kt`).
> On the Pixel 6 this exposes `0:2` (wide) and `0:3` (ultra-wide); the BLU G5 (single sensor per
> facing) just lists `0`/`1`.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/cameras` | — | — | `{ "cameras": [ { "cameraId": "0:3", "facing": "back", "label": "Ultra-wide", "focalLengthMm": 2.35, "isActive": false }, { "cameraId": "0", "facing": "back", "label": "Wide (auto)", "focalLengthMm": 6.81, "isActive": true }, { "cameraId": "1", "facing": "front", "label": "Front (auto)", "focalLengthMm": 2.51, "isActive": false } ] }` | Lists selectable cameras (logical ids + `"<logical>:<physical>"` sub-cameras) and which is active. `label` is a wide/ultra-wide/tele/front heuristic from relative focal length; a bare logical id gets a "(auto)" suffix. |
| POST | `/api/cameras/active` | — | `{ "cameraId": "0:3" }` | `{ "activeCameraId": "0:3" }` | Switches active camera. Disruptive reconfigure — the service rebuilds the running pipeline (a switch mid-recording ends the current clip). Persisted in `DeviceConfig.activeCameraId`. `404` unknown `cameraId`. |

### Camera control

> **Implemented** — phone-app `http/routes/CameraRoutes.kt` +
> `camera/CameraCapabilitiesReader.kt` / `CameraControlApply.kt` / `CameraControlValidation.kt`
> (the last is framework-free + unit-tested), controller `internal/phoneapi/camera.go` +
> `App.GetCameraControls` / `SetCameraControls` / `ComputeEffectiveRect`. The applied state
> persists in `DeviceConfig.cameraControls` and is re-applied to whichever pipeline
> (`record` or `live`) is running — a state dialled in while watching the live preview also
> governs recording. Verified on the Pixel 6 (API 36) and BLU G5 (API 28).

**The concrete `keys` set** (`CameraControlKeys`; a null/absent field = leave that control on
auto). `POST` replaces the `keys` object wholesale — send the full desired set.

| key | Camera2 mapping | capability gate |
|---|---|---|
| `zoomRatio` (float) | `CONTROL_ZOOM_RATIO` (API 30+) else centred `SCALER_CROP_REGION` | `zoomRatioRange` |
| `cropRegionNorm` `{l,t,r,b}` 0..1 | `SCALER_CROP_REGION` (off-centre) — **exclusive** with `zoomRatio` in one request (readback-corruption quirk); the rect wins | must be a sane sub-rect of 0..1 |
| `aeExposureCompensation` (int) | `CONTROL_AE_EXPOSURE_COMPENSATION` | `aeCompensationRange` |
| `aeLock` (bool) | `CONTROL_AE_LOCK` (metering freeze, not a manual-exposure dial) | — |
| `manualExposure` (bool) + `sensorExposureTimeNs` + `sensorSensitivityIso` | `CONTROL_AE_MODE=OFF` + `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` | `hasManualSensor` (`REQUEST_AVAILABLE_CAPABILITIES` ∋ `MANUAL_SENSOR`) |
| `aeRegionNorm` `{l,t,r,b}` 0..1 | `CONTROL_AE_REGIONS` (one max-weight `MeteringRectangle`, active-array coords) — spot metering. Applied **only alongside `manualExposure`**, as the alternative to the shutter/ISO dials (region → AE stays metering, biased to the box; dials → `AE_MODE=OFF`). The controller sends exactly one. | `maxAeRegions > 0`; sane sub-rect of 0..1 |
| `manualFocus` (bool) + `lensFocusDistanceDiopters` | `CONTROL_AF_MODE=OFF` + `LENS_FOCUS_DISTANCE` | `hasManualFocus` (`LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` + `AF_MODE_OFF`) |
| `afRegionNorm` `{l,t,r,b}` 0..1 | `CONTROL_AF_MODE=CONTINUOUS_VIDEO` + `CONTROL_AF_REGIONS` (one max-weight `MeteringRectangle`) — drag-to-focus. Applied **only alongside `manualFocus`**, as the alternative to the distance dial (region → continuous AF on the box; dial → `AF_MODE=OFF` + fixed distance). The controller sends exactly one. | `maxAfRegions > 0`; sane sub-rect of 0..1 |
| `awbMode` (int) | `CONTROL_AWB_MODE` (0 off / 1 auto / 2 incandescent / … / 8 shade) | must be in `awbModes` (`CONTROL_AWB_AVAILABLE_MODES`, deduped) |
| `manualWhiteBalance` (bool) + `wbRedGain` / `wbGreenGain` / `wbBlueGain` | `AWB_MODE_OFF` + `COLOR_CORRECTION_MODE=TRANSFORM_MATRIX` + identity `COLOR_CORRECTION_TRANSFORM` + `COLOR_CORRECTION_GAINS` (RGGB, green used for both G channels). Wins over `awbMode`. | `hasManualWhiteBalance` (`REQUEST_AVAILABLE_CAPABILITIES` ∋ `MANUAL_POST_PROCESSING`); gains in `wbGainRange` (fixed `1.0..8.0` — Camera2 declares none) |
| `videoStabilizationMode` (int) | `CONTROL_VIDEO_STABILIZATION_MODE` (0 off, 1 on, 2 preview-stabilization API 33+) | must be in `videoStabilizationModes` (`CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`, deduped) |
| `opticalStabilizationMode` (int) | `LENS_OPTICAL_STABILIZATION_MODE` (0 off, 1 on) | must be in `opticalStabilizationModes` (`LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`, deduped) |

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/camera/capabilities` | `cameraId=1` *(optional, defaults to active)* | — | `{ "cameraId": "0", "zoomRatioRange": {"lo":0.67,"hi":7.0}, "zoomViaRatioApi": true, "aeCompensationRange": {"lo":-24,"hi":24}, "aeCompensationStepMilliEv": 166, "exposureTimeRangeNs": {"lo":26503,"hi":8310343667}, "sensitivityRange": {"lo":44,"hi":11377}, "minFocusDistanceDiopters": 9.52, "hasManualSensor": true, "hasManualFocus": true, "hasManualWhiteBalance": true, "wbGainRange": {"lo":1.0,"hi":8.0}, "awbModes": [0,1,2,3,4,5,6,7,8], "videoStabilizationModes": [0,1,2], "opticalStabilizationModes": [0,1], "maxAeRegions": 3, "maxAfRegions": 1, "outputResolutions": ["1920x1080","1280x720","854x480"], "physicalCameraIds": ["2","3"], "croppingType": "CENTER_ONLY", "activeArrayWidth": 4080, "activeArrayHeight": 3072 }` | Declared per-key ranges for a camera; pure `CameraCharacteristics` read, works in any mode. Optional `cameraId` previews another camera without switching. `404` unknown `cameraId`. |
| GET | `/api/camera/state` | — | — | `{ "cameraId": "0", "rotationDegrees": 0, "videoResolution": "1280x720", "manualControlEnabled": false, "keys": { "zoomRatio": null, "...": null } }` | Current manual-control state (from `DeviceConfig`), including `rotationDegrees` (0/90/180/270) and `videoResolution` (the record/broadcast size, one of `capabilities.outputResolutions`). |
| POST | `/api/camera/state` | — | `{ "manualControlEnabled": true, "rotationDegrees": 90, "videoResolution": "1920x1080", "keys": { "zoomRatio": 2.0, "aeExposureCompensation": -2 } }` | *(resulting state, same shape as GET)* | Validate-then-apply against the **active** camera's capabilities: `400 {"error": "...", "key": "zoomRatio"}` on the first offending key, applies nothing. Any subset of `manualControlEnabled` / `rotationDegrees` / `videoResolution` / `keys` may be sent; omitted fields are left unchanged. `rotationDegrees` must be one of 0/90/180/270; `videoResolution` must be one of the camera's `outputResolutions`. A `videoResolution` change rebuilds the running pipeline. Accepted + persisted even in `standby` (applied when a pipeline next starts). |

### Calibration (device-wide)

> **Implemented** in `phone-app` (`CalibrationRunner` + `CalibrationRoutes`) and consumed by
> `controller` (`internal/calibration`). It's now a **real empirical zoom probe**: for every
> camera, at every `StreamConfigurationMap` output size, it applies a geometric range of zoom
> requests (`CONTROL_ZOOM_RATIO` on API 30+, `SCALER_CROP_REGION` on every API) and records what
> the HAL actually did — the effective crop rect (`effectiveCropNorm`), whether the requested
> ratio/position was honoured, which physical camera was active (optical↔digital crossover), and
> a frame-sharpness score. Result body carries `deviceIdentity`, and per camera `opticalRange` /
> `digitalRange` / `crossoverRatio` / `positionHonored` / `qualityCollapseRatio` /
> `perResolution` (the full `ZoomSample` list) plus a thin `steps` summary; the exact shape is
> `phone-app`'s `calibration/CalibrationModels.kt` ↔ `controller`'s `internal/phoneapi/calibration.go`.
> Needs exclusive camera access, so it only runs from `standby` (see Mode).

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| POST | `/api/calibration/start` | — | `{}` | `{ "runId": "cal-8f2a1c", "status": "running", "startedAtMs": 1755270000000, "cameraIds": ["0", "2", "1"] }` | Sweeps every camera the device reports. `409` if already running, **`409` if the phone is in `record` mode** (`{"error":"stop recording on the phone before calibrating"}`), `422` if the device reports no cameras. |
| GET | `/api/calibration/status` | `runId=` | — | `{ "runId": "cal-8f2a1c", "status": "running", "currentCameraId": "2", "camerasCompleted": 1, "camerasTotal": 3, "currentStep": "1920x1080", "stepsCompleted": 4, "stepsTotal": 24, "progressWithinStep": { "index": 9, "total": 14 } }` | Poll target. `currentStep` is the output size being swept; `progressWithinStep` is the zoom step. |
| DELETE | `/api/calibration/:runId` | — | — | `{ "cancelled": true }` | Cooperative stop; completed cameras keep partial results. |
| GET | `/api/calibration/result` | `runId=` *(optional)* | — | *(see the note above — `runId`, `runAtMs`, `deviceIdentity`, `cameras.{id}.{opticalRange,digitalRange,crossoverRatio,positionHonored,qualityCollapseRatio,perResolution,steps}`)* | With `runId`, that specific run (`409` if not completed). **Without it, the phone's last persisted result** — written to disk, so an already-calibrated phone serves it on every request without re-running, including after an app restart. `404` if this phone has never completed a calibration. |

### Live view

**Implemented as plain HLS** (whole ~1s `.ts` segments, 16-segment sliding window,
`#EXT-X-VERSION:3`, `#EXT-X-START:TIME-OFFSET=-4`), not LL-HLS. Glass-to-glass latency ≈ 4–6s.
The phone runs a dedicated single-stream `camera → MediaCodec → TsMuxer` pipeline
(`phone-app`'s `camera/LivePipeline.kt` + `LiveHlsRelay.kt`); `MediaMuxer` can't emit MPEG-TS so
the muxer is hand-rolled (`camera/ts/TsMuxer.kt`). Entering `live` mode arms the pipeline
(camera warm, nothing encoding); `POST /api/live/start` begins broadcasting; a 15s no-request
inactivity watchdog returns it to armed-idle. `/live/*` is behind the normal bearer token — the
prototype's separate GET-only scoped token is deferred (the controller proxies these
server-side). A deep DVR window + hls.js live config + a client stall watchdog are what make
plain HLS hold up (see `docs/quirks/live-hls.md`); the LL-HLS upgrade stays carried-forward.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| POST | `/api/live/start` | — | `{}` | `{ "started": true, "viewerCount": 1 }` | Idempotently begins broadcasting; `409` unless the phone is in `live` mode. `503 { "error": "camera still starting", "retryAfterMs": 2000 }` (plus a `Retry-After` header) while the camera is still arming — a cold front-facing camera can take several seconds; the caller should retry until it succeeds. `503 { "error": "live camera unavailable" }` (no `retryAfterMs`) is a hard failure — do not retry. |
| DELETE | `/api/live/stop` | — | — | `{ "stopped": true, "viewerCount": 0 }` | Explicit stop (usually unnecessary — 15s inactivity watchdog handles it). |
| GET | `/live/live.m3u8` | — | — | *(text `application/vnd.apple.mpegurl`)* | Rolling HLS playlist. `404` before broadcasting starts, `409` when not in `live` mode. |
| GET | `/live/live-<n>.ts` | — | — | *(binary `video/mp2t`)* | One HLS segment; `404` once it's rolled out of the window. |

### Segments (sync)

A **segment** is one recorded file in the ring buffer. Grouping contiguous segments into a
user-facing **clip** is a controller/UX concern (the controller does it on sync, by time gap) —
**not part of this API**. The phone only ever lists, serves, and deletes individual segments.

| Method | URL | Query params | Example request body | Example response body | Description |
|---|---|---|---|---|---|
| GET | `/api/segments` | `since=<epochMs>` | — | `{ "segments": [ { "filename": "clip_0004123.mp4", "url": "/api/segments/clip_0004123.mp4/file", "createdAtMs": 1755270012000, "durationMs": 8000, "endMs": 1755270020000, "sizeBytes": 2100000, "width": 1920, "height": 1080 } ] }` | Delta-pull of segments created after `since` (default `0` = everything on disk). |
| GET | `/api/segments/:filename/file` | — | — | *(binary `video/mp4`, supports `Range`)* | Downloads one segment; `404` if evicted. |
| GET | `/api/segments/:filename/thumbnail` | — | — | *(binary `image/jpeg`)* | Single extracted frame, for the Gallery filmstrip. |
| DELETE | `/api/segments/:filename` | — | — | `{ "deleted": true }` | Explicit early eviction. |

(The `filename` still carries a historical `clip_` prefix — it's an opaque on-disk name, not a
statement that the file is a "clip" in the grouped sense.)

## Open items for the controller-design session

- How the controller stores/uses its per-pairing bearer token and public/private keypair.
- Controller-side archive/sync strategy against `/api/segments` (polling cadence, how it dedupes
  on `filename`, how it decides what to keep locally vs. re-fetch, how it groups contiguous
  segments into clips).
- ~~Server-side device-capability database (mentioned in the old prototype's contract, keyed by
  manufacturer+model+device+`cameraId` now) — whether/how the controller design reuses that
  concept.~~ **Resolved**: the controller owns this, keyed by manufacturer+model, populated by
  pulling `GET /api/calibration/result` from whichever phone of that model calibrates first. See
  "Calibration data model" in [HANDOFF-controller-ux.md](HANDOFF-controller-ux.md).
- Multi-phone "fleet" concerns (the mock's Home screen fleet summary implies a controller
  aggregates status across several phones — this session only designed the single-phone API).
