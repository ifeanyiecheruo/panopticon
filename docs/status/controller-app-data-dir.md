# Plan: per-OS app-data directory for the controller

**Side:** controller · **Size:** small

## Goal

Resolve `data/` and `archive/` to a stable per-OS application-data location, not a path relative
to the current working directory.

## Context

[`../design/decisions/0011-controller-runtime-and-state.md`](../design/decisions/0011-controller-runtime-and-state.md):
`internal/appdirs` currently resolves `data/` (SQLite + lock) and `archive/<phoneId>/` relative
to the launch directory — convenient for `wails dev` / manual runs, but it means running the
binary from the wrong cwd (e.g. the root Makefile's `run-controller`, launched from the repo
root) creates a stray `data/`/`archive/` at the repo root. Harmless (gitignored) but a packaged
installer needs a fixed location.

## Approach

- `appdirs` gets a real resolver: `os.UserConfigDir()` (or `os.UserCacheDir()` for `archive/`)
  + an app subdirectory (`Panopticon/`), created on first run.
- Keep a **dev override** — an env var or a `-data-dir` flag — so `wails dev` and tests can pin
  it to a scratch path; the Makefile's `run-controller` sets it.
- **Migration** — on startup, if the new location is empty but a `data/`/`archive/` exists next
  to the binary, move it (or log a clear one-time "found old data at X, move it to Y" message).

## Affected files

`controller/internal/appdirs/appdirs.go`, `main.go` (flag/env wiring), root `Makefile`
(`run-controller` sets the dev override), `.gitignore` (the cwd `data/`/`archive/` ignore can
stay for dev).

## Testing

`appdirs` unit test: default resolves under `os.UserConfigDir()`; the override wins when set.
Manual: run the built binary from two different directories, confirm one shared data dir.

## Acceptance

The built controller uses one fixed data directory regardless of launch cwd; `wails dev` still
uses a local scratch dir; no stray `data/`/`archive/` at the repo root after `make
run-controller`.

## Not in scope

Multi-profile / multi-instance data dirs; encrypting the DB.
