# Plan: single-element gallery playback (fMP4 remux + MSE)

**Side:** controller · **Size:** medium · **Status:** implemented on
`investigate/single-player-playback`; awaiting manual checks in the Wails app (below)

## Goal

Play a clip, and a play-through across clips, through **one** `<video>` fed by Media Source
Extensions instead of the two-element swap `ClipPlayer` used to do: gapless segment and clip
boundaries, and one element for fullscreen, scrubbing and keyboard control.

## Why the swap had to go

The old player kept a second `<video>` preloaded and swapped which one was visible at each
boundary. Anything attached to *an element* saw the swap (native fullscreen, the seek bar), and
a seek into another segment was a `src` change + `load()` on the visible element. Scrubbing raced
the swap: the idle slot's preload, a `loadedmetadata` seek and an `ended` from the old slot could
all land in either order.

## What was built

1. **Remux per request, not at sync.** `internal/remux` (pure Go, no dependency) reads a
   segment's `moov` and lays it out as fragmented MP4: an init segment, then one `moof`+`mdat`
   per GOP (the phone writes a keyframe every 30 frames, about once a second). Samples are
   copied byte for byte. The phone's segments are as simple as an MP4 gets (one H.264 track, no
   `ctts`, no edit list, a single chunk), so a full remux takes about 10ms per segment. That
   made it possible to drop the original plan's sync-time step, `segments` migration and
   backfill: the archive stays as it was, with no second copy on disk.
2. **`GET /fmp4/<phoneId>/<file>`** (`controller/fmp4.go`) serves the result, with the codec
   string in `X-Codec`. `415` means not H.264, `500` means the file can't be parsed.
3. **`SegmentFeeder`** (`frontend/src/lib/segmentFeeder.ts`) owns one `MediaSource` and one
   `SourceBuffer` in `sequence` mode. Every buffer operation runs in one serialized loop that
   re-reads the playhead between operations. Seeks and timeline changes only record what they
   want. The buffer is always **one contiguous run of whole segments**: a seek outside the run
   clears it and starts a new run at the target. That rules out gaps, and so stalls. It reads
   15s ahead, evicts what is more than 10s behind, and on `QuotaExceededError` evicts back to
   the playing segment and retries once.
4. **`ClipPlayer`** keeps its props, so Gallery and Trash are unchanged. The timeline is the
   clip's segments followed by `nextClip`'s. Playback rolls into `nextClip` on the same stream,
   and `onFinished` fires at the crossing. When `nextClip` changes after it was buffered, the
   stale tail is removed. Picking the queued `nextClip` by hand seeks to it, and any other clip
   starts a fresh stream.

## Verified

- `go test ./...`: remux unit tests on a synthesized MediaMuxer-shaped file (one or two chunks,
  `stss`, `ctts`, a rotation matrix). They check every sample's bytes, duration, sync flag and
  decode time, and a test covers the handler's path validation.
- In headless Edge (Chromium MSE, the same engine as WebView2), on 9 real archived segments
  across 3 clips:
  - One buffered range throughout a play-through. The next clip starts at the previous clip's
    real end, to the millisecond.
  - `ended` fires at the stream's true end.
  - Seeks into unbuffered segments, and a burst of 40 retargets, settle on the last target.
  - Swapping `nextClip` after it was buffered plays into the new clip.
  - At 1x and 2x: no `waiting` events and 0 dropped frames across segment and clip boundaries.
    At 8x, headless software decode falls behind (stalls at `readyState` 2 with 20s+ buffered),
    which is decoder throughput, not a gap.

## Still to check (manual, in the Wails app)

- A multi-segment clip and a play-through across ≥3 clips, windowed and fullscreen.
- Scrubbing across segment boundaries; arrow keys to the next and previous clip while playing.
- A play-through across a resolution change (a 4K clip next to a 576p one, or two phones).
- 8x on real hardware decode.
- An hour-long play-through without memory growth.
- Trash's viewer.

## Not in scope

Re-encoding or transcoding; changing the phone's recording format; HLS/DASH manifests. The
`/fmp4/` output could feed those later.
