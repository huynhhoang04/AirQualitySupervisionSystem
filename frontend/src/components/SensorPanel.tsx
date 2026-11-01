import type { Telemetry } from "../api/types";
import { formatAgo, pm25Tone } from "../lib/format";

interface Props {
  latest?: Telemetry;
  threshold: number;
  offline: boolean;
}

function Tile({
  label,
  value,
  unit,
  hint,
  tone,
}: {
  label: string;
  value: string;
  unit: string;
  hint?: string;
  tone?: string;
}) {
  return (
    <div className="card p-4">
      <div className="label">{label}</div>
      <div className={`mt-1 text-2xl font-semibold tabular-nums ${tone ?? "text-slate-100"}`}>
        {value}
        <span className="ml-1 text-sm font-normal text-slate-500">{unit}</span>
      </div>
      {hint && <div className="mt-1 text-xs text-slate-500">{hint}</div>}
    </div>
  );
}

export function SensorPanel({ latest, threshold, offline }: Props) {
  const pm25 = latest?.pm25 ?? null;
  const scale = Math.max(60, Math.ceil((threshold * 2) / 10) * 10);
  const width = pm25 === null ? 0 : Math.min(100, (pm25 / scale) * 100);
  const thresholdLeft = Math.min(100, (threshold / scale) * 100);

  return (
    <section className="space-y-4">
      <div className="card p-5">
        <div className="flex items-start justify-between">
          <div>
            <div className="label">PM2.5</div>
            <div className={`mt-1 text-4xl font-semibold tabular-nums ${pm25Tone(pm25, threshold)}`}>
              {pm25 === null ? "—" : pm25.toFixed(1)}
              <span className="ml-2 text-base font-normal text-slate-500">µg/m³</span>
            </div>
          </div>
          <div className="text-right text-xs text-slate-500">
            <div>
              ngưỡng <span className="text-slate-300">{threshold.toFixed(1)}</span> µg/m³
            </div>
            <div className="mt-1">{latest ? formatAgo(latest.recorded_at) : "chưa có dữ liệu"}</div>
          </div>
        </div>

        <div className="relative mt-5 h-2.5 w-full overflow-hidden rounded-full bg-slate-800">
          <div
            className={`h-full rounded-full transition-all ${
              pm25 !== null && pm25 >= threshold
                ? "bg-rose-500"
                : pm25 !== null && pm25 >= threshold * 0.7
                  ? "bg-amber-400"
                  : "bg-emerald-500"
            }`}
            style={{ width: `${width}%` }}
          />
          <div
            className="absolute top-0 h-full w-px bg-slate-300/80"
            style={{ left: `${thresholdLeft}%` }}
            title={`ngưỡng ${threshold} µg/m³`}
          />
        </div>
        <div className="mt-1 flex justify-between text-[10px] text-slate-600">
          <span>0</span>
          <span>{scale} µg/m³</span>
        </div>

        {offline && (
          <p className="mt-3 text-xs text-amber-300/90">
            Node chưa gửi dữ liệu trong 30 giây gần nhất — số liệu có thể là bản cũ.
          </p>
        )}
      </div>

      <div className="grid grid-cols-3 gap-4">
        <Tile
          label="CO₂"
          value={latest?.co2 == null ? "—" : Math.round(latest.co2).toString()}
          unit="ppm"
          hint={
            latest?.co2 != null && latest.co2 > 1000
              ? "vượt 1000 ppm — chỉ cảnh báo, HEPA không lọc CO₂"
              : undefined
          }
          tone={latest?.co2 != null && latest.co2 > 1000 ? "text-amber-300" : undefined}
        />
        <Tile
          label="Nhiệt độ"
          value={latest?.temperature == null ? "—" : latest.temperature.toFixed(1)}
          unit="°C"
        />
        <Tile
          label="Độ ẩm"
          value={latest?.humidity == null ? "—" : latest.humidity.toFixed(0)}
          unit="%"
        />
      </div>
    </section>
  );
}
