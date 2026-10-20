# Plan: phone-side Compose UI for camera controls (Preview screen)

**Side:** phone · **Size:** large

## Goal

Build the phone's Preview screen — live view + on-device camera controls — so the phone isn't
control-only-from-the-controller.

## Context

[`../design/decisions/0008-camera-control-and-multi-camera.md`](../design/decisions/0008-camera-control-and-multi-camera.md):
the `/api/camera/*` slice is fully implemented and driven entirely from the controller; there is
**no Compose UI** for it on the phone. [`0012`](../design/decisions/0012-ux-shape.md) documents
the Preview-screen interaction patterns (frosted overlay controls, the custom ruler, the custom
drag-scroller mode bar, slider-only-when-applicable, zoom drag-only) — those are the load-bearing
pieces, worth deliberate Compose equivalents rather than stock widgets. The controller frontend
(`frontend/src/components/phonecam/*`) is a working reference implementation of the same
interactions.

## Approach

- **Preview screen** in `ui/` + a nav entry (the mock has it on the icon rail; current app is a
  bottom nav bar — pick one, note it).
- **Live viewport** — reuse `LivePipeline` via `live` mode (Preview only runs `live`; motion
  detection stays out per [`0005`](../design/decisions/0005-motion-detection.md)). Enter `live`
  on screen open (only from `standby` — surface the same "stop recording" affordance Calibrate
  has), leave on close.
- **Overlay controls** — frosted (`Modifier.blur` / translucent scrim), fade on idle, snap back
  on touch. Bound to `GET /api/camera/capabilities` for the active camera.
- **Custom ruler** — Compose `Canvas`: fixed window, tick strip translated under a fixed thumb,
  edge fade via a gradient `Brush` mask, three tick tiers, end glyphs. No numeric readout.
- **Custom drag-scroller** mode bar — Compose: drag-release snap that never leaves the first/last
  item partially visible; tap centres any item. Port the edge-protection + centering logic from
  `phonecam/HScroll.tsx`.
- **State** — a per-adjuster config (type, range, step, disabled conditions) mirroring the mock's
  `ADJUSTERS`; write through `POST /api/camera/state`.

## Affected files

`phone-app/.../ui/preview/*` (new), `ui/nav/PanopticonNavHost.kt`, `state/AppState.kt`, reuse
`camera/LivePipeline.kt` + `camera/CameraCapabilitiesReader.kt` DTOs.

## Testing

Compose UI tests for the ruler drag → value mapping and the drag-scroller snap/centre edge
cases; manual on the Pixel 6 against a real live feed, cross-checking a value set on the phone
shows up in the controller's `GET /api/camera/state`.

## Acceptance

Every capability-gated control the controller exposes is adjustable on the phone; the ruler and
drag-scroller match the documented edge behaviour; entering Preview from RECORD prompts rather
than silently interrupting.

## Not in scope

Redesigning the control set or the API; the Controllers / Configuration screens
([`phone-remaining-screens.md`](phone-remaining-screens.md)).
