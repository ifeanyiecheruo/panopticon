# 0010 — Phone mode state machine

**Status:** Accepted · implemented. **`live` updated by
[`0016`](0016-live-off-the-recording-encoder.md)** (2026-11-06): `record` and `live` are one
pipeline, and `live` from `record` is allowed.

## Context

The camera is a single exclusive resource. Recording, live preview, and calibration all want it,
and recording is the one that must not be silently interrupted by someone opening a page.

## Decision

Three top-level modes on `/api/mode`:

| Mode | Camera | Notes |
|---|---|---|
| `record` | motion-gated recording pipeline owns it | **sticky** — takes precedence over every other camera-using feature |
| `standby` | released | the only state calibration or live preview can be entered from |
| `live` | the recording pipeline with recording off ([`0016`](0016-live-off-the-recording-encoder.md)) | streams on `POST /api/live/start`; no viewer for 2 min → `standby` |

Transitions:

- `standby` and `record` are always allowed; no-op `200` if already there.
- ~~`live` **from `record`** is a `409`~~ — **superseded by
  [`0016`](0016-live-off-the-recording-encoder.md)**: `record` ⇄ `live` is one flag on one
  pipeline, so both directions are allowed and neither touches the camera.
- `POST /api/calibration/start` while in `record` is a `409`
  (`{"error":"stop recording on the phone before calibrating"}`).

The phone's own Calibrate screen surfaces a "Stop recording" affordance that performs the
`standby` transition.

## Consequences

- A controller clicking a phone's card never yanks it out of recording — live preview is gated
  behind an explicit action, and the API refuses the shortcut.
- `RecordingStatus.IDLE` means "armed" (motion-gated, nothing moving) — a phone with nothing
  moving reports `status:"idle"` and shows as **Standby** on the controller's Fleet.
- Any feature needing exclusive camera access has exactly one precondition to check: mode ==
  `standby`.
- A thermal shutdown ([`0014`](0014-thermal-safety-shutdown.md)) sits outside this machine. It
  stops whatever the mode is running without changing the mode, and the mode resumes once the
  phone has cooled.
