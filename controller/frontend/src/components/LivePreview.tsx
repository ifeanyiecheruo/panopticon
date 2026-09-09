import type { ComponentChildren } from 'preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import Hls from '../vendor/hlsjs/hls.min.mjs';
import { StartLivePreview, StopLivePreview } from '../api';

export type LiveState =
  | { kind: 'idle' }
  | { kind: 'starting' }
  | { kind: 'playing' }
  | { kind: 'error'; message: string };

// The phone's encoder takes a beat to produce segment 0, so the very first
// playlist fetch can 404 — and hls.js treats a manifest 404/parse failure as
// fatal after one try (video-dev/hls.js, see docs/quirks/live-hls.md). Retry the load a
// bounded number of times before giving up. The budget resets once playback
// actually starts, so a mid-stream blip gets its own fresh allowance.
const MANIFEST_RETRY_LIMIT = 8;
const MANIFEST_RETRY_MS = 700;

// Stall watchdog: hls.js has a documented failure mode where its load queue
// stops requesting fragments after a stall with no error event, and another
// where its live-latency target ratchets up after every stall and never
// recovers (docs/quirks/live-hls.md). Poll playback progress; if it's wedged, kick the
// loader and jump back to the live edge.
const STALL_POLL_MS = 2000;
const STALL_AFTER_MS = 6000;

/** Config aimed at a plain-HLS live feed off a phone on the LAN: a generous DVR
 * window and buffer so a slow fetch or a GC pause doesn't immediately starve
 * the player, and patient retries. Not low-latency mode. */
const HLS_CONFIG = {
  enableWorker: true,
  lowLatencyMode: false,
  liveSyncDurationCount: 3,
  liveMaxLatencyDurationCount: 20,
  maxBufferLength: 20,
  maxMaxBufferLength: 40,
  backBufferLength: 12,
  manifestLoadingMaxRetry: 6,
  manifestLoadingRetryDelay: 500,
  levelLoadingMaxRetry: 6,
  levelLoadingRetryDelay: 500,
  fragLoadingMaxRetry: 8,
  fragLoadingRetryDelay: 500,
};

export interface LiveController {
  state: LiveState;
  playing: boolean;
  busy: boolean;
  /** True while the stream is playing OR spinning up (button should show Stop). */
  live: boolean;
  videoRef: { current: HTMLVideoElement | null };
  watch: () => void;
  stop: () => void;
  /** Re-arm the phone broadcast and rebuild the hls.js pipeline against the
   * (now-different) camera, without taking the phone out of live mode. Call
   * after a camera switch so the preview follows the new camera. No-op unless
   * a stream is currently up. */
  reattach: () => Promise<void>;
}

/** The live-preview transport, decoupled from any UI so the command bar can
 * drive it while the video element lives elsewhere on the screen. Always stops
 * the phone's broadcast on unmount. */
export function useLivePreview(phoneId: string): LiveController {
  const [state, setState] = useState<LiveState>({ kind: 'idle' });
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const retriesRef = useRef(0);
  const playingRef = useRef(false);
  const startedRef = useRef(false);
  const stallTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastProgressRef = useRef({ t: 0, at: 0 });

  const teardown = useCallback(
    (opts: { stopPhone: boolean }) => {
      if (stallTimerRef.current) {
        clearInterval(stallTimerRef.current);
        stallTimerRef.current = null;
      }
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
      playingRef.current = false;
      if (opts.stopPhone && startedRef.current) {
        startedRef.current = false;
        StopLivePreview(phoneId).catch(() => {});
      }
    },
    [phoneId],
  );

  // Stop the broadcast when the component goes away.
  useEffect(() => () => teardown({ stopPhone: true }), [teardown]);

  const jumpToLiveEdge = useCallback((hls: Hls) => {
    const video = videoRef.current;
    if (!video) return;
    const target = hls.liveSyncPosition;
    if (typeof target === 'number' && isFinite(target) && target > video.currentTime + 0.5) {
      video.currentTime = target;
    } else if (video.buffered.length > 0) {
      const end = video.buffered.end(video.buffered.length - 1);
      if (end > video.currentTime + 0.5) video.currentTime = end - 0.5;
    }
    video.play().catch(() => {});
  }, []);

  const attachHls = useCallback(
    (playlistPath: string) => {
      const video = videoRef.current;
      if (!video) return;
      if (!Hls.isSupported()) {
        setState({ kind: 'error', message: 'This webview has no MSE / hls.js support.' });
        return;
      }
      retriesRef.current = 0;
      playingRef.current = false;
      const hls = new Hls(HLS_CONFIG);
      hlsRef.current = hls;

      hls.on(Hls.Events.ERROR, (_evt: unknown, data: any) => {
        const isManifest =
          data.details === Hls.ErrorDetails.MANIFEST_LOAD_ERROR ||
          data.details === Hls.ErrorDetails.MANIFEST_LOAD_TIMEOUT ||
          data.details === Hls.ErrorDetails.MANIFEST_PARSING_ERROR ||
          data.details === Hls.ErrorDetails.LEVEL_EMPTY_ERROR;

        if (data.details === Hls.ErrorDetails.BUFFER_STALLED_ERROR) {
          hls.startLoad();
          jumpToLiveEdge(hls);
          return;
        }
        if (!data.fatal) return;

        if (isManifest && retriesRef.current < MANIFEST_RETRY_LIMIT) {
          retriesRef.current += 1;
          setTimeout(() => {
            if (hlsRef.current === hls) hls.loadSource(playlistPath);
          }, MANIFEST_RETRY_MS);
          return;
        }
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          hls.startLoad();
          jumpToLiveEdge(hls);
        } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
          hls.recoverMediaError();
        } else {
          setState({ kind: 'error', message: `Playback error: ${data.details}` });
          teardown({ stopPhone: true });
        }
      });

      hls.on(Hls.Events.FRAG_BUFFERED, () => {
        if (!playingRef.current) {
          playingRef.current = true;
          retriesRef.current = 0;
        }
        setState({ kind: 'playing' });
      });

      hls.loadSource(playlistPath);
      hls.attachMedia(video);
      video.play().catch(() => {});

      lastProgressRef.current = { t: 0, at: Date.now() };
      stallTimerRef.current = setInterval(() => {
        const v = videoRef.current;
        const h = hlsRef.current;
        if (!v || !h || v.paused || v.ended) return;
        const now = Date.now();
        if (v.currentTime > lastProgressRef.current.t + 0.05) {
          lastProgressRef.current = { t: v.currentTime, at: now };
          return;
        }
        if (now - lastProgressRef.current.at > STALL_AFTER_MS) {
          h.startLoad();
          jumpToLiveEdge(h);
          lastProgressRef.current = { t: v.currentTime, at: now };
        }
      }, STALL_POLL_MS);
    },
    [teardown, jumpToLiveEdge],
  );

  const watch = useCallback(async () => {
    setState({ kind: 'starting' });
    let res;
    try {
      res = await StartLivePreview(phoneId);
    } catch (err) {
      setState({ kind: 'error', message: String(err) });
      return;
    }
    if (!res.ok || !res.playlistPath) {
      setState({
        kind: 'error',
        message:
          res.message ||
          (res.outcome === 'recording'
            ? 'The phone is recording — stop recording to watch live.'
            : 'Could not start live preview.'),
      });
      return;
    }
    startedRef.current = true;
    attachHls(res.playlistPath);
  }, [phoneId, attachHls]);

  const stop = useCallback(() => {
    teardown({ stopPhone: true });
    setState({ kind: 'idle' });
  }, [teardown]);

  const reattach = useCallback(async () => {
    if (!startedRef.current) return;
    setState({ kind: 'starting' });
    teardown({ stopPhone: false }); // keep the phone in live mode; just drop hls.js
    // A camera switch tears down + rebuilds the phone's live pipeline. The arming
    // wait is now handled inside StartLivePreview (it retries the phone's
    // 503+retryAfter for ~20s), so this only needs a couple of passes to ride
    // out the brief window where the rebuilt pipeline isn't up yet.
    const REATTACH_TRIES = 3;
    for (let i = 0; i < REATTACH_TRIES; i++) {
      let res;
      try {
        res = await StartLivePreview(phoneId);
      } catch (err) {
        if (i === REATTACH_TRIES - 1) {
          setState({ kind: 'error', message: String(err) });
          return;
        }
        await new Promise((r) => setTimeout(r, 1000));
        continue;
      }
      if (res.ok && res.playlistPath) {
        attachHls(res.playlistPath);
        return;
      }
      if (i === REATTACH_TRIES - 1) {
        setState({
          kind: 'error',
          message: res.message || 'The new camera could not start a live stream. Cycle to another camera.',
        });
        return;
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
  }, [phoneId, teardown, attachHls]);

  return {
    state,
    playing: state.kind === 'playing',
    busy: state.kind === 'starting',
    live: state.kind === 'playing' || state.kind === 'starting',
    videoRef,
    watch,
    stop,
    reattach,
  };
}

interface LivePreviewVideoProps {
  ctl: LiveController;
  /** Rendered absolutely over the <video> box (same coordinate space) with the
   * current playing flag — used by the zoom-rect picker. */
  overlay?: (playing: boolean) => ComponentChildren;
  /** Shown in the frame before a stream is started. */
  idleHint?: ComponentChildren;
}

/** Presentational half of the live preview: just the framed <video> + overlay
 * slot + status line. The Watch/Stop control lives in the command bar. */
export function LivePreviewVideo({ ctl, overlay, idleHint }: LivePreviewVideoProps) {
  const { state, videoRef } = ctl;
  return (
    <div class="live-preview">
      <div class="live-video-wrap" hidden={state.kind === 'idle' || state.kind === 'error'}>
        {/* No `controls`: a live feed has nothing to scrub. */}
        <video ref={videoRef} class="live-video" muted playsInline />
        {overlay?.(state.kind === 'playing')}
      </div>

      {state.kind === 'idle' && (
        <div class="live-frame-idle">{idleHint ?? 'Not watching. Use “Watch live” above.'}</div>
      )}
      {state.kind === 'starting' && <div class="calib-sub">Connecting to the phone…</div>}
      {state.kind === 'error' && <div class="live-frame-idle error">{state.message}</div>}
    </div>
  );
}
