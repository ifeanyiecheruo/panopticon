# phone-app — Compose UI — software design description

## 1. Introduction

### 1.1 Purpose

Describe the on-phone UI built for the vertical slice: identity/status, generating a pairing
invite, browsing recorded clips, and running calibration.

### 1.2 Scope

Covers `ui/**` (`ui/nav/PanopticonNavHost.kt`, `ui/home/*`, `ui/connect/ConnectScreen.kt`,
`ui/gallery/GalleryScreen.kt`, `ui/calibrate/*`, `ui/theme/Theme.kt`), `state/AppState.kt`,
`state/AppConfig.kt`, `MainActivity.kt`. The Preview / Controllers / Configuration screens are
**not built** (see §4).

### 1.3 Context

A leaf component: it drives `PanopticonService`, `SegmentStore`, `InviteManager`, and
`CalibrationRunner`, and provides nothing back to them. All camera control is currently driven
from the controller, not this UI.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| screen | one top-level Compose destination in the nav graph |
| adjust bar / ruler / drag-scroller | the Preview-screen interaction patterns (documented in ADR 0012) to be ported into Compose when Preview is built |
| clip | user-facing gallery item — a contiguous run of segments (system-wide term; architecture §1.3) |

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[UI / UX](../architecture.md#acr-ui),
[QR](../architecture.md#acr-qr),
[LAN](../architecture.md#acr-lan),
[API](../architecture.md#acr-api) (Android API level).

### 1.6 References

- [`../decisions/0012-ux-shape.md`](../decisions/0012-ux-shape.md).
- Mocks + storyboards:
  [`../ux-mocks/phone-ux-mock.html`](../ux-mocks/phone-ux-mock.html),
  [`../storyboards/`](../storyboards/).
- Plans for the missing screens:
  [`../../status/phone-camera-control-ui.md`](../../status/phone-camera-control-ui.md),
  [`../../status/phone-remaining-screens.md`](../../status/phone-remaining-screens.md).

## 2. Design overview

Four Compose screens on a bottom nav bar, over a shared dark/teal theme. State is read from
`PanopticonService` and `SegmentStore`; mutations (delete a clip, create an invite, start a
sweep) call those components directly.

## 3. Detailed design

### 3.1 Design entities

| Screen | Responsibility |
|---|---|
| **Home** | device identity, ring-buffer storage usage, recording status. |
| **Connect** | `InviteManager.createInvite()` → show an invite code + this phone's LAN address (plain text; QR display deferred). |
| **Gallery** | list **clips** (contiguous segments grouped by time gap); play a run via `Intent.ACTION_VIEW` to the system video player (opens the first segment — no in-Gallery segment advance); delete a whole clip. Loads via `LaunchedEffect`, deletes on `Dispatchers.IO` behind a busy flag that no-ops re-entrant taps. |
| **Calibrate** | reachable only from Configuration's button; "Stop recording" (→ `standby`) then run/re-run a sweep; live progress; per-camera optical/digital/position/quality readout. |
| `PanopticonNavHost` | bottom nav bar (not the mock's left icon rail + top status pill) — a deliberate slice-level simplification. |
| `Theme.kt` | the dark/teal placeholder theme, carried over from the mock. |

### 3.2 Dependencies

- `PanopticonService` — mode, status.
- `SegmentStore` — clip list, delete.
- `InviteManager` — invite creation.
- `CalibrationRunner` — start / status / result.

### 3.3 Interfaces

- **Provided:** none (leaf UI).
- **Required:** the four components above.

### 3.4 Data

- `AppState` / `AppConfig` — the observable UI-facing view of service mode and `DeviceConfig`.
  No persistence of its own.

### 3.5 Processing and behaviour

- Gallery grouping mirrors the controller's time-gap rule for display only.
- Calibrate gates its start on mode == `standby` and surfaces the transition affordance.

## 4. Design rationale and decisions

- **Fresh Compose start** — [`0012`](../decisions/0012-ux-shape.md): the prototype's
  code is not reused, only its lessons.
- **Bottom nav vs the mock's rail + status pill** — a proportionate slice-level simplification;
  the visual language (dark/teal) is carried over, the exact chrome is not.
- **Not built (deferred):** Preview screen (live view + camera controls), Controllers screen,
  Configuration screen, QR-code invite display. The Preview screen's ruler and drag-scroller are
  the load-bearing patterns to port —
  [`../../status/phone-camera-control-ui.md`](../../status/phone-camera-control-ui.md),
  [`../../status/phone-remaining-screens.md`](../../status/phone-remaining-screens.md).
- **Placeholder theme** — the visual-language pass is deferred
  ([`../../status/visual-language-pass.md`](../../status/visual-language-pass.md)).
