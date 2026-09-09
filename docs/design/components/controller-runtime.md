# controller — runtime — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that hosts the controller: a single Go binary with a tray presence, an
embedded webview window, a single-instance guard, and the Wails bindings the frontend calls.

### 1.2 Scope

Covers `main.go`, `app.go`, `internal/trayapp/`, `internal/singleinstance/`, `internal/appdirs/`,
`liveproxy.go`. The subsystems the bindings orchestrate have their own descriptions (§1.6).

### 1.3 Context

`App` is the frontend's only entry into Go. Everything else in `controller/` is either invoked
by `App` methods or run on a background goroutine started by `main.go` (the syncer, the tray).

### 1.4 Definitions

| Term | Meaning |
|---|---|
| Wails binding | an exported `App` method exposed to the frontend as `window.go.main.App.*` |
| tray-app model | the app's persistent presence is a system-tray icon, not a window; background work runs window-open or not |
| asset-server middleware | Wails' HTTP layer serving frontend assets, which `liveproxy` and `/archive/` hook into |
| app-data dir | the resolved location of `data/` (SQLite + lock) and `archive/<phoneId>/` |

System-wide terms: architecture §1.3.

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[CGO](../architecture.md#acr-cgo),
[SPA](../architecture.md#acr-spa),
[HLS](../architecture.md#acr-hls),
[CORS](../architecture.md#acr-cors),
[DB](../architecture.md#acr-db),
[OS](../architecture.md#acr-os),
[PID](../architecture.md#acr-pid),
[cwd](../architecture.md#acr-cwd).

### 1.6 References

- [`../decisions/0011-controller-runtime-and-state.md`](../decisions/0011-controller-runtime-and-state.md),
  [`0001`](../decisions/0001-repository-and-build.md).
- App-data dir plan:
  [`../../status/controller-app-data-dir.md`](../../status/controller-app-data-dir.md).
- Peers: [`controller-state-store.md`](controller-state-store.md),
  [`controller-sync.md`](controller-sync.md),
  [`controller-pairing.md`](controller-pairing.md),
  [`controller-live-and-camera.md`](controller-live-and-camera.md).

## 2. Design overview

`main.go` runs `wails.Run()` and starts the syncer and tray on their own goroutines. `App`
exposes a thin binding surface that orchestrates the `internal/*` packages. `liveproxy` and the
single-instance lock are the two pieces of host plumbing that don't belong to any feature
package.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| `App` (`app.go`) | Wails-bound struct | Exported methods → `window.go.main.App.*`: `ListPhones`, `GetPhoneDetail`, `SetRecording`, `SetConfig`, `StartLivePreview` / `StopLivePreview`, `ListCameras` / `SetActiveCamera` / `GetCameraControls` / `SetCameraControls` / `ComputeEffectiveRect`, `UnpairPhone` / `ForceUnpairPhone`, `RequestQuit`, `ParseInviteURL`, calibration re-run. Thin orchestration over `internal/*`. |
| `main.go` | entrypoint | `wails.Run()` + config (`syncPollInterval`, 30s). Starts the syncer and tray goroutines. |
| `trayapp` | tray menu | `getlantern/systray` Open/Quit; its own goroutine alongside the Wails message loop. Closing the window hides it; only Quit (or `RequestQuit`) exits. |
| `singleinstance` | lock | Windows: an exclusive `CreateFile` share-mode handle (OS-released on crash, unlike a PID file). Second launch prints a message and exits. Other OSes: a best-effort PID file. |
| `appdirs` | path resolver | `data/` and `archive/<phoneId>/`, resolved **relative to cwd** — dev convenience. |
| `liveproxy` | asset-server handler | Proxies `GET /live/<phoneID>/live.m3u8` and `.../live-<n>.ts` from the phone with the stored bearer token, so hls.js fetches same-origin (token server-side, no CORS/mixed-content). Playlist segment URIs are relative — no rewriting. |

### 3.2 Dependencies

`Store`, `syncer`, `pairing`, `unpair`, `calibration`, `phoneapi`, and the Wails / systray
runtime.

### 3.3 Interfaces

- **Provided:** the Wails binding surface to the frontend; `/live/<phoneID>/*` and `/archive/*`
  on the asset server.
- **Required:** the `internal/*` packages in §3.2.

### 3.4 Data

- No data of its own. `appdirs` computes paths; the SQLite file and the archive tree belong to
  `controller-state-store` and `controller-sync` respectively.

### 3.5 Processing and behaviour

- The tray and syncer goroutines outlive any window; closing the window only hides it.
- A transient WebView2 `80080005` on the very first launch after a fresh build is a known race,
  not a regression.
- Running the binary from the wrong cwd creates a stray `data/`/`archive/` there (gitignored,
  harmless).

## 4. Design rationale and decisions

- **Tray-app model, single binary, embedded webview** —
  [`0011`](../decisions/0011-controller-runtime-and-state.md): "gallery always almost
  up to date" is only true if sync is independent of the window.
- **Exclusive-file-handle single-instance lock** — OS-released on crash, unlike a PID file that
  can be left stale.
- **`appdirs` cwd-relative (for now)** — a deliberate dev-convenience simplification, flagged
  for a fix to a per-OS path
  ([`../../status/controller-app-data-dir.md`](../../status/controller-app-data-dir.md)).
- **`liveproxy` keeps the token server-side** — see
  [`0007`](../decisions/0007-live-preview-plain-hls.md); the scoped `/live/*` token is
  deferred.
