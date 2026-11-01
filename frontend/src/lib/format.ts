export function formatAgo(iso: string | null): string {
  if (!iso) return "chưa có";
  const seconds = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
  if (seconds < 60) return `${seconds}s trước`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}p trước`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)}h trước`;
  return `${Math.round(seconds / 86400)}d trước`;
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" });
}

export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString("vi-VN", {
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

/** Green / amber / red against the space threshold. */
export function pm25Tone(value: number | null, threshold: number): string {
  if (value === null) return "text-slate-500";
  if (value >= threshold) return "text-rose-400";
  if (value >= threshold * 0.7) return "text-amber-300";
  return "text-emerald-400";
}

export function isOnline(lastSeenAt: string | null): boolean {
  if (!lastSeenAt) return false;
  return Date.now() - Date.parse(lastSeenAt) < 30_000;
}
