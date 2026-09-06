import type { ClipView } from '../api';

export interface DayGroup {
  day: string;
  clips: ClipView[];
}

/** A "clip" (the gallery item) is a contiguous run of segments, uniquely
 * identified on the controller by (phoneId, clipId). Gallery/Trash selection is
 * tracked as that pair joined with '|'. */
export function clipKey(c: Pick<ClipView, 'clipId' | 'phoneId'>): string {
  return c.phoneId + '|' + c.clipId;
}

export function groupByDay(clips: ClipView[]): DayGroup[] {
  const byDay = new Map<string, ClipView[]>();
  for (const c of clips) {
    const day = new Date(c.startedAtMs).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
    if (!byDay.has(day)) byDay.set(day, []);
    byDay.get(day)!.push(c);
  }
  return Array.from(byDay.entries()).map(([day, dayClips]) => ({ day, clips: dayClips }));
}
