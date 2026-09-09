# 0009 — Calibration model

**Status:** Accepted · implemented (both sides). Component specs:
[`phone-calibration.md`](../components/phone-calibration.md),
[`controller-calibration-store.md`](../components/controller-calibration-store.md).

## Context

Calibration's whole purpose is empirically catching a device's Camera2 API lying about its own
capabilities — a per-camera risk, not just on the default camera. A full sweep is expensive
(tens of minutes), but its result depends only on hardware + firmware, not which phone ran it.

## Decision

### Device-wide empirical sweep

`POST /api/calibration/start` sweeps **every** camera the device reports. For each camera, at
every `StreamConfigurationMap` output size, it applies a geometric range of zoom requests
(`CONTROL_ZOOM_RATIO` on API 30+, `SCALER_CROP_REGION` on every API) and records what the HAL
*actually did*: the effective crop rect, whether the requested ratio/position was honoured,
which physical camera was active (optical↔digital crossover), a brightness-normalised
median-of-frames sharpness score, and — for an off-centre crop — both the metadata round-trip
**and** whether the pixels actually shifted (so a HAL echoing a crop it doesn't apply is
caught). Per camera it derives `opticalRange` / `digitalRange` / `crossoverRatio` /
`positionHonored` / `positionMetadataLiedRatios` / `qualityCollapseRatio` / `perResolution`.

### Runs only from `standby`

Needs exclusive camera access. `record` is sticky — `409` if the phone is recording.

### Results are persisted on the phone

`GET /api/calibration/result` without a `runId` serves the last completed result from disk,
including after an app restart. This is what lets the controller pull results without asking the
phone to re-run.

### The controller treats calibration as a shared resource keyed by `manufacturer + model`

- Its own store: `manufacturer+model → { cameras, calibratedAtMs, sourcePhoneId }`, separate
  from any phone's DB row.
- **Populated opportunistically** — right after pairing (and optionally on opening Phone
  detail), the controller calls `GET /api/calibration/result` and ingests under that phone's
  `manufacturer+model` key.
- **Reuse, don't repeat** — if the controller already holds data for a phone's model (from *any*
  phone), that phone is treated as already calibrated and never prompted to sweep. The first
  phone of a model to calibrate does the work for all of them.
- **Manual re-run always available** (firmware can change measured capabilities); it
  unconditionally overwrites the `manufacturer+model` entry — last run wins, no merge.
- **Nothing is pushed back to a phone.** The controller uses cached data for its own purposes
  (accurate adjuster ranges, the `EffectiveRect` predicted-crop overlay).

## Consequences

- Resolves the old "server-side device-capability database" open item carried from the prototype
  contract — the controller owns it, model-keyed.
- Phone detail's Calibration section is a lookup by `manufacturer+model`, not a per-phone
  counter: no entry → "Calibration needed" (this phone becomes the model's reference); an entry
  → check count and, if from another phone, which (e.g. "14/14 checks completed · via Porch Cam").
