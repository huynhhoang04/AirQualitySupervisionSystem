import { useMemo, useRef, useState } from "react";

import type { Telemetry } from "../api/types";
import { formatDateTime } from "../lib/format";

interface Props {
  history: Telemetry[];
  threshold: number;
  /** A reading whose sensor was at its rail is a floor, not a measurement. */
  hasSaturation?: boolean;
}

const PAD = { top: 12, right: 8, bottom: 22, left: 34 };
const HEIGHT = 240;

/**
 * Hand-drawn SVG rather than a chart library: the series is thinned to ~800 points by the backend,
 * a fixed viewBox keeps the layout stable while data refetches, and the hover readout is a plain
 * absolutely-positioned div.
 */
export function HistoryChart({ history, threshold, hasSaturation }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<{ x: number; point: Telemetry } | null>(null);

  const points = useMemo(
    () =>
      history
        .filter((row) => row.pm25 != null)
        .map((row) => ({ t: Date.parse(row.recorded_at), v: row.pm25 as number, row })),
    [history],
  );

  const { width, minT, maxT, maxV, niceStep, y } = useMemo(() => {
    const w = 720;
    const now = Date.now();
    const minTime = points.length ? Math.min(...points.map((p) => p.t)) : now - 3600_000;
    const maxTime = points.length ? Math.max(...points.map((p) => p.t)) : now;
    // The axis tops out above both the data and the threshold so the line never touches the frame.
    const top = Math.max(threshold * 1.4, ...points.map((p) => p.v), 10) * 1.1;
    // Grid labels land on round numbers: a step of 10/15/20/25… no wider than five lines.
    const rawStep = top / 4;
    const magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
    const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((candidate) => candidate >= rawStep) ?? rawStep;
    const axisTop = Math.ceil(top / step) * step;
    return {
      width: w,
      minT: minTime,
      maxT: Math.max(maxTime, minTime + 60_000),
      maxV: axisTop,
      niceStep: step,
      y: (v: number) => PAD.top + (1 - v / axisTop) * (HEIGHT - PAD.top - PAD.bottom),
    };
  }, [points, threshold]);

  const gridValues = useMemo(() => {
    const values: number[] = [];
    for (let v = 0; v <= maxV + 0.001; v += niceStep) {
      values.push(v);
    }
    return values;
  }, [maxV, niceStep]);

  const x = (t: number) =>
    PAD.left + ((t - minT) / (maxT - minT)) * (width - PAD.left - PAD.right);

  const path = points.map((p, i) => `${i === 0 ? "M" : "L"}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join(" ");
  const thresholdY = y(threshold);
  const avg = points.length
    ? points.reduce((sum, p) => sum + p.v, 0) / points.length
    : null;

  const onMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const box = wrapRef.current?.getBoundingClientRect();
    if (!box || points.length === 0) return;
    const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    const target = minT + ratio * (maxT - minT);
    // Nearest sample, not nearest pixel: the series is sparse relative to its time axis.
    let best = points[0];
    for (const p of points) {
      if (Math.abs(p.t - target) < Math.abs(best.t - target)) best = p;
    }
    setHover({ x: ratio * 100, point: best.row });
  };

  if (points.length === 0) {
    return (
      <div className="flex h-[240px] items-center justify-center rounded-lg border border-dashed border-line text-sm text-muted">
        Chưa có dữ liệu trong 24 giờ qua.
      </div>
    );
  }

  return (
    <div>
      <div
        ref={wrapRef}
        className="relative -mx-1 overflow-x-auto px-1 [scrollbar-width:thin]"
        onPointerMove={onMove}
        onPointerLeave={() => setHover(null)}
      >
        {/* Below ~600 px the 720-unit viewBox would shrink its own labels into noise, so the chart
            keeps a readable minimum width and the wrapper scrolls instead. */}
        <div className="min-w-[600px]">
        <svg
          viewBox={`0 0 ${width} ${HEIGHT}`}
          className="h-[240px] w-full"
          role="img"
          aria-label={`Biểu đồ PM2.5 24 giờ, ${points.length} điểm, ngưỡng ${threshold} µg/m³`}
        >
          {/* Horizontal grid + value labels */}
          {gridValues.map((v) => (
            <g key={v}>
              <line
                x1={PAD.left}
                x2={width - PAD.right}
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
          ))}

          {/* Threshold: solid dashed line plus a direct label, never colour alone */}
          <line
            x1={PAD.left}
            x2={width - PAD.right}
            y1={thresholdY}
            y2={thresholdY}
            stroke="currentColor"
            className="text-bad"
            strokeDasharray="6 4"
            strokeWidth="1.5"
          />
          <text
            x={PAD.left + 4}
            y={thresholdY - 8}
            textAnchor="start"
            className="fill-bad"
            fontSize="9"
          >
            ngưỡng {Math.round(threshold)}
          </text>

          <path d={path} fill="none" stroke="currentColor" className="text-info" strokeWidth="1.8" />

          {/* Time axis labels: start, middle, end */}
          {[0, 0.5, 1].map((f) => (
            <text
              key={f}
              x={PAD.left + f * (width - PAD.left - PAD.right)}
              y={HEIGHT - 6}
              textAnchor={f === 0 ? "start" : f === 1 ? "middle" : "end"}
              className="fill-muted"
              fontSize="9"
            >
              {new Date(minT + f * (maxT - minT)).toLocaleTimeString("vi-VN", {
                hour: "2-digit",
                minute: "2-digit",
              })}
            </text>
          ))}

          {hover && (
            <line
              x1={(hover.x / 100) * width}
              x2={(hover.x / 100) * width}
              y1={PAD.top}
              y2={HEIGHT - PAD.bottom}
              stroke="currentColor"
              className="text-slate-500"
              strokeDasharray="2 3"
            />
          )}
        </svg>

        {hover && (
          <div
            className="pointer-events-none absolute top-2 z-10 -translate-x-1/2 rounded-lg border border-line bg-surface/95 px-2.5 py-1.5 text-[11px] shadow-lg"
            style={{ left: `${hover.x}%` }}
          >
            <div className="font-mono text-sm text-white">{hover.point.pm25?.toFixed(1)} µg/m³</div>
            <div className="text-muted">{formatDateTime(hover.point.recorded_at)}</div>
          </div>
        )}
        </div>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-muted">
        <span>{points.length} điểm</span>
        {avg != null && <span>trung bình {avg.toFixed(1)} µg/m³</span>}
        <span className="inline-flex items-center gap-1">
          <span className="inline-block h-0.5 w-4 bg-info" aria-hidden="true" /> PM2.5
        </span>
        <span className="inline-flex items-center gap-1">
          <span className="inline-block h-0.5 w-4 border-t border-dashed border-bad" aria-hidden="true" />
          ngưỡng
        </span>
        {hasSaturation && (
          <span className="text-warn">
            cảm biến bụi đã bão hoà — giá trị cao nhất là sàn, không phải số đo
          </span>
        )}
      </div>
    </div>
  );
}
