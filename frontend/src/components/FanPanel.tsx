import type { Device } from "../api/types";
import { formatAgo } from "../lib/format";

interface Props {
  device?: Device;
  pending: boolean;
  onSet: (on: boolean, level: number) => void;
}

const LEVELS = [1, 2, 3];
const DUTY = [0, 35, 60, 90];

export function FanPanel({ device, pending, onSet }: Props) {
  const on = device?.fan_state === "ON";
  const level = device?.fan_level ?? 0;

  return (
    <section className="card flex h-full flex-col p-5">
      <div className="flex items-center justify-between">
        <div className="label">Quạt lọc</div>
        <span
          className={`rounded-full px-2 py-0.5 text-[11px] ${
            device?.fan_mode === "MANUAL"
              ? "bg-sky-500/15 text-sky-300"
              : "bg-slate-600/20 text-slate-400"
          }`}
        >
          {device?.fan_mode ?? "AUTO"}
        </span>
      </div>

      <div className="mt-3 flex items-baseline gap-2">
        <span className={`text-2xl font-semibold ${on ? "text-emerald-400" : "text-slate-400"}`}>
          {on ? "ON" : "OFF"}
        </span>
        {on && <span className="text-sm text-slate-400">level {level} · {DUTY[level] ?? 0}%</span>}
      </div>

      <div className="mt-4 grid grid-cols-4 gap-2">
        <button
          type="button"
          disabled={pending}
          onClick={() => onSet(false, 0)}
          className={`rounded-lg border px-2 py-2 text-sm transition ${
            !on
              ? "border-slate-500 bg-slate-600/30 text-slate-100"
              : "border-line text-slate-400 hover:border-slate-500 hover:text-slate-200"
          } disabled:opacity-50`}
        >
          OFF
        </button>
        {LEVELS.map((value) => (
          <button
            key={value}
            type="button"
            disabled={pending}
            onClick={() => onSet(true, value)}
            className={`rounded-lg border px-2 py-2 text-sm transition ${
              on && level === value
                ? "border-emerald-500/60 bg-emerald-500/15 text-emerald-300"
                : "border-line text-slate-400 hover:border-slate-500 hover:text-slate-200"
            } disabled:opacity-50`}
          >
            L{value}
            <span className="block text-[10px] text-slate-500">{DUTY[value]}%</span>
          </button>
        ))}
      </div>

      <dl className="mt-5 space-y-2 text-xs text-slate-500">
        <div className="flex justify-between">
          <dt>Lệnh gần nhất</dt>
          <dd className="text-slate-300">{formatAgo(device?.last_command_at ?? null)}</dd>
        </div>
        <div className="flex justify-between">
          <dt>Chu kỳ telemetry</dt>
          <dd className="text-slate-300">
            {device ? `${(device.telemetry_interval_ms / 1000).toFixed(0)} s` : "—"}
          </dd>
        </div>
        <div className="flex justify-between">
          <dt>Baseline bụi (W₀)</dt>
          <dd className="text-slate-300">
            {device ? `${device.dust_baseline_us.toFixed(0)} µs` : "—"}
          </dd>
        </div>
      </dl>

      <p className="mt-auto pt-4 text-[11px] leading-relaxed text-slate-600">
        Bấm một mức sẽ chuyển quạt sang MANUAL. Vòng điều khiển tự động chỉ can thiệp khi quạt ở
        AUTO.
      </p>
    </section>
  );
}
