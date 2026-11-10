import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "../api/client";
import type {
  DeviceUpdateRequest,
  FanCommand,
  PairRequest,
  SpaceRequest,
} from "../api/types";

// Polling intervals are tied to what the backend can change: telemetry lands every 5 s, the
// control loop runs every 60 s, the audit trail is append-only.
const LIVE_MS = 5_000;
const SLOW_MS = 30_000;

export const useDevices = () =>
  useQuery({ queryKey: ["devices"], queryFn: api.devices, refetchInterval: LIVE_MS });

export const useLatest = (deviceId?: string) =>
  useQuery({
    queryKey: ["latest", deviceId],
    queryFn: () => api.latest(deviceId!),
    enabled: Boolean(deviceId),
    refetchInterval: LIVE_MS,
    // A brand-new paired node has no reading yet: a 404 is the expected state, not an error to
    // retry and shout about.
    retry: false,
  });

export const useHistory = (deviceId?: string, hours = 24) =>
  useQuery({
    queryKey: ["history", deviceId, hours],
    queryFn: () => api.history(deviceId!, hours),
    enabled: Boolean(deviceId),
    refetchInterval: SLOW_MS,
  });

export const useForecast = (deviceId?: string) =>
  useQuery({
    queryKey: ["forecast", deviceId],
    queryFn: () => api.forecast(deviceId!),
    enabled: Boolean(deviceId),
    refetchInterval: 60_000,
  });

export const useSpaces = () =>
  useQuery({ queryKey: ["spaces"], queryFn: api.spaces, refetchInterval: 60_000 });

export const useAudit = (deviceId?: string) =>
  useQuery({
    queryKey: ["audit", deviceId],
    queryFn: () => api.audit(deviceId, 50),
    refetchInterval: 15_000,
  });

/** Everything a fan command can invalidate. */
function invalidateDeviceViews(queryClient: ReturnType<typeof useQueryClient>, deviceId?: string) {
  queryClient.invalidateQueries({ queryKey: ["devices"] });
  queryClient.invalidateQueries({ queryKey: ["audit", deviceId] });
  if (deviceId) {
    queryClient.invalidateQueries({ queryKey: ["latest", deviceId] });
  }
}

export function useFanCommand(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: FanCommand) => api.command(deviceId!, body),
    onSuccess: () => invalidateDeviceViews(queryClient, deviceId),
  });
}

export function usePairDevice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: PairRequest) => api.pair(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["devices"] });
      queryClient.invalidateQueries({ queryKey: ["spaces"] });
      queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
  });
}

export function useUnpairDevice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (deviceId: string) => api.unpair(deviceId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["devices"] });
      queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
  });
}

export function useUpdateDevice(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: DeviceUpdateRequest) => api.updateDevice(deviceId!, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["devices"] });
      queryClient.invalidateQueries({ queryKey: ["audit", deviceId] });
    },
  });
}

export function useSaveSpace(code?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SpaceRequest) => api.saveSpace(code!, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["spaces"] });
      queryClient.invalidateQueries({ queryKey: ["devices"] });
      queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
  });
}

export function useRefreshForecast(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.refreshForecast(deviceId!),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["forecast", deviceId] }),
  });
}
