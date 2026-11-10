import type {
  AuditEntry,
  Device,
  DeviceUpdateRequest,
  FanCommand,
  PairRequest,
  Space,
  SpaceRequest,
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
};
