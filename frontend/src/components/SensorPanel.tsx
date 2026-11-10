import type { Telemetry } from "../api/types";
import { co2Tone, formatAgo, pm25Tone } from "../lib/format";
import { Icon } from "./Icon";
import { Metric, MetricGrid, ThresholdBar } from "./StatusChip";

interface Props {
  latest?: Telemetry;
  threshold: number;
  /** Online from last_seen_at, not from the age of this reading. */
  online: boolean;
  /** The latest reading is too old to drive the fan (backend's stale rule). */
  stale: boolean;
}

export function SensorPanel({ latest, threshold, online, stale }: Props) {
  const pm25 = latest?.pm25 ?? null;
  const scale = Math.max(60, Math.ceil((threshold * 2) / 10) * 10);
  const tone = pm25Tone(pm25, threshold);
  const toneClass = tone === "bad" ? "text-bad" : tone === "warn" ? "text-warn" : tone === "good" ? "text-good" : "text-muted";

  return (
    <section className="space-y-4">
      <div className="card p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="label flex items-center gap-1.5">
              <Icon name="wind" size={13} /> PM2.5 · bụi mịn
            </div>
            <div className={`mt-1 font-mono text-5xl font-semibold tabular-nums ${toneClass}`}>
              {pm25 == null ? "—" : pm25.toFixed(1)}
              <span className="ml-2 font-sans text-base font-normal text-muted">µg/m³</span>
            </div>
            {latest?.pm25_saturated && (
              <div className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-warn/30 bg-warn/10 px-2 py-1 text-[11px] text-warn">
                <Icon name="alert" size={13} />
                Cảm biến bão hoà — đây là sàn, mức thật còn cao hơn
              </div>
            )}
          </div>
          <div className="text-right text-xs">
            <div className="text-muted">
              ngưỡng <span className="font-mono text-slate-200">{threshold.toFixed(1)}</span> µg/m³
            </div>
            <div className="mt-1 text-muted">
              {latest ? formatAgo(latest.recorded_at) : "chưa có dữ liệu"}
            </div>
            <div className="mt-1 text-muted">
              bật sớm khi q90 ≥ ngưỡng trong 2 giờ
            </div>
          </div>
        </div>

        <div className="mt-5">
          <ThresholdBar value={pm25} threshold={threshold} scaleMax={scale} />
        </div>

        {stale && online && (
          <p className="mt-3 text-xs text-warn">
            Số liệu mới nhất đã cũ hơn 5 phút — vòng điều khiển đang bỏ qua nó để không bật quạt
            bằng dữ liệu chết.
          </p>
        )}
      </div>

      <div className="card p-5">
        <MetricGrid>
          <Metric
            label="CO₂ · chỉ cảnh báo"
            value={latest?.co2 == null ? "—" : Math.round(latest.co2).toString()}
            unit="ppm"
            tone={co2Tone(latest?.co2)}
            hint={
              latest?.co2 != null && latest.co2 > 1000
                ? "HEPA không lọc được CO₂ — cần mở cửa / thông gió"
                : "ngưỡng cảnh báo 1000 ppm"
            }
          />
          <Metric
            label="Nhiệt độ"
            value={latest?.temperature == null ? "—" : latest.temperature.toFixed(1)}
            unit="°C"
            tone={latest?.temperature == null ? "muted" : "good"}
          />
          <Metric
            label="Độ ẩm"
            value={latest?.humidity == null ? "—" : latest.humidity.toFixed(0)}
            unit="%"
            tone={latest?.humidity == null ? "muted" : latest.humidity > 80 ? "warn" : "good"}
            hint={latest?.humidity != null && latest.humidity > 80 ? "trên 80 % bụi sẽ đọc sai" : undefined}
          />
        </MetricGrid>
      </div>
    </section>
  );
}
