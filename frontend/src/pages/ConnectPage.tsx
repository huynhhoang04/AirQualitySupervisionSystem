import { useState } from "react";
import { useNavigate } from "react-router-dom";

import { ApiError } from "../api/client";
import { Icon } from "../components/Icon";
import { usePairDevice, useSaveSpace, useSpaces } from "../hooks/useAqss";

/**
 * First run, and the only flow that must work without anything already configured.
 *
 * Two steps, because the user's mental model has two: "which box is this" and "which room is it in".
 * Thresholds, volume, purpose and region are the system's problem — they are filled in with sane
 * defaults and can be edited later in the room's settings.
 */
export function ConnectPage() {
  const navigate = useNavigate();
  const spaces = useSpaces();
  const pair = usePairDevice();
  const [step, setStep] = useState<"code" | "room">("code");
  const [code, setCode] = useState("");
  const [roomName, setRoomName] = useState("");
  const [error, setError] = useState<string | null>(null);

  const createSpace = useSaveSpace(undefined);

  const normalise = (raw: string) => raw.toUpperCase().replace(/[^A-Z2-9]/g, "").slice(0, 6);

  /**
   * With a space already on the account, one step is enough. Without one, the second step names the
   * room and creates it — the operator never sees a "space code".
   */
  const submitCode = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    if (code.length < 6) {
      setError("Mã có 6 ký tự. Đọc lại trên màn hình máy.");
      return;
    }
    const existingSpace = spaces.data?.[0];
    if (!existingSpace) {
      setStep("room");
      return;
    }
    pair.mutate(
      { pairing_code: code, space_code: existingSpace.code, name: roomName || undefined },
      {
        onSuccess: (device) => navigate(`/room/${encodeURIComponent(device.device_id)}`),
        onError: (err) => setError(describePairError(err)),
      },
    );
  };

  const submitRoom = (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    const name = roomName.trim() || "Phòng của tôi";
    const spaceCode = slug(name);
    createSpace.mutate(
      { name, threshold_pm25: 35 },
      {
        onSuccess: () => {
          pair.mutate(
            { pairing_code: code, space_code: spaceCode, name },
            {
              onSuccess: (device) => navigate(`/room/${encodeURIComponent(device.device_id)}`),
              onError: (err) => setError(describePairError(err)),
            },
          );
        },
        onError: (err) => setError(err.message),
      },
    );
  };

  return (
    <div className="mx-auto flex min-h-screen max-w-md flex-col px-5 py-10">
      <div className="flex items-center gap-2">
        <span className="flex h-9 w-9 items-center justify-center rounded-xl border border-line text-info">
          <Icon name="wind" size={19} />
        </span>
        <span className="font-semibold tracking-tight">AQSS</span>
      </div>

      <div className="mt-14">
        {step === "code" ? (
          <form onSubmit={submitCode}>
            <h1 className="text-xl font-semibold tracking-tight">Kết nối máy lọc</h1>
            <p className="mt-2 text-sm leading-relaxed text-muted">
              Nhập mã 6 ký tự trên màn hình của máy. Mã hiện ngay sau khi cắm điện.
            </p>

            <label className="mt-7 block">
              <span className="label">Mã trên máy</span>
              <input
                id="pairing-code"
                autoFocus
                value={code}
                onChange={(event) => setCode(normalise(event.target.value))}
                placeholder="X35AJK"
                autoComplete="off"
                spellCheck={false}
                inputMode="text"
                className="mt-2 w-full rounded-2xl border border-line bg-surface px-4 py-5 text-center font-mono text-3xl uppercase tracking-[0.4em] text-white placeholder:text-line focus:border-info"
              />
            </label>

            <button type="submit" disabled={pair.isPending} className="btn-primary mt-5 w-full py-4">
              {pair.isPending ? "Đang tìm máy…" : "Kết nối"}
            </button>

            {error && (
              <div className="mt-4 rounded-xl border border-bad/30 bg-bad/10 px-3 py-2.5 text-sm text-bad">
                {error}
              </div>
            )}

            <div className="mt-8 border-t border-line/70 pt-5 text-sm leading-relaxed text-muted">
              <div className="font-medium text-slate-300">Không thấy mã?</div>
              <p className="mt-1">
                Màn hình hiện <span className="font-mono text-slate-300">AQSS SETUP</span> thay vì mã
                nghĩa là máy chưa vào được Wi-Fi. Giữ nút trên máy 3 giây rồi cấp nguồn lại, sau đó
                làm theo hướng dẫn trên màn hình.
              </p>
            </div>
          </form>
        ) : (
          <form onSubmit={submitRoom}>
            <button
              type="button"
              onClick={() => {
                setStep("code");
                setError(null);
              }}
              className="mb-6 inline-flex items-center gap-1 text-sm text-muted hover:text-white"
            >
              <Icon name="back" size={15} /> Quay lại
            </button>

            <div className="flex items-center gap-2">
              <span className="chip border border-good/30 bg-good/10 text-good">
                <Icon name="check" size={13} /> đã tìm thấy
              </span>
              <span className="font-mono text-sm text-white">{code}</span>
            </div>

            <h1 className="mt-4 text-xl font-semibold tracking-tight">Máy này ở đâu?</h1>
            <p className="mt-2 text-sm leading-relaxed text-muted">
              Đặt tên cho căn phòng. Hệ thống tự tạo phòng và gắn máy vào.
            </p>

            <label className="mt-6 block">
              <span className="label">Tên phòng</span>
              <input
                id="room-name"
                autoFocus
                value={roomName}
                onChange={(event) => setRoomName(event.target.value)}
                placeholder="Phòng ngủ"
                className="field mt-2 text-lg"
              />
            </label>

            <div className="mt-3 flex flex-wrap gap-2">
              {["Phòng ngủ", "Phòng khách", "Văn phòng", "Lớp học"].map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => setRoomName(suggestion)}
                  className="chip border border-line text-muted hover:border-slate-500 hover:text-white"
                >
                  {suggestion}
                </button>
              ))}
            </div>

            <button
              type="submit"
              disabled={pair.isPending || createSpace.isPending}
              className="btn-primary mt-6 w-full py-4"
            >
              {pair.isPending || createSpace.isPending ? "Đang hoàn tất…" : "Hoàn tất"}
            </button>

            {error && (
              <div className="mt-4 rounded-xl border border-bad/30 bg-bad/10 px-3 py-2.5 text-sm text-bad">
                {error}
              </div>
            )}

            <p className="mt-4 text-[11px] leading-relaxed text-muted">
              Ngưỡng mặc định là 35 µg/m³. Sửa được sau trong phần cài đặt của phòng.
            </p>
          </form>
        )}
      </div>
    </div>
  );
}

/** Pairing errors are HTTP details; the screen shows what to do about them. */
function describePairError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) {
      return "Chưa có máy nào dùng mã này. Kiểm tra lại trên màn hình, và đợi khoảng 10 giây sau khi cắm điện.";
    }
    if (error.status === 409) return "Máy này đã được ghép với một phòng khác.";
    if (error.status === 0) return "Không kết nối được tới hệ thống. Kiểm tra mạng.";
    return error.message;
  }
  return "Ghép máy thất bại. Thử lại.";
}

function slug(name: string): string {
  return (
    name
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .replace(/đ/gi, "d")
      .toUpperCase()
      .replace(/[^A-Z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "")
      .slice(0, 20) || `SP_${Math.floor(Math.random() * 9000 + 1000)}`
  );
}
