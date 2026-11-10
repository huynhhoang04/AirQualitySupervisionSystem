import { Link, useParams } from "react-router-dom";

import { useAudit, useDevices, useForecast, useHistory, useLatest, useRefreshForecast, useSpaces } from "../hooks/useAqss";
import { AuditTimeline } from "../components/AuditTable";
import { DeviceSettings } from "../components/DeviceSettings";
import { FanPanel } from "../components/FanPanel";
import { ForecastPanel } from "../components/ForecastPanel";
import { HistoryChart } from "../components/HistoryChart";
import { Header, countOnline } from "../components/Header";
import { Icon } from "../components/Icon";
import { SensorPanel } from "../components/SensorPanel";
import { SpaceCard } from "../components/SpaceCard";
import { StatusChip } from "../components/StatusChip";
import { useFanCommand, useSaveSpace, useUnpairDevice } from "../hooks/useAqss";
import { isOnline } from "../lib/format";
export function DevicePage() {
  const { deviceId = "" } = useParams();
  const devicesQuery = useDevices();
  const device = devicesQuery.data?.find((item) => item.device_id === deviceId);

  const spacesQuery = useSpaces();
  const space = spacesQuery.data?.find((item) => item.code === device?.space_code);
  const threshold = space?.threshold_pm25 ?? 35;

  const latestQuery = useLatest(device?.device_id);
  const historyQuery = useHistory(device?.device_id);
  const forecastQuery = useForecast(device?.device_id);
  const auditQuery = useAudit(device?.device_id);

  const fan = useFanCommand(device?.device_id);
  const saveSpace = useSaveSpace(space?.code);
  const refreshForecast = useRefreshForecast(device?.device_id);

  const latest = latestQuery.data;
  const stale =
    latest != null && Date.now() - Date.parse(latest.recorded_at) > 5 * 60_000;

  if (devicesQuery.isLoading) {
    return <Shell devices={devicesQuery.data ?? []}><p className="text-sm text-muted">Đang tải…</p></Shell>;
  }

  if (!device) {
    return (
      <Shell devices={devicesQuery.data ?? []}>
        <div className="card p-6">
          <h1 className="text-base font-medium">Không tìm thấy thiết bị</h1>
          <p className="mt-1 text-sm text-muted">
            Node <code className="font-mono">{deviceId}</code> không nằm trong danh sách đã ghép.
          </p>
          <Link to="/" className="btn-ghost mt-4">
            <Icon name="chevronLeft" /> Về tổng quan
          </Link>
        </div>
      </Shell>
    );
  }

  const fanError = fan.error?.message ?? null;

  return (
    <Shell devices={devicesQuery.data ?? []}>
      <nav className="mb-4 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <Link to="/" className="inline-flex items-center gap-1 text-muted hover:text-white">
          <Icon name="chevronLeft" size={14} /> Tổng quan
        </Link>
        <span className="text-line">/</span>
        <h1 className="font-medium">{device.name ?? device.device_id}</h1>
        <StatusChip lastSeenAt={device.last_seen_at} />
        <span className="chip border border-line bg-canvas text-muted">
          {space?.name ?? "chưa gán phòng"}
        </span>
        <Link to={`/device/${encodeURIComponent(device.device_id)}/settings`} className="btn-ghost ml-auto px-2 py-1">
          <Icon name="settings" size={14} /> Cài đặt node
        </Link>
      </nav>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <SensorPanel
            latest={latest}
            threshold={threshold}
            online={isOnline(device.last_seen_at)}
            stale={stale}
          />
        </div>
        <FanPanel
          device={device}
          pending={fan.isPending}
          error={fanError}
          onSet={(state, level, mode) => fan.mutate({ command: "set_actuator", params: { state, level, mode } })}
        />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <div className="card p-5 lg:col-span-2">
          <div className="mb-3 flex items-baseline justify-between">
            <div className="label">PM2.5 · 24 giờ</div>
            <span className="text-[11px] text-muted">nét đứt = ngưỡng phòng</span>
          </div>
          <HistoryChart
            history={historyQuery.data ?? []}
            threshold={threshold}
            hasSaturation={(historyQuery.data ?? []).some((row) => row.pm25_saturated)}
          />
        </div>
        <SpaceCard
          space={space}
          saving={saveSpace.isPending}
          error={saveSpace.error?.message ?? null}
          onSave={(body) => saveSpace.mutate(body)}
        />
      </div>

      <div className="mt-4">
        <ForecastPanel
          points={forecastQuery.data ?? []}
          space={space}
          refreshing={refreshForecast.isPending}
          onRefresh={() => refreshForecast.mutate()}
        />
      </div>

      <div className="mt-4">
        <AuditTimeline entries={auditQuery.data ?? []} />
      </div>
    </Shell>
  );
}

export function SettingsPage() {
  const { deviceId = "" } = useParams();
  const devicesQuery = useDevices();
  const devices = devicesQuery.data ?? [];
  const device = devices.find((item) => item.device_id === deviceId);
  return (
    <Shell devices={devices}>
      <nav className="mb-4 flex items-center gap-2 text-sm">
        <Link to={`/device/${encodeURIComponent(deviceId)}`} className="inline-flex items-center gap-1 text-muted hover:text-white">
          <Icon name="chevronLeft" size={14} /> {device?.name ?? deviceId}
        </Link>
      </nav>
      {device ? (
        <div className="mx-auto max-w-xl">
          <SettingsBody deviceId={device.device_id} />
        </div>
      ) : (
        <p className="text-sm text-muted">Thiết bị không tồn tại hoặc chưa ghép.</p>
      )}
    </Shell>
  );
}

function SettingsBody({ deviceId }: { deviceId: string }) {
  const devicesQuery = useDevices();
  const unpair = useUnpairDevice();
  const device = devicesQuery.data?.find((item) => item.device_id === deviceId);
  if (!device) return null;
  return (
    <DeviceSettings
      device={device}
      onUnpair={() => unpair.mutate(device.device_id)}
    />
  );
}

export function Shell({ devices, children }: { devices: { last_seen_at: string | null }[]; children: React.ReactNode }) {
  return (
    <div className="min-h-screen">
      <Header deviceCount={devices.length} onlineCount={countOnline(devices)} homeHref="/" />
      <main className="mx-auto max-w-6xl px-4 py-6 pb-16">{children}</main>
    </div>
  );
}
