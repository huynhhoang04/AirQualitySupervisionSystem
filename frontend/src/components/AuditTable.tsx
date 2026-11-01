import type { AuditEntry } from "../api/types";
import { formatDateTime } from "../lib/format";

interface Props {
  entries: AuditEntry[];
}

const ACTOR_STYLE: Record<string, string> = {
  USER: "bg-sky-500/15 text-sky-300",
  BACKEND: "bg-emerald-500/15 text-emerald-300",
  DEVICE: "bg-slate-600/25 text-slate-300",
  AI: "bg-violet-500/15 text-violet-300",
};

function summarise(entry: AuditEntry): string {
  if (!entry.detail) return "—";
  try {
    const parsed = JSON.parse(entry.detail) as Record<string, unknown>;
    return Object.entries(parsed)
      .map(([key, value]) => `${key}: ${String(value)}`)
      .join(" · ");
  } catch {
    return entry.detail;
  }
}

export function AuditTable({ entries }: Props) {
  return (
    <section className="card overflow-hidden">
      <div className="flex items-center justify-between px-5 py-4">
        <div>
          <div className="label">Nhật ký hệ thống</div>
          <div className="mt-1 text-xs text-slate-500">
            lệnh của bạn, quyết định của backend, trạng thái thiết bị và cập nhật từ AI
          </div>
        </div>
      </div>

      {entries.length === 0 ? (
        <div className="px-5 pb-6 text-sm text-slate-500">chưa có bản ghi</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="border-y border-line bg-slate-900/40 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-5 py-2 font-medium">Thời gian</th>
                <th className="px-3 py-2 font-medium">Nguồn</th>
                <th className="px-3 py-2 font-medium">Hành động</th>
                <th className="px-3 py-2 font-medium">Thiết bị</th>
                <th className="px-5 py-2 font-medium">Chi tiết</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.id} className="border-b border-line/60 last:border-0">
                  <td className="whitespace-nowrap px-5 py-2 text-xs text-slate-400">
                    {formatDateTime(entry.occurred_at)}
                  </td>
                  <td className="px-3 py-2">
                    <span
                      className={`rounded px-1.5 py-0.5 text-[11px] ${
                        ACTOR_STYLE[entry.actor] ?? "bg-slate-600/25 text-slate-300"
                      }`}
                    >
                      {entry.actor}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-slate-200">{entry.action}</td>
                  <td className="px-3 py-2 text-xs text-slate-400">{entry.device_id ?? "—"}</td>
                  <td className="px-5 py-2 text-xs text-slate-500">{summarise(entry)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
