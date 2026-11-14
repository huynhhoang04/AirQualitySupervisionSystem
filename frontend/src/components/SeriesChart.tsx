import { useMemo, useState } from "react";
import { Area, CartesianGrid, ComposedChart, Line, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

import type { SeriesBucket } from "../api/types";

interface Props {
  items: SeriesBucket[];
  threshold: number;
  height?: number;
  bucketSeconds?: number;
  from?: string;
  to?: string;
}

interface ChartPoint {
  at: number;
  median: number | null;
  range: [number, number] | null;
  count: number;
  coverage: number;
}

const timeLabel = (at: number) => new Date(at).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" });

/** A regular grid keeps both the range and the median broken across missing buckets. */
export function SeriesChart({ items, threshold, height = 320, bucketSeconds = 300, from, to }: Props) {
  const [showRange, setShowRange] = useState(false);
  const points = useMemo(() => {
    const valid = items.filter((item) => Number.isFinite(Date.parse(item.bucket_start)));
    if (!valid.length) return [];
    const step = bucketSeconds * 1000;
    const first = from ? Date.parse(from) : Math.min(...valid.map((item) => Date.parse(item.bucket_start)));
    const last = to ? Date.parse(to) : Math.max(...valid.map((item) => Date.parse(item.bucket_start))) + step;
    const lookup = new Map(valid.map((item) => [Math.floor(Date.parse(item.bucket_start) / step), item]));
    const result: ChartPoint[] = [];
    for (let at = Math.floor(first / step) * step; at < last && result.length < 2001; at += step) {
      const item = lookup.get(Math.floor(at / step));
      const median = item?.median != null && Number.isFinite(item.median) ? item.median : null;
      result.push({ at, median, range: median == null ? null : [item?.min ?? median, item?.max ?? median], count: item?.count ?? 0, coverage: item?.coverage ?? 0 });
    }
    return result;
  }, [items, from, to, bucketSeconds]);
  const measured = points.filter((point) => point.median != null);
  const max = Math.max(threshold * 1.3, 20, ...measured.map((point) => showRange ? point.range![1] : point.median!));
  const ceiling = Math.ceil(max * 1.12 / 10) * 10;

  if (!measured.length) return <div className="flex items-center justify-center rounded-xl border border-dashed border-line text-sm text-muted" style={{ height }}>Chưa có dữ liệu trong khoảng này</div>;

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2 text-xs text-muted">
        <span>µg/m³ · mỗi điểm {bucketSeconds / 60} phút · khoảng mất dữ liệu được để trống</span>
        <label className="flex cursor-pointer items-center gap-2">
          <input type="checkbox" checked={showRange} onChange={(event) => setShowRange(event.target.checked)} className="accent-cyan-300" />
          Hiện dải thấp nhất–cao nhất
        </label>
      </div>
      <div style={{ height }} role="img" aria-label={`Biểu đồ PM2.5, ${measured.length} khoảng có dữ liệu, ngưỡng ${threshold} µg/m³`}>
        <ResponsiveContainer width="100%" height="100%" minWidth={0}>
          <ComposedChart data={points} margin={{ top: 16, right: 24, bottom: 8, left: 4 }} accessibilityLayer>
            <CartesianGrid stroke="#334155" strokeDasharray="3 5" vertical={false} />
            <XAxis dataKey="at" type="number" scale="time" domain={["dataMin", "dataMax"]} tickFormatter={timeLabel} tick={{ fill: "#94a3b8", fontSize: 12 }} minTickGap={55} axisLine={false} tickLine={false} />
            <YAxis domain={[0, ceiling]} tick={{ fill: "#cbd5e1", fontSize: 12 }} width={45} tickCount={5} axisLine={false} tickLine={false} />
            <Tooltip content={({ active, payload }) => {
              const point = payload?.[0]?.payload as ChartPoint | undefined;
              if (!active || !point) return null;
              return <div className="rounded-xl border border-line bg-canvas px-4 py-3 text-sm shadow-xl">
                <p className="mb-1 text-muted">{new Date(point.at).toLocaleString("vi-VN")}</p>
                {point.median == null ? <p>Không có mẫu hợp lệ</p> : <>
                  <p className="font-semibold text-info">PM2.5: {point.median.toFixed(1)} µg/m³</p>
                  <p className="mt-1">Thấp nhất {point.range![0].toFixed(1)} · cao nhất {point.range![1].toFixed(1)}</p>
                  <p className="mt-1 text-muted">{point.count} mẫu · độ phủ {Math.round(point.coverage * 100)}%</p>
                </>}
              </div>;
            }} />
            <ReferenceLine y={threshold} stroke="#fb7185" strokeDasharray="6 4" label={{ value: `Ngưỡng ${threshold.toFixed(1)}`, fill: "#fda4af", position: "insideTopRight", fontSize: 12 }} />
            {showRange && <Area dataKey="range" type="linear" fill="#67e8f9" fillOpacity={0.12} stroke="none" connectNulls={false} isAnimationActive={false} />}
            <Line dataKey="median" name="PM2.5" type="linear" stroke="#67e8f9" strokeWidth={2.2} dot={measured.length < 50 ? { r: 2, fill: "#67e8f9" } : false} activeDot={{ r: 5 }} connectNulls={false} isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-muted">
        <span className="text-info">━ PM2.5 trung vị</span><span className="text-bad">┄ Ngưỡng hiện tại</span>
        <span>{measured.length}/{points.length} khoảng có dữ liệu</span>
      </div>
    </div>
  );
}
