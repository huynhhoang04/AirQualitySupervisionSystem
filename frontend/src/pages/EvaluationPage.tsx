import { useSearchParams } from "react-router-dom";

import type { AiAlert, AiModelStatus } from "../api/types";
import { useAiModels, useAlerts, useDevices, useForecast, useForecastAccuracy, useSpaces } from "../hooks/useAqss";
import { formatAgo } from "../lib/room";
import { modelLabel } from "../lib/modelLabel";

/**
 * Two different questions, two different sections. Confusing them is what made this page unreadable.
 *
 * 1. "Is the model that is running right now any good?" — answered by scoring the forecasts it
 *    already published against the measurements that arrived after them. Live, always moving.
 * 2. "Was the sklearn model we tried worth keeping?" — answered by a frozen test window, scored
 *    once. It lost, so Chronos-2 serves instead. This is evidence for a report, not an operation.
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
  const forecast = useForecast(deviceId);
  // Same horizon as the room page: the product forecasts 30 minutes, so 30 minutes is what is
  // scored here. This page used to ask for +5, which produced dashes while the room showed numbers.
  const live = useForecastAccuracy(deviceId, 30, 6);

  const model = pickModel(models.data);

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-[11px] uppercase tracking-[0.14em] text-muted">Thí nghiệm &amp; đánh giá</p>
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

      <LiveSection accuracy={live.data} forecastRun={forecast.data?.run} />

      {models.isLoading ? (
        <p className="text-sm text-muted">Đang tải kết quả…</p>
      ) : !model ? (
        <section className="card p-5">
          <div className="label">Model sklearn đã thử</div>
          <h3 className="mt-1.5 text-sm font-medium">Chưa từng có model sklearn nào để đánh giá</h3>
          <p className="mt-2 max-w-2xl text-[12px] leading-relaxed text-muted">
            Hệ thống có hai đường dự báo. Đường đang chạy là Chronos-2, đánh giá ngay ở khối trên.
            Đường còn lại là một model sklearn (<span className="font-mono">feature-v2</span>) huấn
            luyện trên dữ liệu node thật, và nó chưa từng được train xong trên node này — nên không có
            test window nào để chấm. Không có gì bị hỏng.
          </p>
        </section>
      ) : (
        <>
          <DataCard model={model} />
          <section className="grid gap-4 xl:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
            <BenchmarkPanel model={model} />
            <section className="card p-4 lg:p-5">
              <div className="label">Có được dùng làm model chính không?</div>
              <div className="mt-2 flex items-center gap-2">
                <span
                  className={`chip border ${
                    model.status === "PRODUCTION"
                      ? "border-good/30 bg-good/10 text-good"
                      : "border-warn/30 bg-warn/10 text-warn"
                  }`}
                >
                  {model.status === "PRODUCTION" ? "Đang dùng" : "Không dùng"}
                </span>
                <span className="font-mono text-[10px] text-muted">{model.status}</span>
              </div>
              <p className="mt-3 text-[13px] leading-relaxed text-slate-300">
                {model.promotion_reason ?? "Không có lý do được ghi."}
              </p>
              <p className="mt-2 text-[11px] leading-relaxed text-muted">
                Muốn thay model đang chạy, nó phải giảm sai số ở mốc 15 và 30 phút ít nhất 10% so với
                bản trước, và không được làm mốc 5 phút xấu đi quá 5%. Đây là cổng chặn để một lần
                train chưa tốt không âm thầm thay model đang phục vụ.
              </p>
              <dl className="mt-4 space-y-2 text-[12px]">
                <Row label="Feature" value={model.feature_version ?? "—"} mono />
                <Row label="Dataset hash" value={model.dataset_hash?.slice(0, 16) ?? "—"} mono />
                <Row label="Artifact hash" value={model.artifact_hash?.slice(0, 16) ?? "—"} mono />
                <Row label="Train cutoff" value={model.train_cutoff ?? "—"} mono />
              </dl>
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

/**
 * "Is the model running right now any good?"
 *
 * Every forecast is stored before its target time, then scored with the measurement that arrives
 * afterwards. Nothing here is a frozen test set — it is the live record of the model being right or
 * wrong, and it changes as time passes. When there is nothing to show, this says why instead of
 * printing three dashes and letting the reader guess.
 */
function LiveSection({
  accuracy,
  forecastRun,
}: {
  accuracy?: import("../api/types").ForecastAccuracy;
  forecastRun?: import("../api/types").ForecastRun | null;
}) {
  const matched = accuracy?.matched ?? 0;
  const hasNumbers = matched > 0 && accuracy?.mae_ug_m3 != null;
  const readiness = forecastRun?.data_quality?.readiness;

  return (
    <section className="card p-4 lg:p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="label">Model đang chạy — dự báo 30 phút đúng đến đâu</div>
        <span className="font-mono text-[11px] text-slate-300">{modelLabel(forecastRun)}</span>
      </div>

      <p className="mt-1.5 max-w-3xl text-[11px] leading-relaxed text-muted">
        Mỗi dự báo được lưu lại <em>trước</em> thời điểm nó nói tới. Khi 30 phút trôi qua, hệ thống
        lấy số đo thật lúc đó để chấm. Vì vậy đây là bằng chứng model đúng hay sai trên dữ liệu thật,
        không phải một bài test đóng băng.
      </p>

      {hasNumbers ? (
        <>
          <dl className="mt-3 grid grid-cols-2 gap-4 sm:grid-cols-4">
            <div>
              <dt className="text-[10px] uppercase tracking-wider text-muted">Lệch trung bình</dt>
              <dd className="font-mono text-lg text-slate-100">
                {accuracy!.mae_ug_m3!.toFixed(1)}
                <span className="ml-1 font-sans text-[10px] text-muted">µg/m³</span>
              </dd>
            </div>
            <div>
              <dt className="text-[10px] uppercase tracking-wider text-muted">Trong ±5 µg/m³</dt>
              <dd className="font-mono text-lg text-slate-100">
                {accuracy!.within_tolerance_pct == null
                  ? "—"
                  : `${accuracy!.within_tolerance_pct.toFixed(0)}%`}
              </dd>
            </div>
            <div>
              <dt className="text-[10px] uppercase tracking-wider text-muted">
                Trong ±{Math.round((accuracy!.relative_tolerance ?? 0.3) * 100)}%
              </dt>
              <dd className="font-mono text-lg text-slate-100">
                {accuracy!.within_relative_pct == null
                  ? "—"
                  : `${accuracy!.within_relative_pct.toFixed(0)}%`}
              </dd>
            </div>
            <div>
              <dt className="text-[10px] uppercase tracking-wider text-muted">Mốc đã chấm</dt>
              <dd className="font-mono text-lg text-slate-100">{matched}</dd>
            </div>
          </dl>
          <p className="mt-2 text-[11px] text-muted">
            {matched} dự báo đã đủ 30 phút để chấm · 6 giờ gần nhất
          </p>
        </>
      ) : (
        <div className="mt-3 rounded-lg border border-line/60 px-3 py-2.5">
          <p className="text-[12px] leading-relaxed text-slate-200">
            {forecastRun
              ? "Chưa có dự báo nào đủ 30 phút để chấm trong 6 giờ qua."
              : "Chưa có dự báo nào được phát hành cho phòng này."}
          </p>
          <p className="mt-1 text-[11px] leading-relaxed text-muted">
            Cần hai việc: worker AI phải đang phát dự báo, và mỗi dự báo phải chờ 30 phút trước khi
            chấm được. Nếu node vừa khởi động lại, nó cần ít nhất 15 phút số đo liên tục trước khi
            phát dự báo đầu tiên.
            {accuracy && accuracy.pending > 0 && ` Hiện có ${accuracy.pending} dự báo đang chờ đủ 30 phút.`}
          </p>
        </div>
      )}

      {readiness === "warmup" && (
        <p className="mt-2 text-[11px] text-warn">
          Dự báo gần nhất chạy ở chế độ warm-up: mới có 15–60 phút lịch sử, chưa đủ context một giờ.
        </p>
      )}
    </section>
  );
}

function DataCard({ model }: { model: AiModelStatus }) {
  const metrics = (model.metrics ?? {}) as Record<string, unknown>;
  const data = (metrics.data ?? {}) as Record<string, unknown>;
  return (
    <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <Stat label="Gói thô đã nhận" value={int(data.raw_rows)} />
      <Stat label="Phút đo hợp lệ" value={int(data.minute_buckets)} />
      <Stat label="Khoảng quan sát" value={data.span_hours ? `${data.span_hours} giờ` : "—"} />
      <Stat label="Giữ riêng để chấm" value={data.test_hours ? `${data.test_hours} giờ cuối` : "—"} />
    </section>
  );
}

/**
 * "Was the sklearn model we tried worth keeping?"
 *
 * This is a frozen test window, scored once — evidence for a report, not a live measurement. The
 * "persistence" column is not a model: it is the do-nothing baseline, repeat the current reading for
 * the whole horizon. A model that cannot beat it adds nothing, however small its own error looks.
 * That comparison is the point of the table, so the column is labelled instead of being left to guess.
 */
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
        <div className="label">Model sklearn đã thử — chấm trên test window đóng băng</div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-[12px]">
          <thead className="text-[10px] uppercase tracking-wider text-muted">
            <tr className="border-b border-line/50">
              <th className="whitespace-nowrap px-3 py-2">Dự báo trước</th>
              <th className="whitespace-nowrap px-3 py-2 text-right">Model học</th>
              <th className="whitespace-nowrap px-3 py-2 text-right">Ridge</th>
              <th className="whitespace-nowrap px-3 py-2 text-right">Giữ nguyên</th>
              <th className="whitespace-nowrap px-3 py-2 text-right">Mẫu</th>
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
        MAE = sai lệch tuyệt đối trung bình, µg/m³, càng thấp càng tốt. Cột “giữ nguyên số đo” là
        baseline không học gì: nó lặp lại số hiện tại cho cả 30 phút. Model nào không thắng được cột
        đó thì không đóng góp thông tin, dù sai số của nó nhìn có vẻ nhỏ.
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
