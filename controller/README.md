# Panopticon controller

The desktop "controller" half of Panopticon: a Go/Wails system-tray app that pairs with
phone-app cameras, syncs their footage into a local aggregate gallery, and runs in the
background whether or not a window is open.

This is a **vertical slice**, not the full app — see "What's deferred" below. It implements
end to end: project scaffold, tray presence, embedded SQLite state, the Add-phone pairing
flow, a Fleet screen, a background sync loop, and a Gallery/Trash. It does not implement
live camera preview, calibration, or the eviction-probe loop.

Reference docs (read-only, live in the parent `panopticon` repo):
- `../docs/implementation/phone-http-api.md` — the phone-side HTTP contract this controller
  calls.
- `../docs/implementation/HANDOFF-controller-ux.md` — the settled product/UX design.
- `../docs/design/ux-mocks/controller-ux-mock.html` — the clickable UX mock this UI's visual
  language and interaction details are ported from.

## Running it

Build/install/run/test tasks live in the **root Makefile** (`../Makefile`), alongside the
phone-app's - run `make help` from the repo root for the full list. From there:

```
make build-controller     # wails build -> installer-src/bin/panopticon-controller.exe
make run-controller       # build + launch (tray icon; keeps running in the background)
make test-controller      # go test ./...
```

For hot-reload dev mode (not wired into the Makefile - it's an interactive foreground process,
not a one-shot task): `cd controller && wails dev`, which also opens
`http://localhost:34115` for calling bound Go methods from an ordinary browser tab.

On first launch it creates `data/` (SQLite DB + single-instance lock file) and `archive/`
(downloaded clips, one subdirectory per paired phone) next to wherever the binary is run
from. Both are gitignored — they're runtime state, not source.

A tray icon appears with "Open"/"Quit". Closing the window hides it (sync keeps running in
the background); only the tray's "Quit" (or a frontend quit affordance calling
`RequestQuit`) actually exits. Launching a second copy while one is already running prints
a message and exits immediately rather than running a duplicate instance.

## Testing without a real phone

[`../tools/mock-phone`](../tools/mock-phone) is a throwaway HTTP server (its own top-level
module, not part of the shipped app) implementing just enough of `phone-http-api.md` to exercise
pairing + sync:

```
go run ../tools/mock-phone/cmd/mockphone -addr :8091 -invite TESTCODE1234 -clips 3
```

`internal/integrationtest` has automated tests against an equivalent in-process fake server,
covering: successful pairing (with a real generated Ed25519 identity), invalid-invite vs.
unreachable-address error classification, clip download + cursor advancement, and the
evicted-clip-is-a-normal-skip behavior. Run with `go test ./...`.

This was also verified manually end to end: `wails dev`'s browser-bindings URL
(`http://localhost:34115`) driving the real Go backend against a running `mockphone`,
confirming real pairing, real file downloads landing in `archive/<phoneId>/`, real SQLite
rows, and the Gallery/Trash screens reading them back correctly.

## Architecture

- `main.go` / `app.go` — Wails entrypoint and the `App` struct whose exported methods are
  bound to the frontend (`window.go.main.App.*`).
- `internal/dbstore` — SQLite schema + typed queries (`modernc.org/sqlite`, a pure-Go driver
  — no CGO/gcc toolchain needed, same reasoning as the old prototype's `node:sqlite` choice).
  Tables: `identity` (controller's own Ed25519 keypair), `phones` (paired phones + bearer
  token + sync cursor), `clips` (active/trashed/purged lifecycle), and an as-yet-unused
  `calibration` table (schema reserved, not populated by this slice). Schema migrations are
  goose-managed (`internal/dbstore/schemas/db/*.sql`, applied automatically on every `Open()`)
  and query code is sqlc-generated (`internal/dbstore/queries/*.sql` → `queries/*.sql.go`) —
  see `internal/dbstore/README.md` for what's hand-written vs. generated and how to add a
  migration or a query. `Store` itself (`db.go`/`identity.go`/`phones.go`/`clips.go`) is the
  hand-written domain-shaped wrapper on top, unchanged from a caller's perspective.
- `internal/identity` — generates/persists the controller's Ed25519 keypair.
- `internal/phoneapi` — HTTP client for the phone routes, one explicit timeout per call
  type, sentinel errors (`ErrUnreachable`, `ErrUnauthorized`, `ErrInvalidInvite`,
  `ErrEvicted`) so callers can react distinctly rather than pattern-matching error strings.
- `internal/pairing` — the Add-phone flow: `POST /api/pair` with this controller's identity,
  classifying failures into the two distinct UI messages the handoff doc calls for.
- `internal/syncer` — per-phone background poll loop (`syncPollInterval` in `main.go`, 30s by
  default). Downloads new clips + thumbnails, advances the sync cursor only after each clip
  is durably written and indexed (crash-safe/idempotent), treats a 404 on download as a
  normal "already evicted" skip.
- `internal/trayapp` — `getlantern/systray` Open/Quit menu; runs on its own goroutine
  alongside `wails.Run()`'s own message loop.
- `internal/singleinstance` — Windows single-instance lock via an exclusive `CreateFile`
  share-mode handle (auto-released by the OS on crash, unlike a plain PID file).
- `internal/appdirs` — resolves the `data/`/`archive/` directory layout.
- `frontend/` — TypeScript + JSX on [Preact](https://preactjs.com/) (a ~3kb React-API-compatible
  library), built with Vite (`@preact/preset-vite`). Visual language ported from
  `controller-ux-mock.html`; `src/style.css` is still plain hand-written CSS, imported once from
  the entry point. Layout:
  - `src/main.tsx` — entry point, mounts `<App/>`.
  - `src/App.tsx` — top-level router/state: one `useState<AppState>` covering the current
    route plus each screen's in-flight selection, mirroring the original single-object
    `state` the vanilla router mutated. Every `navigate()` call bumps a `nonce` that's folded
    into the active screen's `key`, forcing a full unmount/remount (and refetch) — the same
    "wipe `main.innerHTML` and re-run `renderX()` from scratch on every navigation" behavior
    the vanilla version had, including the loading flash on every clip selection.
  - `src/screens/` — one component per screen: `Fleet.tsx`, `PhoneDetail.tsx`,
    `Gallery.tsx`, `Trash.tsx`, `AddPhone.tsx`.
  - `src/components/` — `Shell.tsx` (the left nav rail + main slot) and `ClipTiles.tsx`
    (the day-grouped clip grid shared by Gallery and Trash).
  - `src/api.ts` — thin typed re-export of the generated Wails bindings
    (`generated/wailsjs/go/main/App` + `generated/wailsjs/go/models`) under stable names, so a
    binding-shape change only needs a fix in one place.
  - `generated/wailsjs/` — Wails' own generated bindings (`wailsjsdir` in `wails.json` points
    here instead of the default `frontend/wailsjs`, precisely so generated code is visually and
    physically separated from hand-written code) - regenerated by `wails build`/`wails dev`,
    never hand-edited.
  - `src/lib/` — `format.ts` (byte/duration/status-label formatting), `clips.ts`
    (day-grouping + the `phoneId|filename` selection-key helper), `icons.tsx` (the inline
    SVG icon set, as small components instead of the old HTML-string map).
  - `src/types.ts` — shared `Route`/`AppState` types.

## What's deferred (out of scope for this slice)

Per the task's explicit scope cut — these are real gaps versus the full handoff doc, not
oversights:

- **Live preview / adjusters / calibration UI.** Phone detail shows only raw
  `GET /api/status` + `GET /api/config` JSON instead. The `calibration` DB table exists
  (schema settled) but nothing populates or reads it yet.
- **Eviction-probe loop.** The handoff doc's tombstone-cleanup mechanism (probing
  `/api/clips/:filename/file` on trashed/purged clips until a 404 confirms the phone's ring
  buffer evicted them, then dropping the DB row) is not implemented. `DeleteClipPermanently`
  and `EmptyTrash` purge the on-disk file immediately and mark the row `purged`, but purged
  tombstones accumulate forever rather than eventually being dropped.
- **Unpair / force-unpair.** No UI or bound method for either yet — `DELETE /api/pair` is
  implemented phone-side conceptually (see `phoneapi.Client.Unpair`) but nothing in `app.go`
  calls it, and there's no unsynced-clips warning check.
- **Bulk arm / bulk stand-down** (Fleet's fleet-wide record/standby actions) — not
  implemented; this slice's Fleet screen is read-only (status display + drill-down).
  Likewise no per-phone rename action.
- **QR-code pairing.** Only the paste-two-fields path is implemented (address + invite code,
  with full-URL autofill into either field). No webcam viewfinder.
- **Gallery autoplay-on-end / custom scrubber.** Uses a native `<video controls>` element
  instead of the mock's custom scrubber+playhead component. Native controls do give
  play/pause/seek/fullscreen, just not the mock's exact visual treatment or
  advance-to-next-clip-on-end behavior.
- **Per-phone app-data directory.** `data/`/`archive/` are resolved relative to the current
  working directory (`internal/appdirs`), not a proper per-OS app-data directory
  (`os.UserConfigDir()`), for development convenience. Fine for `wails dev`/manual runs; a
  packaged installer should point this at somewhere stable regardless of launch directory.
- **Live phone-status polling on Fleet/Phone-detail** is a synchronous
  `GET /api/status` call made when those screens render, not a continuously
  updating background poll — the screen doesn't auto-refresh while left open.

## Known rough edges worth knowing about

- `ListPhones`/`GetPhoneDetail`'s live-status fetch has no caching — every Fleet
  render re-hits every paired phone's `/api/status`. Fine for a handful of phones, would
  want debouncing/caching at real fleet scale.
- The sync loop's poll interval is fixed at process start (`syncPollInterval` in
  `main.go`); the handoff doc leaves cadence/backoff as an open item and this slice doesn't
  implement backoff against a phone that's been unreachable for a while — it just retries
  on the same fixed interval forever.
