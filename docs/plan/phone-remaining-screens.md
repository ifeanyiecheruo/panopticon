# Plan: phone Controllers & Configuration screens

**Side:** phone · **Size:** medium

## Goal

Build the two phone screens still missing versus the UX design: **Controllers** (paired
controller list + invite generation) and **Configuration** (device settings + the Calibrate
entry point).

## Context

[`../design/decisions/0012-ux-shape.md`](../design/decisions/0012-ux-shape.md) and the mock
([`../design/ux-mocks/phone-ux-mock.html`](../design/ux-mocks/phone-ux-mock.html)) specify both.
The backing library functions already exist: `ControllerRegistry.list()/revoke()`,
`InviteManager.createInvite()/listPendingInvites()/revokeInvite()` (local-only, not routes — see
[`0002`](../design/decisions/0002-http-api-surface-and-auth.md)), and `DeviceConfig` /
`/api/config`. Calibrate is already built and is reached from Configuration's button.

## Approach

**Controllers screen:** list paired controllers as name + public-key fingerprint — **no IP**
([`0003`](../design/decisions/0003-pairing-and-unpairing.md)). Per-row revoke
(`ControllerRegistry.revoke`). "Generate invite link" → the Connect screen (already built);
optionally show/revoke pending invites.

**Configuration screen:** device name, `motionSensitivity`, storage cap, ring-buffer max age —
edit → `POST /api/config` locally (same persistence the HTTP route writes). A "Calibration"
button → the Calibrate screen. `rotationDegrees` and `videoResolution` are **not** here (they
moved to `/api/camera/state` per [`0008`](../design/decisions/0008-camera-control-and-multi-camera.md))
— they belong on the Preview screen ([`phone-camera-control-ui.md`](phone-camera-control-ui.md)).

## Affected files

`phone-app/.../ui/controllers/*` + `ui/config/*` (new), `ui/nav/PanopticonNavHost.kt`,
`state/AppConfig.kt`, reuse `pairing/ControllerRegistry.kt` / `pairing/InviteManager.kt`.

## Testing

Compose UI tests: config edit round-trips through `DeviceConfig`; revoking a controller removes
its row and its token stops authenticating (`ControllerRegistry` unit test). Manual: revoke a
paired controller from the phone, confirm its next call `401`s.

## Acceptance

Both screens match the mock's content; config edits persist and are visible via `GET /api/config`;
controller revoke from the phone works end to end; no IP shown anywhere.

## Not in scope

QR display ([`qr-pairing.md`](qr-pairing.md)); the Preview screen
([`phone-camera-control-ui.md`](phone-camera-control-ui.md)).
