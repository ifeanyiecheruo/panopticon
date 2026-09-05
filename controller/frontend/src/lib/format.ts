export function fmtBytes(n: number): string {
  if (!n) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 10 || i === 0 ? 0 : 1)} ${units[i]}`;
}

export function fmtDuration(ms: number): string {
  const sec = Math.round((ms || 0) / 1000);
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function statusLabel(s: string): string {
  const labels: Record<string, string> = {
    recording: 'Recording',
    standby: 'Standby',
    unreachable: 'Unreachable',
  };
  return labels[s] || s;
}
