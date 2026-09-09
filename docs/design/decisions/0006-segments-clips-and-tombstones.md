# 0006 — Segments, clips, and the tombstone model

**Status:** Accepted · implemented, except the eviction-probe loop
([`../../status/eviction-probe-loop.md`](../../status/eviction-probe-loop.md)).

## Context

A phone recording for weeks accumulates thousands of ~10s files. Nobody wants a gallery item per
fragment. And the phone's ring buffer will eventually evict old files — the controller must not
lose footage it hasn't archived, nor redownload footage the user already discarded.

## Decision

### The phone speaks segments; the controller assembles clips

A **segment** is one recorded file in the ring buffer — the phone's only unit. A **clip** is a
contiguous run of segments recorded back-to-back during one motion event — the user-facing
gallery item, assembled **by the controller** on sync by time gap. The phone HTTP API has no
clip route (`/api/segments…`, response key `"segments"`). Grouping gap: `GroupingGapMs` /
`SegmentGrouping.GAP_MS` = **500 ms** (the phone rolls segment files gaplessly, so contiguous
segments are within a few ms).

Controller storage: a `segments` table (one row per file, each with a `clip_id`) and a `clips`
group table (lifecycle + aggregate span/size/count). Migration `002` + a one-time
`RegroupUnassignedSegments` backfill grouped pre-existing rows.

### Three-state clip lifecycle

| State | On disk | Visible | Meaning |
|---|---|---|---|
| `active` | file + thumbnail | Gallery | normal synced clip |
| `trashed` | file + thumbnail | Trash bin | controller-local only; nothing sent to the phone |
| `purged` | deleted | nowhere | DB row (tombstone) kept |

### A purged row keeps its tombstone until phone-side eviction is confirmed

Manual Delete / Empty trash remove the files **immediately** but cannot drop the DB row: a
resync (after a cursor reset or DB rebuild) would otherwise redownload a discarded clip. The
tombstone is deleted only once a probe against `/api/segments/:filename/file` returns `404` —
proof the phone can no longer be a source. The probe loop only needs to iterate trashed +
purged clips.

### The controller never ages clips out on its own

`active` clips are kept regardless of what the phone's ring buffer does.

### Favoriting is removed

No favorite field on segments, no favorite route.

## Consequences

- Both galleries (controller Preact + phone Compose) show one item per clip; trash/restore/delete
  act on the whole clip.
- Verified: migration `002` + backfill collapsed the real Pixel 6 archive's 15 contiguous
  segments into one clip.
- Until the eviction-probe loop is built, purged tombstones accumulate forever — harmless but
  unbounded.
