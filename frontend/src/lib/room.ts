import type { AirStatus, DecisionReason, Device, FanMode, Space, Telemetry } from "../api/types";

/**
 * The one place the product's vocabulary is decided.
 *
 * The screens never show a raw number where a word will do: a room is "Tốt" or "Đang lọc", the fan
 * "đang tắt" or "tự bật 12 phút trước", and the reason a decision happened is a sentence, not a
 * database enum.
 */

export const ONLINE_WINDOW_MS = 30_000;
/** A reading older than this does not drive the fan (CONTROL_STALE_READING_MINUTES). */
export const STALE_READING_MS = 5 * 60_000;
/** Filter life: the node counts hours, this is where "sắp hết" starts. */
export const FILTER_LOW_HOURS = 100;

export function isOnline(lastSeenAt: string | null | undefined): boolean {
  if (!lastSeenAt) return false;
  return Date.now() - Date.parse(lastSeenAt) < ONLINE_WINDOW_MS;
}

export function formatAgo(iso: string | null | undefined): string {
  if (!iso) return "chưa có";
  const seconds = Math.round((Date.now() - Date.parse(iso)) / 1000);
  if (seconds < 10) return "vừa xong";
  if (seconds < 60) return `${seconds} giây trước`;
  if (seconds < 3600) return `${Math.round(seconds / 60)} phút trước`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)} giờ trước`;
  return `${Math.round(seconds / 86400)} ngày trước`;
}

/** "12 phút" / "1 giờ 20 phút" — how long something has been happening. */
export function formatMinutes(totalMinutes: number): string {
  const minutes = Math.max(0, Math.round(totalMinutes));
  if (minutes < 60) return `${minutes} phút`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} giờ` : `${hours} giờ ${rest} phút`;
}

export function minutesSince(iso: string | null | undefined): number | null {
  if (!iso) return null;
  return Math.max(0, (Date.now() - Date.parse(iso)) / 60_000);
}

export function formatHours(hours: number): string {
  if (hours >= 100) return `${Math.round(hours).toLocaleString("vi-VN")} giờ`;
  if (hours >= 1) return `${hours.toFixed(0)} giờ`;
  return `${Math.round(hours * 60)} phút`;
}

// ── The room's state, in one word ───────────────────────────────────────────

export interface RoomState {
  status: AirStatus;
  /** "Tốt" · "Đang lọc" · "Cần chú ý" */
  word: string;
  /** One sentence: what the system is doing and why. */
  detail: string;
  /** True when the fan is running under the control loop's own decision. */
  automatic: boolean;
}

const REASON_TEXT: Record<Exclude<DecisionReason, null>, string> = {
  threshold_breach: "vì bụi vượt ngưỡng",
  forecast_breach: "vì dự báo báo trước",
  hysteresis_off: "vì không khí đã xuống dưới mức tắt",
  max_on: "vì đã chạy đủ lâu tối đa",
  manual: "vì bạn điều chỉnh",
};

export function roomState(device: Device | undefined, latest: Telemetry | undefined, space: Space | undefined): RoomState {
  const threshold = space?.threshold_pm25 ?? 35;
  const pm25 = latest?.pm25 ?? null;
  const fanOn = device?.fan_state === "ON";
  const reason = device?.last_reason ?? null;

  if (pm25 == null) {
    return {
      status: "good",
      word: isOnline(device?.last_seen_at) ? "Đang chờ" : "Mất liên lạc",
      detail: isOnline(device?.last_seen_at)
        ? "máy đã kết nối, chưa gửi số đo đầu tiên"
        : `số liệu cuối ${formatAgo(device?.last_seen_at ?? latest?.recorded_at)}`,
      automatic: false,
    };
  }

  if (fanOn) {
    const since = minutesSince(device?.on_since);
    const when = since == null ? "" : `${reason === "manual" ? "bật" : "tự bật"} ${formatMinutes(since)} trước`;
    const why = reason ? REASON_TEXT[reason] : "đang lọc";
    // Still filtering, but the air is already under the cut-off: say so, so the word and the number
    // do not disagree on screen.
    const settling = pm25 < threshold;
    return {
      status: settling ? "good" : "filtering",
      word: "Đang lọc",
      detail: settling
        ? `${when} · ${why} · sắp đạt ngưỡng tắt ${(threshold * 0.7).toFixed(1)}`
        : `${when} · ${why}`,
      automatic: device?.fan_mode === "AUTO",
    };
  }

  if (pm25 >= threshold) {
    return {
      status: "watch",
      word: "Cần chú ý",
      detail: "bụi trên ngưỡng — quạt sẽ tự bật ở chu kỳ tới",
      automatic: false,
    };
  }

  return {
    status: "good",
    word: "Tốt",
    detail: `dưới ngưỡng ${threshold.toFixed(0)} µg/m³ · quạt đang tắt`,
    automatic: false,
  };
}

/** What the fan will do next, in the user's terms — never "0.7 × threshold". */
export function fanBehaviour(space: Space | undefined): { on: string; off: string } {
  const threshold = space?.threshold_pm25 ?? 35;
  return {
    on: `bật khi bụi ≥ ${threshold.toFixed(1)} µg/m³`,
    off: `tắt khi xuống dưới ${(threshold * 0.7).toFixed(1)} µg/m³`,
  };
}

export function filterHours(device: Device | undefined): { used: number; low: boolean } {
  const seconds = device?.filter_runtime_s ?? 0;
  const used = seconds / 3600;
  return { used, low: used > 0 && used < FILTER_LOW_HOURS };
}

// ── Fan control: five mutually exclusive positions ──────────────────────────

export interface FanSelection {
  mode: FanMode;
  on: boolean;
  level: number;
}

export function fanSelection(device: Device | undefined): FanSelection {
  return {
    mode: device?.fan_mode ?? "AUTO",
    on: device?.fan_state === "ON",
    level: device?.fan_level ?? 0,
  };
}

/**
 * A position is "selected" only when the machine is in exactly that state. AUTO has no level of its
 * own — the fan may well be running under AUTO — so it compares on mode alone.
 */
export function isPositionActive(
  current: FanSelection,
  position: { mode: FanMode; on: boolean; level: number },
): boolean {
  if (position.mode === "AUTO") {
    return current.mode === "AUTO";
  }
  if (current.mode !== "MANUAL") {
    return false;
  }
  if (!position.on) {
    return !current.on;
  }
  return current.on && current.level === position.level;
}

/** Picking any position is one command, not a sequence. */
export function fanCommand(selection: FanSelection): { state: "ON" | "OFF"; level: number; mode: FanMode } {
  return {
    state: selection.on ? "ON" : "OFF",
    level: selection.on ? Math.max(1, selection.level) : 0,
    mode: selection.mode,
  };
}
