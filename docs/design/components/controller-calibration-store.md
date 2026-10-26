# controller — calibration store — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that treats calibration as a shared resource keyed by
`manufacturer + model` — populated opportunistically from whichever phone of a model calibrates
first, and used to predict the crop a phone's HAL will actually honour for a zoom request.

### 1.2 Scope

Covers `internal/calibration/` (`calibration.go`, `EffectiveRect`),
`internal/phoneapi/calibration.go`, `internal/dbstore/calibration.go`, and the
`camera_calibration` table in `internal/dbstore/schemas/db/004_camera_calibration.sql`.

### 1.3 Context

Produced by the phone-side sweep ([`phone-calibration.md`](phone-calibration.md)); consumed by
the camera-control UI ([`controller-live-and-camera.md`](controller-live-and-camera.md)) for
adjuster ranges and the predicted-crop overlay, and by Phone detail for its Calibration summary.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| model key | `manufacturer + model` — the store's key, not a phone id |
| opportunistic ingest | pulling `GET /api/calibration/result` (no `runId`) and storing each camera nothing fresher is held for |
| `Lookup` | the Phone-detail read view of a model's calibration summary |
| `EffectiveRect` | requested zoom + centre → the crop the HAL will actually honour |
| reuse-don't-repeat | a phone whose model is already calibrated is never prompted to sweep |

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[HAL](../architecture.md#acr-hal),
[JSON](../architecture.md#acr-json),
[DB](../architecture.md#acr-db),
[UI](../architecture.md#acr-ui).

### 1.6 References

- [`../decisions/0009-calibration-model.md`](../decisions/0009-calibration-model.md).
- [`../http-api.md`](../http-api.md) — `GET /api/calibration/result`.
- [`phone-calibration.md`](phone-calibration.md) (producer),
  [`controller-calibration-store.md`](controller-calibration-store.md) is consumed by
  [`controller-live-and-camera.md`](controller-live-and-camera.md).

## 2. Design overview

An ingest side (`IngestOpportunistic` / `StoreRun`) that writes the `camera_calibration` table,
one row per model and camera, and a read side (`Lookup` for the UI summary, `EffectiveRect` for the predicted-crop
overlay). Nothing is ever pushed back to a phone.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `ModelKey` | key | `manufacturer + model`. |
| `IngestOpportunistic` | ingest | Pull `GET /api/calibration/result` (no `runId`); store each camera we don't already hold something fresher for. Called right after a pair, and optionally on opening Phone detail. A partial result (`complete: false`) is never stored. |
| `StoreRun` | ingest | After a manual run: the cameras that run finished overwrite unconditionally (last run wins); the phone's other cameras follow the freshness rule. `App.GetCalibrationProgress` calls it each time another camera finishes and once when the sweep stops. |
| `Load` | read | Assembles a model's stored cameras into one result for `Lookup` and `EffectiveRect`. |
| `Lookup` | read view | The Phone-detail summary: per camera optical/digital ranges, crossover, position-honoured, quality-collapse. No entry → "Calibration needed"; an entry from another phone shows which (e.g. "14/14 checks completed · via Porch Cam"). |
| `EffectiveRect` | predictor | Maps a requested zoom + centre for a camera/resolution to the crop the HAL will honour (nearest-resolution + interpolation over the stored `ZoomSample`s). Bound as `App.ComputeEffectiveRect`; drawn as the dashed predicted-crop overlay under the zoom-rect picker. |

### 3.2 Dependencies

- `phoneapi/calibration.go` — the HTTP client surface.
- `Store` — the `calibration` table.

### 3.3 Interfaces

- **Provided:** `Lookup` (Phone detail), `EffectiveRect` (`App.ComputeEffectiveRect`), ingest
  hooks to `pairing` and `App`.
- **Required:** `phoneapi`, `Store`.

### 3.4 Data

- `camera_calibration` table: `(manufacturer+model, cameraId)` → that camera's result JSON +
  source phone id + timestamp. One row per model and camera, not per phone.
  `App.EraseCalibration` deletes the rows a phone supplied after the phone erases its own.

### 3.5 Processing and behaviour

- The first phone of a given model to complete calibration effectively does the work for every
  other phone of that model.
- A manual re-run always overwrites the cameras it finishes, regardless of cache state (firmware
  can change measured capabilities).
- Nothing is pushed back down to a phone — cached data is used only for the controller's own
  purposes.

## 4. Design rationale and decisions

- **Shared resource keyed by `manufacturer+model`** —
  [`0009`](../decisions/0009-calibration-model.md): the result depends on hardware +
  firmware, not which phone ran the sweep; this resolves the old "server-side device-capability
  database" open item.
- **Opportunistic ingest, reuse-don't-repeat** — a full sweep is tens of minutes; the controller
  pulls a persisted result instead of asking for a re-run.
- **`EffectiveRect` over the raw samples** — the controller can show the *honoured* crop, which
  can differ from the requested one on a `CENTER_ONLY` HAL.
