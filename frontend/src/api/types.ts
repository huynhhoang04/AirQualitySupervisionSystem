export type FanState = "ON" | "OFF";
export type FanMode = "AUTO" | "MANUAL";

export interface Device {
  device_id: string;
  name: string | null;
  space_code: string | null;
  fan_state: FanState;
  fan_level: number;
  fan_mode: FanMode;
  on_since: string | null;
  last_command_at: string | null;
  telemetry_interval_ms: number;
  dust_baseline_us: number;
  last_seen_at: string | null;
  created_at: string;
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

export interface CommandRequest {
  command: string;
  params: Record<string, unknown>;
}

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
