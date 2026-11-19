import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "../api/client";
import type { DeviceUpdateRequest, FanCommand, PairRequest, SpaceRequest } from "../api/types";

// Two rhythms: telemetry lands every 5 s, the control loop runs every 60 s.
const LIVE_MS = 5_000;

export const useDevices = () =>
  useQuery({ queryKey: ["devices"], queryFn: api.devices, refetchInterval: LIVE_MS });

export const useLatest = (deviceId?: string, enabled = true) =>
  useQuery({
    queryKey: ["latest", deviceId],
    queryFn: () => api.latest(deviceId!),
    enabled: Boolean(deviceId) && enabled,
    refetchInterval: enabled ? LIVE_MS : false,
    // A freshly connected node has no reading yet; 404 is the expected state, not an error.
    retry: false,
  });

export const useHistory = (deviceId?: string, hours = 24) =>
  useQuery({
    queryKey: ["history", deviceId, hours],
    queryFn: () => api.history(deviceId!, hours),
    enabled: Boolean(deviceId),
    refetchInterval: 60_000,
  });

export const useSeries = (deviceId?: string, hours = 24, bucket: "1m" | "5m" | "15m" = "5m", enabled = true) =>
  useQuery({
    queryKey: ["series", deviceId, hours, bucket],
    queryFn: () => api.series(deviceId!, hours, bucket),
    enabled: Boolean(deviceId) && enabled,
    refetchInterval: 60_000,
  });

export const useSummary = (deviceId?: string, hours = 24) =>
  useQuery({
    queryKey: ["summary", deviceId, hours],
    queryFn: () => api.summary(deviceId!, hours),
    enabled: Boolean(deviceId),
    refetchInterval: 60_000,
  });

export const useSamples = (deviceId?: string, hours = 24, limit = 200) =>
  useQuery({
    queryKey: ["samples", deviceId, hours, limit],
    queryFn: () => api.samples(deviceId!, hours, limit),
    enabled: Boolean(deviceId),
    refetchInterval: 60_000,
  });

export const useCommands = (deviceId?: string, limit = 30) =>
  useQuery({
    queryKey: ["commands", deviceId, limit],
    queryFn: () => api.commands(deviceId!, limit),
    enabled: Boolean(deviceId),
    refetchInterval: 5_000,
  });

export const useActuatorHistory = (deviceId?: string, limit = 30) =>
  useQuery({
    queryKey: ["actuator-history", deviceId, limit],
    queryFn: () => api.actuatorHistory(deviceId!, limit),
    enabled: Boolean(deviceId),
    refetchInterval: 5_000,
  });

export const useForecast = (deviceId?: string) =>
  useQuery({
    queryKey: ["forecast", deviceId],
    queryFn: () => api.forecast(deviceId!),
    enabled: Boolean(deviceId),
    refetchInterval: 60_000,
    retry: false,
  });

/**
 * Forecast accuracy for one horizon.
 *
 * Two things keep it in step with the data rather than with a timer:
 * * `version` is bumped by an SSE `metrics` event, so a freshly scored target refetches at once;
 * * `placeholderData` keeps the last numbers on screen while that refetch is in flight. Without it
 *   every scored point replaced the panel with "Đang tải…" — the value was briefly missing exactly
 *   when it had just changed.
 */
export const useForecastAccuracy = (deviceId?: string, horizon = 5, hours = 6, version = 0) =>
  useQuery({
    queryKey: ["forecast-accuracy", deviceId, horizon, hours, version],
    queryFn: () => api.forecastAccuracy(deviceId!, horizon, hours),
    enabled: Boolean(deviceId),
    refetchInterval: 60_000,
    placeholderData: (previous) => previous,
    retry: false,
  });

export const useAccuracyPoints = (
  deviceId?: string,
  horizon = 0,
  hours = 6,
  enabled = false,
  version = 0,
) =>
  useQuery({
    queryKey: ["accuracy-points", deviceId, horizon, hours, version],
    queryFn: () => api.accuracyPoints(deviceId!, horizon, hours),
    enabled: Boolean(deviceId) && enabled,
    placeholderData: (previous) => previous,
    retry: false,
  });

export const useAlerts = (deviceId?: string) =>
  useQuery({
    queryKey: ["alerts", deviceId],
    queryFn: () => api.alerts(deviceId!),
    enabled: Boolean(deviceId),
    refetchInterval: 60_000,
  });

export const useAiStatus = (deviceId?: string) =>
  useQuery({
    queryKey: ["ai-status", deviceId],
    queryFn: () => api.aiStatus(deviceId),
    refetchInterval: 15_000,
  });

export const useAiModels = (deviceId?: string) =>
  useQuery({
    queryKey: ["ai-models", deviceId],
    queryFn: () => api.aiModels(deviceId!),
    enabled: Boolean(deviceId),
    refetchInterval: 60_000,
  });

export const useAiJobs = (deviceId?: string) =>
  useQuery({
    queryKey: ["ai-jobs", deviceId],
    queryFn: () => api.aiJobs(deviceId),
    refetchInterval: 5_000,
  });

export function useCreateJob(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (type: string) => api.createJob({ type, device_id: deviceId! }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["ai-jobs", deviceId] });
      queryClient.invalidateQueries({ queryKey: ["ai-status", deviceId] });
    },
  });
}

export const useSpaces = () =>
  useQuery({ queryKey: ["spaces"], queryFn: api.spaces, refetchInterval: 60_000 });

export const useAudit = (deviceId?: string, limit = 100) =>
  useQuery({
    queryKey: ["audit", deviceId ?? "all", limit],
    queryFn: () => api.audit(deviceId, limit),
    refetchInterval: 30_000,
  });

function invalidate(queryClient: ReturnType<typeof useQueryClient>, deviceId?: string) {
  queryClient.invalidateQueries({ queryKey: ["devices"] });
  if (deviceId) {
    for (const key of ["latest", "history", "series", "summary", "samples", "commands",
      "actuator-history", "forecast", "forecast-accuracy", "accuracy-points", "alerts"]) {
      queryClient.invalidateQueries({ queryKey: [key, deviceId] });
    }
  }
}

export function useFanCommand(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: FanCommand) => api.command(deviceId!, body),
    onSuccess: () => invalidate(queryClient, deviceId),
  });
}

export function usePairDevice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: PairRequest) => api.pair(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["devices"] });
      queryClient.invalidateQueries({ queryKey: ["spaces"] });
    },
  });
}

export function useUnpairDevice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (deviceId: string) => api.unpair(deviceId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["devices"] }),
  });
}

export function useResetFilter(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.resetFilter(deviceId!),
    onSuccess: () => invalidate(queryClient, deviceId),
  });
}

export function useUpdateDevice(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: DeviceUpdateRequest) => api.updateDevice(deviceId!, body),
    onSuccess: () => invalidate(queryClient, deviceId),
  });
}

export function useSaveSpace(code?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SpaceRequest) => api.saveSpace(code!, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["spaces"] });
      queryClient.invalidateQueries({ queryKey: ["threshold-preview", code] });
      queryClient.invalidateQueries({ queryKey: ["devices"] });
    },
  });
}

/** The threshold the room profile implies — shown before the operator accepts it. */
export const useThresholdPreview = (code?: string, enabled = true) =>
  useQuery({
    queryKey: ["threshold-preview", code],
    queryFn: () => api.previewThreshold(code!),
    enabled: Boolean(code) && enabled,
    staleTime: 0,
  });

export function useApplyThreshold(code?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.applyThreshold(code!),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["spaces"] });
      queryClient.invalidateQueries({ queryKey: ["threshold-preview", code] });
      queryClient.invalidateQueries({ queryKey: ["devices"] });
    },
  });
}
