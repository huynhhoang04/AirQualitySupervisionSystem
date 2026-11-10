import { Link } from "react-router-dom";

import { HistoryChart } from "../components/HistoryChart";
import { FanSelector } from "../components/FanSelector";
import { Icon } from "../components/Icon";
import { ReadingScale, Secondary, StatusWord, SystemLine } from "../components/Reading";
import { useDevices, useFanCommand, useHistory, useLatest, useSpaces } from "../hooks/useAqss";
import {
  filterHours,
  formatAgo,
  formatHours,
  isOnline,
  roomState,
  STALE_READING_MS,
} from "../lib/room";

/**
 * The room, every day. One question at the top — is the air okay — then what the machine is doing
 * about it, then the only two things a person can act on.
 */
export function RoomPage({ deviceId }: { deviceId: string }) {
  const devices = useDevices();
  const spaces = useSpaces();
  const device = devices.data?.find((item) => item.device_id === deviceId);
  const space = spaces.data?.find((item) => item.code === device?.space_code);
  const latest = useLatest(device?.device_id);
  const history = useHistory(device?.device_id);
  const fan = useFanCommand(device?.device_id);

  if (devices.isLoading) {
    return <Centered>Đang tải…</Centered>;
  }
  if (devices.isError) {
    return (
      <Centered>
        <p className="text-sm text-slate-200">Không kết nối được tới hệ thống</p>
        <p className="mt-1 text-[12px] text-muted">{String(devices.error?.message ?? "")}</p>
        <button type="button" onClick={() => devices.refetch()} className="btn-ghost mt-4">
          Thử lại
        </button>
      </Centered>
    );
  }

  if (!device) {
    return (
      <Centered>
        <p className="text-sm text-slate-200">Máy này không còn trong hệ thống</p>
        <p className="mt-1 text-[12px] leading-relaxed text-muted">
          Có thể nó đã được ngắt kết nối ở một tab khác.
        </p>
        <Link to="/" className="btn-ghost mt-4">
          Về danh sách máy
        </Link>
      </Centered>
    );
  }

  const threshold = space?.threshold_pm25 ?? 35;
  const pm25 = latest.data?.pm25 ?? null;
  const state = roomState(device, latest.data, space);
  const filter = filterHours(device);
  const online = isOnline(device.last_seen_at);
  const stale =
    latest.data != null && Date.now() - Date.parse(latest.data.recorded_at) > STALE_READING_MS;

  return (
    <div className="mx-auto min-h-screen max-w-lg px-5 pb-16">
      <header className="flex items-center gap-3 pt-6">
        <Link to="/" className="rounded-lg p-1.5 text-muted hover:text-white" aria-label="Về danh sách phòng">
          <Icon name="back" />
        </Link>
        <div className="min-w-0 flex-1">
          <h1 className="truncate text-[15px] font-medium">{space?.name ?? device.name ?? "Phòng"}</h1>
          <div className="font-mono text-[10px] text-muted">
            {space?.code ?? "—"} · {space?.volume_m3 ?? "?"} m³
          </div>
        </div>
        <Link
          to={`/room/${encodeURIComponent(device.device_id)}/settings`}
          className="rounded-lg p-1.5 text-muted hover:text-white"
          aria-label="Cài đặt phòng"
        >
          <Icon name="filter" />
        </Link>
      </header>

      <section className="mt-8">
        <div className="label">Không khí</div>
        <div className="mt-1.5 flex items-end gap-4">
          <StatusWord state={state} />
          <div className="pb-1">
            <span className="reading text-slate-100">{pm25 == null ? "—" : pm25.toFixed(1)}</span>
            <span className="reading-unit">µg/m³</span>
          </div>
        </div>
        <SystemLine state={state} />
      </section>

      <section className="mt-5">
        <ReadingScale value={pm25} threshold={threshold} status={state.status} />
      </section>

      {(stale || !online) && (
        <p className="mt-4 rounded-xl border border-warn/30 bg-warn/10 px-3 py-2 text-[12px] leading-relaxed text-warn">
          {!online
            ? `Máy không gửi dữ liệu. Lần cuối ${formatAgo(device.last_seen_at)}.`
            : "Số liệu mới nhất đã cũ hơn 5 phút, nên hệ thống đang tạm dừng tự động."}
        </p>
      )}

      <section className="card mt-6 p-4">
        <FanSelector
          device={device}
          pending={fan.isPending}
          error={fan.error?.message ?? null}
          onCommand={(selection) => fan.mutate({ command: "set_actuator", params: selection })}
        />
      </section>

      <section className="mt-6 grid grid-cols-3 gap-3">
        <Secondary label="CO₂" value={latest.data?.co2 == null ? "—" : `${Math.round(latest.data.co2)} ppm`} />
        <Secondary
          label="Nhiệt độ"
          value={latest.data?.temperature == null ? "—" : `${latest.data.temperature.toFixed(1)}°C`}
        />
        <Secondary
          label="Độ ẩm"
          value={latest.data?.humidity == null ? "—" : `${latest.data.humidity.toFixed(0)}%`}
        />
      </section>

      <section className="card mt-6 p-4">
        <div className="flex items-baseline justify-between">
          <div className="label">24 giờ</div>
          <span className="font-mono text-[10px] text-muted">nét đứt = ngưỡng</span>
        </div>
        <div className="mt-2">
          <HistoryChart history={history.data ?? []} threshold={threshold} />
        </div>
      </section>

      <section className="card mt-4 flex items-center justify-between gap-3 p-4">
        <div className="flex min-w-0 items-center gap-3">
          <span className={`shrink-0 ${filter.low ? "text-warn" : "text-slate-400"}`}>
            <Icon name="filter" size={20} />
          </span>
          <div className="min-w-0">
            <div className="text-[11px] uppercase tracking-[0.12em] text-muted">Lõi lọc</div>
            <div className="font-mono text-sm text-slate-200">
              {filter.low ? (
                <>
                  còn {formatHours(filter.remaining)}
                  <span className="ml-2 text-warn">sắp hết</span>
                </>
              ) : (
                <>còn {formatHours(filter.remaining)}</>
              )}
            </div>
          </div>
        </div>
        <Link
          to={`/room/${encodeURIComponent(device.device_id)}/settings`}
          className="shrink-0 text-[11px] text-muted underline-offset-4 hover:text-white hover:underline"
        >
          đổi lõi
        </Link>
      </section>

      {device.last_reason && (
        <p className="mt-4 text-center text-[11px] text-muted">
          Quyết định gần nhất {formatAgo(device.last_decision_at)}
        </p>
      )}
    </div>
  );
}

function Centered({ children }: { children: React.ReactNode }) {
  return <div className="mx-auto flex min-h-[60vh] max-w-md flex-col items-center justify-center px-5 text-center">{children}</div>;
}
