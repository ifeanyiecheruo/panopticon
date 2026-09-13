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
 */
export function ClipPlayer({ clip, controls = false, autoplay = false, onFinished, nextClip }: ClipPlayerProps) {
  const [active, setActive] = useState<0 | 1>(0);
  const [segIdx, setSegIdx] = useState(0);
  const refs = [useRef<HTMLVideoElement>(null), useRef<HTMLVideoElement>(null)] as const;
  // What each slot's src is currently set to, so a preloaded swap can be told
  // apart from a manual jump that needs a hard cut.
  const loadedRef = useRef<[string | null, string | null]>([null, null]);
  const trackedClipRef = useRef<string | null>(null);

  const segments = clip.segments ?? [];
  const cid = clipId(clip);

  const hardCut = (slot: 0 | 1, url: string | null, play: boolean) => {
    const v = refs[slot].current;
    if (!v) return;
    loadedRef.current[slot] = url;
    v.src = url ?? '';
    v.load();
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

    const idle = active === 0 ? 1 : 0;
    const wantUrl = segUrl(clip, 0);
    setSegIdx(0);
    if (wasTracked && wantUrl && loadedRef.current[idle] === wantUrl) {
      setActive(idle);
      void refs[idle].current?.play().catch(() => {});
    } else {
      hardCut(active, wantUrl, autoplay);
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
  }, [cid, segIdx, nextClip]);

  // Autoplay-on-mount for the first render of a play-through.
  useEffect(() => {
    if (autoplay) void refs[active].current?.play().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleEnded = (slot: 0 | 1) => {
    if (slot !== active) return; // stray event from the (paused, preloading) idle element
    const idle = active === 0 ? 1 : 0;
    const isLastSegment = segIdx >= segments.length - 1;

    if (!isLastSegment) {
      const wantUrl = segUrl(clip, segIdx + 1);
      if (wantUrl && loadedRef.current[idle] === wantUrl) {
        setActive(idle);
        setSegIdx(segIdx + 1);
        void refs[idle].current?.play().catch(() => {});
      } else {
        // Not preloaded yet (rare race) - fall back to a hard cut in place.
        setSegIdx(segIdx + 1);
        hardCut(active, wantUrl, true);
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
      setSegIdx(0);
      void refs[idle].current?.play().catch(() => {});
    }
    onFinished?.();
  };

  if (segments.length === 0) {
    return (
      <div className="clip-player">
        <video preload="metadata" poster={clip.thumbnailUrl}></video>
      </div>
    );
  }

  return (
    <div className="clip-player">
      {([0, 1] as const).map((slot) => (
        <video
          key={slot}
          ref={refs[slot]}
          // Set on both slots (not just the active one) so a segment swap
          // never flips this attribute from false -> true - that transition
          // is what makes the browser show/flash the native controls overlay
          // as if they'd just been freshly enabled, on every segment change
          // instead of only the clip's first.
          controls={controls}
          preload="auto"
          poster={clip.thumbnailUrl}
          hidden={slot !== active}
          onEnded={() => handleEnded(slot)}
        ></video>
      ))}
    </div>
  );
}
