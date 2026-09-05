# Panopticon phone UX — handoff

Status as of 2026-09-04: phone app UX has been prototyped as a clickable HTML/CSS/JS
mock (no Kotlin/Compose code written yet). This doc is the compact starting point
for the next session, which will move into real Android implementation.

## Where things live

- **Live artifact** (always the latest version): https://claude.ai/code/artifact/a8956ebd-47ef-484b-abcd-178821c56c1a
- **Committed copy of the mock**: [../design/ux-mocks/phone-ux-mock.html](../design/ux-mocks/phone-ux-mock.html) — single self-contained HTML file, open it directly in a browser
- **Original sketches**: [../design/storyboards/](../design/storyboards/) — 7 numbered screen sketches (1-home, 2-gallery, 3-preview, 4-controllers, 4.1-connect, 5-config, 5.1-calibration) plus reference photos of a real Pixel camera's zoom slider and hand sketches of the desired ruler/picker layout
- **Old prototype** (architecture/lessons only, not code to reuse): `../panopticon-prototype` — Kotlin phone app + Node/TS server, abandoned after hitting the limits of its usefulness

## Product shape (from the original brief)

Old Android phones become standalone security cameras: record motion-triggered
video to a local ring buffer, no cloud. A separate "controller" app (desktop/server)
pairs with phones via invite link, stores only the controller's public key on the
phone (no IP tracking), and can remotely view/configure/pull recordings. Phone
generates invites; controllers cannot.

## Screens implemented in the mock

- **Home** — device identity, ring-buffer storage usage, fleet summary
- **Preview** — live camera view + camera configuration (see below, this got the most iteration)
- **Gallery** — clip viewer, scrubber, filmstrip of recent motion events, favorite/delete
- **Controllers** — paired controller list (name + public-key fingerprint, no IP), "Generate invite link"
- **Connect** — QR code + manual invite code + raw pairing URL for a controller to scan
- **Configuration** — device name, motion/storage settings, "Calibration" button
- **Calibrate** — empirically-tested camera capability ranges (zoom/exposure/focus/resolution) + a live "currently probing" row

Navigation: left icon rail (Home logo-button, Preview, Gallery, Controllers, Config).
Calibrate is reachable only from Configuration's button, not from the rail.
Top bar: back button, a recording-status pill (STANDBY / REC / UNAVAILABLE — pill
goes UNAVAILABLE whenever the camera is busy rendering: Preview, Gallery, or
Calibrate), screen title, battery.

## Preview screen — the interesting part

This screen went through the most design iteration and has the patterns worth
reusing elsewhere:

- **Live viewport** (3:4) with a LIVE badge, a motion-detection badge, and a
  camera/resolution tag — this is still the single biggest vertical-space cost
  on the screen (~400px); explicitly left untouched pending a product call on
  whether to shrink it.
- **Overlay controls float on the video itself**, translucent + blurred
  (frosted glass), not in normal document flow: a narrow (180px) slider
  bottom-center, and a small circular camera-cycle button bottom-right. Both
  **fade out after 5s idle** and snap back instantly on any tap/drag.
- **The slider only exists when it should**: no slider at all for modes with
  no numeric parameter (Scene), and no slider while a hybrid control's picker
  makes it inapplicable (Focus's ruler is absent until its own
  Continuous/Manual/Macro picker below is set to "Manual"). No greyed-out
  placeholder sliders.
- **Ruler design** (rebuilt from scratch to match a real Pixel-camera
  reference, see the two sketch photos): fixed-width window; the tick strip
  is wider than the window and slides under a thumb that never moves; ticks
  fade at the window edges via `mask-image` rather than clipping hard; three
  tick tiers (major/minor/fine "mm" ticks) like a physical ruler; a
  directional glyph anchored at each end says what dragging that way does
  (zoom out/in, near/far focus, dark/bright, low/high shadows, cool/warm white
  balance). No numeric labels or value readout anywhere on the ruler anymore —
  removed per explicit request.
- **Adjust bar** — a horizontal row of mode buttons (Zoom, Focus, Scene,
  Brightness, Shadows, White balance) below the viewport, built as a **custom
  drag-scroller** (not native CSS scroll-snap), because it has two distinct
  behaviors worth carrying forward:
  - *Drag release* snaps to whichever item's flush-left position is nearest,
    but a stop is only valid if it doesn't leave the **first or last** item
    partially visible — see `computeSnapStops()`. A partial "next item" peek
    is the intended way to signal there's more to scroll; it just can never
    happen to the two end items.
  - *Tapping an item* always scrolls it to dead center of the viewport, even
    the first/last item — see `centerItemInScroller()` and
    `padEndsForCentering()`. This requires padding the track by
    `(viewport width − item width) / 2` on both ends, **and** giving the
    track `width: max-content` — without that, `box-sizing: border-box` makes
    a padded block box eat its own content area instead of growing, which was
    a real bug caught mid-session.
  - Tapping a child button inside the scroller had a second real bug: calling
    `setPointerCapture` on the scroller for drag-tracking silently retargets
    the browser's synthesized click event away from whatever was actually
    tapped. Fixed by detecting "no drag occurred" manually and replaying a
    real `.click()` on the pressed element — see `makeHScroll()`.
  - Same component is reused for the Gallery filmstrip.
- **Non-numeric adjuster content** (Scene's Auto/Outdoor/Indoor/Night list,
  Focus's Continuous/Manual/Macro picker) renders **below** the adjust bar,
  not above. Numeric rulers render **above**. Every segmented picker
  (including Focus's, which lives alongside a ruler) shares one
  `data-pickerkey` attribute convention so a single `wireSegment()` handles
  all of them generically.
- Capture settings (Resolution, Frame rate) live in a plain card below
  everything else. No section headings ("Adjust", "Capture") on this screen —
  removed for vertical density; other screens kept their headings since they
  weren't reported as cramped.

## Visual language

Dark near-black/teal theme, IBM Plex Sans (UI) + IBM Plex Mono (data/telemetry)
typefaces, teal `--accent`, translucent frosted-glass (`backdrop-filter: blur`)
for anything overlaid on the live video. Padding/gaps were tightened twice
over the session (content, cards, field-rows, buttons, rail, top bar) in
response to "too much padding for a phone UX" — current values are in the
`<style>` block at the top of the mock file if they need further adjustment.

## Explicit decisions made along the way (worth not re-litigating)

- Fresh Android/Kotlin start, not reusing the old prototype's code — only
  mining `../panopticon-prototype`'s `QUIRKS.md`/`ARCHITECTURE.md` for lessons.
- Jetpack Compose for the eventual real UI (decided early, not yet started).
- Controllers are identified to the phone by public key only — no IP address
  is stored or displayed anywhere.
- Zoom has no tap-to-preset shortcuts (tried, then explicitly removed) — drag
  is the only interaction, consistent with every other ruler.

## Suggested next steps

1. Decide whether to shrink the 3:4 viewport aspect ratio for more density
   (flagged, not acted on).
2. Start translating the mock's screen states/flows into Compose screens +
   navigation graph; the mock's `state` object and `ADJUSTERS` config
   (in the `<script>` block) are a reasonable map of what state the real app
   needs to track per adjuster (type, range, step, format, disabled
   conditions).
3. The drag-scroller and ruler components are the most "designed" pieces
   here and worth deliberate Compose equivalents rather than reaching for a
   stock slider/carousel — the edge-protection and centering behaviors are
   load-bearing UX decisions, not incidental.
