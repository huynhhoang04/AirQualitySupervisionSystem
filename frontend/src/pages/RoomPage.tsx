import { useState } from "react";
import { Link } from "react-router-dom";

import { AccuracyPanel } from "../components/AccuracyPanel";
import { LiveChart } from "../components/LiveChart";
import { FanSelector } from "../components/FanSelector";
import { Icon } from "../components/Icon";
import { ReadingScale, StatusWord, SystemLine } from "../components/Reading";
import { SeriesChart } from "../components/SeriesChart";
import { ThresholdPanel } from "../components/ThresholdPanel";
import {
  useCommands,
  useDevices,
  useFanCommand,
  useLatest,
  useSamples,
  useSeries,
  useSpaces,
  useSummary,
} from "../hooks/useAqss";
import { useLiveRoom } from "../hooks/useLiveRoom";
import { filterHours, formatAgo, formatHours, isOnline, roomState, STALE_READING_MS } from "../lib/room";
import { useNow } from "../hooks/useNow";

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
  const now = useNow();
  const spaces = useSpaces();
  const device = devices.data?.find((item) => item.device_id === deviceId);
  const space = spaces.data?.find((item) => item.code === device?.space_code);
  const live = useLiveRoom(device?.device_id);
  const latest = useLatest(device?.device_id, live.status !== "live");
  const fan = useFanCommand(device?.device_id);
  const [liveMode, setLiveMode] = useState(true);
  const [range, setRange] = useState(RANGES[2]);
  const series = useSeries(device?.device_id, range.hours, range.bucket, !liveMode);
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
  const latestSample = live.samples[live.samples.length - 1];
  const reading = latestSample ?? latest.data;
  const pm25 = reading?.pm25 ?? null;
  const state = roomState(device, reading, space);
  const filter = filterHours(device);
  const online = isOnline(device.last_seen_at);
  const stale = reading != null && now - Date.parse(reading.recorded_at) > STALE_READING_MS;
  const latestCommand = commands.data?.[0];
  // The panel opens itself when the profile cannot produce a threshold yet: that is the one moment
  // the operator has to act, and a collapsed panel is a dead end.
  const profileIncomplete =
    space != null &&
    (space.volume_m3 == null || space.occupancy_hours == null || space.ventilation_estimate == null);
  const liveChip =
    live.status === "live"
      ? "border-good/30 bg-good/10 text-good"
      : live.status === "loading"
        ? "border-line bg-surface text-muted"
        : "border-warn/30 bg-warn/10 text-warn";
  const liveText =
    live.status === "live"
      ? "trực tiếp"
      : live.status === "loading"
        ? "đang tải"
        : live.status === "error"
          ? "mất kết nối"
          : "đang kết nối lại";

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
          <Link
            to={`/room/${encodeURIComponent(device.device_id)}/settings`}
            className="btn-ghost px-3 py-2 text-[12px]"
          >
            <Icon name="settings" size={14} /> Thiết bị
          </Link>
        </div>
      </header>

      <section className="card p-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div>
            <div className="flex items-baseline gap-3">
              <span className="reading text-slate-100">
                {pm25 == null ? "—" : pm25.toFixed(1)}
              </span>
              <span className="reading-unit">µg/m³</span>
              <StatusWord state={state} />
            </div>
            <SystemLine state={state} />
          </div>
          <div className="grid grid-cols-2 gap-x-6 gap-y-1 pt-1 text-right text-[11px] text-muted sm:grid-cols-4">
            <Metric label="TB 24h" value={fmtNum(summary.data?.avg_pm25, 1)} />
            <Metric label="Cao nhất" value={fmtNum(summary.data?.max_pm25, 1)} />
            <Metric label="Phút vượt" value={`${summary.data?.minutes_above_threshold ?? "—"}`} />
            <Metric label="Độ phủ" value={summary.data ? `${Math.round(summary.data.coverage * 100)}%` : "—"} />
          </div>
        </div>
        <div className="mt-3">
          <ReadingScale value={pm25} threshold={threshold} status={state.status} />
        </div>
        {(stale || !online) && (
          <p className="mt-2 text-[11px] leading-relaxed text-warn">
            {!online
              ? `Máy không gửi dữ liệu. Lần cuối ${formatAgo(device.last_seen_at)}.`
              : "Số liệu mới nhất đã cũ hơn 5 phút, nên hệ thống đang tạm dừng tự động."}
          </p>
        )}
      </section>

      <section className="card p-4 lg:p-5">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <div className="flex items-center gap-2">
            <div className="label">PM2.5 · thực đo &amp; dự báo</div>
            <span className={`chip border ${liveChip}`}>{liveText}</span>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <div className="flex gap-4 text-[11px] text-muted">
              <span>
                CO₂ <span className="font-mono text-[13px] text-slate-200">{reading?.co2 == null ? "—" : Math.round(reading.co2)}</span>
              </span>
              <span>
                Nhiệt độ{" "}
                <span className="font-mono text-[13px] text-slate-200">
                  {reading?.temperature == null ? "—" : reading.temperature.toFixed(1)}°
                </span>
              </span>
              <span>
                Ẩm{" "}
                <span className="font-mono text-[13px] text-slate-200">
                  {reading?.humidity == null ? "—" : Math.round(reading.humidity)}%
                </span>
              </span>
            </div>
            <div
              className="flex gap-1 rounded-lg border border-line p-0.5"
              role="group"
              aria-label="Chế độ chart"
            >
              <button
                type="button"
                aria-pressed={liveMode}
                onClick={() => setLiveMode(true)}
                className={modeTone(liveMode)}
              >
                Trực tiếp
              </button>
              {RANGES.map((option) => (
                <button
                  key={option.label}
                  type="button"
                  aria-pressed={!liveMode && range.hours === option.hours}
                  onClick={() => {
                    setLiveMode(false);
                    setRange(option);
                  }}
                  className={modeTone(!liveMode && range.hours === option.hours)}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
        </div>
        <div className="mt-2">
          {liveMode ? (
            <LiveChart
              samples={live.samples}
              forecast={live.forecast}
              threshold={threshold}
              status={live.status}
              height={430}
            />
          ) : (
            <SeriesChart
              items={series.data?.items ?? []}
              threshold={threshold}
              bucketSeconds={series.data?.bucket_seconds ?? 300}
              from={series.data?.from}
              to={series.data?.to}
              height={430}
            />
          )}
        </div>
        {liveMode && live.status === "error" && (
          <p className="mt-2 rounded-lg border border-warn/30 bg-warn/5 px-3 py-2 text-[11px] text-warn">
            {live.error ?? "Chưa kết nối được dòng dữ liệu trực tiếp."}
          </p>
        )}
      </section>

      <ThresholdPanel space={space} defaultOpen={profileIncomplete} />

      <div className="grid gap-4 lg:grid-cols-2">
        <AccuracyPanel deviceId={device.device_id} metricsVersion={live.metricsVersion} />

        <section className="card p-4">
          <FanSelector
            device={device}
            pending={fan.isPending}
            error={fan.error?.message ?? null}
            onCommand={(selection) => fan.mutate({ command: "set_actuator", params: selection })}
          />
          <div className="mt-2.5 flex flex-wrap items-center gap-x-4 gap-y-1 border-t border-line/50 pt-2.5 text-[11px] text-muted">
            <span>
              Máy báo{" "}
              <span className="text-slate-200">
                {device.fan_state} · mức {device.fan_level} · {device.fan_mode}
              </span>
            </span>
            <span>
              lệnh cuối{" "}
              <span className={commandTone(latestCommand?.status)}>
                {latestCommand ? commandLabel(latestCommand.status) : "—"}
              </span>
            </span>
            <span className="ml-auto flex items-center gap-1.5">
              <Icon name="filter" size={13} className={filter.low ? "text-warn" : ""} />
              lõi còn {formatHours(filter.remaining)}
              <Link
                to={`/room/${encodeURIComponent(device.device_id)}/settings`}
                className="underline-offset-4 hover:text-white hover:underline"
              >
                đổi lõi
              </Link>
            </span>
          </div>
          {device.last_reason && (
            <p className="mt-1.5 text-[11px] text-muted">
              Quyết định gần nhất {formatAgo(device.last_decision_at)} · {device.last_reason}
            </p>
          )}
        </section>
      </div>

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
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[10px] uppercase tracking-wider text-muted">{label}</div>
      <div className="font-mono text-[13px] text-slate-200">{value}</div>
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

function modeTone(active: boolean): string {
  return `rounded-md px-2.5 py-1 text-[11px] transition-colors ${
    active ? "bg-white font-medium text-canvas" : "text-muted hover:text-white"
  }`;
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
