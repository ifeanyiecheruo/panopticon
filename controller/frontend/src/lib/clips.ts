import type { ClipView } from '../api';

export interface DayGroup {
  day: string;
  clips: ClipView[];
}

/** Same composite identity used throughout the vanilla version: a clip is
 * unique per (phoneId, filename), and gallery/trash selection is tracked as
 * that pair joined with '|'. */
export function clipKey(c: Pick<ClipView, 'filename' | 'phoneId'>): string {
  return c.filename + '|' + c.phoneId;
}

export function groupByDay(clips: ClipView[]): DayGroup[] {
  const byDay = new Map<string, ClipView[]>();
  for (const c of clips) {
    const day = new Date(c.createdAtMs).toLocaleDateString(undefined, {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
    });
    if (!byDay.has(day)) byDay.set(day, []);
    byDay.get(day)!.push(c);
  }
  return Array.from(byDay.entries()).map(([day, dayClips]) => ({ day, clips: dayClips }));
}
