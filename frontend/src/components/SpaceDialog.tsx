import { useState } from "react";

import type { Space } from "../api/types";
import { useSaveSpace } from "../hooks/useAqss";
import { Icon } from "./Icon";

interface Props {
  onCreated: (code: string) => void;
}

function slugify(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .replace(/đ/gi, "d")
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 20);
}

/** A room must exist before a node can be paired to it. */
export function SpaceDialog({ onCreated }: Props) {
  const [name, setName] = useState("");
  const [code, setCode] = useState("");
  const [volume, setVolume] = useState("");
  const [threshold, setThreshold] = useState("35");
  const save = useSaveSpace(code || slugify(name) || "NEW_SPACE");

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    const finalCode = code || slugify(name);
    if (!name.trim() || !finalCode) return;
    save.mutate(
      {
        name: name.trim(),
        volume_m3: volume ? Number(volume) : undefined,
        threshold_pm25: threshold ? Number(threshold) : undefined,
      },
      { onSuccess: (space: Space) => onCreated(space.code) },
    );
  };

  return (
    <div
      className="fixed inset-0 z-30 flex items-end justify-center bg-black/60 p-4 backdrop-blur-sm sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby="space-title"
    >
      <form onSubmit={submit} className="w-full max-w-md rounded-xl border border-line bg-surface p-5 shadow-2xl">
        <h2 id="space-title" className="text-base font-semibold">
          Thêm phòng
        </h2>
        <p className="mt-1 text-xs text-muted">
          Ngưỡng PM2.5 thuộc về phòng, không thuộc thiết bị. Có thể chỉnh lại sau.
        </p>

        <label className="mt-4 block text-xs">
          <span className="text-muted">Tên phòng</span>
          <input
            autoFocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="Phòng 202"
            maxLength={100}
            className="field mt-1"
          />
        </label>

        <div className="mt-3 grid grid-cols-2 gap-3">
          <label className="block text-xs">
            <span className="text-muted">Mã phòng</span>
            <input
              value={code}
              onChange={(event) => setCode(slugify(event.target.value))}
              placeholder={slugify(name) || "SP_202"}
              className="field mt-1 font-mono uppercase"
            />
          </label>
          <label className="block text-xs">
            <span className="text-muted">Thể tích (m³)</span>
            <input
              type="number"
              min={1}
              step="0.5"
              value={volume}
              onChange={(event) => setVolume(event.target.value)}
              className="field mt-1 font-mono"
            />
          </label>
        </div>

        <label className="mt-3 block text-xs">
          <span className="text-muted">Ngưỡng PM2.5 (µg/m³)</span>
          <input
            type="number"
            min={1}
            max={500}
            step="0.5"
            value={threshold}
            onChange={(event) => setThreshold(event.target.value)}
            className="field mt-1 font-mono"
          />
        </label>

        <div className="mt-5 flex gap-2">
          <button
            type="submit"
            disabled={save.isPending || !name.trim()}
            className="btn-primary w-full"
          >
            <Icon name="plus" />
            {save.isPending ? "Đang tạo…" : "Tạo phòng"}
          </button>
        </div>
        {save.error && (
          <p className="mt-2 rounded-lg border border-bad/30 bg-bad/10 px-2 py-1.5 text-[11px] text-bad">
            {save.error.message}
          </p>
        )}
      </form>
    </div>
  );
}
