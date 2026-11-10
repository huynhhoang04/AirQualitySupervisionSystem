import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "../api/client";
import type { DeviceUpdateRequest, FanCommand, PairRequest, SpaceRequest } from "../api/types";

// Two rhythms: telemetry lands every 5 s, the control loop runs every 60 s.
const LIVE_MS = 5_000;

export const useDevices = () =>
  useQuery({ queryKey: ["devices"], queryFn: api.devices, refetchInterval: LIVE_MS });

export const useLatest = (deviceId?: string) =>
  useQuery({
    queryKey: ["latest", deviceId],
    queryFn: () => api.latest(deviceId!),
    enabled: Boolean(deviceId),
    refetchInterval: LIVE_MS,
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

export const useSpaces = () =>
  useQuery({ queryKey: ["spaces"], queryFn: api.spaces, refetchInterval: 60_000 });

function invalidate(queryClient: ReturnType<typeof useQueryClient>, deviceId?: string) {
  queryClient.invalidateQueries({ queryKey: ["devices"] });
  if (deviceId) {
    queryClient.invalidateQueries({ queryKey: ["latest", deviceId] });
    queryClient.invalidateQueries({ queryKey: ["history", deviceId] });
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
