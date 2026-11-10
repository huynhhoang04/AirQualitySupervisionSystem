import { formatAgo, isOnline, minutesSince, type Tone } from "../lib/format";

export function StatusChip({ lastSeenAt }: { lastSeenAt: string | null }) {
  const online = isOnline(lastSeenAt);
  return (
    <span
      className={`chip border ${
        online ? "border-good/30 bg-good/10 text-good" : "border-line bg-surface text-muted"
      }`}
      role="status"
      aria-atomic="true"
    >
      <span
        className={`h-1.5 w-1.5 rounded-full ${online ? "bg-good" : "bg-muted"}`}
        aria-hidden="true"
      />
      {online ? "online" : "offline"}
    </span>
  );
}

/**
 * CO2 and every other advisory value: the label says what the number means, and the hint explains
 * the one thing a reader gets wrong — that CO2 cannot be filtered away.
 */
export function Metric({
  label,
  value,
  unit,
  tone = "muted",
  hint,
}: {
  label: string;
  value: string;
  unit?: string;
  tone?: Tone;
  hint?: string;
}) {
  const toneClass =
    tone === "good"
      ? "text-good"
      : tone === "warn"
        ? "text-warn"
        : tone === "bad"
          ? "text-bad"
          : "text-slate-300";

  return (
    <div className="min-w-0">
      <div className="label">{label}</div>
      <div className={`mt-1 font-mono text-2xl font-semibold tabular-nums ${toneClass}`}>
        {value}
        {unit && <span className="ml-1 font-sans text-xs font-normal text-muted">{unit}</span>}
      </div>
      {hint && <div className="mt-1 text-[11px] leading-snug text-muted">{hint}</div>}
    </div>
  );
}

export function MetricGrid({ children }: { children: React.ReactNode }) {
  // Three columns on any width: on a phone each cell gets ~100 px, so the labels are allowed to
  // wrap to two lines rather than overflow into the neighbouring cell.
  return <div className="grid grid-cols-3 gap-3 sm:gap-4">{children}</div>;
}

/** A labelled bar with a threshold marker — the shape of every "how close am I" question. */
export function ThresholdBar({
  value,
  threshold,
  scaleMax,
}: {
  value: number | null;
  threshold: number;
  scaleMax: number;
}) {
  const pct = (n: number) => `${Math.min(100, Math.max(0, (n / scaleMax) * 100))}%`;
  const fill = value == null ? "0%" : pct(value);
  const marker = pct(threshold);
  const tone: Tone = value == null ? "muted" : value >= threshold ? "bad" : value >= threshold * 0.7 ? "warn" : "good";
  const fillClass = tone === "bad" ? "bg-bad" : tone === "warn" ? "bg-warn" : tone === "good" ? "bg-good" : "bg-line";

  return (
    <div>
      <div
        className="relative h-2.5 w-full overflow-hidden rounded-full bg-line/40"
        role="meter"
        aria-valuemin={0}
        aria-valuemax={scaleMax}
        aria-valuenow={value ?? undefined}
        aria-label={`PM2.5 ${value ?? "chưa có"} trên ngưỡng ${threshold} µg/m³`}
      >
        <div className={`h-full rounded-full transition-all duration-500 ${fillClass}`} style={{ width: fill }} />
        <div
          className="absolute top-0 h-full w-0.5 bg-white/80"
          style={{ left: marker }}
          title={`ngưỡng ${threshold} µg/m³`}
        />
      </div>
      <div className="mt-1 flex justify-between font-mono text-[10px] text-muted">
        <span>0</span>
        <span>{Math.round(threshold)} µg/m³</span>
        <span>{scaleMax}</span>
      </div>
    </div>
  );
}

/** "Bật 12 phút trước" / "Tắt 40 giây trước" — the control loop's own clock. */
export function StateAge({ since, state }: { since: string | null; state: boolean }) {
  const minutes = minutesSince(since);
  if (minutes == null) return <span className="text-muted">chưa có mốc thời gian</span>;
  return (
    <span className="text-muted">
      {state ? "bật" : "tắt"} {formatAgo(since)}
    </span>
  );
}
