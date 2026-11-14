import { Link } from "react-router-dom";

import type { ForecastPoint } from "../api/types";
import { useForecast } from "../hooks/useAqss";
import { formatAgo } from "../lib/room";

interface Props {
  deviceId: string;
  threshold: number;
  anchor?: number | null;
}

/**
 * The learned forecast. Three real target points — +5, +15, +30 — never a per-minute line drawn
 * through them. The panel says when the run was issued, how old its input is, and which model made
 * it, because those are the parts that decide whether the number is still worth reading.
 */
export function ForecastPanel({ deviceId, threshold, anchor }: Props) {
  const query = useForecast(deviceId);

  if (query.isLoading) {
    return (
      <section className="card p-4">
        <div className="label">Dự báo PM2.5 · 30 phút</div>
        <p className="mt-2 text-sm text-muted">Đang tải forecast…</p>
      </section>
    );
  }

  if (query.isError) {
    return (
      <section className="card p-4">
        <div className="flex items-baseline justify-between gap-2">
          <div className="label">Dự báo PM2.5 · 30 phút</div>
          <span className="chip border border-line text-muted">chưa có</span>
        </div>
        <p className="mt-2 text-sm leading-relaxed text-muted">
          Worker chưa phát hành forecast cho máy này. Dữ liệu đo và điều khiển theo ngưỡng vẫn chạy
          bình thường.
        </p>
        <Link to="/ai" className="mt-3 inline-block text-[12px] text-muted underline-offset-4 hover:text-white hover:underline">
          Mở Trung tâm AI để train
        </Link>
      </section>
    );
  }

  const view = query.data;
  if (!view) {
    return null;
  }
  const { run, points } = view;
  const issuedMinutes = Math.max(0, Math.round((Date.now() - Date.parse(run.issued_at)) / 60_000));
  const stale = issuedMinutes > 15;
  const quality = run.data_quality ?? {};
  const coverage = typeof quality.coverage_60m === "number" ? quality.coverage_60m : null;
  const late = run.status === "LATE_SUBMISSION";

  return (
    <section className="card p-4" aria-label="Dự báo PM2.5 30 phút">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="label">Dự báo PM2.5 · 30 phút</div>
        <span
          className={`chip border ${
            late
              ? "border-warn/30 bg-warn/10 text-warn"
              : stale
                ? "border-warn/30 bg-warn/10 text-warn"
                : "border-info/30 bg-info/10 text-info"
          }`}
        >
          {late ? "nộp trễ — không tính prospective" : stale ? "forecast cũ" : "đang hiện hành"}
        </span>
      </div>

      <ForecastChart points={points} threshold={threshold} anchor={anchor ?? null} />

      <div className="mt-3 grid gap-2 sm:grid-cols-3">
        {points.map((point) => (
          <ForecastCell key={point.horizon_minutes} point={point} threshold={threshold} />
        ))}
      </div>

      <dl className="mt-3 grid gap-x-6 gap-y-1 text-[11px] text-muted sm:grid-cols-2">
        <div className="flex justify-between gap-2">
          <dt>Phát hành</dt>
          <dd className="font-mono text-slate-300">
            {new Date(run.issued_at).toLocaleString("vi-VN", {
              hour: "2-digit",
              minute: "2-digit",
              day: "2-digit",
              month: "2-digit",
            })}{" "}
            ({issuedMinutes} phút trước)
          </dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt>Model</dt>
          <dd className="truncate font-mono text-slate-300" title={run.model_version}>
            {run.model_version}
          </dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt>Input cutoff</dt>
          <dd className="font-mono text-slate-300">{formatAgo(run.input_cutoff)}</dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt>Coverage 60 phút</dt>
          <dd className="font-mono text-slate-300">
            {coverage == null ? "—" : `${Math.round(coverage * 100)}%`}
          </dd>
        </div>
      </dl>

      {query.isFetching && (
        <p className="mt-2 text-[10px] text-muted">Đang cập nhật… vẫn hiển thị kết quả trước.</p>
      )}
    </section>
  );
}

function ForecastCell({ point, threshold }: { point: ForecastPoint; threshold: number }) {
  const breach = point.pm25 >= threshold;
  const actual = point.actual_pm25;
  return (
    <div className={`rounded-xl border px-3 py-2.5 ${breach ? "border-bad/30 bg-bad/5" : "border-line/60"}`}>
      <div className="flex items-baseline justify-between gap-2">
        <span className="font-mono text-[10px] uppercase tracking-wider text-muted">
          +{point.horizon_minutes} phút
        </span>
        <span className="font-mono text-[10px] text-muted">
          {new Date(point.target_at).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })}
        </span>
      </div>
      <div className={`mt-1 font-mono text-xl tabular-nums ${breach ? "text-bad" : "text-slate-100"}`}>
        {point.pm25.toFixed(1)}
        <span className="ml-1 font-sans text-[10px] font-normal text-muted">µg/m³</span>
      </div>
      {actual != null && (
        <div className="mt-0.5 text-[10px] text-muted">
          thực đo {actual.toFixed(1)} · lệch {point.error == null ? "—" : point.error.toFixed(1)}
        </div>
      )}
      {point.evaluation_status === "PENDING" && (
        <div className="mt-0.5 text-[10px] text-muted">chờ mốc thời gian</div>
      )}
    </div>
  );
}

/** Three real points, plus the last observation when the caller has it. */
function ForecastChart({
  points,
  threshold,
  anchor,
}: {
  points: ForecastPoint[];
  threshold: number;
  anchor: number | null;
}) {
  const width = 640;
  const height = 150;
  const pad = { top: 16, right: 12, bottom: 22, left: 36 };
  const ordered = [...points].sort((a, b) => a.horizon_minutes - b.horizon_minutes);
  if (ordered.length === 0) {
    return null;
  }

  const series = [
    ...(anchor == null ? [] : [{ t: Date.parse(ordered[0].target_at) - ordered[0].horizon_minutes * 60_000, v: anchor }]),
    ...ordered.map((point) => ({ t: Date.parse(point.target_at), v: point.pm25 })),
  ];
  const start = series[0].t - 60_000;
  const end = series[series.length - 1].t + 60_000;
  const top = Math.max(threshold * 1.5, ...series.map((point) => point.v), 20) * 1.1;
  const x = (t: number) => pad.left + ((t - start) / (end - start)) * (width - pad.left - pad.right);
  const y = (v: number) => pad.top + (1 - v / top) * (height - pad.top - pad.bottom);

  const path = series.map((point, index) => `${index === 0 ? "M" : "L"}${x(point.t)},${y(point.v)}`).join(" ");

  return (
    <svg viewBox={`0 0 ${width} ${height}`} className="mt-2 w-full" style={{ height }} role="img"
      aria-label={`Forecast ${ordered.map((point) => `+${point.horizon_minutes} phút ${point.pm25.toFixed(1)}`).join(", ")}`}>
      <line x1={pad.left} x2={width - pad.right} y1={y(threshold)} y2={y(threshold)}
        stroke="currentColor" className="text-bad" strokeDasharray="6 4" strokeWidth="1.3" />
      <text x={pad.left + 4} y={y(threshold) - 4} className="fill-bad" fontSize="9">
        ngưỡng {threshold.toFixed(0)}
      </text>
      <path d={path} fill="none" stroke="currentColor" className="text-warn" strokeDasharray="7 5" strokeWidth="1.8" />
      {series.map((point, index) => (
        <circle key={point.t} cx={x(point.t)} cy={y(point.v)} r={index === 0 && anchor != null ? 3 : 4}
          fill="currentColor" className={index === 0 && anchor != null ? "text-info" : "text-warn"} />
      ))}
      {series.map((point) => (
        <text key={`label-${point.t}`} x={x(point.t)} y={height - 5} textAnchor="middle" className="fill-muted" fontSize="9">
          {new Date(point.t).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })}
        </text>
      ))}
    </svg>
  );
}
