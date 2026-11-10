import { useMemo, useRef, useState } from "react";

import type { Telemetry } from "../api/types";
import { formatDateTime } from "../lib/format";

interface Props {
  history: Telemetry[];
  threshold: number;
}

const PAD = { top: 10, right: 6, bottom: 18, left: 28 };
const HEIGHT = 132;
const WIDTH = 640;

/**
 * Drawn for trend, not for forensics. Samples are median-bucketed into five-minute cells so a
 * single dust mote cannot become a spike, and any bucket that still sits far above its neighbours
 * is drawn as a separate hairline instead of joining the line.
 */
export function HistoryChart({ history, threshold }: Props) {
  const wrapRef = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<{ x: number; label: string; value: number } | null>(null);

  const { line, outliers, thresholdY, gridY, minT, maxT, x, y, count } = useMemo(() => {
    const rows = history.filter((row) => row.pm25 != null);
    const buckets = new Map<number, number[]>();
    for (const row of rows) {
      const t = Date.parse(row.recorded_at);
      const cell = Math.floor(t / 300_000) * 300_000;
      const list = buckets.get(cell) ?? [];
      list.push(row.pm25 as number);
      buckets.set(cell, list);
    }
    const points = [...buckets.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([t, values]) => {
        const sorted = [...values].sort((a, b) => a - b);
        return { t, v: sorted[Math.floor(sorted.length / 2)] };
      });

    const now = Date.now();
    const start = points.length ? Math.min(points[0].t, now - 3_600_000) : now - 3_600_000;
    const end = points.length ? Math.max(points[points.length - 1].t, start + 60_000) : now;
    const top = Math.max(threshold * 1.6, ...points.map((p) => p.v), 20) * 1.08;
    const sx = (t: number) => PAD.left + ((t - start) / (end - start)) * (WIDTH - PAD.left - PAD.right);
    const sy = (v: number) => PAD.top + (1 - v / top) * (HEIGHT - PAD.top - PAD.bottom);

    // A point is an outlier when it is far above both neighbours — the shape of a one-sample event.
    const spikes: { t: number; v: number }[] = [];
    for (let i = 1; i < points.length - 1; i++) {
      const [prev, cur, next] = [points[i - 1], points[i], points[i + 1]];
      if (cur.v > prev.v + threshold * 0.8 && cur.v > next.v + threshold * 0.8) {
        spikes.push(cur);
      }
    }
    const spikeTimes = new Set(spikes.map((s) => s.t));
    const smoothed = points.filter((p) => !spikeTimes.has(p.t));

    return {
      line: smoothed.map((p, i) => `${i === 0 ? "M" : "L"}${sx(p.t).toFixed(1)},${sy(p.v).toFixed(1)}`).join(" "),
      outliers: spikes.map((s) => ({ x: sx(s.t), y: sy(s.v), value: s.v, t: s.t })),
      thresholdY: sy(threshold),
      gridY: [0, threshold, top].map((v) => ({ y: sy(v), v })),
      minT: start,
      maxT: end,
      x: sx,
      y: sy,
      count: points.length,
    };
  }, [history, threshold]);

  const onMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const box = wrapRef.current?.getBoundingClientRect();
    if (!box) return;
    const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    const t = minT + ratio * (maxT - minT);
    const nearest = history
      .filter((row) => row.pm25 != null)
      .reduce<{ t: number; v: number; at: string } | null>((best, row) => {
        const rt = Date.parse(row.recorded_at);
        if (!best || Math.abs(rt - t) < Math.abs(best.t - t)) {
          return { t: rt, v: row.pm25 as number, at: row.recorded_at };
        }
        return best;
      }, null);
    if (nearest) setHover({ x: ratio * 100, label: formatDateTime(nearest.at), value: nearest.v });
  };

  if (count === 0) {
    return (
      <div className="flex h-[132px] items-center justify-center rounded-xl border border-dashed border-line text-sm text-muted">
        Chưa có dữ liệu trong 24 giờ qua
      </div>
    );
  }

  const hourTicks = [0, 0.5, 1].map((f) => ({ x: x(minT + f * (maxT - minT)), t: minT + f * (maxT - minT) }));

  return (
    <div>
      <div ref={wrapRef} className="relative" onPointerMove={onMove} onPointerLeave={() => setHover(null)}>
        <svg
          viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
          className="h-[132px] w-full"
          role="img"
          aria-label={`PM2.5 24 giờ, ${count} điểm, ngưỡng ${threshold} µg/m³`}
        >
          {gridY.map((g, i) => (
            <g key={i}>
              <line x1={PAD.left} x2={WIDTH - PAD.right} y1={g.y} y2={g.y} stroke="currentColor"
                className="text-line/50" strokeDasharray="2 4" />
              <text x={PAD.left - 5} y={g.y + 3} textAnchor="end" className="fill-muted" fontSize="8">
                {Math.round(g.v)}
              </text>
            </g>
          ))}

          <line x1={PAD.left} x2={WIDTH - PAD.right} y1={thresholdY} y2={thresholdY}
            stroke="currentColor" className="text-bad" strokeWidth="1.2" strokeDasharray="5 3" />
          <text x={PAD.left + 3} y={thresholdY - 4} className="fill-bad" fontSize="8">
            ngưỡng {threshold.toFixed(0)}
          </text>

          <path d={line} fill="none" stroke="currentColor" className="text-info" strokeWidth="1.6"
            strokeLinejoin="round" />

          {outliers.map((o) => (
            <line key={o.t} x1={o.x} x2={o.x} y1={y(threshold)} y2={o.y} stroke="currentColor"
              className="text-warn/70" strokeWidth="1" strokeDasharray="2 2" />
          ))}

          {hourTicks.map((tick) => (
            <text key={tick.t} x={tick.x} y={HEIGHT - 4}
              textAnchor={tick.x < PAD.left + 20 ? "start" : tick.x > WIDTH - PAD.right - 20 ? "end" : "middle"}
              className="fill-muted" fontSize="8">
              {new Date(tick.t).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })}
            </text>
          ))}

          {hover && (
            <line x1={(hover.x / 100) * WIDTH} x2={(hover.x / 100) * WIDTH} y1={PAD.top}
              y2={HEIGHT - PAD.bottom} stroke="currentColor" className="text-slate-500" strokeDasharray="2 3" />
          )}
        </svg>

        {hover && (
          <div
            className="pointer-events-none absolute top-0 z-10 -translate-x-1/2 rounded-lg border border-line bg-surface/95 px-2 py-1 text-[10px] shadow-lg"
            style={{ left: `${hover.x}%` }}
          >
            <div className="font-mono text-xs text-white">{hover.value.toFixed(1)} µg/m³</div>
            <div className="text-muted">{hover.label}</div>
          </div>
        )}
      </div>

      <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[10px] text-muted">
        <span>24 giờ · gộp 5 phút</span>
        {outliers.length > 0 && <span className="text-warn">{outliers.length} điểm bất thường</span>}
      </div>
    </div>
  );
}
