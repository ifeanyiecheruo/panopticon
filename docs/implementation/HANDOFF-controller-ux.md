# Panopticon controller UX — handoff

Status as of 2026-09-05: this is the settled design for the controller (desktop) app, worked
out across two design sessions (no code written yet). Builds on
[phone-http-api.md](phone-http-api.md) (added `DELETE /api/pair` for self-unpair, and settled
calibration-result persistence, during these sessions) and the visual/interaction language
from [HANDOFF-phone-ux.md](HANDOFF-phone-ux.md). All five screens (Fleet, Phone detail,
Gallery, Trash, Add phone) are designed and have a working interactive mock — see "Screens"
below. Use this doc, [phone-http-api.md](phone-http-api.md), and the mock as the starting
point for controller implementation.

> **Superseded by implementation as of 2026-09-05** — real Go/Wails + Preact code now exists
> (see `controller/`). This doc is kept for the design rationale below (the Calibration data
> model section in particular is still the authoritative spec for not-yet-built work), but for
> current implementation status, what's built vs. deferred, and next steps, see
> [`HANDOFF-implementation.md`](HANDOFF-implementation.md) and `controller/README.md` instead.

## Product shape

A zero-dependency single binary the user launches, which then runs persistently in the
background (system tray), accepts phone invites, can configure paired phones, starts/stops
recording, and continuously syncs each phone's clips into a local aggregate gallery — so the
gallery is always almost up to date whether or not the user has a window open.

## Runtime & tech shape

- **Single Go binary**, no Node/Electron/Python runtime dependency.
- **System tray app**, not a window-first app (model: Syncthing/Tailscale). The tray icon is
  the persistent presence; background sync runs whether or not a window is open — that's what
  makes "gallery always almost up to date" true without the user thinking about it.
- **UI**: embedded native webview (Wails — Go backend + OS-native webview: WebView2 /
  WebKitGTK / WKWebView) reusing the phone mock's HTML/CSS/JS visual language, rather than a
  separate localhost-in-a-browser tab. Still a single binary; no bundled runtime.
- **Local state**: one embedded SQLite DB — no server dependency — holding the controller's
  own keypair, paired phones (id, name, base URL, bearer token, last-seen, sync cursor), and
  the clip index (see Data model below). The UI is a read view over this DB, not something
  that re-derives state from live phone calls each time.
- Visual language: currently inheriting the phone mock's dark/teal theme as a placeholder —
  explicitly **not settled**, flagged for a later pass.

## Data model: clips

Three states per clip, keyed by `(phone_id, filename)`:

- **active** — normal synced clip, file + thumbnail on disk, visible in the aggregate Gallery.
- **trashed** — file + thumbnail still on disk (restorable), hidden from Gallery, shown in
  Trash bin. Purely a controller-local concept; nothing is sent to the phone.
- **purged** — file + thumbnail deleted from disk, but the DB row (tombstone) is kept. Not
  shown anywhere in the UI. Exists only so a resync (e.g. after a cursor reset or DB rebuild)
  doesn't redownload a clip the user already discarded.

The tombstone (purged row) is deleted entirely — the clip is fully forgotten — only once the
controller has *evidence the phone evicted it from its ring buffer*: a probe against
`/api/clips/:filename/file` (HEAD or a 1-byte range GET) returning `404`. That's the one
signal that resync can never bring the clip back, since the phone itself is no longer a
source for it. This probe loop only needs to iterate trashed + purged clips, not active ones —
active clips are already safely archived and the controller keeps them regardless of what
happens on the phone's ring buffer (**the controller never ages clips out on its own**).

Trash bin actions and their effect on state:

| Action | Effect |
|---|---|
| Trash (from Gallery) | active → trashed |
| Restore (from Trash) | trashed → active |
| Delete (single, from Trash) | trashed → purged (file gone now; tombstone kept until eviction confirmed) |
| Empty trash (bulk) | same as Delete, applied to every trashed clip |

Manual Delete/Empty-trash are permanent-on-disk immediately — they don't wait for phone-side
eviction. They just can't drop the DB row yet, for the resync-safety reason above.

## Calibration data model

Calibration is expensive (a full device-wide sweep) but its result only depends on hardware +
firmware, not on which specific phone ran it — so the controller treats it as a **shared
resource keyed by `manufacturer + model`**, not a per-phone one:

- The controller keeps its own store: `manufacturer+model → { cameras: {...}, calibratedAtMs,
  sourcePhoneId }`, separate from any single phone's DB row.
- **Populated opportunistically**: right after pairing a phone (and optionally re-checked when
  opening its Phone detail page), the controller calls `GET /api/calibration/result` (no
  `runId` — the phone's last persisted result, see phone-http-api.md) on that phone. If it
  returns data, the controller ingests it under that phone's `manufacturer+model` key.
- **Reuse, don't repeat**: if the controller already holds data for a phone's
  `manufacturer+model` (from any phone, not necessarily this one), it treats this phone as
  already calibrated and never prompts it to run its own sweep — that's the whole point of
  keying by model instead of by phone. The first phone of a given model to complete a
  calibration effectively does the work for every other phone of that model.
- **Manual re-run always stays available** regardless of cache state (firmware updates can
  change measured capabilities) and simply overwrites the `manufacturer+model` entry —
  last-completed-run wins, no merge logic.
- Nothing is ever pushed back down to a phone — the controller uses cached calibration data for
  its own purposes (e.g. accurate adjuster ranges in Live preview), it doesn't tell a phone
  "you're calibrated" or hand it another phone's results.
- **Controller UX implication**: Phone detail's Calibration section reflects a lookup by this
  phone's `manufacturer+model`, not a per-phone counter. No entry → "Calibration needed" (this
  phone would become the reference for its model). An entry → shows check count and, if the
  data came from a different phone, which one, e.g. "14/14 checks completed · via Porch Cam."

## Disk usage

- **Aggregate**: total bytes on disk across all clips in `active` + `trashed` state (purged
  clips have no file left to count), shown as a top-level stat.
- **Per-phone breakdown**: same figure split by source phone — shown in Phone detail only
  (not on the Fleet card, which stays name + status).

## Pairing & unpairing

- **Pairing**: controller consumes an invite the phone generated (code/URL, optionally QR).
  Desktop's primary path is pasting the code/URL, since a desktop generally can't scan its
  own QR conveniently; webcam QR-scan is a secondary option. `POST /api/pair` → store token +
  phone identity.
  - **The invite code alone is not enough** — `POST /api/pair` is a request *to the phone's own
    HTTP server*, so the controller also needs the phone's address (IP or hostname) on the
    network to even know where to send it. The full invite URL (e.g.
    `https://192.168.1.87/api/connect?invite=XYZF-EBDO-ORMS`) embeds both; a bare short code
    (the large text shown on the phone's Connect screen) does not, and needs the address
    entered separately. QR sidesteps this entirely — the QR encodes the same full URL, so
    scanning it captures address + code together without either being typed.
- **Unpair**: calls the new `DELETE /api/pair` (self-revoke, see phone-http-api.md). Before
  calling it, checks `GET /api/clips?since=<last-synced-cursor>` — if the phone reports any
  clips not yet pulled, warns the user before proceeding ("N clips haven't synced yet;
  unpairing risks losing them once the ring buffer evicts them"). If the phone is unreachable
  or the revoke call fails, **does not** unpair locally — the phone still holds a live token,
  so local state must stay in sync with that fact.
- **Force unpair**: same local removal, but proceeds regardless of reachability/failure —
  best-effort attempt at the revoke call, but success isn't required. Needs a more deliberate
  confirmation than plain Unpair, since it can leave a live orphaned token on the phone that
  only the phone's own UI can clean up. Can't perform the unsynced-clips check at all (the
  premise is the phone may be unreachable), so the confirmation copy should say so explicitly
  rather than silently omitting the warning.
- **Already-synced clips are kept after unpairing** (either kind) — they stay visible in the
  aggregate Gallery as historical footage from a now-unpaired phone; only the pairing/sync
  relationship ends.

## Screens

All five have an interactive HTML/CSS/JS mock at
[../design/ux-mocks/controller-ux-mock.html](../design/ux-mocks/controller-ux-mock.html) — open it in a
browser to click through the actual flows described below (Fleet's bulk actions, Phone
detail's live-preview gating and calibration lookup, Gallery/Trash's master-detail viewer,
Add phone's two-field pairing) rather than re-deriving them from prose alone.

Navigation: left rail — **Fleet** (home), **Gallery** (aggregate), **Trash**, **Add phone**.
No per-phone rail item; phones are reached by drilling into Fleet.

- **Fleet** — aggregate disk-usage stat in the screen header/top bar, above a card grid, one
  card per paired phone. At-a-glance card content is deliberately minimal: phone name + phone
  status + a **battery icon** (no thumbnail, no per-phone disk usage on the card — those live
  in Phone detail), where **unreachable is itself a status value** (alongside e.g.
  recording/standby), not a separate error state layered on top. An unreachable phone has no
  live battery reading, so its battery icon is omitted rather than showing a stale number.
  Click a card → Phone detail.
  - **Bulk arm** and **bulk stand-down** are one-click, fleet-wide actions — no per-phone
    selection step. Bulk arm (applies to every phone not currently recording): cancels any
    in-progress phone action first (live preview, calibration, etc.), returns each phone to
    its home screen, then starts recording. Bulk stand-down (applies to every phone currently
    recording): returns each phone to its home screen, then puts it into standby.
  - The finer-grained sync distinction — **unreachable vs. syncing vs. up to date** — is not
    shown on the Fleet card; it lives in Phone detail (the drill-down), since it's more detail
    than an at-a-glance fleet overview needs.
- **Phone detail** — header (name/model/battery/connection), sync status (unreachable /
  syncing / up to date), live/config section mirroring the phone's Preview+Configuration
  screens, this phone's clips (filtered Gallery view), rename/re-calibrate/unpair/force-unpair
  actions.
  - **Live preview is gated behind an explicit action**, never auto-started just by opening the
    page: live and record are mutually-exclusive phone modes (starting live preview tears down
    the recording pipeline), so silently switching a phone that's currently recording the
    moment its card is clicked would be a surprising, disruptive side effect of navigation. A
    phone that isn't recording can still start live preview immediately since there's no
    conflict to warn about.
  - **Adjusters (zoom/focus/scene/brightness/shadows/wb) keep the phone's one-at-a-time
    switcher-strip interaction**, not an all-at-once panel — deliberately consistent with the
    phone app's own Preview screen rather than redesigned for desktop's extra space.
  - **Calibration is summarized, not detailed**: Phone detail shows only overall progress (e.g.
    "3/14 checks completed"), not the per-capability chip breakdown or live per-step probe
    view the phone app's own Calibrate screen has. Re-run is still an available action.
  - **Calibration is looked up by manufacturer+model, not stored per-phone** — see "Calibration
    data model" above. A phone whose model has no cached data shows a distinct "Calibration
    needed" state instead of a progress readout.
- **Gallery** — **master-detail layout**, not a lightbox/modal: a persistent viewer+scrubber
  pane alongside the clip grid, not an overlay that closes back to the grid. Chosen specifically
  so that on **autoplay** (clip ends → next clip plays automatically) the grid's selection
  highlight can advance in place and stay visible, which a modal would make awkward. Grid is
  **grouped by day** (day-header sections, like the phone app's own Gallery) rather than one
  continuous newest-first feed. **Filterable by phone via a chip row** (All + one chip per
  phone name) across the top. Per-clip: Trash. Already-synced clips from an unpaired or
  currently-unreachable phone remain visible here (see Pairing & unpairing above).
- **Trash** — reuses **Gallery's master-detail layout** (persistent viewer+scrubber pane next
  to a day-grouped grid, filtered to trashed clips only), but **without autoplay-on-end** —
  playback simply stops at the end of a clip rather than advancing, since Trash is for
  reviewing what you're about to restore or discard, not watching a feed. No phone filter chip
  row (wasn't asked for, and this wasn't in the original screen spec either). Per-clip:
  **Restore** and **Delete** as two icon buttons in the viewer's bottom-right corner (same
  translucent-fab treatment as Gallery's Trash button). Bulk **Empty trash** sits in the
  header and is **gated behind a confirm step naming the clip count** ("Permanently delete N
  clips? This can't be undone.") — consistent with how Force unpair treats other irreversible
  actions in this app, and stronger than plain Delete since it's an unqualified all-of-trash
  action rather than one clip at a time.
- **Add phone** — paste view is the default; a text link ("Or scan a QR code from the phone →")
  switches to a webcam viewfinder instead, with its own link back — an asymmetric
  primary/secondary framing rather than two equal-weight tabs.
  - **Paste view has two fields, not one**: "Phone address" (IP/hostname) and "Invite code" —
    because a bare code can't be resolved to a phone on its own (see Pairing above). Pasting a
    full invite URL into *either* field auto-fills both by parsing it, so someone who copies
    the whole URL never has to deal with two fields in practice; someone reading the short code
    off the phone's screen does need to also enter its address (shown on the phone's own Home
    screen) since that path genuinely doesn't carry it.
  - **QR view needs neither field**: the scanned QR carries the same full URL, so it resolves
    straight to address + code without asking for either.
  - **Both entry methods feed the same pairing outcome**: a brief "Connecting to phone…"
    spinner, then either an inline error — distinguishing an unreachable address ("Could not
    reach 192.168.1.87 on the network") from a bad invite ("Invalid or expired invite code"),
    since those are different failures a user would act on differently — or a success card
    showing the newly paired phone's name + manufacturer/model, with actions to jump straight
    to Fleet or pair another phone.
  - The controller doesn't ask the user to name the phone — identity comes from the phone
    itself (`GET /api/config`'s `deviceName`, `GET /api/device`), consistent with pairing being
    invite-consumption, not phone setup.

## Explicit decisions made along the way (worth not re-litigating)

- Tray-app model, not window-first — background sync must not depend on a window being open.
- SQLite as local source of truth; UI never re-derives gallery state live from phones.
- Trash/purge/tombstone is a three-state model, not a simple deleted flag — see Data model.
- Manual permanent-delete purges the file immediately but must not drop the DB row until
  phone-side eviction is confirmed, or a resync could bring the clip back.
- Self-unpair required adding one new phone-API route (`DELETE /api/pair`), since it's the
  one controller-registry action where the remote party is the one who needs it.
- Force unpair is a deliberately separate, more strongly-confirmed action from plain Unpair.
- Teal/dark visual language is inherited as a placeholder, not a settled decision.
- Fleet card is deliberately minimal (name + status only); sync-state granularity
  (unreachable/syncing/up to date) is pushed down to Phone detail rather than crowding the card.
- Bulk arm and bulk stand-down are defined as full-sequence actions, not simple mode toggles:
  arm cancels whatever the phone is doing, returns it home, then starts recording; stand-down
  returns it home, then goes to standby.
- Calibration is a manufacturer+model-keyed shared resource on the controller, not a per-phone
  record — see Calibration data model. Resolves the device-capability-database open item
  carried over from phone-http-api.md.

## Open items for implementation

- Visual language pass — current teal skin is a placeholder.
- Cadence/backoff strategy for the background sync loop and the eviction-probe loop (how
  often, how they behave against an unreachable phone).
