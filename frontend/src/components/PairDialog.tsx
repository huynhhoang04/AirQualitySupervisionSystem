import { useState } from "react";

import { ApiError } from "../api/client";
import { usePairDevice } from "../hooks/useAqss";
import type { Space } from "../api/types";
import { Icon } from "./Icon";

interface Props {
  spaces: Space[];
  onClose: () => void;
  onPaired: (deviceId: string) => void;
}

/**
 * The one place a node becomes visible. The code is on the node's OLED; this dialog only asks for
 * it and for the room it belongs to. Normalisation (case, dashes) happens on the backend.
 */
export function PairDialog({ spaces, onClose, onPaired }: Props) {
  const [code, setCode] = useState("");
  const [spaceCode, setSpaceCode] = useState(spaces[0]?.code ?? "");
  const [name, setName] = useState("");
  const pair = usePairDevice();

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!code.trim() || !spaceCode) return;
    pair.mutate(
      { pairing_code: code.trim(), space_code: spaceCode, name: name.trim() || undefined },
      { onSuccess: (device) => onPaired(device.device_id) },
    );
  };

  const error = pair.error instanceof ApiError ? pair.error.message : pair.error ? "Ghép thất bại." : null;

  return (
    <div
      className="fixed inset-0 z-30 flex items-end justify-center bg-black/60 p-4 backdrop-blur-sm sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby="pair-title"
      onKeyDown={(event) => {
        if (event.key === "Escape") onClose();
      }}
    >
      <form
        onSubmit={submit}
        className="w-full max-w-md rounded-xl border border-line bg-surface p-5 shadow-2xl"
      >
        <div className="flex items-start justify-between">
          <div>
            <h2 id="pair-title" className="text-base font-semibold">
              Ghép thiết bị
            </h2>
            <p className="mt-1 text-xs leading-relaxed text-muted">
              Đọc mã 6 ký tự trên màn hình OLED của node (màn hình đang ở trạng thái{" "}
              <span className="font-mono text-slate-300">AQSS PAIRING</span>), rồi chọn phòng cho nó.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="btn-ghost -mr-1 -mt-1 px-2 py-1"
            aria-label="Đóng"
          >
            ✕
          </button>
        </div>

        <label className="mt-4 block text-xs">
          <span className="text-muted">Mã thiết bị</span>
          <input
            autoFocus
            value={code}
            onChange={(event) => setCode(event.target.value.toUpperCase())}
            placeholder="7K2M9X"
            maxLength={12}
            autoComplete="off"
            spellCheck={false}
            className="field mt-1 text-center font-mono text-2xl tracking-[0.35em] uppercase"
          />
        </label>

        <label className="mt-3 block text-xs">
          <span className="text-muted">Phòng</span>
          <select
            value={spaceCode}
            onChange={(event) => setSpaceCode(event.target.value)}
            className="field mt-1"
            required
          >
            {spaces.length === 0 && <option value="">chưa có phòng nào</option>}
            {spaces.map((space) => (
              <option key={space.code} value={space.code}>
                {space.name} ({space.code})
              </option>
            ))}
          </select>
        </label>

        <label className="mt-3 block text-xs">
          <span className="text-muted">Tên thiết bị (tuỳ chọn)</span>
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Node phòng ngủ"
            maxLength={100}
            className="field mt-1"
          />
        </label>

        {error && (
          <p className="mt-3 rounded-lg border border-bad/30 bg-bad/10 px-2 py-1.5 text-[11px] text-bad">
            {error}
          </p>
        )}
        {spaces.length === 0 && (
          <p className="mt-3 text-[11px] text-warn">
            Chưa có phòng nào trong hệ thống. Tạo phòng ở trang tổng quan trước.
          </p>
        )}

        <div className="mt-5 flex gap-2">
          <button type="button" onClick={onClose} className="btn-ghost flex-1">
            Huỷ
          </button>
          <button
            type="submit"
            disabled={pair.isPending || !code.trim() || !spaceCode}
            className="btn-primary flex-1"
          >
            <Icon name="link" />
            {pair.isPending ? "Đang ghép…" : "Ghép"}
          </button>
        </div>
      </form>
    </div>
  );
}
