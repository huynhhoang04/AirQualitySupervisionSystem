import type { RoomState } from "../lib/room";

const TONE: Record<RoomState["status"], { text: string; bar: string; track: string }> = {
  good: { text: "text-good", bar: "bg-good", track: "bg-good/15" },
  filtering: { text: "text-bad", bar: "bg-bad", track: "bg-bad/15" },
  watch: { text: "text-warn", bar: "bg-warn", track: "bg-warn/15" },
};

/**
 * The scale runs to twice the threshold, and the threshold itself is a notch — so "how close am I"
 * is readable without a legend. The fraction is drawn from the number the user is looking at.
 */
export function ReadingScale({
  value,
  threshold,
  status,
}: {
  value: number | null;
  threshold: number;
  status: RoomState["status"];
}) {
  const max = Math.max(threshold * 2, 20);
  const pct = value == null ? 0 : Math.min(100, Math.max(0, (value / max) * 100));
  const tone = TONE[status];

  return (
    <div>
      <div
        className="relative h-1.5 w-full overflow-visible rounded-full bg-line/70"
        role="meter"
        aria-valuemin={0}
        aria-valuemax={max}
        aria-valuenow={value ?? undefined}
        aria-label={`PM2.5 ${value ?? "chưa có"}, ngưỡng ${threshold}`}
      >
        <div
          className={`h-full rounded-full transition-[width] duration-700 ease-out ${tone.bar}`}
          style={{ width: `${pct}%` }}
        />
        {/* The threshold notch: a cut through the track, taller than it, so it reads as a mark. */}
        <span
          className="absolute -top-1 h-3.5 w-0.5 rounded-full bg-slate-100/80"
          style={{ left: `${Math.min(100, (threshold / max) * 100)}%` }}
          aria-hidden="true"
        />
      </div>
      <div className="mt-2 flex justify-between font-mono text-[10px] text-muted">
        <span>0</span>
        <span>ngưỡng {threshold.toFixed(0)}</span>
        <span>{max.toFixed(0)}</span>
      </div>
    </div>
  );
}

export function StatusWord({ state }: { state: RoomState }) {
  return (
    <div className={`status-word ${TONE[state.status].text}`} aria-live="polite">
      {state.word}
    </div>
  );
}

/** The one sentence that says what the system is doing and why. */
export function SystemLine({ state }: { state: RoomState }) {
  return (
    <p className="mt-1.5 text-sm leading-snug text-slate-300">
      {state.detail}
      {state.automatic && <span className="ml-1.5 text-[11px] uppercase tracking-wider text-muted">auto</span>}
    </p>
  );
}

export function Secondary({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <div className="font-mono text-[10px] uppercase tracking-[0.12em] text-muted">{label}</div>
      <div className="mt-0.5 truncate font-mono text-[15px] text-slate-200">{value}</div>
    </div>
  );
}
