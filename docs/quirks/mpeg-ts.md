# MPEG-TS muxing

Device context and the "Reconfirmed" / "Carried forward" convention: see [`README.md`](README.md).
This is the muxer the live HLS feed depends on; the streaming-side decisions live in
[`live-hls.md`](live-hls.md).

## `MediaMuxer` cannot emit MPEG-TS, so the TS muxer is hand-rolled
**What we assumed:** `android.media.MediaMuxer` could produce the `.ts` segments an HLS feed
needs, the same way it produces the `.mp4` segments the recording pipeline uses.
**Actually:** `MediaMuxer` supports only MP4 / WebM / 3GP / OGG output formats - there is no
MPEG-TS `OutputFormat`. Nothing in the platform muxes TS.
**What we do:** the TS muxer is hand-rolled - `phone-app`'s `camera/ts/TsMuxer.kt` writes one
PAT, one PMT, PCR on the video PID, and PES-wrapped Annex-B access units itself. It was ported
verbatim from the prototype (which had already built and debugged it against a real hls.js
client).
**Where:** `phone-app/app/src/main/kotlin/com/panopticon/phoneapp/camera/ts/TsMuxer.kt`;
consumed by `camera/LiveHlsRelay.kt` / `camera/LivePipeline.kt`.

## Every TS packet must be exactly 188 bytes, including padded PAT/PMT writes
**What bit the prototype:** a TS packet that isn't exactly 188 bytes (e.g. a PAT or PMT written
short, without stuffing the remainder of the packet to 188) desyncs every downstream TS parser -
the feed either fails to play or drifts.
**What we do:** `TsMuxerTest` guards the one invariant - every packet `TsMuxer` emits is exactly
188 bytes, including the padded PAT/PMT writes. The test is the regression guard against
re-introducing the prototype's bug during any future change to the muxer.
**Where:** `phone-app` `camera/ts/TsMuxer.kt`, `TsMuxerTest`.
