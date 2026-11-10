import { useState } from "react";

import type { Device } from "../api/types";
import { fanCommand, fanSelection, isPositionActive, type FanSelection } from "../lib/room";

interface Props {
  device: Device;
  pending: boolean;
  error?: string | null;
  onCommand: (selection: { state: "ON" | "OFF"; level: number; mode: "AUTO" | "MANUAL" }) => void;
}

const POSITIONS = [
  { key: "auto", label: "AUTO", selection: { mode: "AUTO", on: false, level: 0 } },
  { key: "off", label: "TẮT", selection: { mode: "MANUAL", on: false, level: 0 } },
  { key: "1", label: "1", selection: { mode: "MANUAL", on: true, level: 1 } },
  { key: "2", label: "2", selection: { mode: "MANUAL", on: true, level: 2 } },
  { key: "3", label: "3", selection: { mode: "MANUAL", on: true, level: 3 } },
] as const;

/**
 * Five mutually exclusive positions, because that is what the machine actually has. Picking one is
 * a single command; there is no separate "return to AUTO" button, because AUTO is a position.
 */
export function FanSelector({ device, pending, error, onCommand }: Props) {
  const current = fanSelection(device);
  const [optimistic, setOptimistic] = useState<FanSelection | null>(null);

  // The optimistic value is only trusted while the command is in flight; once the server answers,
  // the device itself is the truth again.
  const shown = pending && optimistic ? optimistic : current;

  const pick = (next: FanSelection) => {
    setOptimistic(next);
    onCommand(fanCommand(next));
  };

  return (
    <div>
      <div className="flex items-center justify-between">
        <div className="label">Điều khiển quạt</div>
        <div className="font-mono text-[10px] uppercase tracking-wider text-muted">
          MANUAL khi bạn chọn mức
        </div>
      </div>

      <div className="mt-2 grid grid-cols-5 gap-1.5" role="group" aria-label="Mức quạt">
        {POSITIONS.map((position) => {
          const active = isPositionActive(shown, position.selection);
          return (
            <button
              key={position.key}
              type="button"
              disabled={pending}
              aria-pressed={active}
              onClick={() => pick(position.selection)}
              className={`rounded-xl border py-3 font-mono text-sm transition-colors duration-150 ${
                active
                  ? "border-white bg-white font-semibold text-canvas"
                  : "border-line text-slate-400 hover:border-slate-500 hover:text-slate-200"
              } disabled:opacity-40`}
            >
              {position.label}
            </button>
          );
        })}
      </div>

      <p className="mt-2 text-[11px] leading-relaxed text-muted">
        AUTO để hệ thống tự quyết định · chọn 1–3 là giữ nguyên cho tới khi bạn đổi lại
      </p>

      {error && (
        <p className="mt-2 rounded-lg border border-bad/30 bg-bad/10 px-2.5 py-1.5 text-[11px] text-bad">
          {error}
        </p>
      )}
    </div>
  );
}
