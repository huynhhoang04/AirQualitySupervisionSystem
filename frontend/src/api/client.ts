import type {
  ActuatorHistory,
  AiAlert,
  AiJob,
  AiModelStatus,
  AiStatus,
  AuditEntry,
  CommandLog,
  Device,
  DeviceUpdateRequest,
  FanCommand,
  ForecastView,
  PairRequest,
  RoomEvent,
  SamplePage,
  Series,
  Space,
  SpaceRequest,
  Summary,
  Telemetry,
  ThresholdPreview,
} from "./types";

export const API_BASE = (import.meta.env.VITE_API_URL ?? "http://localhost:8080").replace(/\/$/, "");

/** Raised for every non-2xx answer, carrying what the server actually said. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      headers: { "Content-Type": "application/json" },
      ...init,
    });
  } catch {
    throw new ApiError(0, "Không kết nối được tới backend.");
  }

  if (!response.ok) {
    // Spring's default error body carries a human-readable `message` for the 4xx we raise.
    let detail = `${response.status} ${response.statusText}`;
    try {
      const body = (await response.json()) as { message?: string };
      if (body.message) detail = body.message;
    } catch {
      /* not JSON — keep the status line */
    }
    throw new ApiError(response.status, detail);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export const api = {
  // ── Devices ───────────────────────────────────────────────────────────────
  devices: () => request<Device[]>("/api/v1/devices"),
  device: (id: string) => request<Device>(`/api/v1/devices/${encodeURIComponent(id)}`),
  updateDevice: (id: string, body: DeviceUpdateRequest) =>
    request<Device>(`/api/v1/devices/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  deleteDevice: (id: string) =>
    request<void>(`/api/v1/devices/${encodeURIComponent(id)}`, { method: "DELETE" }),
  pair: (body: PairRequest) =>
    request<Device>("/api/v1/devices/pair", { method: "POST", body: JSON.stringify(body) }),
  unpair: (id: string) =>
    request<Device>(`/api/v1/devices/${encodeURIComponent(id)}/unpair`, { method: "POST" }),
  /** "Đã thay lõi" — resets the fan-hours counter on the backend and on the node. */
  resetFilter: (id: string) =>
    request<Device>(`/api/v1/devices/${encodeURIComponent(id)}/filter`, { method: "POST" }),

  latest: (id: string) => request<Telemetry>(`/api/v1/devices/${encodeURIComponent(id)}/latest`),
  history: (id: string, hours = 24) => {
    const to = new Date();
    const from = new Date(to.getTime() - hours * 3600_000);
    return request<Telemetry[]>(
      `/api/v1/devices/${encodeURIComponent(id)}/telemetry` +
        `?from=${from.toISOString()}&to=${to.toISOString()}`,
    );
  },


  command: (id: string, body: FanCommand) =>
    request<Device>(`/api/v1/devices/${encodeURIComponent(id)}/command`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // ── Spaces ────────────────────────────────────────────────────────────────
  spaces: () => request<Space[]>("/api/v1/spaces"),
  space: (code: string) => request<Space>(`/api/v1/spaces/${encodeURIComponent(code)}`),
  saveSpace: (code: string, body: SpaceRequest) =>
    request<Space>(`/api/v1/spaces/${encodeURIComponent(code)}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),
  deleteSpace: (code: string) =>
    request<void>(`/api/v1/spaces/${encodeURIComponent(code)}`, { method: "DELETE" }),
  /** The threshold the room profile implies, without saving it. */
  previewThreshold: (code: string) =>
    request<ThresholdPreview>(`/api/v1/spaces/${encodeURIComponent(code)}/threshold/preview`),
  applyThreshold: (code: string) =>
    request<ThresholdPreview>(`/api/v1/spaces/${encodeURIComponent(code)}/threshold`, { method: "POST" }),

  // ── Audit ─────────────────────────────────────────────────────────────────
  audit: (deviceId?: string, limit = 50) => {
    const query = new URLSearchParams({ limit: String(limit) });
    if (deviceId) query.set("device_id", deviceId);
    return request<AuditEntry[]>(`/api/v1/audit?${query.toString()}`);
  },

  // ── Analytics (SQL-bucketed; spikes keep their min/max per bucket) ─────────
  series: (id: string, hours = 24, bucket = "5m") => {
    const to = new Date();
    const from = new Date(to.getTime() - hours * 3600_000);
    return request<Series>(
      `/api/v1/devices/${encodeURIComponent(id)}/series` +
        `?from=${from.toISOString()}&to=${to.toISOString()}&bucket=${bucket}`,
    );
  },
  summary: (id: string, hours = 24) => {
    const to = new Date();
    const from = new Date(to.getTime() - hours * 3600_000);
    return request<Summary>(
      `/api/v1/devices/${encodeURIComponent(id)}/summary` +
        `?from=${from.toISOString()}&to=${to.toISOString()}`,
    );
  },
  samples: (id: string, hours = 24, limit = 200) => {
    const to = new Date();
    const from = new Date(to.getTime() - hours * 3600_000);
    return request<SamplePage>(
      `/api/v1/devices/${encodeURIComponent(id)}/samples` +
        `?from=${from.toISOString()}&to=${to.toISOString()}&limit=${limit}`,
    );
  },

  // ── Command lifecycle + reported state ────────────────────────────────────
  commands: (id: string, limit = 50) =>
    request<CommandLog[]>(`/api/v1/devices/${encodeURIComponent(id)}/commands?limit=${limit}`),
  actuatorHistory: (id: string, limit = 50) =>
    request<ActuatorHistory[]>(
      `/api/v1/devices/${encodeURIComponent(id)}/actuator-history?limit=${limit}`,
    ),

  // ── AI ────────────────────────────────────────────────────────────────────
  forecast: (id: string) =>
    request<ForecastView>(`/api/v1/devices/${encodeURIComponent(id)}/forecast`),
  alerts: (id: string) => request<AiAlert[]>(`/api/v1/devices/${encodeURIComponent(id)}/alerts`),
  aiStatus: (deviceId?: string) => {
    const query = deviceId ? `?device_id=${encodeURIComponent(deviceId)}` : "";
    return request<AiStatus>(`/api/v1/ai/status${query}`);
  },
  aiModels: (deviceId: string) =>
    request<AiModelStatus[]>(`/api/v1/ai/models?device_id=${encodeURIComponent(deviceId)}`),
  aiJobs: (deviceId?: string, limit = 20) => {
    const query = new URLSearchParams({ limit: String(limit) });
    if (deviceId) query.set("device_id", deviceId);
    return request<AiJob[]>(`/api/v1/ai/jobs?${query.toString()}`);
  },
  createJob: (body: { type: string; device_id: string; params?: Record<string, unknown> }) =>
    request<AiJob>("/api/v1/ai/jobs", { method: "POST", body: JSON.stringify(body) }),

  // ── Room events ───────────────────────────────────────────────────────────
  events: (id: string) =>
    request<RoomEvent[]>(`/api/v1/devices/${encodeURIComponent(id)}/events`),
  recordEvent: (id: string, body: { type: string; note?: string; occurred_at?: string }) =>
    request<RoomEvent>(`/api/v1/devices/${encodeURIComponent(id)}/events`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
};
