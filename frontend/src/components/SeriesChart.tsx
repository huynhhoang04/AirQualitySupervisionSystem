import { useMemo } from "react";

import type { SeriesBucket } from "../api/types";

interface Props {
  items: SeriesBucket[];
  threshold: number;
  height?: number;
  bucketSeconds?: number;
}

const WIDTH = 1000;
const PAD = { top: 14, right: 12, bottom: 22, left: 40 };

/**
 * Median line with a min/max band. The band is the point: a spike that a stride-thinning chart
 * would drop still shows up as a vertical excursion, and a missing bucket leaves a gap.
 */
export function SeriesChart({ items, threshold, height = 260, bucketSeconds = 300 }: Props) {
  const model = useMemo(() => {
    const points = items
      .filter((item) => item.median != null)
      .map((item) => ({
        t: Date.parse(item.bucket_start),
        median: item.median as number,
        min: item.min ?? (item.median as number),
        max: item.max ?? (item.median as number),
        coverage: item.coverage,
      }))
      .sort((a, b) => a.t - b.t);

    if (points.length === 0) {
      return null;
    }

    const start = points[0].t;
    const end = Math.max(points[points.length - 1].t, start + bucketSeconds * 1000);
    const top = Math.max(threshold * 1.5, ...points.map((p) => p.max), 20) * 1.08;
    const x = (t: number) => PAD.left + ((t - start) / (end - start)) * (WIDTH - PAD.left - PAD.right);
    const y = (v: number) => PAD.top + (1 - v / top) * (height - PAD.top - PAD.bottom);

    const gapMs = bucketSeconds * 1000 * 2.5;
    const lineParts: string[] = [];
    const bandParts: string[] = [];
    let previous: number | null = null;
    for (const point of points) {
      const move = previous == null || point.t - previous > gapMs;
      lineParts.push(`${move ? "M" : "L"}${x(point.t).toFixed(1)},${y(point.median).toFixed(1)}`);
      previous = point.t;
    }
    // Build the band as one path: upper edge forward, lower edge backward.
    bandParts.push(
      points.map((p, i) => `${i === 0 ? "M" : "L"}${x(p.t).toFixed(1)},${y(p.max).toFixed(1)}`).join(" "),
    );
    bandParts.push(
      [...points]
        .reverse()
        .map((p) => `L${x(p.t).toFixed(1)},${y(p.min).toFixed(1)}`)
        .join(" "),
    );
    bandParts.push("Z");

    return { points, start, end, top, x, y, line: lineParts.join(" "), band: bandParts.join(" ") };
  }, [items, threshold, height, bucketSeconds]);

  if (!model) {
    return (
      <div
        className="flex items-center justify-center rounded-xl border border-dashed border-line text-sm text-muted"
        style={{ height }}
      >
        Chưa có dữ liệu trong khoảng này
      </div>
    );
  }

  const { points, start, end, x, y, line, band } = model;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((fraction) => ({
    at: start + fraction * (end - start),
    x: x(start + fraction * (end - start)),
  }));

  return (
    <div>
      <svg
        viewBox={`0 0 ${WIDTH} ${height}`}
        className="w-full"
        style={{ height }}
        role="img"
        aria-label={`PM2.5, ${points.length} bucket, ngưỡng ${threshold} µg/m³`}
      >
        {[0, threshold, model.top].map((value) => (
          <g key={value}>
            <line
              x1={PAD.left}
              x2={WIDTH - PAD.right}
              y1={y(value)}
              y2={y(value)}
              stroke="currentColor"
              className="text-line/40"
              strokeDasharray="3 5"
            />
            <text x={PAD.left - 6} y={y(value) + 3} textAnchor="end" className="fill-muted" fontSize="10">
              {Math.round(value)}
            </text>
          </g>
        ))}

        <line
          x1={PAD.left}
          x2={WIDTH - PAD.right}
          y1={y(threshold)}
          y2={y(threshold)}
          stroke="currentColor"
          className="text-bad"
          strokeWidth="1.4"
          strokeDasharray="6 4"
        />
        <text x={PAD.left + 4} y={y(threshold) - 5} className="fill-bad" fontSize="10">
          ngưỡng {threshold.toFixed(0)}
        </text>

        <path d={band} fill="currentColor" className="text-info/15" />
        <path
          d={line}
          fill="none"
          stroke="currentColor"
          className="text-info"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />

        {ticks.map((tick) => (
          <text key={tick.at} x={tick.x} y={height - 5} textAnchor="middle" className="fill-muted" fontSize="9">
            {new Date(tick.at).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" })}
          </text>
        ))}
      </svg>
      <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-[10px] text-muted">
        <span className="text-info">— trung vị</span>
        <span className="text-info/60">▮ min–max theo bucket</span>
        <span className="text-bad">- - ngưỡng</span>
      </div>
    </div>
  );
}
