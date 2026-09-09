# 0011 — Controller runtime and local state

**Status:** Accepted · implemented. Component specs:
[`controller-runtime.md`](../components/controller-runtime.md),
[`controller-state-store.md`](../components/controller-state-store.md).

## Context

The product promise is "the gallery is always almost up to date" — which is only true if syncing
happens whether or not the user has a window open. The controller also must not depend on a
server, and must survive crashes without corrupting local state.

## Decision

### Tray-app model, not window-first

Model: Syncthing / Tailscale. The tray icon is the persistent presence; a background sync loop
runs regardless of window state. Closing the window hides it; only the tray's "Quit" (or a
frontend affordance calling `RequestQuit`) exits.

### Single Go binary, embedded OS webview

No Node/Electron/Python runtime dependency. UI is an embedded native webview (Wails: WebView2 /
WebKitGTK / WKWebView) rendering the Preact frontend — not a localhost tab in the user's
browser. Still one binary, no bundled runtime.

### One embedded SQLite DB as the local source of truth

`modernc.org/sqlite` (pure Go, no CGO). Holds the controller's keypair, paired phones, the
segment/clip index, and the model-keyed calibration store. **The UI is a read view over this
DB** — it does not re-derive gallery state from live phone calls on each render. Live
`GET /api/status` is fetched for the Fleet/Phone-detail *status* line only.

### Single-instance lock via an exclusive file handle

Windows: an exclusive `CreateFile` share-mode handle (auto-released by the OS on crash, unlike a
plain PID file). A second launch prints a message and exits.

### App-data layout resolved relative to cwd (for now)

`internal/appdirs` resolves `data/`/`archive/` relative to the launch directory — convenient for
`wails dev` / manual runs. A packaged installer should point this at a stable per-OS path
(`os.UserConfigDir()`); deferred
([`../../status/controller-app-data-dir.md`](../../status/controller-app-data-dir.md)).

## Consequences

- Sync progress is decoupled from the UI; opening a window shows already-current data.
- A crash can't corrupt a half-written sync step — the cursor only advances after a segment is
  durably written, assigned, and indexed.
- Running the binary from the wrong cwd creates a stray `data/`/`archive/` there (gitignored,
  harmless, worth cleaning up).
