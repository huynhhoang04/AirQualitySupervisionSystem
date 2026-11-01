import type { ForecastPoint } from "../api/types";

interface Props {
  points: ForecastPoint[];
  refreshing: boolean;
  onRefresh: () => void;
}

export function ForecastPanel({ points, refreshing, onRefresh }: Props) {
  const unavailable = points.length === 0;

  return (
    <section className="card p-5">
      <div className="flex items-start justify-between gap-4">
        <div>
          <div className="label">Dự báo 24 giờ (Layer 3)</div>
          <div className="mt-1 text-xs text-slate-500">
            {unavailable
              ? "chưa có mô hình — backend đang chạy với ngưỡng thủ công"
              : `${points.length} điểm · q10 / q50 / q90`}
          </div>
        </div>
        <button
          type="button"
          onClick={onRefresh}
          disabled={refreshing}
          className="rounded-lg border border-line px-3 py-1.5 text-xs text-slate-300 transition hover:border-slate-500 disabled:opacity-50"
        >
          {refreshing ? "đang hỏi…" : "yêu cầu dự báo"}
        </button>
      </div>

      {unavailable ? (
        <div className="mt-4 rounded-lg border border-dashed border-line bg-slate-900/40 p-4 text-sm text-slate-500">
          <span className="mr-2 rounded bg-slate-700/60 px-1.5 py-0.5 text-[11px] uppercase tracking-wide text-slate-300">
            unavailable
          </span>
          Lớp AI chưa được triển khai. Khi FastAPI của Layer 3 có mặt, bật <code>AI_ENABLED=true</code>{" "}
          và endpoint này sẽ trả 96 điểm quantile để vẽ dải tin cậy.
        </div>
      ) : (
        <div className="mt-4 grid gap-2 text-sm text-slate-300 sm:grid-cols-3">
          {points.slice(0, 3).map((point) => (
            <div key={point.target_time} className="rounded-lg border border-line p-3">
              <div className="text-xs text-slate-500">
                {new Date(point.target_time).toLocaleString("vi-VN", {
                  hour: "2-digit",
                  minute: "2-digit",
                  day: "2-digit",
                  month: "2-digit",
                })}
              </div>
              <div className="mt-1 font-medium tabular-nums">{point.pm25_q50.toFixed(1)} µg/m³</div>
              <div className="text-xs text-slate-500 tabular-nums">
                {point.pm25_q10.toFixed(1)} – {point.pm25_q90.toFixed(1)}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
