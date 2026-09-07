import type { JSX } from 'preact';
import { useEffect, useLayoutEffect, useRef } from 'preact/hooks';
import { rulerIcon, type RulerIconName } from './icons';

export interface RulerSpec {
  min: number;
  max: number;
  /** Pixels of tick strip per 1 unit of value — sets drag sensitivity. */
  pxPerUnit: number;
  microStep: number;
  tickStep: number;
  majorStep: number;
  endIcons: [RulerIconName, RulerIconName];
}

/** Pixel-camera-style drag-to-scrub ruler — port of the phone Preview mock's
 * `renderRuler` + `wireRuler`. Fixed-width window; the tick strip is wider than
 * the window and slides under a thumb that never moves. No numeric readout
 * (removed per HANDOFF-phone-ux.md) — the live video is the feedback. */
export function Ruler({
  spec,
  value,
  onChange,
  onCommit,
  tint,
}: {
  spec: RulerSpec;
  value: number;
  onChange: (v: number) => void;
  onCommit?: (v: number) => void;
  /** Colour for the two end-icons (e.g. per-channel red/green/blue). */
  tint?: string;
}) {
  const trackRef = useRef<HTMLDivElement | null>(null);
  const stripRef = useRef<HTMLDivElement | null>(null);
  const drag = useRef<{ x: number; startVal: number } | null>(null);
  const latest = useRef(value);
  latest.current = value;

  const position = () => {
    const track = trackRef.current;
    const strip = stripRef.current;
    if (!track || !strip) return;
    const center = track.offsetWidth / 2;
    strip.style.transform = `translateX(${center - latest.current * spec.pxPerUnit}px)`;
  };

  useLayoutEffect(position);
  useEffect(() => {
    const onResize = () => position();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const clamp = (n: number) => Math.min(spec.max, Math.max(spec.min, n));

  const ticks: JSX.Element[] = [];
  const step = spec.microStep || spec.tickStep;
  const first = Math.ceil(spec.min / step) * step;
  for (let v = first, i = 0; v <= spec.max + 1e-6; v += step, i++) {
    const isMajor = Math.abs(v - Math.round(v / spec.majorStep) * spec.majorStep) < step / 2;
    const isMinor =
      !isMajor && Math.abs(v - Math.round(v / spec.tickStep) * spec.tickStep) < step / 2;
    ticks.push(
      <div
        key={i}
        className={`tick ${isMajor ? 'major' : isMinor ? 'minor' : 'micro'}`}
        style={{ left: `${v * spec.pxPerUnit}px` }}
      />,
    );
  }

  return (
    <div
      className="ruler"
      onPointerDown={(e) => {
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
        drag.current = { x: e.clientX, startVal: value };
      }}
      onPointerMove={(e) => {
        if (!drag.current) return;
        const dx = e.clientX - drag.current.x;
        onChange(clamp(drag.current.startVal - dx / spec.pxPerUnit));
      }}
      onPointerUp={(e) => {
        if (drag.current) {
          try {
            (e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId);
          } catch {
            /* not captured */
          }
          drag.current = null;
          onCommit?.(latest.current);
        }
      }}
      onPointerCancel={() => {
        drag.current = null;
      }}
    >
      <div className="ruler-body">
        <div className="ruler-endicon" style={tint ? { color: tint } : undefined}>
          {rulerIcon(spec.endIcons[0])}
        </div>
        <div className="ruler-track" ref={trackRef}>
          <div className="strip" ref={stripRef}>
            {ticks}
          </div>
        </div>
        <div className="ruler-endicon" style={tint ? { color: tint } : undefined}>
          {rulerIcon(spec.endIcons[1])}
        </div>
        <div className="thumb" />
      </div>
    </div>
  );
}
