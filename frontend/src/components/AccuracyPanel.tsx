import { useState } from "react";

import type { ScoredPoint } from "../api/types";
import { useAccuracyPoints, useForecastAccuracy } from "../hooks/useAqss";

const HORIZONS = [5, 15, 30] as const;
/** Below this, the numbers are published but labelled as still collecting data. */
const MIN_PAIRS = 20;

interface Props {
  deviceId: string;
  /** Bumped by an SSE `metrics` event, so a scored target refreshes the panel without polling. */
  metricsVersion: number;
}

/**
 * How the forecasts already issued matched the room. Two absolute indicators and a count — never a
 * single "accuracy %", which PM near zero makes meaningless.
 */
export function AccuracyPanel({ deviceId, metricsVersion }: Props) {
  const [horizon, setHorizon] = useState<number>(5);
  const [open, setOpen] = useState(false);
  const query = useForecastAccuracy(deviceId, horizon, 6, metricsVersion);
  const accuracy = query.data;

  return (
    <section className="card p-4" aria-label="Độ khớp dự báo">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="label">Độ khớp dự báo</div>
        <div
          className="flex gap-1 rounded-lg border border-line p-0.5"
          role="group"
          aria-label="Độ xa dự báo"
        >
          {HORIZONS.map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={horizon === value}
              onClick={() => setHorizon(value)}
              className={`rounded-md px-2 py-0.5 font-mono text-[10px] transition-colors ${
                horizon === value ? "bg-white font-medium text-canvas" : "text-muted hover:text-white"
              }`}
            >
              {value}p
            </button>
          ))}
        </div>
      </div>

      {query.isLoading && <p className="mt-3 text-sm text-muted">Đang tải…</p>}

      {!query.isLoading && accuracy == null && (
        <p className="mt-3 text-sm leading-relaxed text-muted">
          Chưa có dự báo nào được phát hành để đối chiếu. Panel cần một run thật.
        </p>
      )}

      {accuracy && (
        <>
          <dl className="mt-3 space-y-2.5">
            <div>
              <dt className="text-[11px] text-muted">Sai lệch trung bình</dt>
              <dd className="font-mono text-2xl tabular-nums text-slate-100">
                {accuracy.mae_ug_m3 == null ? "—" : accuracy.mae_ug_m3.toFixed(1)}
                <span className="ml-1 font-sans text-[11px] font-normal text-muted">µg/m³</span>
              </dd>
            </div>
            <div>
              <dt className="text-[11px] text-muted">
                Trong ±{accuracy.tolerance_ug_m3.toFixed(0)} µg/m³
              </dt>
              <dd className="font-mono text-2xl tabular-nums text-slate-100">
                {accuracy.within_tolerance_pct == null
                  ? "—"
                  : `${accuracy.within_tolerance_pct.toFixed(0)}%`}
              </dd>
            </div>
            <div>
              <dt className="text-[11px] text-muted">Mốc đã đối chiếu</dt>
              <dd className="font-mono text-sm text-slate-200">
                {accuracy.matched}
                {accuracy.pending > 0 && (
                  <span className="ml-2 font-sans text-[10px] text-muted">
                    {accuracy.pending} đang chờ mốc
                  </span>
                )}
              </dd>
            </div>
          </dl>

          <HorizonBreakdown accuracy={accuracy} current={horizon} onSelect={setHorizon} />

          {accuracy.matched === 0 ? (
            <p className="mt-3 text-[11px] leading-relaxed text-muted">Chưa đủ mốc đối chiếu.</p>
          ) : accuracy.matched < MIN_PAIRS ? (
            <p className="mt-3 text-[11px] leading-relaxed text-muted">
              Đang thu thêm dữ liệu — {accuracy.matched} cặp là ít, số liệu sẽ ổn định hơn khi tích luỹ.
            </p>
          ) : null}

          {accuracy.missing + accuracy.late > 0 && (
            <p className="mt-2 text-[10px] leading-relaxed text-muted">
              {accuracy.missing} mốc thiếu số đo · {accuracy.late} run nộp trễ (không tính).
            </p>
          )}

          <p className="mt-2 truncate font-mono text-[10px] text-muted" title={accuracy.model_version}>
            model: {accuracy.model_version}
          </p>

          <button
            type="button"
            onClick={() => setOpen((value) => !value)}
            className="mt-3 text-[11px] text-info underline-offset-4 hover:underline"
          >
            {open ? "Ẩn các mốc" : "Xem các mốc"}
          </button>
        </>
      )}

      {accuracy && open && (
        <AccuracyDrawer deviceId={deviceId} horizon={horizon} metricsVersion={metricsVersion} />
      )}
    </section>
  );
}

/** 5 / 15 / 30 at a glance; clicking a row switches the panel to that horizon. */
function HorizonBreakdown({
  accuracy,
  current,
  onSelect,
}: {
  accuracy: NonNullable<ReturnType<typeof useForecastAccuracy>["data"]>;
  current: number;
  onSelect: (horizon: number) => void;
}) {
  if (accuracy.horizons.length === 0) {
    return null;
  }
  return (
    <table className="mt-3 w-full text-left text-[10px]">
      <thead className="text-[9px] uppercase tracking-wider text-muted">
        <tr>
          <th className="py-1">Độ xa</th>
          <th className="py-1 text-right">Cặp</th>
          <th className="py-1 text-right">Lệch TB</th>
          <th className="py-1 text-right">±5</th>
        </tr>
      </thead>
      <tbody>
        {accuracy.horizons.map((item) => (
          <tr
            key={item.horizon_minutes}
            onClick={() => onSelect(item.horizon_minutes)}
            className={`cursor-pointer border-t border-line/30 ${item.horizon_minutes === current ? "text-slate-100" : "text-muted"}`}
          >
            <td className="py-1">+{item.horizon_minutes}p</td>
            <td className="py-1 text-right font-mono">{item.matched}</td>
            <td className="py-1 text-right font-mono">
              {item.mae_ug_m3 == null ? "—" : item.mae_ug_m3.toFixed(1)}
            </td>
            <td className="py-1 text-right font-mono">
              {item.within_tolerance_pct == null ? "—" : `${item.within_tolerance_pct.toFixed(0)}%`}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function AccuracyDrawer({
  deviceId,
  horizon,
  metricsVersion,
}: {
  deviceId: string;
  horizon: number;
  metricsVersion: number;
}) {
  const query = useAccuracyPoints(deviceId, horizon, 6, true, metricsVersion);
  const rows: ScoredPoint[] = query.data ?? [];

  return (
    <div className="mt-3 border-t border-line/60 pt-3">
      <p className="text-[10px] leading-relaxed text-muted">
        Phát hành → mốc → dự báo → thực đo → lệch. Chỉ các cặp đã chấm, mới nhất trước.
      </p>
      {query.isLoading && <p className="mt-2 text-[11px] text-muted">Đang tải…</p>}
      {!query.isLoading && rows.length === 0 && (
        <p className="mt-2 text-[11px] text-muted">Chưa có cặp nào cho độ xa này.</p>
      )}
      {rows.length > 0 && (
        <div className="mt-2 max-h-64 overflow-auto">
          <table className="w-full text-left text-[11px]">
            <thead className="sticky top-0 bg-surface text-[9px] uppercase tracking-wider text-muted">
              <tr>
                <th className="py-1 pr-2">Phát hành</th>
                <th className="py-1 pr-2">Mốc</th>
                <th className="py-1 pr-2 text-right">Dự báo</th>
                <th className="py-1 pr-2 text-right">Thực đo</th>
                <th className="py-1 text-right">Lệch</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.issued_at}-${row.target_at}`} className="border-t border-line/30">
                  <td className="py-1 pr-2 font-mono text-[10px] text-muted">
                    {new Date(row.issued_at).toLocaleTimeString("vi-VN", {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </td>
                  <td className="py-1 pr-2 font-mono text-[10px] text-muted">
                    {new Date(row.target_at).toLocaleTimeString("vi-VN", {
                      hour: "2-digit",
                      minute: "2-digit",
                      second: "2-digit",
                    })}
                  </td>
                  <td className="py-1 pr-2 text-right font-mono">{row.predicted_pm25.toFixed(1)}</td>
                  <td className="py-1 pr-2 text-right font-mono">{row.actual_pm25.toFixed(1)}</td>
                  <td
                    className={`py-1 text-right font-mono ${
                      Math.abs(row.error) <= 5 ? "text-good" : "text-warn"
                    }`}
                  >
                    {row.error > 0 ? "+" : ""}
                    {row.error.toFixed(1)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
