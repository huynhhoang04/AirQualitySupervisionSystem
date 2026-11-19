import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import type { Device } from "../api/types";
import { Icon } from "../components/Icon";
import { useDevices, useLatest, useResetFilter, useSpaces, useUnpairDevice } from "../hooks/useAqss";
import { filterHours, formatAgo, formatHours, roomState } from "../lib/room";

/**
 * Device-level settings only: the filter counter and the pairing.
 *
 * The room profile and the threshold used to live here. They moved to the room page, next to the
 * reading and the fan they control — a screen that shows neither cannot be the place to change the
 * number that decides when the fan runs.
 */
export function RoomSettingsPage({ deviceId }: { deviceId: string }) {
  const navigate = useNavigate();
  const devices = useDevices();
  const spaces = useSpaces();
  const device = devices.data?.find((item) => item.device_id === deviceId);
  const space = spaces.data?.find((item) => item.code === device?.space_code);

  if (!device) {
    return (
      <div className="mx-auto max-w-lg py-10">
        <p className="text-sm text-muted">Không tìm thấy phòng này.</p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-3xl pb-16">
      <header className="flex items-center gap-3">
        <button
          type="button"
          onClick={() => navigate(`/room/${encodeURIComponent(deviceId)}`)}
          className="rounded-lg p-1.5 text-muted hover:text-white"
          aria-label="Quay lại phòng"
        >
          <Icon name="back" />
        </button>
        <div>
          <h1 className="text-[15px] font-medium">Cài đặt thiết bị</h1>
          <div className="font-mono text-[10px] text-muted">{space?.name ?? deviceId}</div>
        </div>
      </header>

      <p className="mt-4 rounded-xl border border-line/60 px-3 py-2.5 text-[12px] leading-relaxed text-muted">
        Hồ sơ phòng và ngưỡng bật máy nằm ở{" "}
        <Link
          to={`/room/${encodeURIComponent(deviceId)}`}
          className="text-info underline-offset-4 hover:underline"
        >
          trang phòng
        </Link>
        , cạnh số đo và điều khiển quạt.
      </p>

      <FilterSection device={device} />
      <DeviceSection device={device} />
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
