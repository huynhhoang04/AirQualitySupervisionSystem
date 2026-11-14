import { Link } from "react-router-dom";

import { useDevices, useSpaces } from "../hooks/useAqss";
import { formatAgo, isOnline } from "../lib/room";

/** Device and room configuration in one place: what exists, what it is bound to, what it implies. */
export function DevicesPage() {
  const devices = useDevices();
  const spaces = useSpaces();

  if (devices.isLoading) {
    return <p className="text-sm text-muted">Đang tải…</p>;
  }
  if (devices.isError) {
    return (
      <div className="card p-5">
        <p className="text-sm text-slate-200">Không tải được danh sách thiết bị</p>
        <p className="mt-1 text-[12px] text-muted">{devices.error.message}</p>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-[11px] uppercase tracking-[0.14em] text-muted">Thiết bị & cấu hình</p>
          <h2 className="mt-1 text-lg font-semibold tracking-tight">Node và phòng</h2>
        </div>
        <Link to="/connect" className="btn-primary px-3 py-2 text-[12px]">
          + Kết nối máy
        </Link>
      </header>

      <section className="card overflow-hidden">
        <div className="border-b border-line/60 px-4 py-3">
          <div className="label">Thiết bị</div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] text-left text-[12px]">
            <thead className="text-[10px] uppercase tracking-wider text-muted">
              <tr className="border-b border-line/50">
                <th className="px-3 py-2">Thiết bị</th>
                <th className="px-3 py-2">Phòng</th>
                <th className="px-3 py-2">Kết nối</th>
                <th className="px-3 py-2">Quạt báo cáo</th>
                <th className="px-3 py-2">Lần cuối</th>
                <th className="px-3 py-2">Interval</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              {(devices.data ?? []).length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-3 py-8 text-center text-muted">
                    Chưa có máy nào được ghép phòng.
                  </td>
                </tr>
              ) : (
                (devices.data ?? []).map((device) => {
                  const space = spaces.data?.find((item) => item.code === device.space_code);
                  return (
                    <tr key={device.device_id} className="border-b border-line/40 last:border-0">
                      <td className="px-3 py-3">
                        <div className="font-medium">{device.name ?? device.device_id}</div>
                        <div className="font-mono text-[10px] text-muted">{device.device_id}</div>
                      </td>
                      <td className="px-3 py-3">
                        <div>{space?.name ?? "—"}</div>
                        <div className="font-mono text-[10px] text-muted">{space?.code ?? "chưa ghép"}</div>
                      </td>
                      <td className="px-3 py-3">
                        <span className={isOnline(device.last_seen_at) ? "text-good" : "text-muted"}>
                          {isOnline(device.last_seen_at) ? "online" : "offline"}
                        </span>
                      </td>
                      <td className="px-3 py-3 font-mono">
                        {device.fan_state} · {device.fan_level} · {device.fan_mode}
                      </td>
                      <td className="px-3 py-3 text-muted">{formatAgo(device.last_seen_at)}</td>
                      <td className="px-3 py-3 font-mono text-muted">{device.telemetry_interval_ms} ms</td>
                      <td className="px-3 py-3 text-right">
                        <div className="flex justify-end gap-2">
                          <Link
                            to={`/room/${encodeURIComponent(device.device_id)}`}
                            className="text-[11px] text-muted hover:text-white"
                          >
                            phòng
                          </Link>
                          <Link
                            to={`/room/${encodeURIComponent(device.device_id)}/settings`}
                            className="text-[11px] text-muted hover:text-white"
                          >
                            cài đặt
                          </Link>
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card overflow-hidden">
        <div className="border-b border-line/60 px-4 py-3">
          <div className="label">Hồ sơ phòng</div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] text-left text-[12px]">
            <thead className="text-[10px] uppercase tracking-wider text-muted">
              <tr className="border-b border-line/50">
                <th className="px-3 py-2">Phòng</th>
                <th className="px-3 py-2">Thể tích</th>
                <th className="px-3 py-2">Công năng</th>
                <th className="px-3 py-2">Giờ có người</th>
                <th className="px-3 py-2">Thông gió</th>
                <th className="px-3 py-2 text-right">Ngưỡng</th>
                <th className="px-3 py-2">Nguồn</th>
                <th className="px-3 py-2 text-right">Profile</th>
              </tr>
            </thead>
            <tbody>
              {(spaces.data ?? []).length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-3 py-8 text-center text-muted">
                    Chưa có phòng nào.
                  </td>
                </tr>
              ) : (
                (spaces.data ?? []).map((space) => (
                  <tr key={space.code} className="border-b border-line/40 last:border-0">
                    <td className="px-3 py-3">
                      <div className="font-medium">{space.name}</div>
                      <div className="font-mono text-[10px] text-muted">{space.code}</div>
                    </td>
                    <td className="px-3 py-3 font-mono">{space.volume_m3 == null ? "—" : `${space.volume_m3} m³`}</td>
                    <td className="px-3 py-3">{space.purpose ?? "—"}</td>
                    <td className="px-3 py-3 font-mono">{space.occupancy_hours ?? "theo công năng"}</td>
                    <td className="px-3 py-3">{space.ventilation_estimate ?? "—"}</td>
                    <td className="px-3 py-3 text-right font-mono">{space.threshold_pm25.toFixed(1)}</td>
                    <td className="px-3 py-3">
                      <span className={space.threshold_source === "profile" ? "text-info" : "text-muted"}>
                        {space.threshold_source}
                      </span>
                    </td>
                    <td className="px-3 py-3 text-right font-mono text-muted">v{space.profile_version}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
