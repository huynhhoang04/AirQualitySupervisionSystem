import { useState } from "react";

import type { Space, SpaceRequest } from "../api/types";
import { Icon } from "./Icon";

interface Props {
  space?: Space;
  saving: boolean;
  error?: string | null;
  onSave: (body: SpaceRequest) => void;
  onRecompute?: () => void;
}

/**
 * The threshold lives on the space, and ML#1 derives it from the room profile — so the card edits
 * the whole profile, not just the number. Fields left empty are simply not sent, which the backend
 * treats as "unchanged" (io_contract §7).
 */
export function SpaceCard({ space, saving, error, onSave, onRecompute }: Props) {
  const [threshold, setThreshold] = useState(space ? String(space.threshold_pm25) : "");
  const [name, setName] = useState(space?.name ?? "");
  const [volume, setVolume] = useState(space?.volume_m3 != null ? String(space.volume_m3) : "");
  const [purpose, setPurpose] = useState(space?.purpose ?? "");
  const [region, setRegion] = useState(space?.region ?? "");
  const [ventilation, setVentilation] = useState(space?.ventilation_estimate ?? "");

  const dirty =
    threshold !== (space ? String(space.threshold_pm25) : "") ||
    name !== (space?.name ?? "") ||
    volume !== (space?.volume_m3 != null ? String(space.volume_m3) : "") ||
    purpose !== (space?.purpose ?? "") ||
    region !== (space?.region ?? "") ||
    ventilation !== (space?.ventilation_estimate ?? "");

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    const body: SpaceRequest = {};
    if (name.trim()) body.name = name.trim();
    if (volume.trim() && Number.isFinite(Number(volume))) body.volume_m3 = Number(volume);
    if (purpose.trim()) body.purpose = purpose.trim();
    if (region.trim()) body.region = region.trim();
    if (ventilation.trim()) body.ventilation_estimate = ventilation.trim();
    if (threshold.trim() && Number.isFinite(Number(threshold))) body.threshold_pm25 = Number(threshold);
    onSave(body);
  };

  return (
    <section className="card p-5">
      <div className="flex items-start justify-between gap-3">
        <div>
          <div className="label">Không gian</div>
          <div className="mt-1 text-lg font-medium">{space?.name ?? "chưa gán phòng"}</div>
          <div className="text-xs text-muted">
            {space ? `${space.code} · ${space.volume_m3 ?? "?"} m³` : "Ghép node với một phòng để bắt đầu"}
          </div>
        </div>
        <span className="chip border border-line bg-canvas uppercase text-muted">
          {space?.threshold_source === "ai" ? "AI" : "thủ công"}
        </span>
      </div>

      {!space ? (
        <p className="mt-4 rounded-lg border border-dashed border-line px-3 py-4 text-sm text-muted">
          Node chưa được ghép với phòng nào. Ghép ở trang tổng quan trước, rồi quay lại đây để chỉnh
          hồ sơ phòng và ngưỡng.
        </p>
      ) : (
        <form onSubmit={submit} className="mt-4 space-y-3">
          <Field label="Tên phòng">
            <input className="field" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
          </Field>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Thể tích (m³)">
              <input
                className="field font-mono"
                type="number"
                min={1}
                step="0.5"
                value={volume}
                onChange={(e) => setVolume(e.target.value)}
              />
            </Field>
            <Field label="Thông gió">
              <select
                className="field"
                value={ventilation}
                onChange={(e) => setVentilation(e.target.value)}
              >
                <option value="">—</option>
                <option value="low">thấp</option>
                <option value="medium">trung bình</option>
                <option value="high">cao</option>
              </select>
            </Field>
          </div>

          <div className="grid grid-cols-2 gap-3">
            <Field label="Công năng">
              <select className="field" value={purpose} onChange={(e) => setPurpose(e.target.value)}>
                <option value="">—</option>
                <option value="bedroom">phòng ngủ</option>
                <option value="living_room">phòng khách</option>
                <option value="office">văn phòng</option>
                <option value="classroom">lớp học</option>
                <option value="kitchen">bếp</option>
              </select>
            </Field>
            <Field label="Khu vực">
              <input
                className="field"
                value={region}
                onChange={(e) => setRegion(e.target.value)}
                placeholder="hanoi_caugiay"
                maxLength={50}
              />
            </Field>
          </div>

          <Field
            label="Ngưỡng PM2.5 (µg/m³)"
            hint="1 – 500. Vòng điều khiển bật quạt khi PM2.5 ≥ ngưỡng, tắt khi < 0.7 × ngưỡng."
          >
            <input
              className="field font-mono"
              type="number"
              min={1}
              max={500}
              step="0.5"
              value={threshold}
              onChange={(e) => setThreshold(e.target.value)}
            />
          </Field>

          {error && (
            <p className="rounded-lg border border-bad/30 bg-bad/10 px-2 py-1.5 text-[11px] text-bad">
              {error}
            </p>
          )}

          <div className="flex gap-2">
            <button type="submit" disabled={saving || !dirty} className="btn-primary flex-1">
              <Icon name="check" />
              {saving ? "Đang lưu…" : "Lưu hồ sơ"}
            </button>
            {onRecompute && (
              <button type="button" onClick={onRecompute} className="btn-ghost" title="Nhờ ML#1 tính ngưỡng">
                <Icon name="refresh" />
                ML#1
              </button>
            )}
          </div>
        </form>
      )}
    </section>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block text-xs">
      <span className="text-muted">{label}</span>
      <div className="mt-1">{children}</div>
      {hint && <span className="mt-1 block text-[11px] leading-snug text-muted">{hint}</span>}
    </label>
  );
}
