import { useMemo, useState } from "react";

import type { ScoredPoint } from "../api/types";
import { useAccuracyPoints, useForecastAccuracy } from "../hooks/useAqss";

/**
 * The product forecasts 30 minutes, so the scoreboard scores 30 minutes.
 *
 * The previous version offered +5/+15/+30 tabs and two statistics (MAE, "% inside a ±5 band")
 * without saying what either meant. Nobody opens a room page to read a mean absolute error. This
 * version answers the question the room actually asks — "how close was it, really?" — by putting
 * the guesses next to the truth as bars.
 */
const HORIZON_MINUTES = 30;
const WINDOW_HOURS = 6;
const MIN_PAIRS = 20;
/** How many recent forecasts the comparison strip draws. */
const STRIP_SIZE = 24;

interface Props {
  deviceId: string;
  /** Bumped by an SSE `metrics` event, so a scored target refreshes the panel without polling. */
  metricsVersion: number;
}

export function AccuracyPanel({ deviceId, metricsVersion }: Props) {
  const [open, setOpen] = useState(false);
  const summary = useForecastAccuracy(deviceId, HORIZON_MINUTES, WINDOW_HOURS, metricsVersion);
  const accuracy = summary.data;

  if (summary.isLoading) {
    return (
      <section className="card self-start p-4" aria-label="Dự báo 30 phút khớp đến đâu">
        <Header />
        <p className="mt-2 text-[12px] text-muted">Đang tải…</p>
      </section>
    );
  }

  if (!accuracy || accuracy.matched === 0) {
    return (
      <section className="card self-start p-4" aria-label="Dự báo 30 phút khớp đến đâu">
        <Header />
        <p className="mt-2 text-[12px] leading-relaxed text-muted">
          Chưa có dự báo nào đủ 30 phút để chấm. Mỗi đường dự báo cần 30 phút trôi qua mới biết nó
          đúng đến đâu.
        </p>
        {accuracy && accuracy.pending > 0 && (
          <p className="mt-1.5 text-[11px] text-muted">
            {accuracy.pending} dự báo đang chờ đủ 30 phút.
          </p>
        )}
      </section>
    );
  }

  const mae = accuracy.mae_ug_m3;
  const within = accuracy.within_tolerance_pct;
  const relative = accuracy.within_relative_pct;
  const relativePct = Math.round(accuracy.relative_tolerance * 100);

  return (
    <section className="card self-start p-4" aria-label="Dự báo 30 phút khớp đến đâu">
      <Header />

      {mae != null && (
        <p className="mt-2 text-[12px] leading-relaxed text-slate-200">
          Nhìn trước 30 phút, con số thường lệch{" "}
          <strong className="font-mono text-[16px] text-slate-100">{mae.toFixed(1)}</strong> µg/m³.
        </p>
      )}

      {relative != null && (
        <p className="mt-1 text-[12px] leading-relaxed text-slate-200">
          <strong
            className={`font-mono text-[16px] ${
              relative >= 70 ? "text-good" : relative >= 40 ? "text-warn" : "text-bad"
            }`}
          >
            {relative.toFixed(0)}%
          </strong>{" "}
          số dự báo nằm trong ±{relativePct}% so với số đo thật.
        </p>
      )}

      {within != null && (
        <p className="mt-1 text-[12px] leading-relaxed text-muted">
          Cứ 10 lần thì khoảng {Math.round(within / 10)} lần lệch dưới 5 µg/m³.
        </p>
      )}

      <Comparison deviceId={deviceId} metricsVersion={metricsVersion} />

      <p className="mt-2 text-[11px] text-muted">
        {accuracy.matched} lần đã chấm · 6 giờ gần nhất
        {accuracy.matched < MIN_PAIRS && " · còn ít, số sẽ ổn định hơn khi tích luỹ"}
      </p>

      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        className="mt-1.5 text-[11px] text-info underline-offset-4 hover:underline"
      >
        {open ? "Ẩn bảng chi tiết" : "Xem bảng chi tiết"}
      </button>

      {open && <AccuracyDrawer deviceId={deviceId} metricsVersion={metricsVersion} />}
    </section>
  );
}

function Header() {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-2">
      <div className="label">Dự báo 30 phút khớp đến đâu</div>
      <span className="font-mono text-[10px] text-muted">mỗi cột ≈ 1 lần</span>
    </div>
  );
}

/**
 * Two bars per forecast: what was predicted and what was measured.
 *
 * A shorter yellow bar than the cyan one means the forecast was too low; equal heights mean it was
 * right. This needs no legend beyond colour and no statistics at all — it is the same visual
 * grammar as the chart above.
 */
function Comparison({ deviceId, metricsVersion }: { deviceId: string; metricsVersion: number }) {
  const query = useAccuracyPoints(deviceId, HORIZON_MINUTES, WINDOW_HOURS, true, metricsVersion);
  const rows = useMemo(
    () => [...(query.data ?? [])].slice(0, STRIP_SIZE).reverse(),
    [query.data],
  );

  if (query.isLoading) {
    return <div className="mt-3 h-20 animate-pulse rounded-lg bg-line/30" />;
  }
  if (rows.length === 0) {
    return null;
  }

  const top = Math.max(...rows.flatMap((row) => [row.predicted_pm25, row.actual_pm25]), 10) * 1.1;

  return (
    <div className="mt-3">
      <div
        className="flex h-20 items-end gap-[3px]"
        role="img"
        aria-label="So sánh dự báo và thực đo của các lần gần nhất"
      >
        {rows.map((row) => {
          const close = Math.abs(row.error) <= 5;
          return (
            <div
              key={`${row.issued_at}-${row.target_at}`}
              className="flex h-full flex-1 items-end justify-center gap-px"
              title={`Đoán ${row.predicted_pm25.toFixed(1)} · thực tế ${row.actual_pm25.toFixed(1)} · lệch ${row.error > 0 ? "+" : ""}${row.error.toFixed(1)}`}
            >
              <span
                className="w-1/2 rounded-t-sm bg-warn/70"
                style={{ height: `${Math.max(2, (row.predicted_pm25 / top) * 100)}%` }}
              />
              <span
                className={`w-1/2 rounded-t-sm ${close ? "bg-info" : "bg-bad/80"}`}
                style={{ height: `${Math.max(2, (row.actual_pm25 / top) * 100)}%` }}
              />
            </div>
          );
        })}
      </div>
      <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-[10px] text-muted">
        <span className="text-warn">▮ đoán</span>
        <span className="text-info">▮ thực tế, lệch ≤ 5</span>
        <span className="text-bad">▮ thực tế, lệch &gt; 5</span>
        <span className="ml-auto">{rows.length} lần gần nhất</span>
      </div>
    </div>
  );
}

function AccuracyDrawer({
  deviceId,
  metricsVersion,
}: {
  deviceId: string;
  metricsVersion: number;
}) {
  const query = useAccuracyPoints(deviceId, HORIZON_MINUTES, WINDOW_HOURS, true, metricsVersion);
  const rows: ScoredPoint[] = query.data ?? [];

  return (
    <div className="mt-2 border-t border-line/60 pt-2">
      {query.isLoading && <p className="text-[11px] text-muted">Đang tải…</p>}
      {!query.isLoading && rows.length === 0 && (
        <p className="text-[11px] text-muted">Chưa có lần nào được chấm.</p>
      )}
      {rows.length > 0 && (
        <div className="max-h-56 overflow-auto">
          <table className="w-full text-left text-[11px]">
            <thead className="sticky top-0 bg-surface text-[9px] uppercase tracking-wider text-muted">
              <tr>
                <th className="py-1 pr-2">Phát hành → mốc</th>
                <th className="py-1 pr-2 text-right">Đoán</th>
                <th className="py-1 pr-2 text-right">Thực tế</th>
                <th className="py-1 text-right">Lệch</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.issued_at}-${row.target_at}`} className="border-t border-line/30">
                  <td className="whitespace-nowrap py-1 pr-2 font-mono text-[10px] text-muted">
                    {new Date(row.issued_at).toLocaleTimeString("vi-VN", {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                    <span className="mx-1 opacity-50">→</span>
                    {new Date(row.target_at).toLocaleTimeString("vi-VN", {
                      hour: "2-digit",
                      minute: "2-digit",
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
