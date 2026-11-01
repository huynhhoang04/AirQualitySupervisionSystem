import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "../api/client";
import type { SpaceRequest } from "../api/types";

export const useDevices = () =>
  useQuery({ queryKey: ["devices"], queryFn: api.devices, refetchInterval: 5_000 });

export const useLatest = (deviceId?: string) =>
  useQuery({
    queryKey: ["latest", deviceId],
    queryFn: () => api.latest(deviceId!),
    enabled: Boolean(deviceId),
    refetchInterval: 5_000,
  });

export const useHistory = (deviceId?: string, hours = 24) =>
  useQuery({
    queryKey: ["history", deviceId, hours],
    queryFn: () => api.history(deviceId!, hours),
    enabled: Boolean(deviceId),
    refetchInterval: 30_000,
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
    queryFn: () => api.audit(deviceId, 25),
    refetchInterval: 15_000,
  });

export function useSetFan(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { on: boolean; level: number }) =>
      api.command(deviceId!, {
        command: "set_actuator",
        params: { state: input.on ? "ON" : "OFF", level: input.on ? input.level : 0 },
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["devices"] });
      queryClient.invalidateQueries({ queryKey: ["audit"] });
    },
  });
}

export function useSaveSpace(code?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SpaceRequest) => api.saveSpace(code!, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["spaces"] });
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
