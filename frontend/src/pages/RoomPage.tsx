import { useState } from "react";
import { Link } from "react-router-dom";

import { ForecastPanel } from "../components/ForecastPanel";
import { FanSelector } from "../components/FanSelector";
import { Icon } from "../components/Icon";
import { ReadingScale, Secondary, StatusWord, SystemLine } from "../components/Reading";
import { SeriesChart } from "../components/SeriesChart";
import {
  useCommands,
  useDevices,
  useFanCommand,
  useLatest,
  useRecordEvent,
  useSamples,
  useSeries,
  useSpaces,
  useSummary,
} from "../hooks/useAqss";
import { filterHours, formatAgo, formatHours, isOnline, roomState, STALE_READING_MS } from "../lib/room";

const RANGES = [
  { hours: 1, bucket: "1m" as const, label: "1 giờ" },
  { hours: 6, bucket: "5m" as const, label: "6 giờ" },
  { hours: 24, bucket: "5m" as const, label: "24 giờ" },
];

/**
 * One room, desktop-first: the question "is the air okay" on the left with the evidence, the things
 * a person can act on on the right. Requested and reported fan state are kept apart.
 */
export function RoomPage({ deviceId }: { deviceId: string }) {
  const devices = useDevices();
  const spaces = useSpaces();
  const device = devices.data?.find((item) => item.device_id === deviceId);
  const space = spaces.data?.find((item) => item.code === device?.space_code);
  const latest = useLatest(device?.device_id);
  const fan = useFanCommand(device?.device_id);
  const [range, setRange] = useState(RANGES[2]);
  const series = useSeries(device?.device_id, range.hours, range.bucket);
  const summary = useSummary(device?.device_id, 24);
  const samples = useSamples(device?.device_id, 24, 50);
  const commands = useCommands(device?.device_id, 5);

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
          Về tổng quan
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
  const latestCommand = commands.data?.[0];

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Link to="/" className="rounded-lg p-1 text-muted hover:text-white" aria-label="Về tổng quan">
              <Icon name="back" size={16} />
            </Link>
            <h2 className="truncate text-lg font-semibold tracking-tight">
              {space?.name ?? device.name ?? "Phòng"}
            </h2>
            <span
              className={`chip border ${
                online
                  ? "border-good/30 bg-good/10 text-good"
                  : "border-line bg-surface text-muted"
              }`}
            >
              {online ? `online · ${formatAgo(device.last_seen_at)}` : `offline · ${formatAgo(device.last_seen_at)}`}
            </span>
          </div>
          <p className="mt-1 font-mono text-[11px] text-muted">
            {space?.code ?? "—"} · {space?.volume_m3 ?? "?"} m³ · {device.device_id}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <EventRecorder deviceId={device.device_id} />
          <Link
            to={`/room/${encodeURIComponent(device.device_id)}/settings`}
            className="btn-ghost px-3 py-2 text-[12px]"
          >
            <Icon name="settings" size={14} /> Cài đặt
          </Link>
        </div>
      </header>

      <div className="grid gap-4 xl:grid-cols-[minmax(0,2fr)_minmax(340px,1fr)]">
        <div className="space-y-4">
          <section className="card p-5">
            <div className="flex flex-wrap items-end justify-between gap-4">
              <div>
                <div className="label">Không khí</div>
                <div className="mt-1 flex items-end gap-4">
                  <StatusWord state={state} />
                  <div className="pb-0.5">
                    <span className="reading text-slate-100">
                      {pm25 == null ? "—" : pm25.toFixed(1)}
                    </span>
                    <span className="reading-unit">µg/m³</span>
                  </div>
                </div>
                <SystemLine state={state} />
              </div>
              <div className="grid grid-cols-2 gap-x-6 gap-y-1 text-right">
                <Metric label="Coverage 24h" value={summary.data ? `${Math.round(summary.data.coverage * 100)}%` : "—"} />
                <Metric label="Phút vượt ngưỡng" value={summary.data ? `${summary.data.minutes_above_threshold}` : "—"} />
                <Metric label="TB 24h" value={fmtNum(summary.data?.avg_pm25, 1)} />
                <Metric label="Cao nhất" value={fmtNum(summary.data?.max_pm25, 1)} />
              </div>
            </div>
            <div className="mt-4">
              <ReadingScale value={pm25} threshold={threshold} status={state.status} />
            </div>
            {(stale || !online) && (
              <p className="mt-3 rounded-xl border border-warn/30 bg-warn/10 px-3 py-2 text-[12px] leading-relaxed text-warn">
                {!online
                  ? `Máy không gửi dữ liệu. Lần cuối ${formatAgo(device.last_seen_at)}.`
                  : "Số liệu mới nhất đã cũ hơn 5 phút, nên hệ thống đang tạm dừng tự động."}
              </p>
            )}
          </section>

          <section className="card p-4 lg:p-5">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <div className="label">PM2.5 · thực đo</div>
              <div className="flex gap-1 rounded-lg border border-line p-0.5" role="group" aria-label="Khoảng thời gian">
                {RANGES.map((option) => (
                  <button
                    key={option.label}
                    type="button"
                    aria-pressed={range.hours === option.hours}
                    onClick={() => setRange(option)}
                    className={`rounded-md px-2.5 py-1 text-[11px] transition-colors ${
                      range.hours === option.hours
                        ? "bg-white font-medium text-canvas"
                        : "text-muted hover:text-white"
                    }`}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>
            <div className="mt-2">
              <SeriesChart
                items={series.data?.items ?? []}
                threshold={threshold}
                bucketSeconds={series.data?.bucket_seconds ?? 300}
              />
            </div>
            <div className="mt-3 grid grid-cols-3 gap-3">
              <Secondary label="CO₂" value={latest.data?.co2 == null ? "—" : `${Math.round(latest.data.co2)} ppm`} />
              <Secondary
                label="Nhiệt độ"
                value={latest.data?.temperature == null ? "—" : `${latest.data.temperature.toFixed(1)}°C`}
              />
              <Secondary
                label="Độ ẩm"
                value={latest.data?.humidity == null ? "—" : `${latest.data.humidity.toFixed(0)}%`}
              />
            </div>
          </section>

          <details className="card overflow-hidden">
            <summary className="cursor-pointer px-4 py-3 text-[12px] text-slate-300 hover:text-white">
              Bảng số đo gần nhất ({samples.data?.items.length ?? 0})
            </summary>
            <div className="overflow-x-auto border-t border-line/60">
              <table className="w-full min-w-[760px] text-left text-[12px]">
                <thead className="text-[10px] uppercase tracking-wider text-muted">
                  <tr className="border-b border-line/50">
                    <th className="px-3 py-2">Đo lúc</th>
                    <th className="px-3 py-2">Nhận lúc</th>
                    <th className="px-3 py-2 text-right">PM2.5</th>
                    <th className="px-3 py-2 text-right">CO₂</th>
                    <th className="px-3 py-2 text-right">T</th>
                    <th className="px-3 py-2 text-right">RH</th>
                    <th className="px-3 py-2">Chất lượng</th>
                  </tr>
                </thead>
                <tbody>
                  {(samples.data?.items ?? []).map((sample) => (
                    <tr key={sample.id} className="border-b border-line/30 last:border-0">
                      <td className="whitespace-nowrap px-3 py-1.5 font-mono text-[11px]">
                        {new Date(sample.recorded_at).toLocaleString("vi-VN")}
                      </td>
                      <td className="whitespace-nowrap px-3 py-1.5 font-mono text-[11px] text-muted">
                        {sample.received_at ? new Date(sample.received_at).toLocaleTimeString("vi-VN") : "—"}
                      </td>
                      <td className="px-3 py-1.5 text-right font-mono">{fmtNum(sample.pm25, 1)}</td>
                      <td className="px-3 py-1.5 text-right font-mono">{sample.co2 == null ? "—" : Math.round(sample.co2)}</td>
                      <td className="px-3 py-1.5 text-right font-mono">{fmtNum(sample.temperature, 1)}</td>
                      <td className="px-3 py-1.5 text-right font-mono">{fmtNum(sample.humidity, 0)}</td>
                      <td className="px-3 py-1.5 text-[10px] text-muted">
                        {sample.pm25_saturated ? "bão hoà" : sample.dust_fault ? "dust fault" : sample.time_quality ?? "—"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        </div>

        <div className="space-y-4">
          <section className="card p-4">
            <FanSelector
              device={device}
              pending={fan.isPending}
              error={fan.error?.message ?? null}
              onCommand={(selection) => fan.mutate({ command: "set_actuator", params: selection })}
            />
            <div className="mt-3 rounded-xl border border-line/60 px-3 py-2 text-[11px] leading-relaxed">
              <div className="flex justify-between gap-2">
                <span className="text-muted">Máy báo</span>
                <span className="text-slate-200">
                  {device.fan_state} · mức {device.fan_level} · {device.fan_mode}
                </span>
              </div>
              <div className="mt-1 flex justify-between gap-2">
                <span className="text-muted">Lệnh gần nhất</span>
                <span className={commandTone(latestCommand?.status)}>
                  {latestCommand ? commandLabel(latestCommand.status) : "—"}
                </span>
              </div>
              <div className="mt-1 flex justify-between gap-2">
                <span className="text-muted">Báo cáo cuối</span>
                <span className="font-mono text-slate-300">
                  {formatAgo(device.last_actuator_received_at)}
                </span>
              </div>
            </div>
          </section>

          <ForecastPanel deviceId={device.device_id} threshold={threshold} anchor={pm25} />

          <section className="card flex items-center justify-between gap-3 p-4">
            <div className="flex min-w-0 items-center gap-3">
              <span className={`shrink-0 ${filter.low ? "text-warn" : "text-slate-400"}`}>
                <Icon name="filter" size={20} />
              </span>
              <div className="min-w-0">
                <div className="text-[11px] uppercase tracking-[0.12em] text-muted">Lõi lọc</div>
                <div className="font-mono text-sm text-slate-200">
                  còn {formatHours(filter.remaining)}
                  {filter.low && <span className="ml-2 text-warn">sắp hết</span>}
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
            <p className="text-center text-[11px] text-muted">
              Quyết định gần nhất {formatAgo(device.last_decision_at)} · {device.last_reason}
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

function EventRecorder({ deviceId }: { deviceId: string }) {
  const record = useRecordEvent(deviceId);
  const [sent, setSent] = useState<string | null>(null);
  const types = [
    { type: "door_open", label: "Mở cửa" },
    { type: "activity", label: "Có người" },
    { type: "window_open", label: "Mở cửa sổ" },
  ];
  return (
    <div className="flex items-center gap-1">
      {types.map((item) => (
        <button
          key={item.type}
          type="button"
          disabled={record.isPending}
          onClick={() =>
            record.mutate(
              { type: item.type, note: `ghi từ phòng ${deviceId}` },
              { onSuccess: () => setSent(item.label) },
            )
          }
          className="rounded-lg border border-line px-2 py-1.5 text-[11px] text-slate-400 hover:border-slate-500 hover:text-white disabled:opacity-40"
        >
          {item.label}
        </button>
      ))}
      {sent && <span className="text-[10px] text-good">đã ghi {sent}</span>}
      {record.isError && <span className="text-[10px] text-bad">lỗi</span>}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[10px] uppercase tracking-wider text-muted">{label}</div>
      <div className="font-mono text-[14px] text-slate-200">{value}</div>
    </div>
  );
}

function commandLabel(status: string): string {
  return {
    REQUESTED: "đang gửi",
    PUBLISHED: "đã gửi · chờ máy báo",
    REPORT_MATCHED: "máy đã báo khớp",
    PUBLISH_FAILED: "không gửi được",
    SUPERSEDED: "đã thay thế",
    UNCONFIRMED: "chưa xác nhận",
  }[status] ?? status;
}

function commandTone(status: string | undefined): string {
  if (status === "REPORT_MATCHED") return "text-good";
  if (status === "PUBLISH_FAILED") return "text-bad";
  if (status === "PUBLISHED" || status === "REQUESTED") return "text-warn";
  return "text-muted";
}

function fmtNum(value: number | null | undefined, digits: number): string {
  return value == null ? "—" : value.toFixed(digits);
}

function Centered({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto flex min-h-[50vh] max-w-md flex-col items-center justify-center px-5 text-center">
      {children}
    </div>
  );
}
