import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import type { Device, Space } from "../api/types";
import { Icon } from "../components/Icon";
import {
  useDevices,
  useLatest,
  useResetFilter,
  useSaveSpace,
  useSpaces,
  useUnpairDevice,
} from "../hooks/useAqss";
import { fanBehaviour, filterHours, formatAgo, formatHours, roomState } from "../lib/room";

/** Thresholds as three positions a person can reason about, with the number shown underneath. */
const SENSITIVITY = [
  { key: "low", label: "Ít nhạy", threshold: 50 },
  { key: "medium", label: "Vừa", threshold: 35 },
  { key: "high", label: "Nhạy", threshold: 25 },
] as const;

export function RoomSettingsPage({ deviceId }: { deviceId: string }) {
  const navigate = useNavigate();
  const devices = useDevices();
  const spaces = useSpaces();
  const device = devices.data?.find((item) => item.device_id === deviceId);
  const space = spaces.data?.find((item) => item.code === device?.space_code);

  if (!device) {
    return (
      <div className="mx-auto max-w-lg px-5 py-10">
        <p className="text-sm text-muted">Không tìm thấy phòng này.</p>
      </div>
    );
  }

  return (
    <div className="mx-auto min-h-screen max-w-lg px-5 pb-20">
      <header className="flex items-center gap-3 pt-6">
        <button
          type="button"
          onClick={() => navigate(`/room/${encodeURIComponent(deviceId)}`)}
          className="rounded-lg p-1.5 text-muted hover:text-white"
          aria-label="Quay lại phòng"
        >
          <Icon name="back" />
        </button>
        <div>
          <h1 className="text-[15px] font-medium">Cài đặt</h1>
          <div className="font-mono text-[10px] text-muted">{space?.name ?? deviceId}</div>
        </div>
      </header>

      <ThresholdSection space={space} />
      <FilterSection device={device} />
      <DeviceSection device={device} />
    </div>
  );
}

function ThresholdSection({ space }: { space?: Space }) {
  const save = useSaveSpace(space?.code);
  const current = space?.threshold_pm25 ?? 35;
  const selected =
    SENSITIVITY.reduce((best, option) =>
      Math.abs(option.threshold - current) < Math.abs(best.threshold - current) ? option : best,
    SENSITIVITY[1]);
  const [draft, setDraft] = useState<number | null>(null);
  const effective = draft ?? selected.threshold;
  const behaviour = fanBehaviour({ ...(space as Space), threshold_pm25: effective });
  const changed = Math.abs(effective - current) > 0.01;

  return (
    <section className="mt-8">
      <div className="label">Độ nhạy</div>
      <p className="mt-1 text-sm leading-relaxed text-muted">
        Quyết định bụi nhiều đến mức nào thì máy tự bật. Chọn xong, máy giữ đúng mức này.
      </p>

      <div className="mt-3 grid grid-cols-3 gap-2">
        {SENSITIVITY.map((option) => (
          <button
            key={option.key}
            type="button"
            aria-pressed={effective === option.threshold}
            onClick={() => setDraft(option.threshold)}
            className={`rounded-xl border py-3 text-sm transition-colors ${
              effective === option.threshold
                ? "border-white bg-white font-medium text-canvas"
                : "border-line text-slate-400 hover:border-slate-500 hover:text-white"
            }`}
          >
            {option.label}
          </button>
        ))}
      </div>

      <div className="mt-3 rounded-xl border border-line/70 px-3 py-2.5 text-[12px] leading-relaxed text-muted">
        Ngưỡng <span className="font-mono text-slate-200">{effective.toFixed(1)} µg/m³</span> ·{" "}
        {behaviour.on} · {behaviour.off}
      </div>

      {changed && (
        <button
          type="button"
          disabled={save.isPending || !space}
          onClick={() => save.mutate({ threshold_pm25: effective })}
          className="btn-primary mt-3 w-full"
        >
          {save.isPending ? "Đang lưu…" : `Đặt ngưỡng ${effective.toFixed(0)} µg/m³`}
        </button>
      )}
      {save.error && (
        <p className="mt-2 rounded-lg border border-bad/30 bg-bad/10 px-2.5 py-1.5 text-[11px] text-bad">
          {save.error.message}
        </p>
      )}
    </section>
  );
}

function FilterSection({ device }: { device: Device }) {
  const reset = useResetFilter(device.device_id);
  const filter = filterHours(device);
  const [confirming, setConfirming] = useState(false);

  return (
    <section className="mt-10 border-t border-line/60 pt-6">
      <div className="flex items-baseline justify-between gap-3">
        <div className="label">Lõi lọc</div>
        <span className={`font-mono text-[11px] ${filter.low ? "text-warn" : "text-muted"}`}>
          còn {formatHours(filter.remaining)}
        </span>
      </div>
      <p className="mt-1 text-sm leading-relaxed text-muted">
        Đã chạy {formatHours(filter.used)}. Máy tự đếm giờ quạt; thay lõi xong thì đặt lại để đồng hồ
        bắt đầu từ 0.
      </p>

      {!confirming ? (
        <button type="button" onClick={() => setConfirming(true)} className="btn-ghost mt-3 w-full">
          Đã thay lõi mới
        </button>
      ) : (
        <div className="mt-3 rounded-xl border border-line p-3">
          <p className="text-sm">Đặt lại về 0? Số giờ đã đếm sẽ mất.</p>
          <div className="mt-3 flex gap-2">
            <button type="button" onClick={() => setConfirming(false)} className="btn-ghost flex-1">
              Thôi
            </button>
            <button
              type="button"
              disabled={reset.isPending}
              onClick={() =>
                reset.mutate(undefined, {
                  onSuccess: () => setConfirming(false),
                })
              }
              className="btn-primary flex-1"
            >
              {reset.isPending ? "Đang đặt lại…" : "Đặt lại"}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function DeviceSection({ device }: { device: Device }) {
  const unpair = useUnpairDevice();
  const navigate = useNavigate();
  const [confirming, setConfirming] = useState(false);
  const latest = useLatest(device.device_id);
  const state = roomState(device, latest.data, undefined);

  return (
    <section className="mt-10 border-t border-line/60 pt-6">
      <div className="label">Máy lọc</div>
      <dl className="mt-2 space-y-1.5 text-[13px]">
        <Row label="Gửi lần cuối" value={formatAgo(device.last_seen_at)} />
        <Row label="Chế độ" value={device.fan_mode === "AUTO" ? "tự động" : "điều khiển tay"} />
        <Row label="Trạng thái" value={state.word.toLowerCase()} />
      </dl>

      {!confirming ? (
        <button type="button" onClick={() => setConfirming(true)} className="btn-danger mt-4 w-full">
          Ngắt kết nối máy này
        </button>
      ) : (
        <div className="mt-4 rounded-xl border border-bad/30 p-3">
          <p className="text-sm">Ngắt kết nối?</p>
          <p className="mt-1 text-[12px] leading-relaxed text-muted">
            Máy vẫn lọc bình thường, nhưng sẽ không còn gửi dữ liệu về đây. Muốn dùng lại thì phải
            nhập mã trên máy một lần nữa.
          </p>
          <div className="mt-3 flex gap-2">
            <button type="button" onClick={() => setConfirming(false)} className="btn-ghost flex-1">
              Thôi
            </button>
            <button
              type="button"
              disabled={unpair.isPending}
              onClick={() =>
                unpair.mutate(device.device_id, { onSuccess: () => navigate("/") })
              }
              className="btn-danger flex-1"
            >
              {unpair.isPending ? "Đang ngắt…" : "Ngắt kết nối"}
            </button>
          </div>
        </div>
      )}

      <p className="mt-4 text-center text-[11px] text-muted">
        <Link to={`/room/${encodeURIComponent(device.device_id)}`} className="hover:text-white">
          Về phòng
        </Link>
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
