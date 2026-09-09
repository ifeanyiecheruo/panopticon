# 0012 — UX shape (phone and controller)

**Status:** Accepted. Phone UI: Home/Connect/Gallery/Calibrate built; Preview/Controllers/
Configuration screens deferred. Controller UI: all five screens built. Visual-language pass
deferred ([`../../status/visual-language-pass.md`](../../status/visual-language-pass.md)).
Component specs: [`phone-ui.md`](../components/phone-ui.md),
[`controller-ui.md`](../components/controller-ui.md).

## Context

Both UIs were prototyped as clickable HTML/CSS/JS mocks before any real code
([`../ux-mocks/`](../ux-mocks/), [`../storyboards/`](../storyboards/)). The Preview screen went
through the most iteration and has patterns worth reusing.

## Decision

### Fresh Compose start on the phone

Jetpack Compose for the real UI; the prototype's code is not reused, only its
`QUIRKS.md`/`ARCHITECTURE.md` mined for lessons.

### Phone Preview-screen interaction patterns (to carry into Compose)

- **Overlay controls float on the video**, translucent + frosted (`backdrop-filter: blur`), not
  in document flow; fade after idle, snap back on any touch.
- **The slider only exists when it applies** — no slider for non-numeric modes (Scene), none
  while a hybrid picker makes it inapplicable (Focus ruler absent until its picker is "Manual").
  No greyed-out placeholder sliders.
- **Custom ruler** (rebuilt to match a real Pixel-camera reference): fixed window, tick strip
  slides under a fixed thumb, edges faded via `mask-image`, three tick tiers, a directional glyph
  at each end. No numeric labels or value readout — removed per explicit request.
- **Custom drag-scroller** for the mode bar (not native scroll-snap): drag-release snaps to the
  nearest item's flush-left position but never leaves the first/last item partially visible; a
  tap centres any item (needs `width: max-content` + symmetric end-padding). Reused for the
  Gallery filmstrip.
- **Zoom has no tap-to-preset shortcuts** — tried, removed; drag is the only interaction,
  consistent with every ruler.

### Controller screens

- **Master-detail Fleet**, not remount-on-navigate: a device list beside an inline Phone-detail
  pane, stably keyed so selecting a phone swaps only the detail pane (no Gallery/Trash flash).
- **Fleet card is minimal** — phone name + status + battery glyph only. "Unreachable" is itself
  a status value. Per-phone disk usage and the sync-state granularity (unreachable / syncing /
  up to date) live in Phone detail, not on the card.
- **Live preview is gated behind an explicit action**, never auto-started on page open (live and
  record are mutually exclusive — see [`0010`](0010-mode-state-machine.md)).
- **Gallery / Trash are master-detail**, not a lightbox — a persistent viewer+scrubber pane
  beside a day-grouped grid, chosen so autoplay (clip ends → next clip) can advance the grid
  selection in place. Gallery has a per-phone filter chip row and autoplay; Trash has neither
  (playback stops at clip end — you're reviewing what to restore or discard).
- **Bulk arm / bulk stand-down** are one-click fleet-wide, defined as full sequences: arm
  cancels whatever each phone is doing, returns it home, then records; stand-down returns home,
  then standby. (Deferred —
  [`../../status/fleet-bulk-actions.md`](../../status/fleet-bulk-actions.md).)
- **Add phone** — paste view (two fields, address + code, full-URL autofill into either) is the
  default; a text link switches to a webcam QR viewfinder (deferred). Asymmetric
  primary/secondary framing, not two equal tabs. The controller never asks the user to name the
  phone — identity comes from `GET /api/config` / `GET /api/device`.
- **Native `<video controls>`** instead of the mock's custom scrubber for now — gives
  play/pause/seek/fullscreen, just not the exact visual treatment.

### Visual language is a placeholder

The dark near-black/teal theme (IBM Plex Sans/Mono, teal accent, frosted glass) is inherited
from the mocks as a **placeholder**, explicitly not settled.

## Consequences

- The drag-scroller and ruler are load-bearing UX with non-obvious edge behaviour — worth
  deliberate Compose equivalents, not a stock slider/carousel.
- The controller UI stays responsive during sync because it reads the DB, not the phones (see
  [`0011`](0011-controller-runtime-and-state.md)).
- A visual-language pass will touch both apps' theme layers.
