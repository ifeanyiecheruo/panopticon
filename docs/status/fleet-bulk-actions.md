# Plan: Fleet bulk arm / bulk stand-down

**Side:** controller · **Size:** small–medium

## Goal

One-click, fleet-wide "arm everything" and "stand everything down" on the Fleet screen — no
per-phone selection step.

## Context

[`../design/decisions/0012-ux-shape.md`](../design/decisions/0012-ux-shape.md) defines these as
**full-sequence** actions, not mode toggles:

- **Bulk arm** (every phone *not* currently recording): cancel any in-progress phone action
  (live preview, calibration), return the phone to its home screen, then start recording.
- **Bulk stand-down** (every phone *currently* recording): return to home, then `standby`.

Per-phone record start/stop already exists (`App.SetRecording`, the Phone-detail command bar).

## Approach

- Two `App` methods: `BulkArm()` / `BulkStandDown()`. Each fans out over paired phones,
  per-phone: read `/api/mode` + `/api/status`, run the sequence (`DELETE
  /api/calibration/:runId` / `DELETE /api/live/stop` as needed → `POST /api/mode {standby}` →
  `POST /api/mode {record}` for arm), collect a per-phone outcome.
- Concurrency-bounded fan-out; a phone that's unreachable or mid-transition yields a
  non-fatal per-phone error in the result, not an aborted batch.
- Fleet-header buttons; a result toast/summary ("armed 4, skipped 1 unreachable").

## Affected files

`controller/app.go` (`BulkArm` / `BulkStandDown`), `internal/phoneapi` (compose existing calls;
maybe a small sequencer helper), `frontend/src/screens/Fleet.tsx` (header buttons + summary),
`internal/integrationtest`.

## Testing

`integrationtest` with several fake phones in mixed states (recording, live, calibrating,
unreachable): arm skips the recorders, cancels live/calibration on the rest, ends with all
non-skipped phones in `record`; stand-down is the inverse.

## Acceptance

Both buttons drive a mixed fleet to the target state in one click; unreachable phones are
reported, not fatal.

## Not in scope

Per-phone selection UI; scheduling / time-of-day automation.
