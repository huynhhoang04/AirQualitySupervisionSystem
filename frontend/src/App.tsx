import { useEffect, useMemo, useState } from "react";

import { API_BASE } from "./api/client";
import { AuditTable } from "./components/AuditTable";
import { FanPanel } from "./components/FanPanel";
import { ForecastPanel } from "./components/ForecastPanel";
import { Header } from "./components/Header";
import { HistoryChart } from "./components/HistoryChart";
import { SensorPanel } from "./components/SensorPanel";
import { ThresholdCard } from "./components/ThresholdCard";
import {
  useAudit,
  useDevices,
  useForecast,
  useHistory,
  useLatest,
  useRefreshForecast,
  useSaveSpace,
  useSetFan,
  useSpaces,
} from "./hooks/useAqss";
import { isOnline } from "./lib/format";

const STORAGE_KEY = "aqss.selectedDevice";

export default function App() {
  const devicesQuery = useDevices();
  const devices = devicesQuery.data ?? [];

  const [selectedId, setSelectedId] = useState<string | null>(() =>
    localStorage.getItem(STORAGE_KEY),
  );

  const device = useMemo(
    () => devices.find((item) => item.device_id === selectedId) ?? devices[0],
    [devices, selectedId],
  );

  useEffect(() => {
    if (device && device.device_id !== selectedId) {
      setSelectedId(device.device_id);
      localStorage.setItem(STORAGE_KEY, device.device_id);
    }
  }, [device, selectedId]);

  const deviceId = device?.device_id;
  const spacesQuery = useSpaces();
  const space = spacesQuery.data?.find((item) => item.code === device?.space_code);
  const threshold = space?.threshold_pm25 ?? 35;

  const latestQuery = useLatest(deviceId);
  const historyQuery = useHistory(deviceId);
  const forecastQuery = useForecast(deviceId);
  const auditQuery = useAudit(deviceId);

  const setFan = useSetFan(deviceId);
  const saveSpace = useSaveSpace(space?.code);
  const refreshForecast = useRefreshForecast(deviceId);

  return (
    <div className="min-h-screen bg-ink">
      <Header
        devices={devices}
        selected={device}
        onSelect={(id) => {
          setSelectedId(id);
          localStorage.setItem(STORAGE_KEY, id);
        }}
      />

      <main className="mx-auto max-w-6xl space-y-4 px-4 py-6">
        {devicesQuery.isError && (
          <div className="card border-rose-500/30 bg-rose-500/5 p-4 text-sm text-rose-200">
            Không gọi được API tại <code className="text-rose-100">{API_BASE}</code>. Kiểm tra backend
            đang chạy và <code>VITE_API_URL</code> đúng.
          </div>
        )}

        {!devicesQuery.isError && devices.length === 0 && (
          <div className="card p-6 text-sm text-slate-400">
            {devicesQuery.isLoading
              ? "Đang tải danh sách thiết bị…"
              : "Chưa có thiết bị nào — node sẽ tự đăng ký khi gửi telemetry đầu tiên."}
          </div>
        )}

        {device && (
          <>
            <div className="grid gap-4 lg:grid-cols-3">
              <div className="lg:col-span-2">
                <SensorPanel
                  latest={latestQuery.data}
                  threshold={threshold}
                  offline={!isOnline(device.last_seen_at)}
                />
              </div>
              <FanPanel
                device={device}
                pending={setFan.isPending}
                onSet={(on, level) => setFan.mutate({ on, level })}
              />
            </div>

            <div className="grid gap-4 lg:grid-cols-3">
              <div className="lg:col-span-2">
                <HistoryChart history={historyQuery.data ?? []} threshold={threshold} />
              </div>
              <ThresholdCard
                space={space}
                saving={saveSpace.isPending}
                onSave={(value) => saveSpace.mutate({ threshold_pm25: value })}
              />
            </div>

            <ForecastPanel
              points={forecastQuery.data ?? []}
              refreshing={refreshForecast.isPending}
              onRefresh={() => refreshForecast.mutate()}
            />

            <AuditTable entries={auditQuery.data ?? []} />
          </>
        )}

        <footer className="pb-6 pt-2 text-center text-[11px] text-slate-600">
          API {API_BASE} · dữ liệu mới nhất mỗi 5 giây
        </footer>
      </main>
    </div>
  );
}
