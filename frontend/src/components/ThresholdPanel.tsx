import { useState } from "react";

import type { Space } from "../api/types";
import { useApplyThreshold, useSaveSpace, useThresholdPreview } from "../hooks/useAqss";
import { fanBehaviour } from "../lib/room";
import { RoomProfileForm } from "./RoomProfileForm";

/** Thresholds as three positions a person can reason about, with the number shown underneath. */
const SENSITIVITY = [
  { key: "low", label: "Ít nhạy", threshold: 50 },
  { key: "medium", label: "Vừa", threshold: 35 },
  { key: "high", label: "Nhạy", threshold: 25 },
] as const;

type ThresholdMode = "manual" | "auto";

/**
 * Room profile and the threshold it produces, on the room page itself.
 *
 * They belong together and they belong next to the number they change: the operator edits the
 * profile, sees the computed threshold, and immediately compares it with the live PM2.5 above. When
 * this lived in Settings, the profile could only be changed on a screen that showed neither the
 * reading nor the fan it controls.
 */
export function ThresholdPanel({ space, defaultOpen }: { space?: Space; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen ?? false);
  const current = space?.threshold_pm25 ?? 35;

  return (
    <section className="card overflow-hidden">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className="flex w-full items-center justify-between gap-3 px-4 py-3 text-left hover:bg-white/[0.02]"
      >
        <span className="flex min-w-0 flex-wrap items-baseline gap-x-3 gap-y-1">
          <span className="label">Hồ sơ phòng &amp; ngưỡng</span>
          <span className="font-mono text-[11px] text-slate-300">
            ngưỡng {current.toFixed(1)} µg/m³
          </span>
          <span className="font-mono text-[10px] text-muted">
            {space?.volume_m3 ?? "—"} m³ · {space?.occupancy_hours ?? "—"} giờ/ngày ·{" "}
            {ventilationLabel(space?.ventilation_estimate)}
          </span>
        </span>
        <span className="shrink-0 text-[11px] text-muted">{open ? "Thu gọn" : "Điều chỉnh"}</span>
      </button>

      {open && (
        <div className="border-t border-line/60 px-4 pb-4">
          {space ? (
            <>
              <RoomProfileForm space={space} embedded />
              <ThresholdSection space={space} />
            </>
          ) : (
            <p className="mt-3 text-sm text-muted">
              Phòng này chưa có hồ sơ. Gán máy vào một phòng trước, rồi quay lại đây.
            </p>
          )}
        </div>
      )}
    </section>
  );
}

function ventilationLabel(value?: string | null): string {
  if (value === "low") return "kín";
  if (value === "high") return "thoáng";
  if (value === "medium") return "vừa";
  return "chưa rõ thông gió";
}

function ThresholdSection({ space }: { space: Space }) {
  const save = useSaveSpace(space.code);
  const applyAuto = useApplyThreshold(space.code);
  const current = space.threshold_pm25 ?? 35;

  const [mode, setMode] = useState<ThresholdMode>(
    space.threshold_source === "profile" ? "auto" : "manual",
  );
  const [draft, setDraft] = useState<number | null>(null);
  const preview = useThresholdPreview(space.code, mode === "auto");
  const profileReady =
    space.volume_m3 != null &&
    space.volume_m3 > 0 &&
    space.occupancy_hours != null &&
    space.occupancy_hours >= 1 &&
    space.occupancy_hours <= 24 &&
    ["low", "medium", "high"].includes(space.ventilation_estimate ?? "");

  const effective =
    mode === "auto" ? (preview.data?.computed.threshold_pm25 ?? current) : (draft ?? current);
  const behaviour = fanBehaviour({ ...space, threshold_pm25: effective });
  const changed = Math.abs(effective - current) > 0.01;

  return (
    <section className="mt-5 border-t border-line/60 pt-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="label">Ngưỡng bật máy</div>
        <div
          className="flex gap-1 rounded-lg border border-line p-0.5"
          role="group"
          aria-label="Cách đặt ngưỡng"
        >
          <button
            type="button"
            aria-pressed={mode === "manual"}
            onClick={() => setMode("manual")}
            className={modeTone(mode === "manual")}
          >
            Tự chọn
          </button>
          <button
            type="button"
            aria-pressed={mode === "auto"}
            onClick={() => setMode("auto")}
            className={modeTone(mode === "auto")}
          >
            Theo hồ sơ
          </button>
        </div>
      </div>

      {mode === "manual" ? (
        <>
          <p className="mt-1 text-[12px] leading-relaxed text-muted">
            Máy bật khi bụi chạm ngưỡng, tắt khi xuống dưới {Math.round(70)}% ngưỡng.
          </p>
          <div className="mt-3 grid grid-cols-3 gap-2">
            {SENSITIVITY.map((option) => (
              <button
                key={option.key}
                type="button"
                aria-pressed={effective === option.threshold}
                onClick={() => setDraft(option.threshold)}
                className={`rounded-xl border py-2.5 text-[13px] transition-colors ${
                  effective === option.threshold
                    ? "border-white bg-white font-medium text-canvas"
                    : "border-line text-slate-400 hover:border-slate-500 hover:text-white"
                }`}
              >
                {option.label}
                <span className="mt-0.5 block font-mono text-[10px] opacity-70">
                  {option.threshold}
                </span>
              </button>
            ))}
          </div>
        </>
      ) : (
        <>
          <p className="mt-1 text-[12px] leading-relaxed text-muted">
            Tính từ hồ sơ phòng và số đo CO₂ hiện tại. Phòng kín hoặc phải ở lâu hơn thì ngưỡng thấp
            hơn.
          </p>

          {preview.isLoading ? (
            <div className="mt-3 rounded-xl border border-line/70 px-3 py-4 text-sm text-muted">
              Đang tính…
            </div>
          ) : preview.data && profileReady ? (
            <div className="mt-3 divide-y divide-line/50 rounded-xl border border-line/70">
              <div className="flex items-baseline justify-between px-3 py-2.5">
                <span className="text-[13px] text-slate-200">Ngưỡng đề xuất</span>
                <span className="font-mono text-lg text-white">
                  {num(preview.data.computed.threshold_pm25, 1)}
                  <span className="ml-1 font-sans text-[11px] text-muted">µg/m³</span>
                </span>
              </div>
              <Factor
                label="Mục tiêu liều/ngày"
                value={`${num(preview.data.computed.daily_dose_target, 1)} µg/m³`}
                note={`15 × √(24/${preview.data.computed.occupancy_hours} giờ); công thức vận hành của dự án`}
              />
              <Factor
                label="Hệ số thể tích"
                value={`× ${num(preview.data.computed.volume_factor, 2)}`}
                note={
                  preview.data.computed.volume_m3 == null
                    ? "chưa nhập thể tích"
                    : `${preview.data.computed.volume_m3} m³ so với chuẩn 20 m³`
                }
              />
              <Factor
                label="Hệ số thông gió"
                value={`× ${num(preview.data.computed.ventilation_factor, 2)}`}
                note={ventilationLabel(space.ventilation_estimate)}
              />
              <Factor
                label="Hệ số CO₂"
                value={`× ${num(preview.data.computed.co2_factor, 2)}`}
                note={
                  preview.data.computed.co2_ppm == null
                    ? "chưa có số đo CO₂"
                    : `${Math.round(preview.data.computed.co2_ppm)} ppm hiện tại`
                }
              />
            </div>
          ) : (
            <p className="mt-3 rounded-xl border border-line/70 px-3 py-2.5 text-[11px] leading-relaxed text-muted">
              Chưa đủ thông số để tính tự động. Điền thể tích, giờ có người và thông gió ở trên trước.
            </p>
          )}
        </>
      )}

      <div className="mt-3 rounded-xl border border-line/70 px-3 py-2.5 text-[12px] leading-relaxed text-muted">
        Ngưỡng <span className="font-mono text-slate-200">{effective.toFixed(1)} µg/m³</span> ·{" "}
        {behaviour.on} · {behaviour.off}
      </div>

      {mode === "auto" ? (
        <button
          type="button"
          disabled={applyAuto.isPending || !profileReady || !preview.data || preview.isFetching}
          onClick={() => applyAuto.mutate()}
          className="btn-primary mt-3 w-full"
        >
          {applyAuto.isPending ? "Đang áp dụng…" : `Áp dụng ${effective.toFixed(0)} µg/m³`}
        </button>
      ) : (
        changed && (
          <button
            type="button"
            disabled={save.isPending}
            onClick={() => save.mutate({ threshold_pm25: effective })}
            className="btn-primary mt-3 w-full"
          >
            {save.isPending ? "Đang lưu…" : `Đặt ngưỡng ${effective.toFixed(0)} µg/m³`}
          </button>
        )
      )}

      {(save.error || applyAuto.error) && (
        <p className="mt-2 rounded-lg border border-bad/30 bg-bad/10 px-2.5 py-1.5 text-[11px] text-bad">
          {(save.error ?? applyAuto.error)?.message}
        </p>
      )}
    </section>
  );
}

function Factor({ label, value, note }: { label: string; value: string; note: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3 px-3 py-2">
      <div className="min-w-0">
        <div className="text-[12px] text-slate-300">{label}</div>
        <div className="text-[11px] leading-snug text-muted">{note}</div>
      </div>
      <span className="shrink-0 font-mono text-sm text-slate-200">{value}</span>
    </div>
  );
}

function modeTone(active: boolean): string {
  return `rounded-md px-2.5 py-1 text-[11px] transition-colors ${
    active ? "bg-white font-medium text-canvas" : "text-muted hover:text-white"
  }`;
}

/** Formats a number that may be missing, so one absent field cannot take the panel down. */
function num(value: number | null | undefined, digits: number): string {
  return typeof value === "number" && Number.isFinite(value) ? value.toFixed(digits) : "—";
}
