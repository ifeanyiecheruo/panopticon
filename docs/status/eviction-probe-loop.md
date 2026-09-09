# Plan: eviction-probe / tombstone-cleanup loop

**Side:** controller · **Size:** medium

## Goal

Drop purged clips' DB rows (and their segment tombstones) once there is evidence the phone can
no longer be a source for them, so tombstones don't accumulate forever.

## Context

Per [`../design/decisions/0006-segments-clips-and-tombstones.md`](../design/decisions/0006-segments-clips-and-tombstones.md):
a `purged` clip keeps its DB row so a resync (cursor reset / DB rebuild) can't redownload a
discarded clip. The row can only be dropped once a probe against `/api/segments/:filename/file`
returns `404` — proof the phone's ring buffer evicted it. `DeleteClipPermanently` / `EmptyTrash`
already delete the files and mark the clip `purged`; nothing probes.

## Approach

- A background loop (own goroutine, alongside the syncer), iterating **trashed + purged** clips
  only — active clips are safely archived and kept regardless.
- Per clip, probe each segment via a HEAD or 1-byte `Range` GET on `/api/segments/:filename/file`
  (`phoneapi` — reuse `ErrEvicted`). When **every** segment of a purged clip probes `404`, delete
  the clip row + its segment rows in one transaction.
- Cadence + backoff shared with the sync loop's strategy — see
  [`sync-cadence-and-backoff.md`](sync-cadence-and-backoff.md). Skip a phone that's currently
  unreachable; don't hammer.
- Trashed clips are probed only to keep the "is this still on the phone?" signal fresh for the
  UI; they are never auto-dropped (the user still has Restore).

## Affected files

`controller/internal/syncer/` (or a new `internal/evictprobe/`), `internal/dbstore/clips.go` +
`segments.go` (a "delete purged clip + tombstones" query), `internal/phoneapi/client.go` (a HEAD
probe helper if not present), `main.go` (start the loop).

## Testing

`internal/integrationtest`: a purged clip whose segments 404 gets its rows dropped; a purged
clip whose segments still 200 is left; an unreachable phone is skipped without error.

## Acceptance

After deleting a clip and letting the phone evict its segments, the `clips` + `segments` rows
for it are gone; `git`-visible DB size stops growing with churn.

## Not in scope

Changing when files are deleted (still immediate on manual Delete). Aging out `active` clips
(the controller never does this).
