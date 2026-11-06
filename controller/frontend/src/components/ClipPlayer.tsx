import { useEffect, useRef, useState } from 'preact/hooks';
import type { ClipView, SegmentView } from '../api';
import { SegmentFeeder, type TimelineEntry } from '../lib/segmentFeeder';
import { PictureRenderer } from '../lib/pictureRenderer';
import type { AreaRect } from '../lib/areaLevels';

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

/** The segment's area of interest (aoi.go): null when it had none - the
 * controller answers 204 - and undefined when it couldn't be fetched. */
async function fetchArea(clip: ClipView, seg: SegmentView): Promise<AreaRect | null | undefined> {
  try {
    const res = await fetch(`/aoi/${encodeURIComponent(clip.phoneId)}/${encodeURIComponent(seg.filename)}`);
    if (res.status === 204) return null;
    if (!res.ok) return undefined;
    return (await res.json()) as AreaRect;
  } catch {
    return undefined;
  }
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
/** Whether area contrast is on until it's first switched; the choice is then
 * remembered under CONTRAST_KEY. */
const CONTRAST_DEFAULT = true;
const CONTRAST_KEY = 'panopticon.clipAreaContrast';
/** What area contrast measures in a segment with no area of interest. */
const WHOLE_FRAME: AreaRect = { l: 0, t: 0, r: 1, b: 1 };
/** How long the area contrast button is held to draw an override area. */
const LONG_PRESS_MS = 500;
/** A drawn area narrower or shorter than this (of the frame) is a stray click. */
const MIN_DRAWN = 0.02;

function loadContrast(): boolean {
  try {
    const v = localStorage.getItem(CONTRAST_KEY);
    return v === null ? CONTRAST_DEFAULT : v === 'on';
  } catch {
    return CONTRAST_DEFAULT;
  }
}

function saveContrast(on: boolean) {
  try {
    localStorage.setItem(CONTRAST_KEY, on ? 'on' : 'off');
  } catch {
    // Not remembered; it still applies to this player.
  }
}

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
 *
 * What's seen is a `<canvas>` over the `<video>`: PictureRenderer draws each
 * frame the video presents, stretching its contrast to the levels inside the
 * area of interest its segment was recorded with - the whole frame for a
 * segment without one. With area contrast switched off, or no WebGL, it shows
 * the video as recorded. The video is still the one decoder and timeline;
 * nothing is swapped.
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
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const rendererRef = useRef<PictureRenderer | null>(null);
  const [gl, setGl] = useState(false); // the canvas renderer is running
  const [contrast, setContrast] = useState(loadContrast); // area contrast on
  // An area drawn over the player, used in place of every segment's own until
  // Esc while drawing resets it.
  const [override, setOverride] = useState<AreaRect | null>(null);
  const overrideRef = useRef<AreaRect | null>(null);
  overrideRef.current = override;
  const [drawing, setDrawing] = useState(false); // drawing an override
  const [drag, setDrag] = useState<{ x0: number; y0: number; x1: number; y1: number } | null>(null);
  const pressTimer = useRef<number | undefined>(undefined);
  const longPressed = useRef(false);
  // Areas of interest that have arrived, by `phoneId/filename` (null: none).
  const areas = useRef(new Map<string, AreaRect | null>());

  const segments = clip.segments ?? [];
  const hasSegments = segments.length > 0;
  const cid = clipId(clip);

  // The renderer needs the video and canvas, which only exist while there are
  // segments to play.
  useEffect(() => {
    const v = videoRef.current;
    const c = canvasRef.current;
    if (!v || !c) return;
    const r = PictureRenderer.create(c, v);
    if (!r) return;
    r.onLost = () => {
      rendererRef.current = null;
      setGl(false);
    };
    rendererRef.current = r;
    setGl(true);
    return () => {
      r.dispose();
      rendererRef.current = null;
      setGl(false);
    };
  }, [hasSegments]);
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

  // Fetch the areas of interest of the clip and the one queued after it, and
  // redraw as each lands - a paused frame adjusts when its area arrives.
  useEffect(() => {
    if (!gl) return;
    for (const c of [clip, nextClip]) {
      if (!c) continue;
      for (const s of c.segments ?? []) {
        const key = `${c.phoneId}/${s.filename}`;
        if (areas.current.has(key)) continue;
        void fetchArea(c, s).then((a) => {
          if (a === undefined) return; // not cached: tried again next time
          areas.current.set(key, a);
          rendererRef.current?.redraw();
        });
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [timelineSig, gl]);

  /** The area of interest of the segment playing at a point on the stream's
   * timeline - of the clip or nextClip - or the whole frame when it has none.
   * A drawn override stands in for all of them. */
  const areaAt = (mediaTime: number): AreaRect => {
    if (overrideRef.current) return overrideRef.current;
    const f = feederRef.current;
    if (!f) return WHOLE_FRAME;
    const { clip: c, nextClip: n } = latest.current;
    for (const cl of [c, n]) {
      if (!cl) continue;
      const segs = cl.segments ?? [];
      for (let i = 0; i < segs.length; i++) {
        const start = f.startOf(segKey(cl, i));
        if (start === null) continue;
        const dur = (segs[i].durationMs ?? 0) / 1000;
        if (mediaTime >= start && mediaTime < start + dur) return areas.current.get(`${cl.phoneId}/${segs[i].filename}`) ?? WHOLE_FRAME;
      }
    }
    return WHOLE_FRAME;
  };

  useEffect(() => {
    rendererRef.current?.setArea(gl && contrast ? areaAt : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [gl, contrast, override]);

  const switchContrast = (on: boolean) => {
    saveContrast(on);
    setContrast(on);
  };

  /** The video's picture within the player box (object-fit: contain), in
   * client pixels. */
  const pictureBox = () => {
    const wrap = wrapRef.current?.getBoundingClientRect();
    const v = videoRef.current;
    if (!wrap || !v || !v.videoWidth || !v.videoHeight) return null;
    const scale = Math.min(wrap.width / v.videoWidth, wrap.height / v.videoHeight);
    const w = v.videoWidth * scale;
    const h = v.videoHeight * scale;
    return { left: wrap.left + (wrap.width - w) / 2, top: wrap.top + (wrap.height - h) / 2, w, h };
  };

  /** A client point as a fraction of the frame, kept inside it. */
  const toFrame = (clientX: number, clientY: number) => {
    const b = pictureBox();
    if (!b) return null;
    const clamp = (n: number) => Math.min(1, Math.max(0, n));
    return { x: clamp((clientX - b.left) / b.w), y: clamp((clientY - b.top) / b.h) };
  };

  /** A frame rect placed over the player, for an outline. */
  const outlineStyle = (a: AreaRect) => {
    const b = pictureBox();
    const wrap = wrapRef.current?.getBoundingClientRect();
    if (!b || !wrap) return { display: 'none' };
    return {
      left: `${b.left - wrap.left + a.l * b.w}px`,
      top: `${b.top - wrap.top + a.t * b.h}px`,
      width: `${(a.r - a.l) * b.w}px`,
      height: `${(a.b - a.t) * b.h}px`,
    };
  };

  const dragRect = (d: { x0: number; y0: number; x1: number; y1: number }): AreaRect => ({
    l: Math.min(d.x0, d.x1), t: Math.min(d.y0, d.y1), r: Math.max(d.x0, d.x1), b: Math.max(d.y0, d.y1),
  });

  const onDrawDown = (e: PointerEvent) => {
    const p = toFrame(e.clientX, e.clientY);
    if (!p) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    setDrag({ x0: p.x, y0: p.y, x1: p.x, y1: p.y });
  };
  const onDrawMove = (e: PointerEvent) => {
    if (!drag) return;
    const p = toFrame(e.clientX, e.clientY);
    if (p) setDrag({ ...drag, x1: p.x, y1: p.y });
  };
  const onDrawUp = () => {
    if (!drag) return;
    const r = dragRect(drag);
    setDrag(null);
    if (r.r - r.l < MIN_DRAWN || r.b - r.t < MIN_DRAWN) return; // keep drawing
    setOverride(r);
    if (!contrast) switchContrast(true);
    setDrawing(false);
  };
  const cancelDraw = () => {
    setDrag(null);
    setDrawing(false);
  };

  // Esc leaves drawing and resets the override, back to each segment's area.
  useEffect(() => {
    if (!drawing) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      e.preventDefault();
      e.stopPropagation();
      setOverride(null);
      cancelDraw();
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [drawing]);

  // Holding the area contrast button draws an override; a click switches.
  const onContrastDown = () => {
    longPressed.current = false;
    window.clearTimeout(pressTimer.current);
    pressTimer.current = window.setTimeout(() => {
      longPressed.current = true;
      setDrawing(true);
    }, LONG_PRESS_MS);
  };
  const onContrastRelease = () => window.clearTimeout(pressTimer.current);
  useEffect(() => () => window.clearTimeout(pressTimer.current), []);

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

  const showControls = !playing || scrubbing || drawing || (hovering && !idle);
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
      <canvas ref={canvasRef} className="clip-canvas" aria-hidden="true"></canvas>
      {gl && contrast && (
        <div className={showControls ? 'clip-badge' : 'clip-badge hidden'} title="Area contrast is on">
          {override ? 'AOI override' : 'AOI contrast'}
        </div>
      )}
      {gl && contrast && override && !drawing && showControls && (
        <div className="clip-area" style={outlineStyle(override)} aria-hidden="true" />
      )}
      {error && <div className="clip-error">{error}</div>}
      <div className="clip-surface" onClick={togglePlay} onDblClick={toggleFullscreen} />
      {drawing && (
        <div
          className="clip-draw"
          onPointerDown={onDrawDown}
          onPointerMove={onDrawMove}
          onPointerUp={onDrawUp}
          onPointerCancel={() => setDrag(null)}
          onContextMenu={(e) => { e.preventDefault(); cancelDraw(); }}
        >
          <div className="clip-draw-hint">Drag over the area to maximize contrast in - Esc to reset to each segment's area</div>
          {drag && <div className="clip-area drawing" style={outlineStyle(dragRect(drag))} />}
        </div>
      )}
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
        {gl && (
          <button
            type="button"
            className={contrast || drawing ? 'clip-btn active' : 'clip-btn'}
            title={contrast
              ? `Area contrast on: stretches the contrast of ${override ? 'the drawn area' : "each segment's area of interest (or the whole frame)"} - click to turn off, hold to draw an area`
              : 'Area contrast off - click to turn on, hold to draw an area'}
            aria-label="Area contrast"
            aria-pressed={contrast}
            onPointerDown={onContrastDown}
            onPointerUp={onContrastRelease}
            onPointerLeave={onContrastRelease}
            onPointerCancel={onContrastRelease}
            onContextMenu={(e) => e.preventDefault()}
            onClick={(e) => {
              (e.currentTarget as HTMLButtonElement).blur();
              if (longPressed.current) { longPressed.current = false; return; }
              if (drawing) { cancelDraw(); return; }
              switchContrast(!contrast);
            }}
          >
            <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <circle cx="12" cy="12" r="4" />
              <path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4" />
              {!contrast && <path d="M4 20L20 4" />}
            </svg>
          </button>
        )}
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
