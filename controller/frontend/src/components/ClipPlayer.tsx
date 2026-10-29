import { useEffect, useRef, useState } from 'preact/hooks';
import type { ClipView, SegmentView } from '../api';
import { SegmentFeeder, type TimelineEntry } from '../lib/segmentFeeder';

interface ClipPlayerProps {
  clip: ClipView;
  /** Start playing on mount — set by the caller when this clip was reached by
   * auto-advance so a play-through continues across every clip. */
  autoplay?: boolean;
  /** Fired when playback runs past the clip's last segment - into `nextClip`,
   * or to the end - so the caller advances the selection. */
  onFinished?: () => void;
  /** Fired the first time this clip actually starts playing (once per clip). */
  onStarted?: (clip: ClipView) => void;
  /** The clip auto-advance will move to once this one finishes, if any. It is
   * queued straight after this clip on the same stream, so playback rolls into
   * it without a cut. */
  nextClip?: ClipView | null;
}

function clipId(clip: ClipView | null | undefined): string | null {
  return clip ? `${clip.phoneId}|${clip.clipId}` : null;
}

/** The feeder's key for a clip's `i`th segment. */
function segKey(clip: ClipView, i: number): string {
  return `${clipId(clip)}#${i}`;
}

/** The archived segment, remuxed to fragmented MP4 by the controller (fmp4.go). */
function fmp4Url(clip: ClipView, seg: SegmentView): string {
  return `/fmp4/${encodeURIComponent(clip.phoneId)}/${encodeURIComponent(seg.filename)}`;
}

function entriesFor(clip: ClipView | null | undefined): TimelineEntry[] {
  if (!clip) return [];
  return (clip.segments ?? []).map((s, i) => ({ key: segKey(clip, i), url: fmp4Url(clip, s), dur: (s.durationMs ?? 0) / 1000 }));
}

function fmtClock(s: number): string {
  const t = Math.max(0, Math.floor(s));
  return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`;
}

/** How long the pointer must rest before the controls fade while playing. */
const CONTROLS_IDLE_MS = 2500;

/** Speeds the fast-forward button cycles through, wrapping back to 1x. */
const RATES = [1, 2, 4, 8] as const;

/**
 * Plays a clip as one continuous stream of its segments and then, if
 * `nextClip` is supplied, straight on into that clip - on a single `<video>`
 * fed through Media Source Extensions (see SegmentFeeder). The controller
 * serves each archived segment remuxed to fragmented MP4, and the feeder
 * appends them back to back, so a segment or clip boundary is just the next
 * frame: there is no `load()` to flash black and no second element to swap to.
 *
 * The media timeline runs on across clips: rolling into `nextClip` keeps the
 * same stream and only moves which stretch of it the controls describe. A clip
 * picked by hand starts a fresh stream, unless it is `nextClip`, which is
 * already queued and just seeked to.
 *
 * The transport controls are ours, not the browser's: the bar spans the whole
 * clip rather than the whole stream, and the native fullscreen control could
 * only be disabled, not removed. Fullscreen is taken on the wrapper so the
 * controls come with it.
 */
export function ClipPlayer({ clip, autoplay = false, onFinished, onStarted, nextClip }: ClipPlayerProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const feederRef = useRef<SegmentFeeder | null>(null);
  // The clip the stream is showing. It moves on to nextClip as playback rolls
  // into it, a render before the caller makes that the `clip` prop.
  const trackedClipRef = useRef<string | null>(null);
  const startedClipRef = useRef<string | null>(null);
  // The latest props, for media events and the playhead loop.
  const latest = useRef({ clip, nextClip, onFinished, onStarted });
  latest.current = { clip, nextClip, onFinished, onStarted };

  const wrapRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const [fullscreen, setFullscreen] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [position, setPosition] = useState(0); // seconds into the whole clip
  const [scrubbing, setScrubbing] = useState(false);
  const [hovering, setHovering] = useState(false);
  const [idle, setIdle] = useState(false);
  const [rate, setRate] = useState<number>(1);
  const [error, setError] = useState<string | null>(null);
  const idleTimer = useRef<number | undefined>(undefined);

  const segments = clip.segments ?? [];
  const cid = clipId(clip);
  // Where each segment starts within the clip, and the clip's total length.
  const starts: number[] = [];
  let total = 0;
  for (const s of segments) {
    starts.push(total);
    total += (s.durationMs ?? 0) / 1000;
  }

  /** The clip the stream is showing (see trackedClipRef). */
  const shownClip = (): ClipView | null => {
    const { clip: c, nextClip: n } = latest.current;
    const id = trackedClipRef.current;
    return id === clipId(c) ? c : id === clipId(n) ? (n ?? null) : null;
  };

  /** Where the shown clip starts on the stream's timeline. */
  const clipBase = (): number => {
    const c = shownClip();
    return (c && feederRef.current?.startOf(segKey(c, 0))) ?? 0;
  };

  const markStarted = (c: ClipView | null) => {
    const id = clipId(c);
    if (!c || !id || startedClipRef.current === id) return;
    startedClipRef.current = id;
    latest.current.onStarted?.(c);
  };

  // Lay the clip and nextClip out on the stream. A clip other than the one
  // showing is a selection: seek to it if it's already queued, else start a
  // fresh stream. The same clip means nextClip changed, the clip grew, or
  // playback just rolled into it - the queue is updated in place.
  const timelineSig = `${cid}:${segments.length}>${clipId(nextClip)}:${nextClip?.segments?.length ?? 0}`;
  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    const entries = [...entriesFor(clip), ...entriesFor(nextClip)];
    const final = !nextClip;
    const f = feederRef.current;
    if (trackedClipRef.current === cid && f?.setTimeline(entries, final)) return;

    const first = trackedClipRef.current === null;
    trackedClipRef.current = cid;
    // A clip picked by hand starts back at 1x; auto-advance (the caller sets
    // `autoplay`) keeps the play-through's speed.
    if (!autoplay) setRate(1);
    setPosition(0);
    setError(null);
    const queued = !!f && f.has(segKey(clip, 0)) && f.setTimeline(entries, final);
    const start = queued ? f.startOf(segKey(clip, 0)) : null;
    if (f && start !== null) {
      f.seek(start);
    } else {
      f?.dispose();
      const nf = new SegmentFeeder(v, entries, final);
      nf.onError = (msg) => setError(`Can't play this clip: ${msg}`);
      feederRef.current = nf;
    }
    if (autoplay) void v.play().catch(() => {});
    else if (!first) v.pause();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timelineSig]);

  useEffect(() => () => feederRef.current?.dispose(), []);

  /** Playback crossed into nextClip: show it, and let the caller catch up. */
  const checkRollover = () => {
    const v = videoRef.current;
    const n = latest.current.nextClip;
    const f = feederRef.current;
    if (!v || !n || !f || trackedClipRef.current === clipId(n)) return;
    const s = f.startOf(segKey(n, 0));
    if (s === null || v.currentTime < s) return;
    trackedClipRef.current = clipId(n);
    setPosition(v.currentTime - s);
    if (!v.paused) markStarted(n);
    latest.current.onFinished?.();
  };

  // Track the playhead smoothly while playing (timeupdate is only ~4Hz).
  useEffect(() => {
    if (!playing || scrubbing) return;
    let raf = 0;
    const tick = () => {
      const v = videoRef.current;
      if (v) {
        checkRollover();
        setPosition(v.currentTime - clipBase());
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playing, scrubbing, cid]);

  useEffect(() => {
    const onChange = () => setFullscreen(!!wrapRef.current && document.fullscreenElement === wrapRef.current);
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);

  // defaultPlaybackRate too: a fresh stream (new src) resets playbackRate to it.
  useEffect(() => {
    const v = videoRef.current;
    if (!v) return;
    v.defaultPlaybackRate = rate;
    v.playbackRate = rate;
  }, [rate]);

  useEffect(() => () => window.clearTimeout(idleTimer.current), []);

  const handleEnded = () => {
    // Only the very end of the stream: with a nextClip queued, playback rolls
    // into it instead (checkRollover).
    setPlaying(false);
    onFinished?.();
  };

  const handlePlaying = () => {
    setPlaying(true);
    markStarted(shownClip());
  };

  const togglePlay = () => {
    const v = videoRef.current;
    if (!v) return;
    if (v.ended) {
      // play() would restart the whole stream, which may begin clips back.
      // (Refs only: the keyboard handler holds the first render's togglePlay.)
      setPosition(0);
      feederRef.current?.seek(clipBase());
      void v.play().catch(() => {});
    } else if (v.paused) void v.play().catch(() => {});
    else v.pause();
  };

  const nextRate = RATES[(RATES.indexOf(rate as (typeof RATES)[number]) + 1) % RATES.length];
  // While paused the button is a way to start playing, so it starts at 1x
  // rather than stepping the speed of a clip that isn't moving.
  const cycleRate = () => {
    if (playing) {
      setRate(nextRate);
      return;
    }
    setRate(1);
    void videoRef.current?.play().catch(() => {});
  };

  const toggleFullscreen = () => {
    const wrap = wrapRef.current;
    if (!wrap) return;
    if (document.fullscreenElement === wrap) void document.exitFullscreen().catch(() => {});
    else void wrap.requestFullscreen().catch(() => {});
  };

  /** Seek to `t` seconds into the whole clip. */
  const seekClip = (t: number) => {
    if (segments.length === 0) return;
    const clamped = Math.max(0, Math.min(total - 0.05, t));
    setPosition(clamped);
    feederRef.current?.seek(clipBase() + clamped);
  };

  const fractionAt = (clientX: number) => {
    const r = trackRef.current?.getBoundingClientRect();
    if (!r || r.width === 0) return 0;
    return Math.max(0, Math.min(1, (clientX - r.left) / r.width));
  };

  const onTrackDown = (e: PointerEvent) => {
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    setScrubbing(true);
    seekClip(fractionAt(e.clientX) * total);
  };
  const onTrackMove = (e: PointerEvent) => {
    if (scrubbing) seekClip(fractionAt(e.clientX) * total);
  };
  const onTrackUp = (e: PointerEvent) => {
    if (!scrubbing) return;
    setScrubbing(false);
    seekClip(fractionAt(e.clientX) * total);
  };

  const poke = () => {
    setIdle(false);
    window.clearTimeout(idleTimer.current);
    idleTimer.current = window.setTimeout(() => setIdle(true), CONTROLS_IDLE_MS);
  };

  // Space plays/pauses, F toggles fullscreen - Gallery and Trash keep the
  // arrows and Delete for selection.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      // A focused button (Trash's empty confirm, say) keeps Space for itself;
      // ours blur after a click so they never hold focus.
      if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA' || t.tagName === 'BUTTON')) return;
      if (e.key === ' ') {
        e.preventDefault();
        togglePlay();
        poke();
      } else if (e.key === 'f' || e.key === 'F') {
        e.preventDefault();
        toggleFullscreen();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (segments.length === 0) {
    return (
      <div className="clip-player">
        <video preload="metadata" poster={clip.thumbnailUrl}></video>
      </div>
    );
  }

  const showControls = !playing || scrubbing || (hovering && !idle);
  const pct = total > 0 ? Math.min(100, (position / total) * 100) : 0;
  const classes = ['clip-player'];
  if (fullscreen) classes.push('is-fullscreen');
  if (!showControls) classes.push('controls-hidden');

  return (
    <div
      ref={wrapRef}
      className={classes.join(' ')}
      onMouseEnter={() => { setHovering(true); poke(); }}
      onMouseMove={poke}
      onMouseLeave={() => setHovering(false)}
    >
      <video
        ref={videoRef}
        playsInline
        poster={clip.thumbnailUrl}
        onEnded={handleEnded}
        onPlaying={handlePlaying}
        onPause={() => setPlaying(false)}
        onTimeUpdate={checkRollover}
        onError={() => setError("Can't play this clip.")}
      ></video>
      {error && <div className="clip-error">{error}</div>}
      <div className="clip-surface" onClick={togglePlay} onDblClick={toggleFullscreen} />
      <div className={showControls ? 'clip-controls' : 'clip-controls hidden'}>
        <button
          type="button"
          className="clip-btn"
          title={playing ? 'Pause (Space)' : 'Play (Space)'}
          aria-label={playing ? 'Pause' : 'Play'}
          onClick={(e) => { togglePlay(); (e.currentTarget as HTMLButtonElement).blur(); }}
        >
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="currentColor">
            {playing ? <path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" /> : <path d="M8 5.5v13l10.5-6.5z" />}
          </svg>
        </button>
        <button
          type="button"
          className={rate === 1 ? 'clip-btn clip-rate' : 'clip-btn clip-rate active'}
          title={playing ? `Playback speed ${rate}x - click for ${nextRate}x` : 'Play at 1x'}
          aria-label={`Fast forward (currently ${rate}x)`}
          onClick={(e) => { cycleRate(); (e.currentTarget as HTMLButtonElement).blur(); }}
        >
          {rate === 1
            ? (
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="currentColor">
                <path d="M3.5 6v12l8.5-6zM12.5 6v12l8.5-6z" />
              </svg>
            )
            : `${rate}×`}
        </button>
        <span className="clip-time">
          {fmtClock(position)} / {fmtClock(total)}
        </span>
        <div
          ref={trackRef}
          className="clip-track"
          onPointerDown={onTrackDown}
          onPointerMove={onTrackMove}
          onPointerUp={onTrackUp}
          onPointerCancel={onTrackUp}
        >
          <div className="clip-track-rail">
            {starts.slice(1).map((s) => (
              <span key={s} className="clip-track-seg" style={{ left: `${(s / total) * 100}%` }} />
            ))}
            <div className="clip-track-fill" style={{ width: `${pct}%` }} />
            <div className="clip-track-thumb" style={{ left: `${pct}%` }} />
          </div>
        </div>
        <button
          type="button"
          className="clip-btn"
          title={fullscreen ? 'Exit full screen (F / Esc)' : 'Full screen (F)'}
          aria-label={fullscreen ? 'Exit full screen' : 'Full screen'}
          onClick={(e) => { toggleFullscreen(); (e.currentTarget as HTMLButtonElement).blur(); }}
        >
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            {fullscreen
              ? <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" />
              : <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />}
          </svg>
        </button>
      </div>
    </div>
  );
}
