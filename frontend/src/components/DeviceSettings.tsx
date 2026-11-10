import { useState } from "react";

import type { Device } from "../api/types";
import { useUpdateDevice } from "../hooks/useAqss";
import { Icon } from "./Icon";

/** Node-level settings: what the firmware persists in NVS, pushed as commands. */
export function DeviceSettings({ device, onUnpair }: { device: Device; onUnpair: () => void }) {
  const [interval, setIntervalMs] = useState(String(Math.round(device.telemetry_interval_ms / 1000)));
  const [baseline, setBaseline] = useState(String(Math.round(device.dust_baseline_us)));
  const [name, setName] = useState(device.name ?? "");
  const update = useUpdateDevice(device.device_id);

  const save = (event: React.FormEvent) => {
    event.preventDefault();
    update.mutate({
      name: name.trim() || undefined,
      telemetry_interval_ms: Math.round(Number(interval) * 1000),
      dust_baseline_us: Number(baseline),
    });
  };

  return (
    <section className="card p-5">
      <div className="label">Cài đặt node</div>
      <p className="mt-1 text-xs leading-relaxed text-muted">
        Mỗi thay đổi được lưu ở backend và đẩy xuống node qua MQTT; node ghi vào NVS nên giữ nguyên
        sau khi mất điện.
      </p>

      <form onSubmit={save} className="mt-4 space-y-3">
        <label className="block text-xs">
          <span className="text-muted">Tên thiết bị</span>
          <input value={name} onChange={(e) => setName(e.target.value)} className="field mt-1" maxLength={100} />
        </label>

        <div className="grid grid-cols-2 gap-3">
          <label className="block text-xs">
            <span className="text-muted">Chu kỳ telemetry (giây)</span>
            <input
              type="number"
              min={1}
              max={60}
              value={interval}
              onChange={(e) => setIntervalMs(e.target.value)}
              className="field mt-1 font-mono"
            />
          </label>
          <label className="block text-xs">
            <span className="text-muted">Baseline bụi W₀ (µs)</span>
            <input
              type="number"
              min={800}
              max={2500}
              value={baseline}
              onChange={(e) => setBaseline(e.target.value)}
              className="field mt-1 font-mono"
            />
          </label>
        </div>

        <button type="submit" disabled={update.isPending} className="btn-primary w-full">
          <Icon name="check" />
          {update.isPending ? "Đang lưu…" : "Lưu và đẩy xuống node"}
        </button>
        {update.error && (
          <p className="rounded-lg border border-bad/30 bg-bad/10 px-2 py-1.5 text-[11px] text-bad">
            {update.error.message}
          </p>
        )}
        {update.isSuccess && (
          <p className="rounded-lg border border-good/30 bg-good/10 px-2 py-1.5 text-[11px] text-good">
            Đã lưu.
          </p>
        )}
      </form>

      <dl className="mt-5 space-y-1.5 border-t border-line/60 pt-4 text-xs">
        <Row label="Mã ghép" value={device.pairing_code ?? "—"} mono />
        <Row label="Định danh" value={device.device_id} mono />
        <Row label="Đã ghép" value={device.paired_at ? new Date(device.paired_at).toLocaleString("vi-VN") : "—"} />
        <Row label="Tạo lúc" value={new Date(device.created_at).toLocaleString("vi-VN")} />
      </dl>

      <button type="button" onClick={onUnpair} className="btn-danger mt-4 w-full">
        <Icon name="unlink" />
        Bỏ ghép khỏi phòng
      </button>
      <p className="mt-2 text-[11px] leading-relaxed text-muted">
        Bỏ ghép không xoá dữ liệu: telemetry vẫn tiếp tục được ghi nhưng node sẽ biến mất khỏi
        dashboard cho tới khi được ghép lại.
      </p>
    </section>
  );
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-muted">{label}</dt>
      <dd className={`truncate ${mono ? "font-mono" : ""} text-slate-300`}>{value}</dd>
    </div>
  );
}
