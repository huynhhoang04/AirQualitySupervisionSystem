import { useMemo } from "react";
import {
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import type { ForecastView, Telemetry } from "../api/types";
import type { LiveStatus } from "../hooks/useLiveRoom";
import { useNow } from "../hooks/useNow";
import { buildChartRows, type ChartRow } from "../lib/chartRows";
import { formatAgo } from "../lib/room";

const WINDOW_MS = 30 * 60_000;

interface Props {
  samples: Telemetry[];
  forecast: ForecastView | null;
  threshold: number;
  status: LiveStatus;
  height?: number;
}

interface Row extends ChartRow {}

const timeLabel = (at: number) =>
  new Date(at).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" });

/**
 * One chart, one time axis: measured history on the left of the anchor, forecast on the right.
 * Missing samples stay gaps; the forecast series only carries the target times it really has.
 */
export function LiveChart({ samples, forecast, threshold, status, height = 400 }: Props) {
  const now = useNow();
  const anchorSample = useMemo(
    () => [...samples].reverse().find((sample) => sample.pm25 != null),
    [samples],
  );
  const anchor = anchorSample ? Date.parse(anchorSample.recorded_at) : now;
  const anchorDate = anchorSample ? Date.parse(anchorSample.recorded_at) : null;
  const anchorValue = anchorSample?.pm25 ?? null;
  const start = anchor - WINDOW_MS;
  const end = anchor + WINDOW_MS;
  // A curve issued for an older anchor must not be drawn inside this window: its targets are not
  // the 30 minutes after the sample on screen, and plotting it would move the chart backwards.
  const visiblePath =
    forecast?.path && Date.parse(forecast.path.anchor_at) >= start ? forecast.path : null;
  const futurePoints = useMemo(
    () =>
      (forecast?.points ?? []).filter((point) => {
        const at = Date.parse(point.target_at);
        return at >= anchor && at <= end;
      }),
    [forecast, anchor, end],
  );
  const rows = useMemo(
    () => buildChartRows(samples, futurePoints, visiblePath, anchorDate, anchorValue),
    [samples, futurePoints, visiblePath, anchorDate, anchorValue],
  );
  const values = rows
    .flatMap((row) => [row.measured, row.forecast, row.q10, row.q90])
    .filter((value): value is number => value != null);
  const top = Math.max(threshold * 1.4, ...values, 20) * 1.12;
  const ceiling = Math.ceil(top / 10) * 10;
  const ageSeconds = Math.max(0, Math.round((now - anchor) / 1000));
  const ageLabel = anchorSample ? formatAgo(anchorSample.recorded_at) : "chưa có mẫu";
  const anchorLabel = ageSeconds > 15 ? "mẫu mới nhất" : "bây giờ";

  if (rows.length === 0) {
    return (
      <div
        className="flex items-center justify-center rounded-xl border border-dashed border-line text-sm text-muted"
        style={{ height }}
      >
        Chưa có mẫu thực đo. Chart bắt đầu khi thiết bị gửi gói đầu tiên.
      </div>
    );
  }

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-[11px] text-muted">
        <span>µg/m³ · 30 phút trước và 30 phút dự báo · một trục thời gian</span>
        <span className={ageSeconds > 15 ? "text-warn" : "text-muted"}>
          {status === "live"
            ? ageSeconds <= 5
              ? "vừa nhận mẫu mới"
              : `mẫu mới nhất ${ageLabel}`
            : status === "reconnecting"
              ? "đang kết nối lại · giữ dữ liệu đã có"
              : status === "loading"
                ? "đang tải dữ liệu trực tiếp"
                : "chưa kết nối được dòng trực tiếp"}
        </span>
      </div>
      <div
        style={{ height }}
        role="img"
        aria-label={`PM2.5 thực đo và dự báo 30 phút, ngưỡng ${threshold} µg/m³`}
      >
        <ResponsiveContainer width="100%" height="100%" minWidth={0}>
          <ComposedChart
            data={rows}
            margin={{ top: 20, right: 26, bottom: 8, left: 4 }}
            accessibilityLayer
          >
            <CartesianGrid stroke="#334155" strokeDasharray="3 5" vertical={false} />
            <XAxis
              dataKey="at"
              type="number"
              scale="time"
              domain={[start, end]}
              allowDataOverflow
              tickFormatter={timeLabel}
              tick={{ fill: "#94a3b8", fontSize: 12 }}
              minTickGap={60}
              axisLine={false}
              tickLine={false}
            />
            <YAxis
              domain={[0, ceiling]}
              tick={{ fill: "#cbd5e1", fontSize: 12 }}
              width={45}
              tickCount={5}
              axisLine={false}
              tickLine={false}
            />
            <Tooltip
              content={({ active, payload }) => {
                const row = payload?.[0]?.payload as Row | undefined;
                if (!active || !row) {
                  return null;
                }
                const lagSeconds =
                  row.receivedAt == null
                    ? null
                    : Math.max(0, Math.round((Date.parse(row.receivedAt) - row.at) / 1000));
                return (
                  <div className="rounded-xl border border-line bg-canvas px-4 py-3 text-sm shadow-xl">
                    <p className="mb-1 text-muted">{new Date(row.at).toLocaleString("vi-VN")}</p>
                    {row.measured != null && (
                      <p className="font-semibold text-info">
                        Thực đo: {row.measured.toFixed(1)} µg/m³
                      </p>
                    )}
                    {row.measured != null && row.receivedAt != null && (
                      <p className="mt-0.5 text-[11px] text-muted">
                        nhận lúc{" "}
                        {new Date(row.receivedAt).toLocaleTimeString("vi-VN")}
                        {lagSeconds != null && lagSeconds > 0 ? ` · trễ ${lagSeconds} giây` : ""}
                      </p>
                    )}
                    {row.forecast != null && (
                      <p className="mt-1 font-semibold text-warn">
                        Dự báo: {row.forecast.toFixed(1)} µg/m³
                      </p>
                    )}
                    {row.q10 != null && row.q90 != null && (
                      <p className="mt-0.5 text-[11px] text-muted">
                        Khoảng 10–90%: {row.q10.toFixed(1)} – {row.q90.toFixed(1)}
                      </p>
                    )}
                    {row.measured == null && row.forecast == null && <p>Không có mẫu</p>}
                  </div>
                );
              }}
            />
            <ReferenceLine
              x={anchor}
              stroke="#64748b"
              strokeDasharray="3 4"
              label={{ value: anchorLabel, fill: "#94a3b8", position: "insideTop", fontSize: 11 }}
            />
            <ReferenceLine
              y={threshold}
              stroke="#fb7185"
              strokeDasharray="6 4"
              label={{
                value: `Ngưỡng ${threshold.toFixed(0)}`,
                fill: "#fda4af",
                position: "insideTopRight",
                fontSize: 11,
              }}
            />
            <Line
              dataKey="measured"
              name="Thực đo"
              type="linear"
              stroke="#67e8f9"
              strokeWidth={2.2}
              dot={false}
              activeDot={{ r: 4 }}
              connectNulls={false}
              isAnimationActive={false}
            />
            {/* The band is two thin lines, not a filled area: Recharts fills from zero, which would
                claim the whole region under the curve is forecast uncertainty. */}
            <Line
              dataKey="q90"
              name="Cận trên 90%"
              type="linear"
              stroke="#fbbf24"
              strokeOpacity={0.45}
              strokeWidth={1}
              dot={false}
              connectNulls
              isAnimationActive={false}
            />
            <Line
              dataKey="q10"
              name="Cận dưới 10%"
              type="linear"
              stroke="#fbbf24"
              strokeOpacity={0.45}
              strokeWidth={1}
              dot={false}
              connectNulls
              isAnimationActive={false}
            />
            <Line
              dataKey="forecast"
              name="Dự báo"
              type="linear"
              stroke="#fbbf24"
              strokeWidth={2}
              strokeDasharray="7 5"
              dot={false}
              activeDot={{ r: 5 }}
              connectNulls
              isAnimationActive={false}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <div className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-2 text-[11px] text-muted">
        <span className="text-info">━ Thực đo</span>
        {visiblePath ? (
          <span className="text-warn">┄ Dự báo 30 phút (360 bước)</span>
        ) : futurePoints.length > 0 ? (
          <span className="text-warn">┄ Dự báo 30 phút (3 mốc)</span>
        ) : (
          <span>
            {forecast ? "dự báo đã cũ so với mẫu mới nhất" : "chưa có dự báo"}
          </span>
        )}
        <span className="text-bad">┄ Ngưỡng hiện tại</span>
        <span className="ml-auto font-mono">{samples.length} mẫu / 30 phút</span>
      </div>
    </div>
  );
}
