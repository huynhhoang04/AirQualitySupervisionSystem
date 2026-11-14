import { useSearchParams } from "react-router-dom";

import type { AiAlert, AiModelStatus } from "../api/types";
import { useAiModels, useAlerts, useDevices, useSpaces } from "../hooks/useAqss";
import { formatAgo } from "../lib/room";

/**
 * Evaluation: what was measured, not what was hoped. Every table carries its sample count, and a
 * model that lost is shown with the reason it lost.
 */
export function EvaluationPage() {
  const [params, setParams] = useSearchParams();
  const devices = useDevices();
  const spaces = useSpaces();
  const deviceId = params.get("device_id") ?? devices.data?.[0]?.device_id ?? undefined;
  const device = devices.data?.find((item) => item.device_id === deviceId);
  const space = spaces.data?.find((item) => item.code === device?.space_code);
  const models = useAiModels(deviceId);
  const alerts = useAlerts(deviceId);

  const model = pickModel(models.data);

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-[11px] uppercase tracking-[0.14em] text-muted">Thí nghiệm & đánh giá</p>
          <h2 className="mt-1 text-lg font-semibold tracking-tight">{space?.name ?? device?.name ?? "—"}</h2>
          <p className="mt-0.5 font-mono text-[11px] text-muted">{deviceId ?? "chưa có thiết bị"}</p>
        </div>
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
      </header>

      {models.isLoading ? (
        <p className="text-sm text-muted">Đang tải kết quả…</p>
      ) : !model ? (
        <section className="card p-6">
          <h3 className="text-sm font-medium">Chưa có kết quả đánh giá</h3>
          <p className="mt-2 max-w-2xl text-sm leading-relaxed text-muted">
            Cần một model đã train trên dữ liệu node thật, với test window tách riêng. Hệ thống không
            điền số minh hoạ vào bảng này; khi chưa đủ dữ liệu, nó nói chưa đủ dữ liệu.
          </p>
        </section>
      ) : (
        <>
          <DataCard model={model} />
          <section className="grid gap-4 xl:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
            <BenchmarkPanel model={model} />
            <section className="card p-4 lg:p-5">
              <div className="label">Promotion</div>
              <div className="mt-3">
                <span
                  className={`chip border ${
                    model.status === "PRODUCTION"
                      ? "border-good/30 bg-good/10 text-good"
                      : "border-warn/30 bg-warn/10 text-warn"
                  }`}
                >
                  {model.status}
                </span>
              </div>
              <p className="mt-3 text-[13px] leading-relaxed text-slate-300">
                {model.promotion_reason ?? "Không có lý do được ghi."}
              </p>
              <dl className="mt-4 space-y-2 text-[12px]">
                <Row label="Feature" value={model.feature_version ?? "—"} mono />
                <Row label="Dataset hash" value={model.dataset_hash?.slice(0, 16) ?? "—"} mono />
                <Row label="Artifact hash" value={model.artifact_hash?.slice(0, 16) ?? "—"} mono />
                <Row label="Train cutoff" value={model.train_cutoff ?? "—"} mono />
              </dl>
              <p className="mt-4 rounded-lg border border-line/60 px-3 py-2 text-[11px] leading-relaxed text-muted">
                Model không thắng baseline vẫn được giữ và hiển thị. Hệ thống không biến “chưa tốt
                hơn” thành “không có kết quả”.
              </p>
            </section>
          </section>
        </>
      )}

      <section className="card overflow-hidden">
        <div className="border-b border-line/60 px-4 py-3">
          <div className="label">Cảnh báo</div>
        </div>
        <div className="divide-y divide-line/40">
          {alerts.data == null || alerts.data.length === 0 ? (
            <p className="px-4 py-6 text-sm text-muted">
              Chưa có cảnh báo nào được ghi. Cảnh báo chỉ được tạo khi hai forecast liên tiếp cùng
              vượt ngưỡng; một điểm lẻ không đủ.
            </p>
          ) : (
            alerts.data.map((alert) => <AlertRow key={alert.id} alert={alert} />)
          )}
        </div>
      </section>

      <section className="card p-4 lg:p-5">
        <div className="label">Giới hạn của lần đánh giá này</div>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-[12px] leading-relaxed text-muted">
          <li>Một node, một phòng; không có thiết bị tham chiếu PM2.5, RPM hay công suất điện.</li>
          <li>MAE đo khả năng dự báo số đo của chính hệ thống, không xác nhận độ đúng tuyệt đối của cảm biến.</li>
          <li>Origins chồng lấn theo thời gian nên không phải quan sát độc lập; khoảng bất định phải resample theo block ≥ 60 phút.</li>
          <li>Dữ liệu mô phỏng chỉ dùng để kiểm thử; không được đưa vào bảng metrics này.</li>
        </ul>
      </section>
    </div>
  );
}

function DataCard({ model }: { model: AiModelStatus }) {
  const metrics = (model.metrics ?? {}) as Record<string, unknown>;
  const data = (metrics.data ?? {}) as Record<string, unknown>;
  return (
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <Stat label="Raw packets" value={int(data.raw_rows)} />
      <Stat label="Minute buckets" value={int(data.minute_buckets)} />
      <Stat label="Khoảng quan sát" value={data.span_hours ? `${data.span_hours} giờ` : "—"} />
      <Stat label="Test window" value={data.test_hours ? `${data.test_hours} giờ cuối` : "—"} />
    </section>
  );
}

function BenchmarkPanel({ model }: { model: AiModelStatus }) {
  const metrics = (model.metrics ?? {}) as Record<string, Record<string, Record<string, number>>>;
  const test = metrics.test ?? {};
  const horizons = [
    { key: "5", label: "+5 phút" },
    { key: "15", label: "+15 phút" },
    { key: "30", label: "+30 phút" },
  ];
  return (
    <section className="card overflow-hidden">
      <div className="border-b border-line/60 px-4 py-3">
        <div className="label">Retrospective test</div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[560px] text-left text-[12px]">
          <thead className="text-[10px] uppercase tracking-wider text-muted">
            <tr className="border-b border-line/50">
              <th className="px-3 py-2">Horizon</th>
              <th className="px-3 py-2 text-right">MAE model</th>
              <th className="px-3 py-2 text-right">MAE Ridge</th>
              <th className="px-3 py-2 text-right">MAE persistence</th>
              <th className="px-3 py-2 text-right">Samples</th>
            </tr>
          </thead>
          <tbody>
            {horizons.map((horizon) => (
              <tr key={horizon.key} className="border-b border-line/40 last:border-0">
                <td className="px-3 py-2 text-slate-300">{horizon.label}</td>
                <td className="px-3 py-2 text-right font-mono text-slate-100">
                  {num(test.candidate?.[`mae_${horizon.key}min`])}
                </td>
                <td className="px-3 py-2 text-right font-mono text-slate-300">
                  {num(test.ridge?.[`mae_${horizon.key}min`])}
                </td>
                <td className="px-3 py-2 text-right font-mono text-muted">
                  {num(test.persistence?.[`mae_${horizon.key}min`])}
                </td>
                <td className="px-3 py-2 text-right font-mono text-muted">
                  {int(test.candidate?.[`samples_${horizon.key}min`])}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="px-3 py-2 text-[10px] leading-relaxed text-muted">
        MAE tính trên các origin có đủ target. Test chỉ được chấm một lần cho model đã đóng băng.
      </p>
    </section>
  );
}

function AlertRow({ alert }: { alert: AiAlert }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-2 px-4 py-3 text-[12px]">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className={alert.lifecycle === "ACTIVE" ? "text-warn" : "text-muted"}>
            {alert.type}
          </span>
          <span className="font-mono text-[10px] text-muted">{formatAgo(alert.created_at)}</span>
        </div>
        {alert.reason && <p className="mt-0.5 text-[11px] leading-relaxed text-muted">{alert.reason}</p>}
      </div>
      <div className="font-mono text-[11px] text-muted">
        {alert.horizon_minutes == null ? "—" : `+${alert.horizon_minutes} phút`} · ngưỡng{" "}
        {alert.threshold_snapshot == null ? "—" : alert.threshold_snapshot.toFixed(0)}
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="card px-4 py-3.5">
      <div className="label">{label}</div>
      <div className="mt-1 font-mono text-[20px] font-semibold leading-none text-white">{value}</div>
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

function pickModel(models?: AiModelStatus[]): AiModelStatus | undefined {
  if (!models || models.length === 0) return undefined;
  return models.find((model) => model.status === "PRODUCTION") ?? models[0];
}

function num(value: unknown): string {
  return typeof value === "number" && Number.isFinite(value) ? value.toFixed(2) : "—";
}

function int(value: unknown): string {
  return typeof value === "number" && Number.isFinite(value) ? Math.round(value).toLocaleString("vi-VN") : "—";
}
