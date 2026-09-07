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
}

/** Plays a clip as a playlist of its segments: on `ended` it advances to the
 * next segment and keeps playing, and when the final segment ends it calls
 * `onFinished`. Give it a `key` per clip so it remounts cleanly on selection
 * change; `autoplay` carries the play-through across that remount. */
export function ClipPlayer({ clip, controls = false, autoplay = false, onFinished }: ClipPlayerProps) {
  const [segIdx, setSegIdx] = useState(0);
  const videoRef = useRef<HTMLVideoElement>(null);

  const segments = clip.segments ?? [];
  const current = segments[segIdx] ?? segments[0] ?? null;

  // Autoplay on mount when we got here by auto-advance.
  useEffect(() => {
    if (autoplay) void videoRef.current?.play().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Advancing to a later segment within the same clip: load + play it.
  useEffect(() => {
    const v = videoRef.current;
    if (!v || segIdx === 0) return;
    v.load();
    void v.play().catch(() => {});
  }, [segIdx]);

  const handleEnded = () => {
    if (segIdx < segments.length - 1) {
      setSegIdx(segIdx + 1);
    } else {
      onFinished?.();
    }
  };

  if (!current) {
    return <video preload="metadata" poster={clip.thumbnailUrl}></video>;
  }

  return (
    <video
      ref={videoRef}
      controls={controls}
      preload="metadata"
      autoPlay={autoplay}
      poster={segIdx === 0 ? clip.thumbnailUrl : undefined}
      src={current.videoUrl}
      onEnded={handleEnded}
    ></video>
  );
}
