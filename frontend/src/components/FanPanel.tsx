import { useEffect, useState } from "react";

import type { Device, FanMode } from "../api/types";
import { fanDuty, formatAgo, minutesSince } from "../lib/format";
import { Icon } from "./Icon";

interface Props {
  device: Device;
  pending: boolean;
  error?: string | null;
  onSet: (state: "ON" | "OFF", level: number, mode: FanMode) => void;
}

const LEVELS = [1, 2, 3] as const;

export function FanPanel({ device, pending, error, onSet }: Props) {
  const on = device.fan_state === "ON";
  const level = device.fan_level ?? 0;
  const manual = device.fan_mode === "MANUAL";
  const onMinutes = minutesSince(device.on_since);

  // Immediate feedback while the POST is in flight: the polling loop takes up to 5 s, which is
  // long enough that a button that does nothing reads as broken.
  const [optimistic, setOptimistic] = useState<{ on: boolean; level: number; mode: FanMode } | null>(null);
  useEffect(() => {
    if (!pending) setOptimistic(null);
  }, [pending, device.fan_state, device.fan_level, device.fan_mode]);

  const shownOn = optimistic ? optimistic.on : on;
  const shownLevel = optimistic ? optimistic.level : level;
  const shownMode: FanMode = optimistic ? optimistic.mode : device.fan_mode;

  const act = (state: "ON" | "OFF", nextLevel: number, mode: FanMode) => {
    setOptimistic({ on: state === "ON", level: state === "ON" ? nextLevel : 0, mode });
    onSet(state, nextLevel, mode);
  };

  return (
    <section className="card flex h-full flex-col p-5">
      <div className="flex items-center justify-between gap-3">
        <div className="label">Quạt lọc HEPA</div>
        <span
          className={`chip border ${
            shownMode === "MANUAL"
              ? "border-info/30 bg-info/10 text-info"
              : "border-line bg-canvas text-muted"
          }`}
        >
          {shownMode}
        </span>
      </div>

      <div className="mt-3 flex items-baseline gap-3">
        <span
          className={`font-mono text-3xl font-semibold ${shownOn ? "text-good" : "text-muted"}`}
          aria-live="polite"
        >
          {shownOn ? "ON" : "OFF"}
        </span>
        {shownOn && (
          <span className="font-mono text-sm text-muted">
            L{shownLevel} · {fanDuty(shownLevel)}%
          </span>
        )}
      </div>

      <div className="mt-4 grid grid-cols-2 gap-2">
        <button
          type="button"
          disabled={pending}
          aria-pressed={!shownOn}
          onClick={() => act("OFF", 0, "MANUAL")}
          className={`btn ${!shownOn ? "border-slate-500 bg-line/60 text-white" : "btn-ghost"}`}
        >
          <Icon name="power" />
          Tắt
        </button>
        {LEVELS.map((value) => {
          const active = shownOn && shownLevel === value;
          return (
            <button
              key={value}
              type="button"
              disabled={pending}
              aria-pressed={active}
              onClick={() => act("ON", value, "MANUAL")}
              className={`btn flex-col gap-0 py-1.5 ${active ? "border-good/50 bg-good/15 text-good" : "btn-ghost"}`}
            >
              <span className="font-medium">Mức {value}</span>
              <span className="font-mono text-[10px] text-muted">{fanDuty(value)}%</span>
            </button>
          );
        })}
      </div>

      <button
        type="button"
        disabled={pending || !manual}
        onClick={() => act(shownOn ? "ON" : "OFF", shownLevel || 2, "AUTO")}
        className="btn-ghost mt-2 w-full"
      >
        <Icon name="refresh" />
        {manual ? "Trả quạt về AUTO" : "Đang ở chế độ AUTO"}
      </button>

      {error && (
        <p className="mt-2 rounded-lg border border-bad/30 bg-bad/10 px-2 py-1.5 text-[11px] text-bad">
          {error}
        </p>
      )}

      <dl className="mt-5 space-y-1.5 text-xs">
        <Row label="Lệnh gần nhất" value={formatAgo(device.last_command_at)} />
        <Row
          label="Đang chạy"
          value={shownOn ? (onMinutes == null ? "—" : `${Math.round(onMinutes)} phút`) : "—"}
        />
        <Row label="Chu kỳ telemetry" value={`${(device.telemetry_interval_ms / 1000).toFixed(0)} s`} />
        <Row label="Baseline bụi (W₀)" value={`${Math.round(device.dust_baseline_us)} µs`} />
      </dl>

      <p className="mt-auto pt-4 text-[11px] leading-relaxed text-muted">
        Bấm một mức sẽ chuyển node sang MANUAL và giữ nguyên cho tới khi bạn trả về AUTO. Ở AUTO,
        backend tự bật khi PM2.5 chạm ngưỡng hoặc khi dự báo q90 báo trước 2 giờ.
      </p>
    </section>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-muted">{label}</dt>
      <dd className="font-mono text-slate-300">{value}</dd>
    </div>
  );
}
