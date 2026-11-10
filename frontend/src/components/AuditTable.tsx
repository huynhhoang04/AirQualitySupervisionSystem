import { useMemo, useState } from "react";

import type { AuditEntry } from "../api/types";
import { formatDateTime } from "../lib/format";
import { Icon } from "./Icon";

interface Props {
  entries: AuditEntry[];
}

const ACTOR_STYLE: Record<string, string> = {
  USER: "border-info/30 bg-info/10 text-info",
  BACKEND: "border-good/30 bg-good/10 text-good",
  DEVICE: "border-line bg-canvas text-muted",
  AI: "border-ai/30 bg-ai/10 text-ai",
};

/** Backend actions carry the reason in `detail`; show it as a short phrase, not raw JSON. */
const ACTION_LABEL: Record<string, string> = {
  ACTIVATE: "Bật quạt",
  DEACTIVATE: "Tắt quạt",
  PAIR: "Ghép thiết bị",
  UNPAIR: "Bỏ ghép",
  CONFIG: "Đổi cấu hình",
  COMMAND: "Gửi lệnh",
  STATUS: "Đổi trạng thái",
  THRESHOLD: "Cập nhật ngưỡng",
  ALERT: "Cảnh báo sớm",
  DELETE: "Xoá thiết bị",
};

function reason(entry: AuditEntry): string {
  if (!entry.detail) {
    return "";
  }
  try {
    const parsed = JSON.parse(entry.detail) as Record<string, unknown>;
    const parts = Object.entries(parsed)
      .filter(([key]) => key !== "published")
      .map(([key, value]) => `${key}=${String(value)}`);
    return parts.join(" · ");
  } catch {
    return entry.detail;
  }
}

/**
 * The decision timeline. Grouped by day with a left rail so "what happened at 14:03" is readable at
 * a glance — this is the artefact that proves the loop is closed.
 */
export function AuditTimeline({ entries }: Props) {
  const [actor, setActor] = useState<string>("ALL");

  const filtered = useMemo(
    () => (actor === "ALL" ? entries : entries.filter((entry) => entry.actor === actor)),
    [entries, actor],
  );

  return (
    <section className="card overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line/60 px-5 py-4">
        <div>
          <h2 className="text-sm font-medium">Dòng thời gian quyết định</h2>
          <p className="mt-0.5 text-xs text-muted">
            Lệnh của bạn, quyết định của backend và cập nhật từ AI — cùng một dòng thời gian
          </p>
        </div>
        <div className="flex gap-1" role="group" aria-label="Lọc theo nguồn">
          {["ALL", "BACKEND", "USER", "DEVICE", "AI"].map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={actor === value}
              onClick={() => setActor(value)}
              className={`rounded-md px-2 py-1 text-[11px] transition-colors ${
                actor === value ? "bg-line/70 text-white" : "text-muted hover:text-slate-200"
              }`}
            >
              {value === "ALL" ? "tất cả" : value.toLowerCase()}
            </button>
          ))}
        </div>
      </div>

      {filtered.length === 0 ? (
        <p className="px-5 py-6 text-sm text-muted">Chưa có bản ghi nào.</p>
      ) : (
        <ol className="max-h-[420px] divide-y divide-line/40 overflow-y-auto">
          {filtered.map((entry) => (
            <li key={entry.id} className="flex gap-3 px-5 py-2.5">
              <time
                className="w-32 shrink-0 font-mono text-[11px] text-muted"
                dateTime={entry.occurred_at}
                title={formatDateTime(entry.occurred_at)}
              >
                {formatDateTime(entry.occurred_at)}
              </time>
              <span
                className={`chip shrink-0 border text-[10px] uppercase ${
                  ACTOR_STYLE[entry.actor] ?? "border-line bg-canvas text-muted"
                }`}
              >
                {entry.actor}
              </span>
              <div className="min-w-0">
                <div className="text-sm text-slate-200">
                  {ACTION_LABEL[entry.action] ?? entry.action}
                  {entry.device_id && (
                    <span className="ml-2 font-mono text-[11px] text-muted">{entry.device_id}</span>
                  )}
                </div>
                {reason(entry) && (
                  <div className="truncate font-mono text-[11px] text-muted" title={reason(entry)}>
                    {reason(entry)}
                  </div>
                )}
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

/** Kept for pages that only have room for a list. */
export function AuditTable({ entries }: Props) {
  return (
    <section className="card overflow-hidden">
      <div className="px-5 py-4">
        <div className="label">Nhật ký</div>
        <p className="mt-1 text-xs text-muted">
          <Icon name="clock" size={12} className="mr-1 inline" />
          {entries.length} bản ghi gần nhất
        </p>
      </div>
      <AuditTimeline entries={entries} />
    </section>
  );
}
