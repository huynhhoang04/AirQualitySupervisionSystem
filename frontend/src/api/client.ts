import type {
  AuditEntry,
  CommandRequest,
  Device,
  DeviceUpdateRequest,
  ForecastPoint,
  Space,
  SpaceRequest,
  Telemetry,
} from "./types";

export const API_BASE = (import.meta.env.VITE_API_URL ?? "http://localhost:8080").replace(/\/$/, "");

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText} — ${path}`);
  }
  return (await response.json()) as T;
}

export const api = {
  devices: () => request<Device[]>("/api/v1/devices"),
  device: (id: string) => request<Device>(`/api/v1/devices/${id}`),
  updateDevice: (id: string, body: DeviceUpdateRequest) =>
    request<Device>(`/api/v1/devices/${id}`, { method: "PUT", body: JSON.stringify(body) }),
  deleteDevice: (id: string) =>
    fetch(`${API_BASE}/api/v1/devices/${id}`, { method: "DELETE" }).then((r) => {
      if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    }),

  latest: (id: string) => request<Telemetry>(`/api/v1/devices/${id}/latest`),
  history: (id: string, hours = 24) => {
    const to = new Date();
    const from = new Date(to.getTime() - hours * 3600_000);
    return request<Telemetry[]>(
      `/api/v1/devices/${id}/telemetry?from=${from.toISOString()}&to=${to.toISOString()}`,
    );
  },

  forecast: (id: string) => request<ForecastPoint[]>(`/api/v1/devices/${id}/forecast`),
  refreshForecast: (id: string) =>
    request<ForecastPoint[]>(`/api/v1/devices/${id}/forecast`, { method: "POST" }),

  command: (id: string, body: CommandRequest) =>
    request<Device>(`/api/v1/devices/${id}/command`, { method: "POST", body: JSON.stringify(body) }),

  spaces: () => request<Space[]>("/api/v1/spaces"),
  space: (code: string) => request<Space>(`/api/v1/spaces/${code}`),
  saveSpace: (code: string, body: SpaceRequest) =>
    request<Space>(`/api/v1/spaces/${code}`, { method: "PUT", body: JSON.stringify(body) }),
  recomputeThreshold: (code: string) =>
    request<Space>(`/api/v1/spaces/${code}/threshold`, { method: "POST" }),

  audit: (deviceId?: string, limit = 25) => {
    const query = new URLSearchParams({ limit: String(limit) });
    if (deviceId) query.set("device_id", deviceId);
    return request<AuditEntry[]>(`/api/v1/audit?${query.toString()}`);
  },
};
