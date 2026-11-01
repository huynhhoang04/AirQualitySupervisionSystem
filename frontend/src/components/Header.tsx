import type { Device } from "../api/types";
import { isOnline } from "../lib/format";

interface Props {
  devices: Device[];
  selected?: Device;
  onSelect: (deviceId: string) => void;
}

export function Header({ devices, selected, onSelect }: Props) {
  const online = selected ? isOnline(selected.last_seen_at) : false;

  return (
    <header className="sticky top-0 z-10 border-b border-line bg-ink/85 backdrop-blur">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-4 px-4 py-3">
        <div className="flex items-baseline gap-2">
          <span className="text-lg font-semibold tracking-tight text-slate-100">AQSS</span>
          <span className="hidden text-xs text-slate-500 sm:inline">
            Air Quality Supervision System
          </span>
        </div>

        <div className="ml-auto flex items-center gap-3">
          <span
            className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs ${
              online
                ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-300"
                : "border-slate-600/40 bg-slate-600/10 text-slate-400"
            }`}
          >
            <span
              className={`h-1.5 w-1.5 rounded-full ${online ? "bg-emerald-400" : "bg-slate-500"}`}
            />
            {online ? "node online" : "node offline"}
          </span>

          <select
            value={selected?.device_id ?? ""}
            onChange={(event) => onSelect(event.target.value)}
            className="rounded-lg border border-line bg-panel px-3 py-1.5 text-sm text-slate-200 outline-none focus:border-sky-500"
          >
            {devices.length === 0 && <option value="">chưa có thiết bị</option>}
            {devices.map((device) => (
              <option key={device.device_id} value={device.device_id}>
                {device.name ?? device.device_id} · {device.device_id}
              </option>
            ))}
          </select>
        </div>
      </div>
    </header>
  );
}
