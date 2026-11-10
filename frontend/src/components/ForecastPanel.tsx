import { useMemo } from "react";

import type { ForecastPoint, Space } from "../api/types";
import { formatDuration } from "../lib/format";
import { Icon } from "./Icon";

interface Props {
  points: ForecastPoint[];
  space?: Space;
  refreshing: boolean;
  onRefresh: () => void;
}

const PAD = { top: 10, right: 10, bottom: 22, left: 34 };
const HEIGHT = 210;
const WIDTH = 720;

export function ForecastPanel({ points, space, refreshing, onRefresh }: Props) {
  const threshold = space?.threshold_pm25 ?? 35;

  const { q10, q50, q90, area, maxV, y, minT, maxT, firstBreach } = useMemo(() => {
    const w = WIDTH;
    const sorted = [...points]
      .filter((p) => p.target_time)
      .sort((a, b) => Date.parse(a.target_time) - Date.parse(b.target_time));

    const now = Date.now();
    const times = sorted.map((p) => Date.parse(p.target_time));
    const start = Math.min(now, ...(times.length ? times : [now]));
    const end = Math.max(now + 3600_000, ...(times.length ? times : [now + 24 * 3600_000]));
    const top = Math.max(threshold * 1.5, ...sorted.map((p) => p.pm25_q90), 10) * 1.1;
    const scaleY = (v: number) => PAD.top + (1 - v / top) * (HEIGHT - PAD.top - PAD.bottom);
    const scaleX = (t: number) => PAD.left + ((t - start) / (end - start)) * (w - PAD.left - PAD.right);

    const line = (key: "pm25_q10" | "pm25_q50" | "pm25_q90") =>
      sorted.map((p, i) => `${i === 0 ? "M" : "L"}${scaleX(Date.parse(p.target_time)).toFixed(1)},${scaleY(p[key]).toFixed(1)}`).join(" ");

    // The band is the q10 path forwards, then the q90 path backwards — one closed shape.
    const band =
      sorted.length === 0
        ? ""
        : `${line("pm25_q10")} ` +
          sorted
            .slice()
            .reverse()
            .map((p) => `L${scaleX(Date.parse(p.target_time)).toFixed(1)},${scaleY(p.pm25_q90).toFixed(1)}`)
            .join(" ") +
          " Z";

    const breach = sorted.find((p) => p.pm25_q90 >= threshold && Date.parse(p.target_time) > now) ?? null;

    return {
      q10: line("pm25_q10"),
      q50: line("pm25_q50"),
      q90: line("pm25_q90"),
      area: band,
      maxV: top,
      y: scaleY,
      minT: start,
      maxT: end,
      firstBreach: breach,
    };
  }, [points, threshold]);

  return (
    <section className="card p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="label">Dự báo 24 giờ · q10 / q50 / q90</div>
          <p className="mt-1 max-w-prose text-xs leading-relaxed text-muted">
            {points.length > 0
              ? "Dải mờ là khoảng bất định (q10–q90). Vòng điều khiển bật quạt sớm khi q90 chạm ngưỡng trong 2 giờ tới."
              : "Chưa có mô hình dự báo. Backend đang chạy với ngưỡng thủ công và luật ngưỡng tức thời."}
          </p>
        </div>
        <button type="button" onClick={onRefresh} disabled={refreshing} className="btn-ghost">
          <Icon name="refresh" className={refreshing ? "animate-spin" : ""} />
          {refreshing ? "Đang hỏi…" : "Yêu cầu dự báo"}
        </button>
      </div>

      {firstBreach && (
        <div className="mt-3 flex items-start gap-2 rounded-lg border border-warn/30 bg-warn/10 px-3 py-2 text-xs text-warn">
          <Icon name="alert" className="mt-px shrink-0" />
          <span>
            q90 dự kiến vượt ngưỡng {threshold.toFixed(0)} µg/m³ sau{" "}
            {formatDuration((Date.parse(firstBreach.target_time) - Date.now()) / 60_000)} nữa — quạt
            sẽ được bật trước mốc đó.
          </span>
        </div>
      )}

      {points.length === 0 ? (
        <div className="mt-4 rounded-lg border border-dashed border-line px-4 py-6 text-sm text-muted">
          Lớp AI (Layer 3) chưa được bật <code className="text-slate-300">AI_ENABLED=true</code>, nên
          endpoint này chỉ trả về mảng rỗng.
        </div>
      ) : (
        <>
          <div className="-mx-1 overflow-x-auto px-1 [scrollbar-width:thin]">
        <svg
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            className="h-[210px] min-w-[600px] w-full"
            role="img"
            aria-label={`Dải dự báo quantile ${points.length} điểm`}
          >
            {[0, 0.5, 1].map((f) => {
              const v = maxV * f;
              return (
                <g key={f}>
                  <line
                    x1={PAD.left}
                    x2={WIDTH - PAD.right}
                    y1={y(v)}
                    y2={y(v)}
                    stroke="currentColor"
                    className="text-line/50"
                    strokeDasharray="3 4"
                  />
                  <text x={PAD.left - 6} y={y(v) + 3} textAnchor="end" className="fill-muted" fontSize="9">
                    {Math.round(v)}
                  </text>
                </g>
              );
            })}

            <line
              x1={PAD.left}
              x2={WIDTH - PAD.right}
              y1={y(threshold)}
              y2={y(threshold)}
              stroke="currentColor"
              className="text-bad"
              strokeDasharray="6 4"
            />
            <text x={PAD.left + 4} y={y(threshold) - 5} textAnchor="start" className="fill-bad" fontSize="9">
              ngưỡng {Math.round(threshold)}
            </text>

            <path d={area} className="fill-ai/20" />
            <path d={q90} fill="none" stroke="currentColor" className="text-ai" strokeWidth="1" strokeDasharray="4 3" />
            <path d={q10} fill="none" stroke="currentColor" className="text-ai" strokeWidth="1" strokeDasharray="4 3" />
            <path d={q50} fill="none" stroke="currentColor" className="text-white" strokeWidth="2" />

            {[0, 0.5, 1].map((f) => (
              <text
                key={f}
                x={PAD.left + f * (WIDTH - PAD.left - PAD.right)}
                y={HEIGHT - 6}
                textAnchor={f === 0 ? "start" : f === 1 ? "middle" : "end"}
                className="fill-muted"
                fontSize="9"
              >
                {new Date(minT + f * (maxT - minT)).toLocaleString("vi-VN", {
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </text>
            ))}
          </svg>
          </div>

          <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-muted">
            <span className="inline-flex items-center gap-1">
              <span className="inline-block h-0.5 w-4 bg-white" aria-hidden="true" /> q50 (trung vị)
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="inline-block h-0.5 w-4 border-t border-dashed border-ai" aria-hidden="true" />
              q10 / q90
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="inline-block h-2 w-4 rounded-sm bg-ai/20" aria-hidden="true" /> dải tin cậy
            </span>
          </div>
        </>
      )}
    </section>
  );
}
