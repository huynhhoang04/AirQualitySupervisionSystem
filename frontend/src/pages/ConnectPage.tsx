import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { ApiError } from "../api/client";
import { Icon } from "../components/Icon";
import { usePairDevice } from "../hooks/useAqss";
import { ROOM_TEMPLATES, type RoomTemplate } from "../lib/room";

/**
 * Three steps, in the order a person actually does them: read the code, say where the machine is,
 * confirm. Nothing is asked that the operator cannot answer from where they are standing.
 */
export function ConnectPage() {
  const navigate = useNavigate();
  const pair = usePairDevice();

  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [code, setCode] = useState("");
  const [template, setTemplate] = useState<RoomTemplate>(ROOM_TEMPLATES[0]);
  const [roomName, setRoomName] = useState(ROOM_TEMPLATES[0].label);
  const [volume, setVolume] = useState("");
  const [occupancy, setOccupancy] = useState("");
  const [deviceName, setDeviceName] = useState("");
  const [error, setError] = useState<string | null>(null);

  const normalise = (raw: string) => raw.toUpperCase().replace(/[^A-Z2-9]/g, "").slice(0, 6);
  const roomLabel = roomName.trim() || template.label;

  const submit = () => {
    setError(null);
    if (template.key === "custom" && (
      !Number.isFinite(Number(volume)) || Number(volume) <= 0 ||
      !Number.isInteger(Number(occupancy)) || Number(occupancy) < 1 || Number(occupancy) > 24
    )) {
      setError("Nhập thể tích lớn hơn 0 và số giờ có người từ 1 đến 24.");
      setStep(2);
      return;
    }
    pair.mutate(
      {
        pairing_code: code,
        space_name: roomLabel,
        name: deviceName.trim() || roomLabel,
        volume_m3: template.key === "custom" ? Number(volume) : template.volumeM3,
        occupancy_hours: template.key === "custom" ? Number(occupancy) : template.occupancyHours,
        ventilation_estimate: template.ventilation,
        purpose: template.purpose,
      },
      {
        onSuccess: (device) => navigate(`/room/${encodeURIComponent(device.device_id)}`),
        onError: (err) => {
          // A wrong code sends the operator back to the field they have to correct — but the reason
          // has to survive the jump, or the step silently resets and looks like a broken button.
          setError(describePairError(err, code));
          if (err instanceof ApiError && (err.status === 404 || err.status === 400)) setStep(1);
        },
      },
    );
  };

  return (
    <div className="mx-auto min-h-screen max-w-md px-5 pb-16 pt-6">
      <header className="flex items-center gap-3">
        {step === 1 ? (
          <Link to="/" className="rounded-lg p-1.5 text-muted hover:text-white" aria-label="Quay lại">
            <Icon name="back" />
          </Link>
        ) : (
          <button
            type="button"
            onClick={() => {
              setStep((current) => (current === 3 ? 2 : 1) as 1 | 2 | 3);
              setError(null);
            }}
            className="rounded-lg p-1.5 text-muted hover:text-white"
            aria-label="Quay lại bước trước"
          >
            <Icon name="back" />
          </button>
        )}
        <div className="flex flex-1 gap-1.5">
          {[1, 2, 3].map((index) => (
            <span
              key={index}
              className={`h-0.5 flex-1 rounded-full transition-colors ${
                index <= step ? "bg-white" : "bg-line"
              }`}
            />
          ))}
        </div>
        <span className="font-mono text-[10px] text-muted">{step}/3</span>
      </header>

      {step === 1 && (
        <section className="mt-10">
          <h1 className="text-xl font-semibold tracking-tight">Đọc mã trên máy</h1>
          <p className="mt-2 text-sm leading-relaxed text-muted">
            Mã 6 ký tự hiện trên màn hình máy ngay sau khi cắm điện. Không phân biệt hoa thường.
          </p>

          <input
            id="pairing-code"
            autoFocus
            value={code}
            onChange={(event) => setCode(normalise(event.target.value))}
            onKeyDown={(event) => {
              if (event.key === "Enter" && code.length === 6) setStep(2);
            }}
            placeholder="X35AJK"
            autoComplete="off"
            spellCheck={false}
            className="mt-7 w-full rounded-2xl border border-line bg-surface px-4 py-5 text-center font-mono text-3xl uppercase tracking-[0.4em] text-white placeholder:text-line focus:border-info"
          />
          <p className="mt-2 text-center font-mono text-[11px] text-muted">
            {code.length}/6 ký tự
          </p>

          <button
            type="button"
            disabled={code.length !== 6}
            onClick={() => setStep(2)}
            className="btn-primary mt-5 w-full py-4"
          >
            Tiếp tục
          </button>

          {error && (
            <div className="mt-4 rounded-xl border border-bad/30 bg-bad/10 px-3 py-2.5 text-sm leading-relaxed text-bad">
              {error}
            </div>
          )}

          <div className="mt-8 rounded-xl border border-line/70 p-4 text-[12px] leading-relaxed text-muted">
            <div className="font-medium text-slate-300">Màn hình không hiện mã?</div>
            <p className="mt-1">
              Nếu đang hiện <span className="font-mono">AQSS SETUP</span>, máy chưa vào được Wi-Fi.
              Xem <Link to="/guide" className="text-info underline-offset-2 hover:underline">hướng dẫn</Link>.
            </p>
          </div>
        </section>
      )}

      {step === 2 && (
        <section className="mt-10">
          <div className="flex items-center gap-2">
            <span className="chip border border-good/30 bg-good/10 text-good">
              <Icon name="check" size={12} /> mã hợp lệ
            </span>
            <span className="font-mono text-sm text-white">{code}</span>
          </div>

          <h1 className="mt-4 text-xl font-semibold tracking-tight">Máy đặt ở đâu?</h1>
          <p className="mt-2 text-sm leading-relaxed text-muted">
            Chọn mẫu gần nhất. Ngưỡng và thể tích được điền sẵn, sửa được sau.
          </p>

          <div className="mt-5 grid grid-cols-2 gap-2">
            {ROOM_TEMPLATES.map((option) => {
              const active = template.key === option.key;
              return (
                <button
                  key={option.key}
                  type="button"
                  aria-pressed={active}
                  onClick={() => {
                    setTemplate(option);
                    setRoomName(option.label);
                    // Choosing "Khác" clears the numbers so the operator types real ones instead of
                    // inheriting a template they did not pick.
                    if (option.key === "custom") {
                      setVolume("");
                      setOccupancy("");
                    }
                  }}
                  className={`rounded-xl border p-3 text-left transition-colors ${
                    active ? "border-white bg-white/10" : "border-line hover:border-slate-500"
                  }`}
                >
                  <div className={`text-sm ${active ? "font-medium text-white" : "text-slate-300"}`}>
                    {option.label}
                  </div>
                  <div className="mt-0.5 text-[10px] leading-snug text-muted">{option.hint}</div>
                </button>
              );
            })}
          </div>

          {/* The chosen template's numbers, shown rather than hidden: they are what the threshold
              is derived from, so the operator can see them before connecting. */}
          {template.key !== "custom" ? (
            <dl className="mt-3 grid grid-cols-3 gap-2 rounded-xl border border-line/70 px-3 py-2.5">
              <Fact label="Thể tích" value={`${template.volumeM3} m³`} />
              <Fact label="Có người" value={`${template.occupancyHours} h/ngày`} />
              <Fact label="Thông gió" value={template.ventilation === "low" ? "Kín" : template.ventilation === "high" ? "Thoáng" : "Trung bình"} />
            </dl>
          ) : (
            <div className="mt-3 space-y-2 rounded-xl border border-line/70 p-3">
              <p className="text-[11px] leading-relaxed text-muted">
                Nhập số đo thật của phòng. Ngưỡng sẽ được tính từ đây.
              </p>
              <div className="grid grid-cols-2 gap-2">
                <label className="block text-[11px]">
                  <span className="text-muted">Thể tích (m³)</span>
                  <input
                    type="number"
                    min={1}
                    max={10000}
                    value={volume}
                    onChange={(event) => setVolume(event.target.value)}
                    className="field mt-1 py-2 font-mono text-sm"
                  />
                </label>
                <label className="block text-[11px]">
                  <span className="text-muted">Giờ có người mỗi ngày</span>
                  <input
                    type="number"
                    min={1}
                    max={24}
                    value={occupancy}
                    onChange={(event) => setOccupancy(event.target.value)}
                    className="field mt-1 py-2 font-mono text-sm"
                  />
                </label>
              </div>
            </div>
          )}

          <label className="mt-4 block">
            <span className="label">Tên máy</span>
            <input
              value={roomName}
              onChange={(event) => setRoomName(event.target.value)}
              placeholder={template.label}
              className="field mt-2"
              maxLength={100}
            />
          </label>

          <button type="button" onClick={() => setStep(3)} className="btn-primary mt-5 w-full py-4">
            Tiếp tục
          </button>

          {error && (
            <div className="mt-4 rounded-xl border border-bad/30 bg-bad/10 px-3 py-2.5 text-sm leading-relaxed text-bad">
              {error}
            </div>
          )}
        </section>
      )}

      {step === 3 && (
        <section className="mt-10">
          <h1 className="text-xl font-semibold tracking-tight">Xác nhận</h1>
          <p className="mt-2 text-sm leading-relaxed text-muted">Kiểm tra lại trước khi hoàn tất.</p>

          <dl className="mt-6 divide-y divide-line/50 rounded-xl border border-line/70">
            <Row label="Mã trên máy" value={code} mono />
            <Row label="Tên máy" value={roomLabel} />
            <Row label="Mẫu phòng" value={template.label} />
            <Row label="Thể tích" value={`${template.key === "custom" ? volume : template.volumeM3} m³`} mono />
            <Row label="Giờ có người" value={`${template.key === "custom" ? occupancy : template.occupancyHours} giờ/ngày`} mono />
            <Row label="Ngưỡng" value="Tính từ hồ sơ phòng khi lưu" />
          </dl>

          <label className="mt-5 block">
            <span className="label">Tên riêng cho máy (tuỳ chọn)</span>
            <input
              value={deviceName}
              onChange={(event) => setDeviceName(event.target.value)}
              placeholder={roomLabel}
              className="field mt-2"
              maxLength={100}
            />
          </label>

          <button
            type="button"
            disabled={pair.isPending}
            onClick={submit}
            className="btn-primary mt-5 w-full py-4"
          >
            {pair.isPending ? "Đang kết nối…" : "Hoàn tất kết nối"}
          </button>

          {error && (
            <div className="mt-4 rounded-xl border border-bad/30 bg-bad/10 px-3 py-2.5 text-sm text-bad">
              {error}
            </div>
          )}

          <p className="mt-4 text-[11px] leading-relaxed text-muted">
            Sau khi hoàn tất, màn hình máy chuyển từ mã sang số đo. Nếu muốn đổi ngưỡng hoặc ngắt
            kết nối, vào phần cài đặt của máy.
          </p>
        </section>
      )}
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[10px] uppercase tracking-wider text-muted">{label}</dt>
      <dd className="mt-0.5 font-mono text-[13px] text-slate-200">{value}</dd>
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 px-4 py-3">
      <dt className="text-[12px] text-muted">{label}</dt>
      <dd className={`text-right text-[13px] text-slate-200 ${mono ? "font-mono" : ""}`}>{value}</dd>
    </div>
  );
}

/** Pairing errors are HTTP details; the screen shows what to do about them. */
function describePairError(error: unknown, code: string): string {
  if (error instanceof ApiError) {
    if (error.status === 404) {
      return `Không có máy nào đang dùng mã ${code}. Đọc lại trên màn hình máy — mã gồm 6 ký tự, ` +
        `và máy phải đang hiện mã (không phải màn hình số đo).`;
    }
    if (error.status === 409) return "Máy này đã được ghép với một phòng khác.";
    if (error.status === 400) return "Mã không hợp lệ. Chỉ dùng chữ A–Z và số 2–9.";
    if (error.status === 0) return "Không kết nối được tới hệ thống. Kiểm tra mạng.";
    return error.message;
  }
  return "Kết nối thất bại. Thử lại.";
}
