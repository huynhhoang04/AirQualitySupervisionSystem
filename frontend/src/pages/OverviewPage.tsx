import { useState } from "react";
import { Link } from "react-router-dom";

import { API_BASE } from "../api/client";
import type { Device, Space } from "../api/types";
import { Header, countOnline } from "../components/Header";
import { Icon } from "../components/Icon";
import { PairDialog } from "../components/PairDialog";
import { SpaceDialog } from "../components/SpaceDialog";
import { StatusChip } from "../components/StatusChip";
import {
  useDevices,
  useLatest,
  useSpaces,
} from "../hooks/useAqss";
import { formatAgo, isOnline, pm25Tone } from "../lib/format";

/**
 * The overview answers one question: is every room safe right now? One card per device, the number
 * that matters first, and a straight path into the room that needs attention.
 */
export function OverviewPage() {
  const devicesQuery = useDevices();
  const spacesQuery = useSpaces();
  const devices = devicesQuery.data ?? [];
  const spaces = spacesQuery.data ?? [];

  const [pairing, setPairing] = useState(false);
  const [addingSpace, setAddingSpace] = useState(false);

  return (
    <div className="min-h-screen">
      <Header deviceCount={devices.length} onlineCount={countOnline(devices)} />
      <main className="mx-auto max-w-6xl px-4 py-6 pb-16">
        {devicesQuery.isError && (
          <div className="card border-bad/30 bg-bad/5 p-4 text-sm">
            <p className="font-medium text-bad">Không gọi được API</p>
            <p className="mt-1 text-muted">
              Đang dùng <code className="font-mono">{API_BASE}</code>. Kiểm tra backend và biến{" "}
              <code className="font-mono">VITE_API_URL</code>.
            </p>
          </div>
        )}

        <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h1 className="text-lg font-semibold">Phòng đang giám sát</h1>
            <p className="mt-0.5 text-xs text-muted">
              {devices.length === 0
                ? "Chưa có node nào được ghép."
                : `${devices.length} node · ${spaces.length} phòng · cập nhật mỗi 5 giây`}
            </p>
          </div>
          <div className="flex gap-2">
            <button type="button" onClick={() => setAddingSpace(true)} className="btn-ghost">
              <Icon name="plus" /> Thêm phòng
            </button>
            <button type="button" onClick={() => setPairing(true)} className="btn-primary">
              <Icon name="link" /> Ghép thiết bị
            </button>
          </div>
        </div>

        {devices.length === 0 && !devicesQuery.isError ? (
          <EmptyState onPair={() => setPairing(true)} />
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {devices.map((device) => (
              <DeviceCard
                key={device.device_id}
                device={device}
                space={spaces.find((space) => space.code === device.space_code)}
              />
            ))}
          </div>
        )}

        <SpacesSection spaces={spaces} devices={devices} onAdd={() => setAddingSpace(true)} />
      </main>

      {pairing && (
        <PairDialog
          spaces={spaces}
          onClose={() => setPairing(false)}
          onPaired={() => setPairing(false)}
        />
      )}
      {addingSpace && <SpaceDialog onCreated={() => setAddingSpace(false)} />}
    </div>
  );
}

function DeviceCard({ device, space }: { device: Device; space?: Space }) {
  const latest = useLatest(device.device_id);
  const threshold = space?.threshold_pm25 ?? 35;
  const pm25 = latest.data?.pm25 ?? null;
  const tone = pm25Tone(pm25, threshold);
  const online = isOnline(device.last_seen_at);
  const toneClass =
    tone === "bad" ? "text-bad" : tone === "warn" ? "text-warn" : tone === "good" ? "text-good" : "text-muted";

  return (
    <Link
      to={`/device/${encodeURIComponent(device.device_id)}`}
      className="card group block p-5 transition-colors hover:border-line"
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="truncate font-medium">{device.name ?? device.device_id}</div>
          <div className="mt-0.5 truncate text-xs text-muted">
            {space ? `${space.name} · ${space.code}` : "chưa gán phòng"}
          </div>
        </div>
        <StatusChip lastSeenAt={device.last_seen_at} />
      </div>

      <div className="mt-4 flex items-end justify-between gap-2">
        <div>
          <div className="label">PM2.5</div>
          <div className={`font-mono text-3xl font-semibold tabular-nums ${toneClass}`}>
            {pm25 == null ? "—" : pm25.toFixed(1)}
            <span className="ml-1 font-sans text-xs font-normal text-muted">µg/m³</span>
          </div>
        </div>
        <div className="text-right">
          <div className="label">Quạt</div>
          <div
            className={`font-mono text-sm font-medium ${
              device.fan_state === "ON" ? "text-good" : "text-muted"
            }`}
          >
            {device.fan_state === "ON" ? `ON · L${device.fan_level}` : "OFF"}
          </div>
          <div className="text-[10px] uppercase text-muted">{device.fan_mode}</div>
        </div>
      </div>

      <div className="mt-4 flex items-center justify-between border-t border-line/50 pt-3 text-[11px] text-muted">
        <span>ngưỡng {threshold.toFixed(0)} µg/m³</span>
        <span className="inline-flex items-center gap-1 group-hover:text-slate-300">
          {online ? formatAgo(device.last_seen_at) : "mất kết nối"}
          <Icon name="chevronRight" size={12} />
        </span>
      </div>

      {latest.data?.pm25_saturated && (
        <p className="mt-2 text-[11px] text-warn">cảm biến bão hoà</p>
      )}
    </Link>
  );
}

function SpacesSection({
  spaces,
  devices,
  onAdd,
}: {
  spaces: Space[];
  devices: Device[];
  onAdd: () => void;
}) {
  if (spaces.length === 0) return null;
  return (
    <section className="mt-8">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-sm font-medium">Phòng &amp; ngưỡng</h2>
        <button type="button" onClick={onAdd} className="text-xs text-muted hover:text-white">
          + thêm phòng
        </button>
      </div>
      <div className="card divide-y divide-line/50">
        {spaces.map((space) => {
          const count = devices.filter((device) => device.space_code === space.code).length;
          return (
            <div key={space.code} className="flex flex-wrap items-center gap-3 px-5 py-3 text-sm">
              <span className="font-mono text-xs text-muted">{space.code}</span>
              <span className="font-medium">{space.name}</span>
              <span className="text-xs text-muted">
                {space.volume_m3 ?? "?"} m³ · {space.purpose ?? "—"} · {space.region ?? "—"}
              </span>
              <span className="ml-auto flex items-center gap-3">
                <span className="font-mono text-sm">{space.threshold_pm25.toFixed(1)} µg/m³</span>
                <span className="chip border border-line bg-canvas text-[10px] uppercase text-muted">
                  {space.threshold_source === "ai" ? "AI" : "thủ công"}
                </span>
                <span className="text-xs text-muted">{count} node</span>
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function EmptyState({ onPair }: { onPair: () => void }) {
  return (
    <div className="card p-8 text-center">
      <div className="mx-auto flex h-12 w-12 items-center justify-center rounded-xl border border-info/30 bg-info/10 text-info">
        <Icon name="wind" size={24} />
      </div>
      <h2 className="mt-4 text-base font-medium">Chưa có thiết bị nào được ghép</h2>
      <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-muted">
        Cấp nguồn cho node, nối nó vào Wi-Fi. Màn hình OLED sẽ hiện một mã 6 ký tự — nhập mã đó ở đây
        để gắn node với phòng của nó. Telemetry của node chưa ghép vẫn được ghi lại, chỉ không hiện
        trên dashboard.
      </p>
      <button type="button" onClick={onPair} className="btn-primary mx-auto mt-5">
        <Icon name="link" /> Ghép thiết bị đầu tiên
      </button>
    </div>
  );
}
