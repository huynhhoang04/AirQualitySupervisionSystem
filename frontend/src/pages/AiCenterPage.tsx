import { useSearchParams } from "react-router-dom";

import type { AiJob, AiModelStatus } from "../api/types";
import {
  useAiJobs,
  useAiModels,
  useAiStatus,
  useCreateJob,
  useDevices,
  useSpaces,
  useSummary,
} from "../hooks/useAqss";
import { formatAgo } from "../lib/room";

/** Trung tâm AI: what the loop has learned, what it is running, and how to ask for a retrain. */
export function AiCenterPage() {
  const [params, setParams] = useSearchParams();
  const devices = useDevices();
  const spaces = useSpaces();
  const deviceId = params.get("device_id") ?? devices.data?.[0]?.device_id ?? undefined;
  const device = devices.data?.find((item) => item.device_id === deviceId);
  const space = spaces.data?.find((item) => item.code === device?.space_code);
  const status = useAiStatus(deviceId);
  const models = useAiModels(deviceId);
  const jobs = useAiJobs(deviceId);
  const summary = useSummary(deviceId, 24);
  const train = useCreateJob(deviceId);

  const current = status.data?.current_model ?? pickProduction(models.data) ?? null;

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-[11px] uppercase tracking-[0.14em] text-muted">Trung tâm AI</p>
          <h2 className="mt-1 text-lg font-semibold tracking-tight">
            {space?.name ?? device?.name ?? "—"}
          </h2>
          <p className="mt-0.5 font-mono text-[11px] text-muted">{deviceId ?? "chưa có thiết bị"}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <select
            value={deviceId ?? ""}
            onChange={(event) => setParams({ device_id: event.target.value })}
            className="field max-w-[240px] py-2 text-[12px]"
            aria-label="Chọn thiết bị"
          >
            {(devices.data ?? []).map((item) => (
              <option key={item.device_id} value={item.device_id}>
                {item.name ?? item.device_id}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="btn-primary px-3 py-2 text-[12px]"
            disabled={!deviceId || train.isPending}
            onClick={() => train.mutate("train")}
          >
            {train.isPending ? "Đang tạo job…" : "Train lại"}
          </button>
        </div>
      </header>

      {train.isError && (
        <p className="rounded-lg border border-bad/30 bg-bad/10 px-3 py-2 text-[12px] text-bad">
          {train.error.message}
        </p>
      )}
      {train.isSuccess && (
        <p className="rounded-lg border border-line/60 bg-surface px-3 py-2 text-[12px] text-slate-300">
          Đã xếp job <span className="font-mono">{train.data.id.slice(0, 8)}</span>. Worker sẽ nhận
          khi kết nối; nếu worker offline, job nằm chờ chứ không báo “đang tính”.
        </p>
      )}

      <section className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        <Stat
          label="Worker"
          value={status.data?.worker_online ? "online" : "offline"}
          note={status.data?.workers[0]?.last_heartbeat_at ? formatAgo(status.data.workers[0].last_heartbeat_at) : "chưa heartbeat"}
          tone={status.data?.worker_online ? "good" : "muted"}
        />
        <Stat
          label="Model phục vụ"
          value={current?.algorithm ?? "persistence"}
          note={current ? `${current.status} · ${current.feature_version ?? "feature-v1"}` : "baseline, chưa có model đạt cổng"}
        />
        <Stat
          label="Coverage 24h"
          value={summary.data ? `${Math.round(summary.data.coverage * 100)}%` : "—"}
          note={summary.data ? `${summary.data.pm_count} mẫu PM hợp lệ` : "chưa có dữ liệu"}
        />
        <Stat
          label="Telemetry cuối"
          value={formatAgo(status.data?.latest_telemetry_recorded_at)}
          note={`nhận ${formatAgo(status.data?.latest_telemetry_received_at)}`}
        />
      </section>

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)]">
        <section className="card p-4 lg:p-5">
          <div className="label">Model card</div>
          {current ? (
            <div className="mt-3 space-y-2.5 text-[13px]">
              <Row label="Version" value={current.version} mono />
              <Row label="Thuật toán" value={current.algorithm ?? "—"} />
              <Row label="Feature" value={current.feature_version ?? "—"} mono />
              <Row label="Train cutoff" value={current.train_cutoff ?? "—"} mono />
              <Row label="Trạng thái" value={current.status} />
              <Row label="Artifact" value={current.artifact_hash?.slice(0, 16) ?? "chưa có"} mono />
              {current.promotion_reason && (
                <p className="rounded-lg border border-line/60 px-3 py-2 text-[11px] leading-relaxed text-muted">
                  {current.promotion_reason}
                </p>
              )}
            </div>
          ) : (
            <div className="mt-3 rounded-xl border border-dashed border-line px-4 py-6 text-sm leading-relaxed text-muted">
              Chưa có model được promote. Worker vẫn phát hành forecast bằng <b>persistence</b>{" "}
              (PM hiện tại), và mọi kết quả đánh giá vẫn được lưu — kể cả khi chưa thắng baseline.
            </div>
          )}
        </section>

        <section className="card p-4 lg:p-5">
          <div className="label">Benchmark gần nhất</div>
          <BenchmarkTable model={current ?? models.data?.[0]} />
        </section>
      </div>

      <section className="card overflow-hidden">
        <div className="border-b border-line/60 px-4 py-3">
          <div className="label">Training jobs</div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[820px] text-left text-[12px]">
            <thead className="text-[10px] uppercase tracking-wider text-muted">
              <tr className="border-b border-line/50">
                <th className="px-3 py-2">Job</th>
                <th className="px-3 py-2">Loại</th>
                <th className="px-3 py-2">Trạng thái</th>
                <th className="px-3 py-2">Phase</th>
                <th className="px-3 py-2">Tiến độ</th>
                <th className="px-3 py-2">Tạo</th>
                <th className="px-3 py-2">Lỗi / kết quả</th>
              </tr>
            </thead>
            <tbody>
              {(jobs.data ?? []).length === 0 ? (
                <tr>
                  <td colSpan={7} className="px-3 py-6 text-center text-muted">
                    Chưa có job nào. Bấm “Train lại” để xếp một job.
                  </td>
                </tr>
              ) : (
                (jobs.data ?? []).map((job) => <JobRow key={job.id} job={job} />)
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="card overflow-hidden">
        <div className="border-b border-line/60 px-4 py-3">
          <div className="label">Model versions</div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[980px] text-left text-[12px]">
            <thead className="text-[10px] uppercase tracking-wider text-muted">
              <tr className="border-b border-line/50">
                <th className="px-3 py-2">Version</th>
                <th className="px-3 py-2">Thuật toán</th>
                <th className="px-3 py-2">MAE 5</th>
                <th className="px-3 py-2">MAE 15</th>
                <th className="px-3 py-2">MAE 30</th>
                <th className="px-3 py-2">Persistence 15</th>
                <th className="px-3 py-2">Trạng thái</th>
                <th className="px-3 py-2">Lý do</th>
              </tr>
            </thead>
            <tbody>
              {(models.data ?? []).length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-3 py-6 text-center text-muted">
                    Chưa có model. Train trên ít nhất 24 giờ dữ liệu hợp lệ.
                  </td>
                </tr>
              ) : (
                (models.data ?? []).map((model) => <ModelRow key={model.id} model={model} />)
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function Stat({
  label,
  value,
  note,
  tone = "default",
}: {
  label: string;
  value: string;
  note: string;
  tone?: "default" | "good" | "muted";
}) {
  return (
    <div className="card px-4 py-3.5">
      <div className="label">{label}</div>
      <div className={`mt-1 truncate font-mono text-[22px] font-semibold leading-none ${
        tone === "good" ? "text-good" : "text-white"
      }`}>
        {value}
      </div>
      <div className="mt-1 truncate text-[11px] text-muted">{note}</div>
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className="text-muted">{label}</span>
      <span className={`truncate text-right text-slate-200 ${mono ? "font-mono text-[11px]" : ""}`}>{value}</span>
    </div>
  );
}

function JobRow({ job }: { job: AiJob }) {
  return (
    <tr className="border-b border-line/40 last:border-0">
      <td className="px-3 py-2 font-mono text-[11px]">{job.id.slice(0, 8)}</td>
      <td className="px-3 py-2">{job.type}</td>
      <td className="px-3 py-2">
        <span className={jobTone(job.status)}>{job.status}</span>
      </td>
      <td className="px-3 py-2 text-muted">{job.phase ?? "—"}</td>
      <td className="px-3 py-2">
        <div className="flex items-center gap-2">
          <div className="h-1 w-20 overflow-hidden rounded-full bg-line/70">
            <div className="h-full rounded-full bg-info" style={{ width: `${job.progress}%` }} />
          </div>
          <span className="font-mono text-[10px] text-muted">{job.progress}%</span>
        </div>
      </td>
      <td className="px-3 py-2 text-muted">{formatAgo(job.created_at)}</td>
      <td className="max-w-[320px] px-3 py-2 text-[11px] text-muted">
        <span className={job.error ? "text-bad" : ""}>{job.error ?? summaryOf(job)}</span>
      </td>
    </tr>
  );
}

function ModelRow({ model }: { model: AiModelStatus }) {
  const metrics = (model.metrics ?? {}) as Record<string, unknown>;
  const test = (metrics.test ?? {}) as Record<string, Record<string, number>>;
  const candidate = test.candidate ?? {};
  const persistence = test.persistence ?? {};
  return (
    <tr className="border-b border-line/40 last:border-0">
      <td className="max-w-[260px] truncate px-3 py-2 font-mono text-[10px]" title={model.version}>
        {model.version}
      </td>
      <td className="px-3 py-2">{model.algorithm ?? "—"}</td>
      <td className="px-3 py-2 font-mono">{num(candidate.mae_5min)}</td>
      <td className="px-3 py-2 font-mono">{num(candidate.mae_15min)}</td>
      <td className="px-3 py-2 font-mono">{num(candidate.mae_30min)}</td>
      <td className="px-3 py-2 font-mono text-muted">{num(persistence.mae_15min)}</td>
      <td className="px-3 py-2">
        <span className={modelTone(model.status)}>{model.status}</span>
      </td>
      <td className="max-w-[280px] px-3 py-2 text-[11px] text-muted">{model.promotion_reason ?? "—"}</td>
    </tr>
  );
}

function BenchmarkTable({ model }: { model?: AiModelStatus }) {
  if (!model) {
    return (
      <p className="mt-3 text-sm leading-relaxed text-muted">
        Chưa có model để so sánh. Cần tối thiểu 24 giờ bucket hợp lệ, và một test window tách riêng.
      </p>
    );
  }
  const metrics = (model.metrics ?? {}) as Record<string, Record<string, Record<string, number>>>;
  const test = metrics.test ?? {};
  const rows = [
    { key: "mae_5min", label: "5 phút" },
    { key: "mae_15min", label: "15 phút" },
    { key: "mae_30min", label: "30 phút" },
  ];
  return (
    <div className="mt-3 overflow-x-auto">
      <table className="w-full text-left text-[12px]">
        <thead className="text-[10px] uppercase tracking-wider text-muted">
          <tr className="border-b border-line/50">
            <th className="py-2 pr-3">Horizon</th>
            <th className="py-2 pr-3 text-right">Model</th>
            <th className="py-2 pr-3 text-right">Ridge</th>
            <th className="py-2 text-right">Persistence</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key} className="border-b border-line/30 last:border-0">
              <td className="py-2 pr-3 text-slate-300">{row.label}</td>
              <td className="py-2 pr-3 text-right font-mono text-slate-100">{num(test.candidate?.[row.key])}</td>
              <td className="py-2 pr-3 text-right font-mono text-slate-300">{num(test.ridge?.[row.key])}</td>
              <td className="py-2 text-right font-mono text-muted">{num(test.persistence?.[row.key])}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="mt-2 text-[10px] leading-relaxed text-muted">
        MAE trên test window đã đóng băng. Số càng thấp càng tốt; model chỉ được promote khi
        mean(MAE15, MAE30) giảm ≥ 10% so với baseline tốt nhất và MAE5 không xấu hơn 5%.
      </p>
    </div>
  );
}

function pickProduction(models?: AiModelStatus[]): AiModelStatus | undefined {
  return models?.find((model) => model.status === "PRODUCTION");
}

function summaryOf(job: AiJob): string {
  if (!job.result) {
    return job.status === "QUEUED" ? "chờ worker" : "—";
  }
  const result = job.result as Record<string, unknown>;
  if (typeof result.status === "string") {
    return `hoàn tất · ${result.status}`;
  }
  return "hoàn tất";
}

function num(value: unknown): string {
  return typeof value === "number" && Number.isFinite(value) ? value.toFixed(2) : "—";
}

function jobTone(status: string): string {
  if (status === "FAILED") return "text-bad";
  if (status === "COMPLETED") return "text-good";
  if (status === "RUNNING") return "text-warn";
  return "text-muted";
}

function modelTone(status: string): string {
  if (status === "PRODUCTION") return "text-good";
  if (status === "REJECTED") return "text-warn";
  return "text-muted";
}
