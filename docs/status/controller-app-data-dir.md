# Plan: per-OS app-data directory for the controller

**Side:** controller · **Size:** small

## Goal

Resolve `panopticon-data/` and `panopticon-archive/` to a stable per-OS application-data location, not a path relative
to the current working directory.

## Context

[`../design/decisions/0011-controller-runtime-and-state.md`](../design/decisions/0011-controller-runtime-and-state.md):
`internal/appdirs` currently resolves `panopticon-data/` (SQLite + lock) and `panopticon-archive/<phoneId>/` relative
to the launch directory — convenient for `wails dev` / manual runs, but it means running the
binary from the wrong cwd (e.g. the root Makefile's `run-controller`, launched from the repo
root) creates a stray `panopticon-data/`/`panopticon-archive/` at the repo root. Harmless (gitignored) but a packaged
installer needs a fixed location.

## Approach

- `appdirs` gets a real resolver: `os.UserConfigDir()` (or `os.UserCacheDir()` for `panopticon-archive/`)
  + an app subdirectory (`Panopticon/`), created on first run.
- Keep a **dev override** — an env var or a `-data-dir` flag — so `wails dev` and tests can pin
  it to a scratch path; the Makefile's `run-controller` sets it.
- **Migration** — on startup, if the new location is empty but a `panopticon-data/`/`panopticon-archive/` exists next
  to the binary, move it (or log a clear one-time "found old data at X, move it to Y" message).
  **Moving the directories is not enough:** `segments.local_path` and `segments.thumbnail_path`
  store **absolute** paths, so every row has to be rewritten to the new root or the whole Gallery
  silently stops resolving (thumbnails blank, playback 404s). Renaming these dirs from
  `data/`/`archive/` needed exactly that - a `replace()` over ~4.3k rows. Rows for `purged`
  clips point at files that are legitimately gone; don't treat those as migration failures.
  Storing paths relative to `ArchiveDir` instead would make this and any future move a no-op.

## Affected files

`controller/internal/appdirs/appdirs.go`, `main.go` (flag/env wiring), root `Makefile`
(`run-controller` sets the dev override), `.gitignore` (the cwd `panopticon-data/`/`panopticon-archive/` ignore can
stay for dev).

## Testing

`appdirs` unit test: default resolves under `os.UserConfigDir()`; the override wins when set.
Manual: run the built binary from two different directories, confirm one shared data dir.

## Acceptance

The built controller uses one fixed data directory regardless of launch cwd; `wails dev` still
uses a local scratch dir; no stray `panopticon-data/`/`panopticon-archive/` at the repo root after `make
run-controller`.

## Not in scope

Multi-profile / multi-instance data dirs; encrypting the DB.
