import type { Device, Space, Telemetry } from "../api/types";

/**
 * The product's vocabulary, in one place.
 *
 * A room name is free text the operator typed. Everything that used to be a technical field —
 * purpose, ventilation, volume — is derived from a template, so the connect flow never asks a
 * question a filter owner cannot answer.
 */

export interface RoomTemplate {
  key: string;
  label: string;
  /** Filled into the space row; invisible to the user but used by ML#1 when it exists. */
  purpose: string;
  volumeM3: number;
  occupancyHours: number;
  ventilation: string;
  /** Threshold in µg/m³, chosen to match the template's typical occupancy. */
  threshold: number;
  hint: string;
}

export const ROOM_TEMPLATES: RoomTemplate[] = [
  {
    key: "bedroom",
    label: "Phòng ngủ",
    purpose: "bedroom",
    volumeM3: 15,
    occupancyHours: 8,
    ventilation: "low",
    threshold: 25,
    hint: "ngủ 8 giờ mỗi đêm",
  },
  {
    key: "living",
    label: "Phòng khách",
    purpose: "living_room",
    volumeM3: 28,
    occupancyHours: 4,
    ventilation: "medium",
    threshold: 35,
    hint: "sinh hoạt buổi tối",
  },
  {
    key: "classroom",
    label: "Lớp học",
    purpose: "classroom",
    volumeM3: 60,
    occupancyHours: 6,
    ventilation: "medium",
    threshold: 30,
    hint: "đông người, 6 giờ mỗi ngày",
  },
  {
    key: "lobby",
    label: "Sảnh",
    purpose: "office",
    volumeM3: 80,
    occupancyHours: 2,
    ventilation: "high",
    threshold: 45,
    hint: "người đi lại liên tục",
  },
  {
    key: "custom",
    label: "Khác",
    purpose: "living_room",
    volumeM3: 20,
    occupancyHours: 8,
    ventilation: "medium",
    threshold: 35,
    hint: "tự nhập thông số phòng",
  },
];

export function templateOf(key: string): RoomTemplate {
  return ROOM_TEMPLATES.find((template) => template.key === key) ?? ROOM_TEMPLATES[0];
}

export type FanState = "ON" | "OFF";
export type FanMode = "AUTO" | "MANUAL";

export type DecisionReason =
  | "threshold_breach"
  | "hysteresis_off"
  | "max_on"
  | "manual"
  | null;

export type AirStatus = "good" | "filtering" | "watch";

// ── Time ────────────────────────────────────────────────────────────────────

export const ONLINE_WINDOW_MS = 30_000;
export const STALE_READING_MS = 5 * 60_000;
export const FILTER_LIFE_HOURS = 2000;
export const FILTER_WARN_HOURS = 100;

/** The loop's timing, from the same defaults the backend uses (application.yml §aqss.control). */
export const CYCLE_SECONDS = 60;
export const MIN_OFF_MINUTES = 3;

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
  if (hours >= 10) return `${Math.round(hours).toLocaleString("vi-VN")} giờ`;
  if (hours >= 1) return `${hours.toFixed(1)} giờ`;
  return `${Math.max(1, Math.round(hours * 60))} phút`;
}

// ── The device's state, in one word ─────────────────────────────────────────

export interface RoomState {
  status: AirStatus;
  word: string;
  detail: string;
  automatic: boolean;
}

const REASON_TEXT: Record<Exclude<DecisionReason, null>, string> = {
  threshold_breach: "vì bụi vượt ngưỡng",
  hysteresis_off: "vì không khí đã xuống dưới mức tắt",
  max_on: "vì đã chạy đủ lâu tối đa",
  manual: "vì bạn điều chỉnh",
};

export function roomState(
  device: Device | undefined,
  latest: Telemetry | undefined,
  space: Space | undefined,
): RoomState {
  const threshold = space?.threshold_pm25 ?? 35;
  const pm25 = latest?.pm25 ?? null;
  const fanOn = device?.fan_state === "ON";
  const reason = device?.last_reason ?? null;

  if (pm25 == null) {
    const online = isOnline(device?.last_seen_at);
    return {
      status: "good",
      word: online ? "Đang chờ" : "Mất liên lạc",
      detail: online
        ? "máy đã kết nối, chưa gửi số đo đầu tiên"
        : `số liệu cuối ${formatAgo(device?.last_seen_at ?? latest?.recorded_at)}`,
      automatic: false,
    };
  }

  if (fanOn) {
    const since = minutesSince(device?.on_since);
    const when =
      since == null ? "" : `${reason === "manual" ? "bật" : "tự bật"} ${formatMinutes(since)} trước`;
    const why = reason && reason in REASON_TEXT ? REASON_TEXT[reason] : "đang lọc";
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
    const restLeft = MIN_OFF_MINUTES - (minutesSince(device?.off_since) ?? MIN_OFF_MINUTES);
    const wait =
      restLeft > 0
        ? `sau ${Math.ceil(restLeft)} phút nữa (quạt đang nghỉ tối thiểu)`
        : "trong vòng 1 phút";
    return {
      status: "watch",
      word: "Cần chú ý",
      detail: `bụi trên ngưỡng — quạt sẽ tự bật ${wait}`,
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

export function fanBehaviour(space: Space | undefined): { on: string; off: string } {
  const threshold = space?.threshold_pm25 ?? 35;
  return {
    on: `bật khi bụi ≥ ${threshold.toFixed(1)} µg/m³`,
    off: `tắt khi xuống dưới ${(threshold * 0.7).toFixed(1)} µg/m³`,
  };
}

export function filterHours(device: Device | undefined): {
  used: number;
  remaining: number;
  low: boolean;
} {
  const used = (device?.filter_runtime_s ?? 0) / 3600;
  const remaining = Math.max(0, FILTER_LIFE_HOURS - used);
  return { used, remaining, low: remaining < FILTER_WARN_HOURS };
}

// ── Fan control ─────────────────────────────────────────────────────────────

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
  if (position.mode === "AUTO") return current.mode === "AUTO";
  if (current.mode !== "MANUAL") return false;
  if (!position.on) return !current.on;
  return current.on && current.level === position.level;
}

export function fanCommand(selection: FanSelection): {
  state: "ON" | "OFF";
  level: number;
  mode: FanMode;
} {
  return {
    state: selection.on ? "ON" : "OFF",
    level: selection.on ? Math.max(1, selection.level) : 0,
    mode: selection.mode,
  };
}
