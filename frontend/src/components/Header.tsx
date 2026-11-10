import { Link } from "react-router-dom";

import { API_BASE } from "../api/client";
import { isOnline } from "../lib/format";
import { Icon } from "./Icon";

interface Props {
  /** Paired devices, used for the "n nodes online" summary. */
  deviceCount: number;
  onlineCount: number;
  /** Where the brand link goes; hidden on the overview itself. */
  homeHref?: string;
}

export function Header({ deviceCount, onlineCount, homeHref }: Props) {
  return (
    <header className="sticky top-0 z-20 border-b border-line/70 bg-canvas/90 backdrop-blur">
      <div className="mx-auto flex max-w-6xl items-center gap-4 px-4 py-3">
        <div className="flex items-center gap-2">
          {homeHref ? (
            <Link to={homeHref} className="flex items-center gap-2 rounded-lg" aria-label="Về tổng quan">
              <Mark />
            </Link>
          ) : (
            <Mark />
          )}
          <div className="leading-tight">
            <div className="font-semibold tracking-tight">AQSS</div>
            <div className="hidden text-[11px] text-muted sm:block">
              Air Quality Supervision System
            </div>
          </div>
        </div>

        <div className="ml-auto flex items-center gap-2 text-xs text-muted">
          <span className="chip border border-line/70 bg-surface" role="status" aria-atomic="true">
            <span
              className={`h-1.5 w-1.5 rounded-full ${onlineCount > 0 ? "bg-good" : "bg-muted"}`}
              aria-hidden="true"
            />
            {onlineCount}/{deviceCount} node online
          </span>
        </div>
      </div>
    </header>
  );
}

function Mark() {
  return (
    <span
      className="flex h-9 w-9 items-center justify-center rounded-lg border border-info/30 bg-info/10 text-info"
      aria-hidden="true"
    >
      <Icon name="wind" size={20} />
    </span>
  );
}

/** Small helper used by pages that need the online tally. */
export function countOnline(devices: { last_seen_at: string | null }[]): number {
  return devices.filter((device) => isOnline(device.last_seen_at)).length;
}

export const API_LABEL = API_BASE;
