import type { ComponentChildren } from 'preact';
import { useEffect, useRef } from 'preact/hooks';

/** Drag-to-scroll, item-snapping horizontal row — a direct port of the phone
 * Preview mock's `makeHScroll` / `computeSnapStops` / `centerItemInScroller` /
 * `padEndsForCentering` (docs/implementation/HANDOFF-phone-ux.md, "Adjust bar").
 *
 * - Pointer-capture drag; on release, snap to the nearest valid item stop.
 * - A stop that would leave the FIRST or LAST child only partly visible is
 *   dropped — those two are always fully in or fully out, never peeking.
 * - Because pointer-capture retargets the browser's click to the scroller, a
 *   tap on a child is detected as "no drag" and replayed with `.click()`.
 * - Track ends are padded by half a viewport so a tapped first/last item can
 *   still be centred. */
export function HScroll({
  children,
  className = '',
  centerKey,
}: {
  children: ComponentChildren;
  className?: string;
  /** When this changes, the child whose `data-key` matches is scrolled to centre. */
  centerKey?: string;
}) {
  const elRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const el = elRef.current;
    if (!el) return;

    const track = el.firstElementChild as HTMLElement | null;
    const firstItem = track?.firstElementChild as HTMLElement | null;
    if (track && firstItem) {
      const pad = Math.max(0, (el.clientWidth - firstItem.offsetWidth) / 2);
      track.style.paddingLeft = track.style.paddingRight = `${pad}px`;
    }

    let isDown = false;
    let moved = false;
    let startX = 0;
    let startScroll = 0;
    let downTarget: HTMLElement | null = null;

    const computeSnapStops = () => {
      const items = track ? ([...track.children] as HTMLElement[]) : [];
      const cw = el.clientWidth;
      const maxScroll = Math.max(0, el.scrollWidth - cw);
      if (items.length === 0) return [0];
      const first = items[0];
      const firstRight = first.offsetLeft + first.offsetWidth;
      const last = items[items.length - 1];
      const lastLeft = last.offsetLeft;
      const lastRight = lastLeft + last.offsetWidth;
      let stops = items.map((it) => Math.min(Math.max(it.offsetLeft, 0), maxScroll));
      stops = stops.filter((s) => {
        const vl = s;
        const vr = s + cw;
        const cutsFirst = vl > 0 && vl < firstRight;
        const cutsLast = vr > lastLeft && vr < lastRight;
        return !cutsFirst && !cutsLast;
      });
      stops.push(0, maxScroll);
      return [...new Set(stops.map((s) => Math.round(s)))].sort((a, b) => a - b);
    };

    const snap = () => {
      const stops = computeSnapStops();
      let best = stops[0];
      let bestDist = Infinity;
      for (const s of stops) {
        const d = Math.abs(s - el.scrollLeft);
        if (d < bestDist) {
          bestDist = d;
          best = s;
        }
      }
      el.scrollTo({ left: best, behavior: 'smooth' });
    };

    const onDown = (e: PointerEvent) => {
      isDown = true;
      moved = false;
      startX = e.clientX;
      startScroll = el.scrollLeft;
      downTarget = (e.target as HTMLElement).closest('button');
      el.setPointerCapture(e.pointerId);
      el.classList.add('dragging');
    };
    const onMove = (e: PointerEvent) => {
      if (!isDown) return;
      const dx = e.clientX - startX;
      if (Math.abs(dx) > 3) moved = true;
      el.scrollLeft = startScroll - dx;
    };
    const onUp = (e: PointerEvent) => {
      if (!isDown) return;
      isDown = false;
      el.classList.remove('dragging');
      try {
        el.releasePointerCapture(e.pointerId);
      } catch {
        /* not captured */
      }
      if (moved) snap();
      else if (downTarget) downTarget.click();
      downTarget = null;
    };
    const onCancel = () => {
      isDown = false;
      el.classList.remove('dragging');
      downTarget = null;
    };

    el.addEventListener('pointerdown', onDown);
    el.addEventListener('pointermove', onMove);
    el.addEventListener('pointerup', onUp);
    el.addEventListener('pointercancel', onCancel);
    return () => {
      el.removeEventListener('pointerdown', onDown);
      el.removeEventListener('pointermove', onMove);
      el.removeEventListener('pointerup', onUp);
      el.removeEventListener('pointercancel', onCancel);
    };
  }, []);

  // Centre the active item when the selection changes.
  useEffect(() => {
    const el = elRef.current;
    const track = el?.firstElementChild as HTMLElement | null;
    if (!el || !track || centerKey == null) return;
    const item = track.querySelector<HTMLElement>(`[data-key="${CSS.escape(centerKey)}"]`);
    if (!item) return;
    const maxScroll = Math.max(0, el.scrollWidth - el.clientWidth);
    const target = Math.min(
      Math.max(item.offsetLeft - (el.clientWidth - item.offsetWidth) / 2, 0),
      maxScroll,
    );
    el.scrollTo({ left: target, behavior: 'smooth' });
  }, [centerKey]);

  return (
    <div ref={elRef} className={`hscroll ${className}`}>
      <div className="hscroll-track">{children}</div>
    </div>
  );
}
