import { useState } from "react";

import type { Space } from "../api/types";

interface Props {
  space?: Space;
  saving: boolean;
  onSave: (threshold: number) => void;
}

export function ThresholdCard({ space, saving, onSave }: Props) {
  const [draft, setDraft] = useState<string>("");

  const value = draft === "" ? (space?.threshold_pm25 ?? 0) : Number(draft);

  return (
    <section className="card p-5">
      <div className="label">Không gian</div>
      <div className="mt-1 flex items-baseline justify-between gap-3">
        <div>
          <div className="text-lg font-medium text-slate-100">{space?.name ?? "chưa gán phòng"}</div>
          <div className="text-xs text-slate-500">
            {space ? `${space.code} · ${space.volume_m3 ?? "?"} m³ · ${space.purpose ?? "—"}` : "—"}
          </div>
        </div>
        <span className="rounded bg-slate-600/20 px-2 py-0.5 text-[11px] uppercase text-slate-400">
          {space?.threshold_source ?? "manual"}
        </span>
      </div>

      <label className="mt-4 block text-xs text-slate-500" htmlFor="threshold">
        Ngưỡng PM2.5 (µg/m³)
      </label>
      <div className="mt-1 flex gap-2">
        <input
          id="threshold"
          type="number"
          min={1}
          step={0.5}
          value={value}
          onChange={(event) => setDraft(event.target.value)}
          className="w-full rounded-lg border border-line bg-slate-900/60 px-3 py-2 text-sm text-slate-100 outline-none focus:border-sky-500"
        />
        <button
          type="button"
          disabled={saving || !space || value <= 0}
          onClick={() => onSave(value)}
          className="rounded-lg border border-sky-500/40 bg-sky-500/15 px-3 py-2 text-sm text-sky-200 transition hover:bg-sky-500/25 disabled:opacity-50"
        >
          Lưu
        </button>
      </div>
      <p className="mt-2 text-[11px] leading-relaxed text-slate-600">
        Vòng điều khiển bật quạt khi PM2.5 ≥ ngưỡng và tắt khi &lt; 0.7 × ngưỡng (giữ ít nhất 5
        phút).
      </p>
    </section>
  );
}
