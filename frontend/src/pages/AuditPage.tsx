import { useSearchParams } from "react-router-dom";

import { useAudit, useDevices } from "../hooks/useAqss";

/** The audit trail: user actions, backend decisions, device status, and AI updates in one list. */
export function AuditPage() {
  const [params, setParams] = useSearchParams();
  const devices = useDevices();
  const selected = params.get("device_id") ?? "";
  const audit = useAudit(selected || undefined, 150);

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-[11px] uppercase tracking-[0.14em] text-muted">Nhật ký</p>
          <h2 className="mt-1 text-lg font-semibold tracking-tight">Sự kiện và quyết định</h2>
        </div>
        <label className="flex items-center gap-2 text-[12px] text-muted">
          Thiết bị
          <select
            value={selected}
            onChange={(event) =>
              setParams(event.target.value ? { device_id: event.target.value } : {})
            }
            className="field max-w-[220px] py-2 text-[12px]"
          >
            <option value="">Tất cả</option>
            {(devices.data ?? []).map((device) => (
              <option key={device.device_id} value={device.device_id}>
                {device.name ?? device.device_id}
              </option>
            ))}
          </select>
        </label>
      </header>

      <section className="card overflow-hidden">
        <div className="border-b border-line/60 px-4 py-3">
          <div className="label">150 mục gần nhất</div>
        </div>
        {audit.isLoading ? (
          <p className="px-4 py-8 text-sm text-muted">Đang tải…</p>
        ) : audit.isError ? (
          <p className="px-4 py-8 text-sm text-bad">{audit.error.message}</p>
        ) : (audit.data ?? []).length === 0 ? (
          <p className="px-4 py-8 text-sm text-muted">Chưa có bản ghi nào.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px] text-left text-[12px]">
              <thead className="text-[10px] uppercase tracking-wider text-muted">
                <tr className="border-b border-line/50">
                  <th className="px-3 py-2">Thời điểm</th>
                  <th className="px-3 py-2">Actor</th>
                  <th className="px-3 py-2">Hành động</th>
                  <th className="px-3 py-2">Thiết bị</th>
                  <th className="px-3 py-2">Chi tiết</th>
                </tr>
              </thead>
              <tbody>
                {(audit.data ?? []).map((entry) => (
                  <tr key={entry.id} className="border-b border-line/40 last:border-0 align-top">
                    <td className="whitespace-nowrap px-3 py-2 font-mono text-[11px] text-slate-300">
                      {new Date(entry.occurred_at).toLocaleString("vi-VN")}
                    </td>
                    <td className="px-3 py-2">
                      <span className={actorTone(entry.actor)}>{entry.actor}</span>
                    </td>
                    <td className="whitespace-nowrap px-3 py-2">{entry.action}</td>
                    <td className="whitespace-nowrap px-3 py-2 font-mono text-[11px] text-muted">
                      {entry.device_id ?? "—"}
                    </td>
                    <td className="max-w-[460px] px-3 py-2">
                      <code className="block whitespace-pre-wrap break-words font-mono text-[10px] leading-relaxed text-muted">
                        {pretty(entry.detail)}
                      </code>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <p className="text-[11px] leading-relaxed text-muted">
        Nhật ký trả lời “hệ thống đã làm gì”, không phải “cảm biến đúng tuyệt đối”. Trạng thái quạt
        được báo bởi thiết bị nằm ở trang phòng và lịch sử actuator.
      </p>
    </div>
  );
}

function actorTone(actor: string): string {
  if (actor === "BACKEND") return "text-info";
  if (actor === "USER") return "text-slate-200";
  if (actor === "DEVICE") return "text-good";
  if (actor === "AI") return "text-warn";
  return "text-muted";
}

function pretty(detail: string | null): string {
  if (!detail) return "—";
  try {
    return JSON.stringify(JSON.parse(detail), null, 1);
  } catch {
    return detail;
  }
}
