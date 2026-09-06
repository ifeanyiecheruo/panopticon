import { useEffect, useRef, useState } from 'preact/hooks';
import type { ClipView } from '../api';

interface ClipPlayerProps {
  clip: ClipView;
  /** Gallery's viewer shows transport controls; Trash's does not (matches the
   * pre-split behaviour of each screen). */
  controls?: boolean;
  /** Fired when the clip's last segment finishes playing — the caller advances
   * the selection to the next clip. */
  onFinished?: () => void;
}

/** Plays a clip as a playlist of its segments: on `ended` it advances to the
 * next segment and keeps playing, and when the final segment ends it calls
 * `onFinished`. Resets to the first segment whenever the clip changes. */
export function ClipPlayer({ clip, controls = false, onFinished }: ClipPlayerProps) {
  const [segIdx, setSegIdx] = useState(0);
  const videoRef = useRef<HTMLVideoElement>(null);

  // New clip selected -> back to its first segment.
  useEffect(() => {
    setSegIdx(0);
  }, [clip.phoneId, clip.clipId]);

  const segments = clip.segments ?? [];
  const current = segments[segIdx] ?? segments[0] ?? null;

  // When we've advanced to a later segment (not on the initial frame, and not
  // on a fresh clip), load and play it. Autoplay may be blocked by the
  // embedder — swallow that and leave the frame paused.
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
      poster={segIdx === 0 ? clip.thumbnailUrl : undefined}
      src={current.videoUrl}
      onEnded={handleEnded}
    ></video>
  );
}
