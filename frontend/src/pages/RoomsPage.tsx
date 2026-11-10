import { Link } from "react-router-dom";

import type { Device, Space, Telemetry } from "../api/types";
import { Icon } from "../components/Icon";
import { useDevices, useLatest, useSpaces } from "../hooks/useAqss";
import { formatAgo, isOnline, roomState } from "../lib/room";

/**
 * Every machine, not every room.
 *
 * A device is the thing that exists; a room is the label the operator gave it. Listing devices means
 * a node that has not finished pairing — or one that lost its room — is still visible and still
 * reachable, instead of silently vanishing from the app.
 */
export function RoomsPage() {
  const devices = useDevices();
  const spaces = useSpaces();

  if (devices.isLoading) {
    return <div className="grid min-h-screen place-items-center text-sm text-muted">Đang tải…</div>;
  }

  // A failed request is not an empty account. Saying "chưa có máy nào" here would tell the
  // operator to re-pair a machine that is already paired and working.
  if (devices.isError) {
    return (
      <div className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-5">
        <Brand />
        <h1 className="mt-10 text-xl font-semibold tracking-tight">Không kết nối được tới hệ thống</h1>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          Trang không gọi được máy chủ. Kiểm tra mạng, hoặc thử tải lại sau vài giây.
        </p>
        <p className="mt-3 rounded-lg border border-line/60 bg-surface px-3 py-2 font-mono text-[11px] leading-relaxed text-muted">
          {String(devices.error?.message ?? "unknown error")}
        </p>
        <button type="button" onClick={() => devices.refetch()} className="btn-primary mt-5 w-full py-3.5">
          Thử lại
        </button>
        <Link to="/guide" className="mt-3 text-center text-sm text-muted hover:text-white">
          Xem hướng dẫn
        </Link>
      </div>
    );
  }

  const machines = [...(devices.data ?? [])].sort(
    (a, b) => urgency(a) - urgency(b) || (a.name ?? a.device_id).localeCompare(b.name ?? b.device_id, "vi"),
  );

  if (machines.length === 0) {
    return (
      <div className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-5">
        <Brand />
        <h1 className="mt-10 text-xl font-semibold tracking-tight">Chưa có máy nào</h1>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          Cắm điện cho máy lọc, đọc mã 6 ký tự trên màn hình, rồi kết nối.
        </p>
        <Link to="/connect" className="btn-primary mt-6 w-full py-4">
          Kết nối máy lọc
        </Link>
        <Link to="/guide" className="mt-3 text-center text-sm text-muted hover:text-white">
          Xem hướng dẫn kết nối
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto min-h-screen max-w-lg px-5 pb-16">
      <header className="flex items-center justify-between pt-6">
        <Brand />
        <div className="flex items-center gap-4">
          <Link to="/guide" className="text-[12px] text-muted hover:text-white">
            hướng dẫn
          </Link>
          <Link to="/connect" className="text-[12px] text-muted hover:text-white">
            + thêm máy
          </Link>
        </div>
      </header>

      <div className="mt-8 flex items-baseline justify-between">
        <h1 className="text-lg font-semibold tracking-tight">Máy lọc</h1>
        <span className="font-mono text-[11px] text-muted">{machines.length} máy</span>
      </div>

      <div className="mt-3 divide-y divide-line/50 rounded-2xl border border-line/60 bg-surface">
        {machines.map((device) => (
          <DeviceRow
            key={device.device_id}
            device={device}
            space={spaces.data?.find((space) => space.code === device.space_code)}
          />
        ))}
      </div>
    </div>
  );
}

/** Lower is more urgent: filtering, then running, then fine, then silent. */
function urgency(device: Device): number {
  if (!isOnline(device.last_seen_at)) return 3;
  if (device.fan_state === "ON") return 0;
  return 1;
}

function DeviceRow({ device, space }: { device: Device; space?: Space }) {
  const latest = useLatest(device.device_id);
  const state = roomState(device, latest.data as Telemetry | undefined, space);
  const online = isOnline(device.last_seen_at);
  const dot = !online
    ? "bg-line"
    : state.status === "good"
      ? "bg-good"
      : state.status === "filtering"
        ? "bg-bad"
        : "bg-warn";

  return (
    <Link
      to={`/room/${encodeURIComponent(device.device_id)}`}
      className="flex items-center gap-3 px-4 py-3.5 transition-colors first:rounded-t-2xl last:rounded-b-2xl hover:bg-white/[0.03]"
    >
      <span className={`h-2 w-2 shrink-0 rounded-full ${dot}`} aria-hidden="true" />
      <div className="min-w-0 flex-1">
        <div className="truncate text-[15px] font-medium">
          {device.name ?? space?.name ?? device.device_id}
        </div>
        <div className="truncate text-[12px] leading-snug text-muted">
          {online ? state.detail : `không gửi dữ liệu · ${formatAgo(device.last_seen_at)}`}
        </div>
      </div>
      <div className="shrink-0 text-right">
        <div className="font-mono text-[15px] text-slate-100">
          {latest.data?.pm25 == null ? "—" : latest.data.pm25.toFixed(1)}
        </div>
        <div className="font-mono text-[9px] text-muted">µg/m³</div>
      </div>
      <Icon name="next" size={14} className="shrink-0 text-line" />
    </Link>
  );
}

function Brand() {
  return (
    <div className="flex items-center gap-2">
      <span className="flex h-9 w-9 items-center justify-center rounded-xl border border-line text-info">
        <Icon name="wind" size={19} />
      </span>
      <span className="font-semibold tracking-tight">AQSS</span>
    </div>
  );
}
