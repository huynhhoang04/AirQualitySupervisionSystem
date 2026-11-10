/** Vietnamese formatting for every number the dashboard shows. */

export function formatAgo(iso: string | null | undefined): string {
  if (!iso) return "chưa có";
  const seconds = Math.max(0, Math.round((Date.now() - Date.parse(iso)) / 1000));
  if (seconds < 10) return "vừa xong";
  if (seconds < 60) return `${seconds} giây trước`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} phút trước`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} giờ trước`;
  return `${Math.round(seconds / 86400)} ngày trước`;
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

export function formatDuration(minutes: number): string {
  if (minutes < 60) return `${Math.round(minutes)} phút`;
  const hours = Math.floor(minutes / 60);
  const rest = Math.round(minutes % 60);
  return rest === 0 ? `${hours} giờ` : `${hours} giờ ${rest} phút`;
}

/** One definition of "the node is alive": telemetry arrives every 5 s. */
export const ONLINE_WINDOW_MS = 30_000;

export function isOnline(lastSeenAt: string | null | undefined): boolean {
  if (!lastSeenAt) return false;
  return Date.now() - Date.parse(lastSeenAt) < ONLINE_WINDOW_MS;
}

export type Tone = "good" | "warn" | "bad" | "muted";

/**
 * The one place a PM2.5 value becomes a colour. Amber starts at the hysteresis point (70 % of the
 * threshold) because that is where the backend is about to intervene.
 */
export function pm25Tone(value: number | null | undefined, threshold: number): Tone {
  if (value == null) return "muted";
  if (value >= threshold) return "bad";
  if (value >= threshold * 0.7) return "warn";
  return "good";
}

export const TONE_TEXT: Record<Tone, string> = {
  good: "text-good",
  warn: "text-warn",
  bad: "text-bad",
  muted: "text-muted",
};

export const TONE_BG: Record<Tone, string> = {
  good: "bg-good",
  warn: "bg-warn",
  bad: "bg-bad",
  muted: "bg-muted",
};

/** CO2 is advisory only — HEPA cannot remove it (io_contract §10). */
export function co2Tone(value: number | null | undefined): Tone {
  if (value == null) return "muted";
  if (value > 1500) return "bad";
  if (value > 1000) return "warn";
  return "good";
}

export const FAN_DUTY = [0, 35, 60, 90] as const;

export function fanDuty(level: number): number {
  return FAN_DUTY[Math.max(0, Math.min(level, 3))] ?? 0;
}

/** Minutes since an ISO instant, for "on for 12 phút". */
export function minutesSince(iso: string | null | undefined): number | null {
  if (!iso) return null;
  return Math.max(0, (Date.now() - Date.parse(iso)) / 60_000);
}
