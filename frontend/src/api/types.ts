export type FanState = "ON" | "OFF";
export type FanMode = "AUTO" | "MANUAL";

/** Why the loop last acted — the server's own vocabulary (io_contract §10). */
export type DecisionReason =
  | "threshold_breach"
  | "forecast_breach"
  | "hysteresis_off"
  | "max_on"
  | "manual"
  | null;

/** What the room screen shows at the top: one word, not a number. */
export type AirStatus = "good" | "filtering" | "watch";

export interface Device {
  device_id: string;
  name: string | null;
  space_code: string | null;
  pairing_code: string | null;
  paired_at: string | null;
  fan_state: FanState;
  fan_level: number;
  fan_mode: FanMode;
  on_since: string | null;
  off_since: string | null;
  last_command_at: string | null;
  telemetry_interval_ms: number;
  dust_baseline_us: number;
  last_seen_at: string | null;
  created_at: string;
  /** Fan hours counted by the node. */
  filter_runtime_s: number;
  filter_replaced_at: string | null;
  last_reason: DecisionReason;
  last_decision_at: string | null;
}

export interface Telemetry {
  id: number;
  device_id: string;
  recorded_at: string;
  pm25: number | null;
  co2: number | null;
  temperature: number | null;
  humidity: number | null;
  wifi_rssi: number | null;
  pm25_saturated: boolean;
}

export interface Space {
  code: string;
  name: string;
  volume_m3: number | null;
  purpose: string | null;
  region: string | null;
  ventilation_estimate: string | null;
  threshold_pm25: number;
  threshold_source: "manual" | "ai";
  updated_at: string;
}

export interface AuditEntry {
  id: number;
  occurred_at: string;
  actor: "USER" | "BACKEND" | "DEVICE" | "AI";
  action: string;
  device_id: string | null;
  detail: string | null;
}

export interface ForecastPoint {
  target_time: string;
  pm25_q10: number;
  pm25_q50: number;
  pm25_q90: number;
}

export type FanCommand =
  | { command: "set_actuator"; params: { state: FanState; level: number; mode: FanMode } }
  | { command: "reboot" }
  | { command: "recalibrate_dust"; params?: { baseline_w0?: number } }
  | { command: "force_ntp_sync" }
  | { command: "set_interval"; params: { interval_ms: number } }
  | { command: "set_space"; params: { space_id: string } }
  | { command: "display_warning"; params: { message: string; severity: string } };

export interface DeviceUpdateRequest {
  name?: string;
  space_code?: string;
  telemetry_interval_ms?: number;
  dust_baseline_us?: number;
}

export interface SpaceRequest {
  name?: string;
  volume_m3?: number;
  purpose?: string;
  region?: string;
  ventilation_estimate?: string;
  threshold_pm25?: number;
}

export interface PairRequest {
  pairing_code: string;
  space_code: string;
  name?: string;
}
