# controller — sync — software design description

## 1. Introduction

### 1.1 Purpose

Describe the component that keeps the local archive "always almost up to date": per phone, pull
new segments + thumbnails, assign each to a clip, and advance the sync cursor — crash-safely,
independent of the UI.

### 1.2 Scope

Covers `internal/syncer/`, the grouping helper in `internal/dbstore/regroup.go`, and the
`internal/integrationtest/` cases that exercise it (`pairing_sync_test.go`, `TestSyncLoop_*`).

### 1.3 Context

Started by `main.go` on its own goroutine; runs whether or not a window is open. Reads phones
from `Store`, calls the phone via `phoneapi`, writes segments/clips back to `Store` and files to
the archive tree.

### 1.4 Definitions

| Term | Meaning |
|---|---|
| poll loop | one per paired phone; polls `GET /api/segments?since=<cursor>` on an interval |
| grouping | assigning a downloaded segment to a clip by time gap |
| `GroupingGapMs` | 500 ms — the max gap between contiguous segments (the phone rolls files gaplessly) |
| cursor advance | moving the per-phone `since` watermark, only after a segment is durably written, assigned, and indexed |
| evicted-segment skip | a `404` on download treated as "already evicted", not an error |

### 1.5 Acronyms and abbreviations

Linked to their row in [`architecture.md` §1.4](../architecture.md#14-acronyms-and-abbreviations):
[DB](../architecture.md#acr-db),
[HTTP](../architecture.md#acr-http).

### 1.6 References

- [`../decisions/0006-segments-clips-and-tombstones.md`](../decisions/0006-segments-clips-and-tombstones.md),
  [`0011`](../decisions/0011-controller-runtime-and-state.md).
- [`../http-api.md`](../http-api.md) — `/api/segments`.
- Plans: [`../../status/sync-cadence-and-backoff.md`](../../status/sync-cadence-and-backoff.md),
  [`../../status/eviction-probe-loop.md`](../../status/eviction-probe-loop.md).

## 2. Design overview

Per phone: a goroutine that polls the segment delta, downloads each new file + thumbnail,
assigns it to the phone's open clip (or opens a new one) by time gap, and advances the cursor
only once that's durable. One slow or dead phone can't wedge the others — each has its own
goroutine and its own timeouts.

## 3. Detailed design

### 3.1 Design entities

| Entity | Type | Responsibility |
|---|---|---|
| per-phone poll loop | goroutine | Every `syncPollInterval` (`main.go`, 30s), `GET /api/segments?since=<cursor>`; download each new segment's file + thumbnail. Started/stopped by `syncer.reconcile` as phones are paired/unpaired. |
| grouping | function | As each segment lands: extend the phone's open clip if the segment starts within `dbstore.GroupingGapMs` (500 ms) of that clip's end, else open a new clip. |
| cursor advance | — | Only after a segment is durably written to `archive/<phoneId>/`, assigned to a clip, and indexed. Idempotent / crash-safe. |
| eviction handling | — | A `404` on download is a normal "already evicted" skip, not an error. |

### 3.2 Dependencies

- `phoneapi` — `GET /api/segments`, `.../file`, `.../thumbnail`.
- `Store` — `segments`, `clips`, the per-phone cursor.
- The archive directory (from `appdirs`).

### 3.3 Interfaces

- **Provided:** none direct — it writes `Store` and the archive; the UI reads those.
- **Required:** `phoneapi`, `Store`, the archive tree.

### 3.4 Data

- Writes `segments` and `clips` rows and advances `phones.sync_cursor`.
- Writes `archive/<phoneId>/<filename>` and its thumbnail.

### 3.5 Processing and behaviour

- Grouping is deterministic given segment timestamps; covered by `regroup_test.go`,
  `SegmentGroupingTest.kt` (phone side), and `TestSyncLoop_GroupsContiguousSegments` /
  `_SplitsOnGap`.
- **Verified:** real pairing + real file downloads landing in `archive/<phoneId>/` + real
  SQLite rows, against both `mock-phone` and the real Pixel 6.

## 4. Design rationale and decisions

- **Background loop independent of the UI** —
  [`0011`](../decisions/0011-controller-runtime-and-state.md): the product promise
  depends on syncing whether or not a window is open.
- **Controller assembles clips from segments by time gap** —
  [`0006`](../decisions/0006-segments-clips-and-tombstones.md): the phone has no clip
  concept; a tight 500 ms gap is safe because rotation is gapless.
- **Cursor advances only after durable + assigned + indexed** — crash-safe and idempotent; a
  half-written step can't corrupt state.
- **Known gaps:** the poll interval is fixed at process start with no backoff against a
  long-unreachable phone
  ([`../../status/sync-cadence-and-backoff.md`](../../status/sync-cadence-and-backoff.md)), and
  there is no eviction-probe loop, so purged tombstones are never dropped
  ([`../../status/eviction-probe-loop.md`](../../status/eviction-probe-loop.md)).
