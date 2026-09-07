import type { ComponentChildren } from 'preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import Hls from '../vendor/hlsjs/hls.min.mjs';
import { StartLivePreview, StopLivePreview } from '../api';

interface LivePreviewProps {
  phoneId: string;
  /** True when the phone is in record mode — live preview is unavailable until
   * recording is stopped on the phone itself (mirrors calibration). */
  phoneRecording: boolean;
  /** Rendered absolutely over the <video> box (same coordinate space), given the
   * current playback state — used by the zoom-rect picker. */
  overlay?: (playing: boolean) => ComponentChildren;
}

type State =
  | { kind: 'idle' }
  | { kind: 'starting' }
  | { kind: 'playing' }
  | { kind: 'error'; message: string };

// The phone's encoder takes a beat to produce segment 0, so the very first
// playlist fetch can 404 — and hls.js treats a manifest 404/parse failure as
// fatal after one try (video-dev/hls.js, see docs/QUIRKS.md). Retry the load a
// bounded number of times before giving up. The budget resets once playback
// actually starts, so a mid-stream blip gets its own fresh allowance.
const MANIFEST_RETRY_LIMIT = 8;
const MANIFEST_RETRY_MS = 700;

// Stall watchdog: hls.js has a documented failure mode where its load queue
// stops requesting fragments after a stall with no error event, and another
// where its live-latency target ratchets up after every stall and never
// recovers (docs/QUIRKS.md). Poll playback progress; if it's wedged, kick the
// loader and jump back to the live edge.
const STALL_POLL_MS = 2000;
const STALL_AFTER_MS = 6000;

/** Config aimed at a plain-HLS live feed off a phone on the LAN: a generous DVR
 * window and buffer so a slow fetch or a GC pause doesn't immediately starve
 * the player, and patient retries. Not low-latency mode. */
const HLS_CONFIG = {
  enableWorker: true,
  lowLatencyMode: false,
  liveSyncDurationCount: 3,        // sit ~3 segments (~3s) behind the live edge
  liveMaxLatencyDurationCount: 20, // if we fall >~20s behind, seek forward
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

export function LivePreview({ phoneId, phoneRecording, overlay }: LivePreviewProps) {
  const [state, setState] = useState<State>({ kind: 'idle' });
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const retriesRef = useRef(0);
  const playingRef = useRef(false); // has playback ever actually progressed?
  const startedRef = useRef(false); // did we actually put the phone into live mode?
  const stallTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const lastProgressRef = useRef({ t: 0, at: 0 });

  const teardown = useCallback((opts: { stopPhone: boolean }) => {
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
      // Fire-and-forget: the phone's own inactivity watchdog also handles this.
      StopLivePreview(phoneId).catch(() => {});
    }
  }, [phoneId]);

  // Always stop the broadcast when the component goes away or the phone starts
  // recording out from under us.
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

  const attachHls = useCallback((playlistPath: string) => {
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

      // Buffer stalls are non-fatal in hls.js but this is exactly the "stuck
      // after a stall" case — nudge the loader and edge.
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
        retriesRef.current = 0; // startup done; give mid-stream blips a fresh budget
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
        // Wedged. Kick the loader and jump to the live edge; reset the timer so
        // we give the recovery a chance before trying again.
        h.startLoad();
        jumpToLiveEdge(h);
        lastProgressRef.current = { t: v.currentTime, at: now };
      }
    }, STALL_POLL_MS);
  }, [teardown, jumpToLiveEdge]);

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
        message: res.message || (res.outcome === 'recording'
          ? 'The phone is recording — stop recording on the phone to watch live.'
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

  const busy = state.kind === 'starting';
  const live = state.kind === 'playing' || state.kind === 'starting';

  return (
    <div className="card" style={{ padding: '14px' }}>
      <div className="live-row">
        <div className="calib-sub">
          {phoneRecording
            ? 'Recording — stop recording on the phone to watch live.'
            : 'Plain HLS, proxied through the controller. Expect a few seconds of latency.'}
        </div>
        {live ? (
          <button className="btn small danger" onClick={stop}>Stop</button>
        ) : (
          <button
            className="btn small"
            onClick={watch}
            disabled={busy || phoneRecording}
            title={phoneRecording ? 'Stop recording on the phone first' : undefined}
          >
            {busy ? 'Starting…' : 'Watch live'}
          </button>
        )}
      </div>

      <div className="live-video-wrap" hidden={state.kind === 'idle' || state.kind === 'error'}>
        <video
          ref={videoRef}
          className="live-video"
          muted
          playsInline
          controls
        />
        {overlay?.(state.kind === 'playing')}
      </div>

      {state.kind === 'starting' && <div className="calib-sub">Connecting to the phone…</div>}
      {state.kind === 'error' && <div className="calib-sub">{state.message}</div>}
    </div>
  );
}
