import { useCallback, useEffect, useRef, useState } from 'preact/hooks';

/** Ask for the next still this long after the last one arrived. The phone shares any still
 *  younger than 1s between viewers, so polling faster than that buys nothing. */
const POLL_MS = 2000;
/** After a camera change, look sooner - but not before the phone's 1s still cache has expired,
 *  or the "fresh" still would be the one from before the change. */
const NUDGE_MS = 1200;
/** A 503 without a hint (overheated) or a phone that can't be reached: back off. */
const SLOW_RETRY_MS = 10000;

export type StillState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'showing'; url: string; capturedAtMs: number; problem: string | null }
  | { kind: 'error'; message: string };

export interface StillController {
  state: StillState;
  /** A still is on screen: the controls and rect pickers can work over it. */
  showing: boolean;
  /** Fetch a still soon, rather than at the next poll: after a camera change, so the user sees
   *  its effect without waiting out the interval. */
  nudge: () => void;
}

/**
 * Stills of what a recording phone is encoding - the recording view's stand-in for the live
 * video, which the phone can't afford to run alongside its recording encoder
 * (docs/design/decisions/0015-recording-stills.md).
 *
 * The phone renders a still only when asked, so this is what decides whether a phone does any
 * of that work at all: it polls only while [enabled] (Phone detail open on a recording phone),
 * and not while the window is hidden. A request starts only once the previous one has finished,
 * so a slow phone is never asked twice at once.
 *
 * [onNotRecording] fires when the phone answers 409 - it has left record mode since the screen
 * last looked - and polling stops until the caller re-enables it.
 */
export function useStillPreview(phoneId: string, enabled: boolean, onNotRecording?: () => void): StillController {
  const [state, setState] = useState<StillState>({ kind: 'idle' });
  const urlRef = useRef<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const tickRef = useRef<(() => void) | null>(null);
  const onNotRecordingRef = useRef(onNotRecording);
  onNotRecordingRef.current = onNotRecording;

  const schedule = useCallback((ms: number) => {
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => tickRef.current?.(), ms);
  }, []);

  useEffect(() => {
    const releaseUrl = () => {
      if (urlRef.current) URL.revokeObjectURL(urlRef.current);
      urlRef.current = null;
    };
    if (!enabled) {
      releaseUrl();
      setState({ kind: 'idle' });
      return;
    }

    let cancelled = false;
    // Per run of this effect, not a ref: a fetch still in flight from a previous phone must not
    // stop this run's first tick.
    let inFlight = false;
    // Keep the last picture up through a transient failure, marked stale, rather than blanking
    // the viewport (and its controls) every time the phone's camera restarts.
    const fail = (message: string) =>
      setState((s) => (s.kind === 'showing' ? { ...s, problem: message } : { kind: 'error', message }));

    const tick = async () => {
      if (cancelled || inFlight) return;
      if (document.hidden) {
        schedule(POLL_MS);
        return;
      }
      inFlight = true;
      let next = POLL_MS;
      try {
        const res = await fetch(`/still/${encodeURIComponent(phoneId)}`, { cache: 'no-store' });
        if (cancelled) return;
        if (res.ok) {
          const blob = await res.blob();
          if (cancelled) return;
          const url = URL.createObjectURL(blob);
          releaseUrl();
          urlRef.current = url;
          const at = Number(res.headers.get('X-Captured-At-Ms')) || Date.now();
          setState({ kind: 'showing', url, capturedAtMs: at, problem: null });
        } else if (res.status === 409) {
          releaseUrl();
          setState({ kind: 'idle' });
          onNotRecordingRef.current?.();
          return; // not recording any more: nothing to poll for
        } else {
          const body = (await res.json().catch(() => ({}))) as { error?: string; retryAfterMs?: number };
          if (cancelled) return;
          fail(
            res.status === 504
              ? 'The phone is not reachable.'
              : body.error || `The phone answered HTTP ${res.status}.`,
          );
          next = body.retryAfterMs && body.retryAfterMs > 0 ? body.retryAfterMs : SLOW_RETRY_MS;
        }
      } catch (err) {
        if (cancelled) return;
        fail(String(err));
        next = SLOW_RETRY_MS;
      } finally {
        inFlight = false;
      }
      if (!cancelled) schedule(next);
    };

    tickRef.current = () => void tick();
    setState((s) => (s.kind === 'showing' ? s : { kind: 'loading' }));
    void tick();
    return () => {
      cancelled = true;
      tickRef.current = null;
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = null;
    };
  }, [phoneId, enabled, schedule]);

  // Revoke the last object URL when the screen goes away.
  useEffect(
    () => () => {
      if (urlRef.current) URL.revokeObjectURL(urlRef.current);
    },
    [],
  );

  const nudge = useCallback(() => {
    if (tickRef.current) schedule(NUDGE_MS);
  }, [schedule]);

  return { state, showing: state.kind === 'showing', nudge };
}

/** Presentational half: the still, sized to its own aspect ratio exactly like the live <video>, so
 *  the shared overlays' 0..1 coordinates are 0..1 over the recorded frame. */
export function StillPreviewImage({ still }: { still: StillController }) {
  const { state } = still;
  if (state.kind === 'showing') {
    return (
      <div class="live-preview">
        <div class={`live-video-wrap ${state.problem ? 'stale' : ''}`}>
          <img class="live-video still-image" src={state.url} alt="Latest still from the phone" draggable={false} />
          {state.problem && <div class="still-problem">{state.problem}</div>}
        </div>
      </div>
    );
  }
  return (
    <div class="live-preview">
      <div class={`live-frame-idle ${state.kind === 'error' ? 'error' : ''}`}>
        {state.kind === 'error' ? state.message : 'Fetching a still from the phone…'}
      </div>
    </div>
  );
}
