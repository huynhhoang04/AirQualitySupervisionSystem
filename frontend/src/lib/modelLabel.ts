import type { ForecastRun } from "../api/types";

/**
 * The name of the model that actually produced a forecast.
 *
 * Three different things were being confused:
 *
 * * `AiModel` — the sklearn registry (`feature-v2`), which is empty because no such model ever
 *   passed the promotion gate. Reading it produced "persistence" in the UI.
 * * `persistence` — a real baseline, but only when the worker says so in `data_quality.model_kind`.
 * * the model the worker used, which is what `model_version` records ("amazon/chronos-2").
 *
 * The last one is what belongs on screen.
 */
export function modelLabel(run?: ForecastRun | null): string {
  if (!run) {
    return "chưa có dự báo";
  }
  const quality = run.data_quality;
  const kind = typeof quality?.model_kind === "string" ? quality.model_kind : null;

  if (kind === "baseline" || run.model_version.startsWith("persistence")) {
    return "baseline (giữ số đo cuối)";
  }
  if (kind === "chronos") {
    const short = run.model_version.replace(/^amazon\//, "");
    return quality?.readiness === "warmup" ? `${short} · warm-up` : short;
  }
  return run.model_version;
}
