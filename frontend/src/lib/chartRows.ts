import type { ForecastView, Telemetry } from "../api/types";

/**
 * Chart rows for one chart with one time axis.
 *
 * Kept out of the component so the rules can be tested directly: they are about what the line
 * *means*, not about how it is drawn.
 */
export const MAX_PATH_POINTS = 120;

/** A break after three missed packets, never sooner than this. */
export const MIN_GAP_MS = 15_000;

const DEFAULT_CADENCE_MS = 5_000;

export interface ChartRow {
  at: number;
  measured: number | null;
  forecast: number | null;
  q10: number | null;
  q90: number | null;
  receivedAt: string | null;
}

/**
 * How far apart two usable packets may be before the measured line must break.
 *
 * The cadence must be inferred from the stream's *typical* spacing, not from the biggest gap: with
 * two samples ten minutes apart the median gap is itself ten minutes, so a naive median would set
 * the threshold to thirty minutes and never break — the exact case this rule exists for. Only gaps
 * at the lower end of the distribution describe the cadence, so the lower quartile is used, with a
 * 5 s default when there is nothing to measure.
 */
export function gapBreakMs(timestamps: number[]): number {
  const gaps: number[] = [];
  for (let index = 1; index < timestamps.length; index += 1) {
    const gap = timestamps[index] - timestamps[index - 1];
    if (gap > 0) {
      gaps.push(gap);
    }
  }
  if (gaps.length === 0) {
    return MIN_GAP_MS;
  }
  gaps.sort((a, b) => a - b);
  // One gap is indistinguishable from the hole it might be: with two samples ten minutes apart the
  // only spacing observed *is* ten minutes. Below three samples the 5 s default is used, which
  // breaks a real hole and does not break a healthy 10 s stream either.
  if (gaps.length < 2) {
    return Math.max(DEFAULT_CADENCE_MS * 3, MIN_GAP_MS);
  }
  // Lower quartile: ignores the gaps we are trying to detect and describes the normal spacing.
  const cadence = gaps[Math.floor((gaps.length - 1) * 0.25)] ?? DEFAULT_CADENCE_MS;
  return Math.max(cadence * 3, MIN_GAP_MS);
}

/**
 * Build the rows for the room chart.
 *
 * Rules this enforces:
 * * a missing sample leaves a real break — an explicit null is inserted when two usable samples
 *   are further apart than the stream's cadence allows, because `connectNulls={false}` can only
 *   break where a null exists;
 * * the forecast line starts at the **newest measured sample**, not at the anchor the model used,
 *   so a new reading is never shown 45 µg/m³ away from where the line begins;
 * * a stored curve is drawn at its own target times only; the segment from the new measurement to
 *   the first surviving target is a display connection, never a stored forecast value.
 */
export function buildChartRows(
  samples: Telemetry[],
  futurePoints: ForecastView["points"],
  path: ForecastView["path"] | undefined,
  anchorAt: number | null,
  anchorPm: number | null,
): ChartRow[] {
  const rows = new Map<number, ChartRow>();
  const put = (at: number, patch: Partial<ChartRow>) => {
    const existing = rows.get(at);
    rows.set(at, {
      at,
      measured: null,
      forecast: null,
      q10: null,
      q90: null,
      receivedAt: null,
      ...existing,
      ...patch,
    });
  };

  const measuredAt: number[] = [];
  for (const sample of samples) {
    if (sample.pm25 == null) {
      continue;
    }
    const at = Date.parse(sample.recorded_at);
    measuredAt.push(at);
    put(at, { measured: sample.pm25, receivedAt: sample.received_at });
  }

  measuredAt.sort((a, b) => a - b);
  const threshold = gapBreakMs(measuredAt);
  for (let index = 1; index < measuredAt.length; index += 1) {
    const previous = measuredAt[index - 1];
    if (measuredAt[index] - previous > threshold) {
      // A point one millisecond after the last real sample ends the segment there.
      put(previous + 1, { measured: null });
    }
  }

  const hasCurve = (path?.values?.length ?? 0) > 0;
  const connectAt = anchorAt ?? (path ? Date.parse(path.anchor_at) : null);
  const connectPm = anchorPm ?? path?.anchor_pm25 ?? null;
  if (connectAt != null && connectPm != null) {
    put(connectAt, { forecast: connectPm });
  }

  if (hasCurve && path) {
    // The curve is the real forecast: 360 values on a 5 s grid. It is thinned for display only —
    // the stored values are never touched.
    const anchorMs = Date.parse(path.anchor_at);
    const stepMs = (path.step_seconds || 5) * 1000;
    const stride = Math.max(1, Math.ceil(path.values.length / MAX_PATH_POINTS));
    const firstVisible = connectAt == null ? anchorMs : Math.max(anchorMs, connectAt);
    const emit = (index: number) => {
      const at = anchorMs + (index + 1) * stepMs;
      if (at <= firstVisible) {
        return;   // superseded by the newer measurement
      }
      put(at, {
        forecast: path.values[index],
        q10: path.q10?.[index] ?? null,
        q90: path.q90?.[index] ?? null,
      });
    };
    for (let index = 0; index < path.values.length; index += stride) {
      emit(index);
    }
    const last = path.values.length - 1;
    if (last % stride !== 0) {
      emit(last);
    }
    return [...rows.values()].sort((a, b) => a.at - b.at);
  }

  for (const point of futurePoints) {
    put(Date.parse(point.target_at), { forecast: point.pm25 });
  }

  return [...rows.values()].sort((a, b) => a.at - b.at);
}
