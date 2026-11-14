import { Link } from "react-router-dom";

import type { Device, Space, Telemetry } from "../api/types";
import { Icon } from "../components/Icon";
import { SeriesChart } from "../components/SeriesChart";
import { useAiStatus, useDevices, useLatest, useSeries, useSpaces } from "../hooks/useAqss";
import { formatAgo, isOnline, roomState } from "../lib/room";

/**
 * Overview: what is in the house, what the machines report, and whether the learning loop is alive.
 * Each row is one real node. There is no synthetic room to fill a grid.
 */
export function RoomsPage() {
  const devices = useDevices();
  const spaces = useSpaces();
  const first = devices.data?.[0];
  const firstSpace = spaces.data?.find((space) => space.code === first?.space_code);
  const status = useAiStatus(first?.device_id);
  const series = useSeries(first?.device_id, 24, "5m");

  if (devices.isLoading) {
    return <div className="grid min-h-[40vh] place-items-center text-sm text-muted">Đang tải…</div>;
  }

  if (devices.isError) {
    return (
      <div className="mx-auto max-w-xl py-12">
        <h2 className="text-xl font-semibold tracking-tight">Không kết nối được tới hệ thống</h2>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          Trang không gọi được máy chủ. Kiểm tra mạng, hoặc thử tải lại sau vài giây.
        </p>
        <p className="mt-3 rounded-lg border border-line/60 bg-surface px-3 py-2 font-mono text-[11px] leading-relaxed text-muted">
          {String(devices.error?.message ?? "unknown error")}
        </p>
        <button type="button" onClick={() => devices.refetch()} className="btn-primary mt-5">
          Thử lại
        </button>
      </div>
    );
  }

  const machines = [...(devices.data ?? [])].sort(
    (a, b) => urgency(a) - urgency(b) || (a.name ?? a.device_id).localeCompare(b.name ?? b.device_id, "vi"),
  );

  if (machines.length === 0) {
    return (
      <div className="mx-auto max-w-xl py-12">
        <h2 className="text-xl font-semibold tracking-tight">Chưa có máy nào</h2>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          Cắm điện cho máy lọc, đọc mã 6 ký tự trên màn hình, rồi kết nối. Dữ liệu sẽ bắt đầu được
          thu ngay khi máy gửi bản tin đầu tiên.
        </p>
        <Link to="/connect" className="btn-primary mt-6">
          Kết nối máy lọc
        </Link>
      </div>
    );
  }

  const filtering = machines.filter((device) => device.fan_state === "ON").length;
  const online = machines.filter((device) => isOnline(device.last_seen_at)).length;
  const model = status.data?.current_model;

  return (
    <div className="space-y-6">
      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Máy lọc" value={`${machines.length}`} note={`${online} đang online`} />
        <Kpi label="Đang lọc" value={`${filtering}`} note="thiết bị báo quạt ON" />
        <Kpi
          label="AI worker"
          value={status.data?.worker_online ? "online" : "offline"}
          note={status.data?.worker_online ? "đang nhận job" : "forecast dùng baseline"}
          tone={status.data?.worker_online ? "good" : "muted"}
        />
        <Kpi
          label="Model"
          value={model ? model.algorithm ?? "learned" : "persistence"}
          note={model ? `${model.status} · ${model.version.slice(0, 24)}` : "chưa có model đạt cổng promote"}
          tone={model?.status === "PRODUCTION" ? "good" : "muted"}
        />
      </section>

      <section className="grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(300px,1fr)]">
        <div className="card p-4 lg:p-5">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <div>
              <div className="label">PM2.5 · 24 giờ</div>
              <div className="mt-0.5 text-sm text-slate-300">
                {firstSpace?.name ?? first?.name ?? "—"}{" "}
                <span className="font-mono text-[11px] text-muted">· gộp 5 phút</span>
              </div>
            </div>
            <Link
              to={`/room/${encodeURIComponent(first!.device_id)}`}
              className="text-[12px] text-muted hover:text-white"
            >
              Phân tích phòng →
            </Link>
          </div>
          <div className="mt-3">
            <SeriesChart
              items={series.data?.items ?? []}
              threshold={firstSpace?.threshold_pm25 ?? 35}
              bucketSeconds={series.data?.bucket_seconds ?? 300}
              from={series.data?.from}
              to={series.data?.to}
            />
          </div>
          {series.isError && (
            <p className="mt-2 text-[11px] text-warn">
              Chưa tải được chuỗi số đo: {series.error.message}
            </p>
          )}
        </div>

        <div className="card p-4 lg:p-5">
          <div className="label">Dữ liệu & mô hình</div>
          <dl className="mt-3 space-y-3 text-[13px]">
            <Item label="Telemetry cuối" value={formatAgo(status.data?.latest_telemetry_recorded_at)} />
            <Item
              label="Backend nhận"
              value={formatAgo(status.data?.latest_telemetry_received_at)}
            />
            <Item label="Forecast phát hành" value={formatAgo(status.data?.latest_forecast_issued_at)} />
            <Item
              label="Forecast được nhận"
              value={formatAgo(status.data?.latest_forecast_accepted_at)}
            />
          </dl>
          <div className="mt-4 rounded-xl border border-line/60 px-3 py-2.5 text-[11px] leading-relaxed text-muted">
            AI không nằm trên đường điều khiển. Tắt worker chỉ làm forecast cũ đi, không ảnh hưởng
            đo hay ngưỡng.
          </div>
          <div className="mt-3 flex gap-2">
            <Link to="/ai" className="btn-ghost flex-1 py-2 text-[12px]">
              Trung tâm AI
            </Link>
            <Link to="/evaluation" className="btn-ghost flex-1 py-2 text-[12px]">
              Đánh giá
            </Link>
          </div>
        </div>
      </section>

      <section className="card overflow-hidden">
        <div className="flex flex-wrap items-baseline justify-between gap-2 border-b border-line/60 px-4 py-3">
          <div className="label">Thiết bị</div>
          <span className="font-mono text-[11px] text-muted">{machines.length} máy</span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[980px] text-left text-[13px]">
            <thead className="text-[10px] uppercase tracking-[0.12em] text-muted">
              <tr className="border-b border-line/50">
                <Th sticky>Phòng / máy</Th>
                <Th>Trạng thái</Th>
                <Th right>PM2.5</Th>
                <Th right>CO₂</Th>
                <Th right>Nhiệt độ</Th>
                <Th right>Độ ẩm</Th>
                <Th>Nhận lúc</Th>
                <Th right>Ngưỡng</Th>
                <Th>Quạt (báo cáo)</Th>
                <Th>Quyết định</Th>
                <Th />
              </tr>
            </thead>
            <tbody>
              {machines.map((device) => (
                <DeviceRow
                  key={device.device_id}
                  device={device}
                  space={spaces.data?.find((space) => space.code === device.space_code)}
                />
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function Kpi({
  label,
  value,
  note,
  tone = "default",
}: {
  label: string;
  value: string;
  note: string;
  tone?: "default" | "good" | "muted";
}) {
  const valueTone = tone === "good" ? "text-good" : tone === "muted" ? "text-slate-200" : "text-white";
  return (
    <div className="card px-4 py-3.5">
      <div className="label">{label}</div>
      <div className={`mt-1 truncate font-mono text-[26px] font-semibold leading-none ${valueTone}`}>
        {value}
      </div>
      <div className="mt-1 truncate text-[11px] text-muted">{note}</div>
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
  const telemetry = latest.data as Telemetry | undefined;
  const state = roomState(device, telemetry, space);
  const online = isOnline(device.last_seen_at);
  const threshold = space?.threshold_pm25 ?? 35;
  const alert = telemetry?.pm25 != null && telemetry.pm25 >= threshold;

  return (
    <tr className="border-b border-line/40 last:border-0 hover:bg-white/[0.025]">
      <Td sticky>
        <Link
          to={`/room/${encodeURIComponent(device.device_id)}`}
          className="block max-w-[220px] truncate font-medium hover:text-white"
        >
          {space?.name ?? device.name ?? device.device_id}
        </Link>
        <span className="font-mono text-[10px] text-muted">{device.device_id}</span>
      </Td>
      <Td>
        <span className={`chip border ${statusTone(state.status)}`}>{state.word}</span>
      </Td>
      <Td right mono>{fmt(telemetry?.pm25, 1)}</Td>
      <Td right mono>{telemetry?.co2 == null ? "—" : Math.round(telemetry.co2)}</Td>
      <Td right mono>{fmt(telemetry?.temperature, 1)}</Td>
      <Td right mono>{fmt(telemetry?.humidity, 0)}</Td>
      <Td>
        <span className={online ? "text-slate-300" : "text-muted"}>
          {online ? formatAgo(device.last_seen_at) : `mất liên lạc · ${formatAgo(device.last_seen_at)}`}
        </span>
      </Td>
      <Td right mono>{threshold.toFixed(0)}</Td>
      <Td>
        <span className={device.fan_state === "ON" ? "text-good" : "text-muted"}>
          {device.fan_state === "ON" ? `ON · mức ${device.fan_level}` : "OFF"}
        </span>
        <span className="ml-1 text-[10px] text-muted">{device.fan_mode.toLowerCase()}</span>
      </Td>
      <Td>
        {alert ? (
          <span className="text-bad">vượt ngưỡng</span>
        ) : device.last_reason ? (
          <span className="text-muted">{reasonText(device.last_reason)}</span>
        ) : (
          <span className="text-muted">—</span>
        )}
      </Td>
      <Td>
        <Link
          to={`/room/${encodeURIComponent(device.device_id)}`}
          className="text-muted hover:text-white"
          aria-label={`Mở ${device.device_id}`}
        >
          <Icon name="next" size={14} />
        </Link>
      </Td>
    </tr>
  );
}

function reasonText(reason: NonNullable<Device["last_reason"]>): string {
  return {
    threshold_breach: "vượt ngưỡng",
    hysteresis_off: "hạ nhiệt",
    max_on: "hết giờ chạy",
    manual: "điều khiển tay",
  }[reason];
}

function statusTone(status: ReturnType<typeof roomState>["status"]): string {
  if (status === "good") return "border-good/30 bg-good/10 text-good";
  if (status === "filtering") return "border-bad/30 bg-bad/10 text-bad";
  return "border-warn/30 bg-warn/10 text-warn";
}

function fmt(value: number | null | undefined, digits: number): string {
  return value == null ? "—" : value.toFixed(digits);
}

function Th({
  children,
  right,
  sticky,
}: {
  children?: React.ReactNode;
  right?: boolean;
  sticky?: boolean;
}) {
  return (
    <th
      className={`whitespace-nowrap px-3 py-2 font-medium ${right ? "text-right" : ""} ${
        sticky ? "sticky left-0 bg-surface" : ""
      }`}
    >
      {children}
    </th>
  );
}

function Td({
  children,
  right,
  mono,
  sticky,
}: {
  children?: React.ReactNode;
  right?: boolean;
  mono?: boolean;
  sticky?: boolean;
}) {
  return (
    <td
      className={`whitespace-nowrap px-3 py-3 ${right ? "text-right" : ""} ${
        mono ? "font-mono tabular-nums" : ""
      } ${sticky ? "sticky left-0 bg-surface" : ""}`}
    >
      {children}
    </td>
  );
}

function Item({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-muted">{label}</dt>
      <dd className="text-right font-mono text-[12px] text-slate-200">{value}</dd>
    </div>
  );
}
