import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import type { Device, Space } from "../api/types";
import { Icon } from "../components/Icon";
import {
  useApplyThreshold,
  useDevices,
  useLatest,
  useResetFilter,
  useSaveSpace,
  useSpaces,
  useThresholdPreview,
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

type ThresholdMode = "manual" | "auto";

function ThresholdSection({ space }: { space?: Space }) {
  const save = useSaveSpace(space?.code);
  const applyAuto = useApplyThreshold(space?.code);
  const current = space?.threshold_pm25 ?? 35;
  const selected = SENSITIVITY.reduce((best, option) =>
    Math.abs(option.threshold - current) < Math.abs(best.threshold - current) ? option : best,
  SENSITIVITY[1]);

  const [mode, setMode] = useState<ThresholdMode>(space?.threshold_source === "profile" ? "auto" : "manual");
  const [draft, setDraft] = useState<number | null>(null);
  const preview = useThresholdPreview(space?.code, mode === "auto");

  const effective = mode === "auto"
    ? (preview.data?.computed.thresholdPm25 ?? current)
    : (draft ?? selected.threshold);
  const behaviour = fanBehaviour({ ...(space as Space), threshold_pm25: effective });
  const changed = Math.abs(effective - current) > 0.01;

  return (
    <section className="mt-8">
      <div className="flex items-baseline justify-between">
        <div className="label">Ngưỡng bật máy</div>
        <div className="flex gap-1 rounded-lg border border-line p-0.5" role="group" aria-label="Cách đặt ngưỡng">
          <button
            type="button"
            aria-pressed={mode === "manual"}
            onClick={() => setMode("manual")}
            className={`rounded-md px-2.5 py-1 text-[11px] transition-colors ${
              mode === "manual" ? "bg-white font-medium text-canvas" : "text-muted hover:text-white"
            }`}
          >
            Tự chọn
          </button>
          <button
            type="button"
            aria-pressed={mode === "auto"}
            onClick={() => setMode("auto")}
            className={`rounded-md px-2.5 py-1 text-[11px] transition-colors ${
              mode === "auto" ? "bg-white font-medium text-canvas" : "text-muted hover:text-white"
            }`}
          >
            Tự động
          </button>
        </div>
      </div>

      {mode === "manual" ? (
        <>
          <p className="mt-1 text-sm leading-relaxed text-muted">
            Chọn mức nhạy bạn muốn. Máy bật khi bụi chạm ngưỡng, tắt khi xuống dưới 70 % ngưỡng.
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
                <span className="mt-0.5 block font-mono text-[10px] opacity-70">{option.threshold}</span>
              </button>
            ))}
          </div>
        </>
      ) : (
        <>
          <p className="mt-1 text-sm leading-relaxed text-muted">
            Tính từ hồ sơ phòng và số đo CO₂ hiện tại. Ngưỡng thấp hơn khi phòng kín hoặc phải ở lâu hơn.
          </p>

          {preview.isLoading ? (
            <div className="mt-3 rounded-xl border border-line/70 px-3 py-4 text-sm text-muted">
              Đang tính…
            </div>
          ) : preview.data ? (
            <div className="mt-3 rounded-xl border border-line/70 divide-y divide-line/50">
              <div className="flex items-baseline justify-between px-3 py-2.5">
                <span className="text-sm text-slate-200">Ngưỡng đề xuất</span>
                <span className="font-mono text-lg text-white">
                  {preview.data.computed.thresholdPm25.toFixed(1)}
                  <span className="ml-1 font-sans text-[11px] text-muted">µg/m³</span>
                </span>
              </div>
              <Factor
                label="Mục tiêu liều/ngày"
                value={`${preview.data.computed.dailyDoseTarget.toFixed(1)} µg/m³`}
                note={`WHO 24h = 15, chia cho ${preview.data.computed.occupancyHours} giờ có người`}
              />
              <Factor
                label="Hệ số thể tích"
                value={`× ${preview.data.computed.volumeFactor.toFixed(2)}`}
                note={
                  preview.data.computed.volumeM3 == null
                    ? "chưa nhập thể tích"
                    : `${preview.data.computed.volumeM3} m³ so với chuẩn 20 m³`
                }
              />
              <Factor
                label="Hệ số thông gió"
                value={`× ${preview.data.computed.ventilationFactor.toFixed(2)}`}
                note={space?.ventilation_estimate === "low" ? "phòng kín" : space?.ventilation_estimate === "high" ? "thoáng" : "trung bình"}
              />
              <Factor
                label="Hệ số CO₂"
                value={`× ${preview.data.computed.co2Factor.toFixed(2)}`}
                note={
                  preview.data.computed.co2Ppm == null
                    ? "chưa có số đo CO₂"
                    : `${Math.round(preview.data.computed.co2Ppm)} ppm hiện tại`
                }
              />
            </div>
          ) : (
            <p className="mt-3 rounded-xl border border-bad/30 bg-bad/10 px-3 py-2 text-[12px] text-bad">
              Không tính được. Kiểm tra lại hồ sơ phòng.
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
          disabled={applyAuto.isPending || !space || !preview.data}
          onClick={() => applyAuto.mutate()}
          className="btn-primary mt-3 w-full"
        >
          {applyAuto.isPending ? "Đang áp dụng…" : `Áp dụng ${effective.toFixed(0)} µg/m³`}
        </button>
      ) : (
        changed && (
          <button
            type="button"
            disabled={save.isPending || !space}
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
    <div className="flex items-baseline justify-between gap-3 px-3 py-2.5">
      <div className="min-w-0">
        <div className="text-[12px] text-slate-300">{label}</div>
        <div className="text-[11px] leading-snug text-muted">{note}</div>
      </div>
      <span className="shrink-0 font-mono text-sm text-slate-200">{value}</span>
    </div>
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
