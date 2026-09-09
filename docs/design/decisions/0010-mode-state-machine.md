# 0010 — Phone mode state machine

**Status:** Accepted · implemented.

## Context

The camera is a single exclusive resource. Recording, live preview, and calibration all want it,
and recording is the one that must not be silently interrupted by someone opening a page.

## Decision

Three top-level modes on `/api/mode`:

| Mode | Camera | Notes |
|---|---|---|
| `record` | motion-gated recording pipeline owns it | **sticky** — takes precedence over every other camera-using feature |
| `standby` | released | the only state calibration or live preview can be entered from |
| `live` | live-preview pipeline (plain HLS) | armed-idle until `POST /api/live/start` |

Transitions:

- `standby` and `record` are always allowed; no-op `200` if already there.
- `live` **from `record`** is a `409` — `{"error":"stop recording first: POST /api/mode
  {\"mode\":\"standby\"}"}`. Go via `standby`.
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
