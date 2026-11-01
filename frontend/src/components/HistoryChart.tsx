import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import type { Telemetry } from "../api/types";

interface Props {
  history: Telemetry[];
  threshold: number;
}

const tick = (ms: number) =>
  new Date(ms).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" });

export function HistoryChart({ history, threshold }: Props) {
  const data = history
    .filter((row) => row.pm25 !== null)
    .map((row) => ({ time: Date.parse(row.recorded_at), pm25: row.pm25 as number }));

  return (
    <section className="card p-5">
      <div className="mb-4 flex items-center justify-between">
        <div>
          <div className="label">PM2.5 · 24 giờ</div>
          <div className="mt-1 text-xs text-slate-500">
            {data.length} điểm · đường ngang = ngưỡng của phòng
          </div>
        </div>
      </div>

      <div className="h-64">
        {data.length === 0 ? (
          <div className="flex h-full items-center justify-center text-sm text-slate-500">
            chưa có dữ liệu trong 24 giờ qua
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 8, right: 12, bottom: 0, left: -12 }}>
              <CartesianGrid stroke="#1f2b45" strokeDasharray="3 3" vertical={false} />
              <XAxis
                dataKey="time"
                type="number"
                domain={["dataMin", "dataMax"]}
                tickFormatter={tick}
                stroke="#475569"
                tick={{ fontSize: 11 }}
              />
              <YAxis stroke="#475569" tick={{ fontSize: 11 }} width={44} />
              <Tooltip
                labelFormatter={(value) => tick(Number(value))}
                contentStyle={{
                  background: "#111a2e",
                  border: "1px solid #1f2b45",
                  borderRadius: 8,
                  fontSize: 12,
                }}
                labelStyle={{ color: "#94a3b8" }}
              />
              <ReferenceLine y={threshold} stroke="#f43f5e" strokeDasharray="5 4" />
              <Line
                type="monotone"
                dataKey="pm25"
                name="PM2.5"
                unit=" µg/m³"
                stroke="#38bdf8"
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
              />
            </LineChart>
          </ResponsiveContainer>
        )}
      </div>
    </section>
  );
}
