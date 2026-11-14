import type { DecisionReason, FanMode, FanState } from "../lib/room";

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
  last_telemetry_received_at: string | null;
  last_actuator_received_at: string | null;
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
  received_at: string | null;
  time_quality: string | null;
  pm25: number | null;
  co2: number | null;
  temperature: number | null;
  humidity: number | null;
  wifi_rssi: number | null;
  pm25_saturated: boolean;
  dust_fault: boolean | null;
  uptime_sec: number | null;
  free_heap: number | null;
  min_free_heap: number | null;
}

export interface Space {
  code: string;
  name: string;
  volume_m3: number | null;
  purpose: string | null;
  region: string | null;
  ventilation_estimate: string | null;
  occupancy_hours: number | null;
  profile_version: number;
  threshold_pm25: number;
  threshold_source: "manual" | "ai" | "profile";
  updated_at: string;
}

// ── Analytics ───────────────────────────────────────────────────────────────

export interface SeriesBucket {
  bucket_start: string;
  median: number | null;
  min: number | null;
  max: number | null;
  count: number;
  expected_count: number;
  coverage: number;
}

export interface Series {
  device_id: string;
  from: string;
  to: string;
  bucket: string;
  bucket_seconds: number;
  items: SeriesBucket[];
}

export interface Summary {
  device_id: string;
  from: string;
  to: string;
  total_rows: number;
  pm_count: number;
  avg_pm25: number | null;
  median_pm25: number | null;
  max_pm25: number | null;
  coverage: number;
  minutes_above_threshold: number;
  unknown_minutes: number;
  total_minutes: number;
  threshold_pm25: number;
}

export interface Sample {
  id: number;
  device_id: string;
  recorded_at: string;
  received_at: string | null;
  pm25: number | null;
  co2: number | null;
  temperature: number | null;
  humidity: number | null;
  wifi_rssi: number | null;
  pm25_saturated: boolean;
  dust_fault: boolean | null;
  time_quality: string | null;
}

export interface SamplePage {
  device_id: string;
  from: string;
  to: string;
  items: Sample[];
  next_cursor: string | null;
}

// ── Commands and actuator reports ───────────────────────────────────────────

export interface CommandLog {
  id: string;
  device_id: string;
  command: string;
  params: Record<string, unknown> | null;
  actor: string;
  requested_at: string;
  published_at: string | null;
  matched_at: string | null;
  publish_attempts: number;
  status: "REQUESTED" | "PUBLISHED" | "PUBLISH_FAILED" | "REPORT_MATCHED" | "UNCONFIRMED" | "SUPERSEDED";
  error: string | null;
  expires_at: string | null;
}

export interface ActuatorHistory {
  id: number;
  device_id: string;
  recorded_at: string | null;
  received_at: string;
  state: FanState | null;
  level: number | null;
  duty_pct: number | null;
  mode: FanMode | null;
  filter_runtime_s: number | null;
  quality: Record<string, unknown>;
}

// ── AI ──────────────────────────────────────────────────────────────────────

export interface ForecastPoint {
  horizon_minutes: number;
  target_at: string;
  pm25: number;
  actual_pm25: number | null;
  error: number | null;
  evaluation_status: string;
}

export interface ForecastRun {
  id: string;
  device_id: string;
  model_id: string | null;
  model_version: string;
  input_cutoff: string;
  issued_at: string;
  accepted_at: string;
  profile_version: number | null;
  threshold_pm25: number | null;
  data_quality: Record<string, unknown> | null;
  worker_id: string | null;
  status: string;
}

export interface ForecastView {
  run: ForecastRun;
  points: ForecastPoint[];
}

export interface AiWorkerStatus {
  id: string;
  online: boolean;
  last_heartbeat_at: string | null;
  model_artifact: string | null;
}

export interface AiModelStatus {
  id: string;
  device_id: string | null;
  version: string;
  algorithm: string | null;
  feature_version: string | null;
  dataset_snapshot_id: string | null;
  dataset_hash: string | null;
  params: Record<string, unknown> | null;
  train_cutoff: string | null;
  metrics: Record<string, unknown> | null;
  artifact_hash: string | null;
  artifact_path: string | null;
  status: "EXPERIMENTAL" | "PRODUCTION" | "REJECTED" | "ARTIFACT_MISSING";
  promotion_reason: string | null;
  created_at: string;
}

export interface AiStatus {
  worker_online: boolean;
  workers: AiWorkerStatus[];
  current_model: AiModelStatus | null;
  latest_telemetry_recorded_at: string | null;
  latest_telemetry_received_at: string | null;
  latest_forecast_issued_at: string | null;
  latest_forecast_accepted_at: string | null;
}

export interface AiJob {
  id: string;
  type: "train" | "evaluate" | "export";
  device_id: string | null;
  params: Record<string, unknown> | null;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  worker_id: string | null;
  progress: number;
  phase: string | null;
  result: Record<string, unknown> | null;
  error: string | null;
  attempts: number;
  created_at: string;
  updated_at: string;
}

export interface AiAlert {
  id: string;
  device_id: string;
  forecast_run_id: string | null;
  type: string;
  created_at: string;
  threshold_snapshot: number | null;
  horizon_minutes: number | null;
  reason: string | null;
  lifecycle: string;
  feedback: string | null;
  feedback_at: string | null;
}

export interface RoomEvent {
  id: string;
  device_id: string | null;
  space_code: string | null;
  occurred_at: string;
  received_at: string;
  type: string;
  note: string | null;
  experiment_id: string | null;
}

export interface AuditEntry {
  id: number;
  occurred_at: string;
  actor: "USER" | "BACKEND" | "DEVICE" | "AI";
  action: string;
  device_id: string | null;
  detail: string | null;
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
  /** Either an existing room code, or omit it and pass space_name to create one. */
  space_code?: string;
  space_name?: string;
  name?: string;
  /** Room profile carried through from the template the operator picked. */
  purpose?: string;
  volume_m3?: number;
  occupancy_hours?: number;
}

/**
 * The reason behind a suggested threshold.
 *
 * Flyway-Maven's Jackson is configured with SNAKE_CASE platform-wide, so the wire names are
 * snake_case like every other response. Spelling them camelCase here produced `undefined`, and
 * `undefined.toFixed()` throws inside render — which unmounts the whole tree and shows a blank page.
 */
export interface ComputedThreshold {
  threshold_pm25: number;
  daily_dose_target: number;
  ventilation_factor: number;
  volume_factor: number;
  co2_factor: number;
  occupancy_hours: number;
  volume_m3: number | null;
  co2_ppm: number | null;
}

export interface ThresholdPreview {
  space: Space;
  computed: ComputedThreshold;
}
