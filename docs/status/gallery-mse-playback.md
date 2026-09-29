# Plan: single-element gallery playback (fMP4 remux at sync + MSE)

**Side:** controller · **Size:** medium–large

## Goal

Play a clip, and a play-through across clips, through **one** `<video>` fed by Media Source
Extensions instead of the two-element swap in `ClipPlayer`: truly gapless segment and clip
boundaries, one element for fullscreen / scrubbing / keyboard control, and a seek bar that spans
the whole clip rather than one segment.

## Context

`ClipPlayer` hides segment and clip boundaries by keeping a second `<video>` preloaded and
swapping which one is visible. It works, but everything that attaches to *an element* sees the
swap: native fullscreen broke across it (fixed 2026-09-28 by fullscreening the wrapper instead),
and the seek bar and time only ever cover the current segment.

MSE needs **fragmented MP4**: an init segment (`ftyp` + `moov` with `mvex`, no samples) and media
segments (`moof` + `mdat`). The archive holds what Android's `MediaMuxer` writes - progressive
MP4, `ftyp` `moov` `free` `mdat` (checked on a 2026-09-28 Pixel 6 segment) - which MSE rejects.
Converting is a **remux**: the same H.264 samples re-wrapped, no re-encode, no quality loss.

Where the remux happens was decided: **on the controller, once, at sync time**, in pure Go - not
ffmpeg (an extra binary to ship), not in the browser (a vendored JS muxer re-running every
play), not on the phone (`MediaMuxer` can't write fMP4; a hand-rolled muxer would change the
recording format that sync, thumbnails and downloads rely on).

## Approach

1. **Remux in the syncer.** After `downloadToFile` succeeds in `internal/syncer`, remux the
   segment into `<filename>.fmp4` alongside it using a pure-Go MP4 library
   ([`github.com/Eyevinn/mp4ff`](https://github.com/Eyevinn/mp4ff) - has progressive→fragmented
   support; confirm it handles `MediaMuxer`'s output, incl. the `free` box and edit lists).
   One fragment per GOP (the phone writes a keyframe every second) so seeking stays cheap.
   Best-effort like the thumbnail: a failed remux logs and leaves the segment playable by the
   old path, never blocks archiving. Keep the original `.mp4` - downloads and thumbnails stay
   as they are.
2. **Backfill.** A one-shot pass at startup (or on first gallery open) that remuxes archived
   segments with no `.fmp4` yet, so existing clips get the new player too. Idempotent, bounded
   concurrency.
3. **Expose it.** `SegmentView` gains `fmp4Url` (empty when missing) plus the codec string
   (`avc1.xxxxxx`, from the `avcC` box) that `MediaSource.isTypeSupported` / `addSourceBuffer`
   need. Add both at sync time to the `segments` table (migration) rather than re-parsing on
   every `ListClips`.
4. **New player.** `ClipPlayer` becomes one `<video>` + one `MediaSource` + one `SourceBuffer`
   in `sequence` mode or with explicit `timestampOffset` per segment, fed from a small queue:
   append segment *n+1* while *n* plays, then the next clip's segments on auto-advance.
   - **Codec or resolution changes** between clips (4K vs 576p recordings, different phones):
     append the new init segment; use `SourceBuffer.changeType()` if the codec string differs.
   - **Memory:** `remove()` played ranges older than ~30s; handle `QuotaExceededError` by
     evicting and retrying.
   - **Seek bar:** duration = sum of the clip's segments; seek maps to segment + offset.
   - **Fallback:** keep the two-element player for any clip with a segment lacking `fmp4Url`
     or when `MediaSource` is unavailable, until the backfill has run everywhere - then delete it.
5. **Trash** uses the same player without controls, as today.

## Affected files

`controller/internal/syncer/syncer.go` (+ a new `internal/remux` package), `internal/dbstore`
(segments migration + queries), `controller/app.go` (`SegmentView`), `controller/go.mod`,
`controller/frontend/src/components/ClipPlayer.tsx` (rewrite), `Gallery.tsx` / `Trash.tsx`
(prop changes only), regenerated `wailsjs` models,
[`../design/components/controller-live-and-camera.md`](../design/components/controller-live-and-camera.md)
or the gallery component spec once delivered.

## Testing

- `internal/remux` unit tests on real archived Pixel 6 and BLU G5 segments (checked in as small
  fixtures): output parses, sample count / durations / keyframe flags match the input, init
  segment carries `mvex`.
- Syncer integration test (the `integrationtest` mock phone): a synced segment gets its `.fmp4`;
  a corrupt one still archives with `fmp4Url` empty.
- Manual in the Wails app: a multi-segment clip and a play-through across ≥3 clips, windowed and
  fullscreen, with no visible gap; a play-through across a resolution change; seek across a
  segment boundary; an hour-long play-through without memory growth.

## Acceptance

Segment and clip boundaries play with no gap or black frame, windowed and fullscreen, on one
element; the seek bar spans the whole clip; switching resolution between clips works; existing
archives are backfilled; downloads and thumbnails unchanged.

## Not in scope

Re-encoding or transcoding; changing the phone's recording format; streaming clips straight
from the phone; server-side HLS/DASH manifests (a possible later step - the same fMP4 files
would serve them).
