import { useEffect, useRef, useState } from 'preact/hooks';
import type { ClipView } from '../api';

interface ClipPlayerProps {
  clip: ClipView;
  /** Gallery's viewer shows transport controls; Trash's does not (matches the
   * pre-split behaviour of each screen). */
  controls?: boolean;
  /** Start playing on mount — set by the caller when this clip was reached by
   * auto-advance so a play-through continues across every clip. */
  autoplay?: boolean;
  /** Fired when the clip's last segment finishes playing — the caller advances
   * the selection to the next clip. */
  onFinished?: () => void;
  /** Fired the first time this clip actually starts playing (once per clip). */
  onStarted?: (clip: ClipView) => void;
  /** The clip auto-advance will move to once this one finishes, if any. Its
   * first segment is preloaded into the idle buffer (see below) so rolling
   * into it is a plain element swap rather than a `load()` reset. */
  nextClip?: ClipView | null;
}

function segUrl(clip: ClipView | null | undefined, idx: number): string | null {
  return clip?.segments?.[idx]?.videoUrl ?? null;
}

function clipId(clip: ClipView | null | undefined): string | null {
  return clip ? `${clip.phoneId}|${clip.clipId}` : null;
}

function fmtClock(s: number): string {
  const t = Math.max(0, Math.floor(s));
  return `${Math.floor(t / 60)}:${String(t % 60).padStart(2, '0')}`;
}

/** How long the pointer must rest before the controls fade while playing. */
const CONTROLS_IDLE_MS = 2500;

/**
 * Plays a clip as a gapless sequence of its own segments and then, if
 * `nextClip` is supplied, straight into that clip's first segment - all
 * without the visible `<video>` element ever calling `load()` mid-playback,
 * which is what used to cause a black-frame flicker at every segment and
 * clip boundary (each was a separate `src`/`load()` cycle on one element).
 *
 * Technique: two `<video>` elements, only one visible at a time. The hidden
 * one is always kept preloaded with whatever plays next (the following
 * segment, or `nextClip`'s first segment once the current clip is on its
 * last one). On `ended`, playback just swaps which element is visible/active
 * - the other one is already primed, so the cut is instant. A clip picked
 * manually (not reached by auto-advance) still hard-cuts, since it isn't
 * part of a continuous play-through and nothing could have preloaded it.
 *
 * The transport controls are ours, not the browser's, for two reasons. Native
 * controls belong to one `<video>`, so their seek bar only ever covered the
 * current segment and their fullscreen took that one element - which the next
 * swap then hid while it stayed fullscreen, leaving an invisible layer over the
 * window that swallowed every click. And the one native control we could not
 * use (fullscreen) could only be disabled, not removed: it stayed on screen,
 * dimmed. So both elements run without `controls`; the bar below spans the
 * whole clip, and fullscreen is taken on the wrapper, which holds both slots
 * and so survives every swap.
 */
export function ClipPlayer({ clip, controls = false, autoplay = false, onFinished, onStarted, nextClip }: ClipPlayerProps) {
  const [active, setActiveState] = useState<0 | 1>(0);
  const [segIdx, setSegIdx] = useState(0);
  const refs = [useRef<HTMLVideoElement>(null), useRef<HTMLVideoElement>(null)] as const;
  // What each slot's src is currently set to, so a preloaded swap can be told
  // apart from a manual jump that needs a hard cut.
  const loadedRef = useRef<[string | null, string | null]>([null, null]);
  const trackedClipRef = useRef<string | null>(null);
  // Mirrors `active` synchronously: media events from the idle slot must be
  // ignored, and they can arrive before a swap has re-rendered.
  const activeRef = useRef<0 | 1>(0);
  const segIdxRef = useRef(0);
  const startedClipRef = useRef<string | null>(null);

  const wrapRef = useRef<HTMLDivElement>(null);
  const trackRef = useRef<HTMLDivElement>(null);
  const [fullscreen, setFullscreen] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [position, setPosition] = useState(0); // seconds into the whole clip
  const [scrubbing, setScrubbing] = useState(false);
  const [hovering, setHovering] = useState(false);
  const [idle, setIdle] = useState(false);
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

  const setActive = (slot: 0 | 1) => {
    activeRef.current = slot;
    setActiveState(slot);
  };
  const setSeg = (i: number) => {
    segIdxRef.current = i;
    setSegIdx(i);
  };

  const hardCut = (slot: 0 | 1, url: string | null, play: boolean, seekTo = 0) => {
    const v = refs[slot].current;
    if (!v) return;
    loadedRef.current[slot] = url;
    v.src = url ?? '';
    v.load();
    if (seekTo > 0) v.addEventListener('loadedmetadata', () => { v.currentTime = seekTo; }, { once: true });
    if (play) void v.play().catch(() => {});
  };

  // The clip prop changed. If it's the clip we were already preloading as
  // `nextClip` (the idle slot already holds its first segment), cut over to
  // it instead of resetting. Otherwise this is a manual selection: hard-cut.
  useEffect(() => {
    const wasTracked = trackedClipRef.current !== null;
    const alreadyOnIt = trackedClipRef.current === cid;
    trackedClipRef.current = cid;
    if (alreadyOnIt) return; // we already cut over to this clip ourselves (see handleEnded)

    const cur = activeRef.current;
    const idle = cur === 0 ? 1 : 0;
    const wantUrl = segUrl(clip, 0);
    setSeg(0);
    setPosition(0);
    if (wasTracked && wantUrl && loadedRef.current[idle] === wantUrl) {
      setActive(idle);
      void refs[idle].current?.play().catch(() => {});
    } else {
      hardCut(cur, wantUrl, autoplay);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cid]);

  // Keep the idle slot preloaded with whatever plays next.
  useEffect(() => {
    const idle = active === 0 ? 1 : 0;
    const upcoming = segIdx < segments.length - 1 ? segUrl(clip, segIdx + 1) : segUrl(nextClip, 0);
    if (!upcoming || loadedRef.current[idle] === upcoming) return;
    const v = refs[idle].current;
    if (!v) return;
    loadedRef.current[idle] = upcoming;
    v.src = upcoming;
    v.load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cid, segIdx, nextClip, active]);

  // Autoplay-on-mount for the first render of a play-through.
  useEffect(() => {
    if (autoplay) void refs[activeRef.current].current?.play().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Track the playhead smoothly while playing (timeupdate is only ~4Hz).
  useEffect(() => {
    if (!playing || scrubbing) return;
    let raf = 0;
    const tick = () => {
      const v = refs[activeRef.current].current;
      if (v) setPosition((starts[segIdxRef.current] ?? 0) + v.currentTime);
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

  useEffect(() => () => window.clearTimeout(idleTimer.current), []);

  const handleEnded = (slot: 0 | 1) => {
    if (slot !== activeRef.current) return; // stray event from the (paused, preloading) idle element
    const cur = activeRef.current;
    const idle = cur === 0 ? 1 : 0;
    const seg = segIdxRef.current;
    const isLastSegment = seg >= segments.length - 1;

    if (!isLastSegment) {
      const wantUrl = segUrl(clip, seg + 1);
      if (wantUrl && loadedRef.current[idle] === wantUrl) {
        setActive(idle);
        setSeg(seg + 1);
        void refs[idle].current?.play().catch(() => {});
      } else {
        // Not preloaded yet (rare race) - fall back to a hard cut in place.
        setSeg(seg + 1);
        hardCut(cur, wantUrl, true);
      }
      return;
    }

    // Last segment of this clip finished. If nextClip's first segment was
    // preloaded, cut over immediately so on-screen playback doesn't wait for
    // the parent's re-render; otherwise it's simply the end of the list.
    const wantUrl = segUrl(nextClip, 0);
    if (nextClip && wantUrl && loadedRef.current[idle] === wantUrl) {
      trackedClipRef.current = clipId(nextClip);
      setActive(idle);
      setSeg(0);
      setPosition(0);
      void refs[idle].current?.play().catch(() => {});
    } else {
      setPlaying(false);
    }
    onFinished?.();
  };

  const handlePlaying = (slot: 0 | 1) => {
    if (slot !== activeRef.current) return;
    setPlaying(true);
    // Once per clip. A preloaded cut-over has already moved trackedClipRef on
    // to the clip now playing, so it names the right one either way.
    const playingId = trackedClipRef.current;
    if (playingId && startedClipRef.current !== playingId) {
      startedClipRef.current = playingId;
      const c = playingId === cid ? clip : playingId === clipId(nextClip) ? nextClip : null;
      if (c) onStarted?.(c);
    }
  };

  const handlePause = (slot: 0 | 1) => {
    if (slot !== activeRef.current) return;
    const v = refs[slot].current;
    // A segment reaching its end pauses too, just before the swap; that isn't
    // the user pausing, so leave the play state to handleEnded.
    if (v && !v.ended) setPlaying(false);
  };

  const togglePlay = () => {
    const v = refs[activeRef.current].current;
    if (!v) return;
    if (v.paused) void v.play().catch(() => {});
    else v.pause();
  };

  const toggleFullscreen = () => {
    const wrap = wrapRef.current;
    if (!wrap) return;
    if (document.fullscreenElement === wrap) void document.exitFullscreen().catch(() => {});
    else void wrap.requestFullscreen().catch(() => {});
  };

  /** Seek to `t` seconds into the whole clip. Within the current segment
   * that's just currentTime; into another one it's a load, so `live` (a drag
   * still in progress) skips those to avoid a black flash per pointer move. */
  const seekClip = (t: number, live = false) => {
    if (segments.length === 0) return;
    const clamped = Math.max(0, Math.min(total - 0.05, t));
    let i = segments.length - 1;
    while (i > 0 && starts[i] > clamped) i--;
    const off = clamped - starts[i];
    setPosition(clamped);
    const cur = activeRef.current;
    const v = refs[cur].current;
    if (!v) return;
    if (i === segIdxRef.current) {
      v.currentTime = off;
      return;
    }
    if (live) return;
    setSeg(i);
    hardCut(cur, segUrl(clip, i), !v.paused, off);
  };

  const fractionAt = (clientX: number) => {
    const r = trackRef.current?.getBoundingClientRect();
    if (!r || r.width === 0) return 0;
    return Math.max(0, Math.min(1, (clientX - r.left) / r.width));
  };

  const onTrackDown = (e: PointerEvent) => {
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    setScrubbing(true);
    seekClip(fractionAt(e.clientX) * total, true);
  };
  const onTrackMove = (e: PointerEvent) => {
    if (scrubbing) seekClip(fractionAt(e.clientX) * total, true);
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

  // Space plays/pauses, F toggles fullscreen - Gallery keeps the arrows and
  // Delete for selection.
  useEffect(() => {
    if (!controls) return;
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA')) return;
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
  }, [controls]);

  if (segments.length === 0) {
    return (
      <div className="clip-player">
        <video preload="metadata" poster={clip.thumbnailUrl}></video>
      </div>
    );
  }

  const showControls = controls && (!playing || scrubbing || (hovering && !idle));
  const pct = total > 0 ? Math.min(100, (position / total) * 100) : 0;
  const classes = ['clip-player'];
  if (fullscreen) classes.push('is-fullscreen');
  if (controls && !showControls) classes.push('controls-hidden');

  return (
    <div
      ref={wrapRef}
      className={classes.join(' ')}
      onMouseEnter={() => { setHovering(true); poke(); }}
      onMouseMove={poke}
      onMouseLeave={() => setHovering(false)}
    >
      {([0, 1] as const).map((slot) => (
        <video
          key={slot}
          ref={refs[slot]}
          preload="auto"
          playsInline
          poster={clip.thumbnailUrl}
          hidden={slot !== active}
          onEnded={() => handleEnded(slot)}
          onPlaying={() => handlePlaying(slot)}
          onPause={() => handlePause(slot)}
        ></video>
      ))}
      {controls && (
        <>
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
        </>
      )}
    </div>
  );
}
