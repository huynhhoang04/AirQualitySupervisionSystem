# 10 — Bước 4: nối Chronos vào worker và đường dự báo 360 bước

Ngày: 18/11/2026. **Đã triển khai và đo trên máy hiện tại.** Phần chưa kiểm chứng được liệt kê
riêng ở §6; không có số nào trong tài liệu này là cam kết về độ chính xác trên thiết bị thật.

Đọc cùng [07 — Dashboard AI trực tiếp](07-ai-live-dashboard-v2.md),
[09 — Benchmark và training](09-step3-benchmark-and-training.md) và
[hợp đồng dữ liệu §14](../io_contract.md).

## 1. Đã thay đổi gì

| Lớp | Thay đổi |
| :--- | :--- |
| Contract | §14: bảng `forecast_paths`, `path` trong upload/read, endpoint incremental cho worker |
| Database | Flyway `V7__forecast_paths.sql` — một `REAL[360]` mỗi run, không phải 360 hàng |
| Backend | Nhận/validate path, trả path trong `/forecast` và SSE `forecast`, `GET /forecast/path`, retention 48 h |
| Backend | `/internal/v1/ai/devices/{id}/samples` và `/actuator-reports` với cursor `after_id` |
| Worker | `ContextCache` (nạp một lần rồi đọc gia tăng) và `ChronosRunner` (một tác vụ/device, gộp yêu cầu) |
| Worker | `FORECAST_ENGINE=chronos` bật engine mới; mặc định vẫn `sklearn` |
| Frontend | Chart vẽ đường 360 bước + dải 10/90, thinned khi hiển thị; curve cũ không được vẽ lùi |

## 2. Vì sao lưu bằng mảng

Một run mỗi 5 giây là **17.280 run/ngày/device** và **6.220.800 giá trị/ngày/device**. Lưu mỗi giá
trị thành một hàng là 6,2 triệu hàng/ngày/device — không truy vấn nổi và không cần thiết.

`forecast_paths` giữ một hàng mỗi run: `anchor_at`, `anchor_pm25`, `step_seconds`, `values REAL[360]`
và dải `q10`/`q90` tuỳ chọn. Mốc thứ `k` suy ra từ `anchor_at + k × step_seconds`, nên không có 360
timestamp lặp lại trong mỗi bản ghi hay mỗi SSE message.

Ba mốc 5/15/30 phút vẫn nằm trong `forecast_points` vì đó là bằng chứng cho ô độ khớp, và được giữ
lâu hơn đường cong.

## 3. Hợp đồng upload

- `path.values` phải có đúng `1800 / step_seconds` phần tử. Sai độ dài → **400**, không cắt bớt.
- `path.anchor_at` phải bằng `input_cutoff` khi cả hai có mặt.
- `step_seconds ≤ 0` → 400.
- `q10`/`q90` nếu có cũng phải đúng độ dài.
- Upload trùng (cùng device + model + `input_cutoff`) trả run đã lưu và **không** phát SSE lần hai,
  nên một lần retry không làm chart nhảy lùi.
- Upload không có `path` vẫn hợp lệ: đường dự báo quay về 3 mốc.

Đã kiểm bằng API thật (Postgres local, Flyway V7):

| Kiểm tra | Kết quả |
| :--- | :--- |
| Upload 360 giá trị | 200, đọc lại `values` = 360, anchor/step giữ nguyên |
| `GET /forecast` | `path` có mặt, 360 giá trị |
| `GET /forecast/path` | 200, đúng run mới nhất |
| Upload trùng | trả **cùng** run id, không tạo hàng mới |
| Độ dài 200 | 400, rollback sạch (số run không đổi) |
| `anchor_at ≠ input_cutoff` | 400, rollback sạch |
| `samples?after_id` | trả đúng phần sau cursor, `next_after_id`/`high_water_id` đúng |
| `actuator-reports?after_id` khi rỗng | 200 với `items: []` (xem §5) |

## 4. Worker: cache và nhịp

`ContextCache` nạp cửa sổ 90 phút **một lần**, ghi nhớ id lớn nhất của mỗi luồng, sau đó chỉ hỏi
phần chưa thấy qua `after_id`. Id là cursor thay vì timestamp vì hai mẫu có thể cùng mili-giây.

`ChronosRunner` giữ đúng luật của 07 §3: **một tác vụ suy luận mỗi device**, trong lúc chạy chỉ giữ
một yêu cầu mới nhất, và đếm số lần gộp để "theo kịp 5 giây" là số đo chứ không phải lời khẳng định.

Nếu Chronos không load được (không có CUDA, thiếu package), runner ghi lý do vào heartbeat và để
worker tiếp tục — đo lường, điều khiển và ngưỡng không phụ thuộc process này.

## 5. Lỗi tìm được trong lúc kiểm

Hai lỗi thật trong đợt đầu, đều có test hồi quy:

1. **Cache nhân đôi mẫu khi cursor lùi.** Backend trả lại các dòng trong khoảng giao một cách trung
   thực, và cache cũ `append` thẳng → cùng một giây xuất hiện hai lần trong context. Sửa: gộp theo
   `id` (`ContextCache._merge`). Test: `tests/test_context_cache.py`.
2. **`actuator-reports` trả 500 khi chưa có báo cáo quạt.** `next_after_id` là `Long` null bị
   auto-unbox vào trường `long` của response. Một node mới chưa từng báo quạt là trạng thái bình
   thường, không phải lỗi. Sửa: trả cursor cũ thay vì unbox. Test:
   `backend/src/test/java/.../IncrementalReadTest.java`.

Cả hai lỗi chỉ lộ ra khi chạy với dữ liệu thật; unit test viết trước đó vẫn xanh vì chúng không đi
qua đường biên đó.

## 5b. Bảy lỗi được báo sau đó, và cách sửa

### [P1] Chronos không dự báo dù thiết bị gửi đều

**Nguyên nhân:** căn lưới dùng `reindex(..., tolerance=step/2)` với hướng "as-of" một chiều. Gói cách
nhau 5,02 s trôi 0,02 s mỗi gói, nên chỉ khoảng một nửa số gói rơi vào dung sai 2,5 s → coverage
~52 %, dưới ngưỡng 80 %, không phát dự báo.

**Sửa:** `_align_to_grid` gán mỗi mẫu vào **điểm lưới gần nhất** (bán kính ±nửa bước), giữ mẫu mới
nhất trong mỗi ô. Đo lại: 5,02 s → **99,6 %**; 5,2 s → 96,1 %; 4,98 s → 99,6 %.

**Phát hiện thêm khi sửa:** coverage một mình vẫn cho lọt lỗ 10 phút (120/720 bước mất = 83,3 %).
Thêm luật thứ hai: **lỗ dài nhất ≤ 60 bước (5 phút)**. Đo lại: lỗ 10 phút bị từ chối, lỗ 5,8 phút bị
từ chối, lỗ 4,2 phút được chấp nhận. Test: `tests/test_chronos_alignment.py`.

### [P1] Độ khớp tính cả mẫu cảm biến lỗi/bão hòa

**Nguyên nhân:** evaluator chỉ kiểm tra `pm25 != null`. Một mẫu `pm25_saturated` (giá trị sàn) hoặc
`dust_fault` (cảm biến hỏng) vẫn được chấm `SCORED`.

**Sửa:** `nearestSample` lọc qua `isUsableReading` — cùng luật với đường ingest và pipeline AI.
Kiểm trên Postgres thật: mẫu saturated 500 và mẫu dust-fault 22 trong dung sai → point vẫn `PENDING`;
thêm một mẫu lành 24,5 → chấm đúng 24,5 (không phải 500, không phải 22). Test:
`ForecastEvaluatorTest` (4 test mới).

### [P1] Mất gói giữa snapshot và SSE

**Nguyên nhân:** `LiveController.snapshot` đọc `live.cursor()` **sau** khi truy vấn samples. Event
phát sinh giữa hai bước không có trong snapshot (chưa kịp lưu) và cũng không được replay (id ≤
cursor).

**Sửa:** chốt cursor **trước** truy vấn. Client dedupe theo sample id nên dòng xuất hiện ở cả hai
được vẽ một lần. Kiểm trên server thật: snapshot cursor = 1, một run phát sinh sau đó có id 2 và
đến được stream. Test: `LiveSnapshotCursorOrderTest` (kiểm tra thứ tự gọi).

### [P1] Chart không nối tại mẫu mới nhất

**Nguyên nhân:** đường dự báo bắt đầu tại `path.anchor_at` (mốc model dùng). Khi mẫu mới 70 µg/m³ về
trong lúc forecast cũ (anchor 25) chưa được thay, chart vẽ 25 tại thời điểm của mẫu 70.

**Sửa:** `buildChartRows` luôn đặt điểm bắt đầu đường dự báo tại **mẫu đo mới nhất**, với giá trị của
mẫu đó; các giá trị curve cũ ở sau mốc mới vẫn giữ nguyên (đoạn nối là hiển thị, không phải giá trị
forecast được lưu). Test: `frontend/tests/chartRows.test.ts`.

### [P2] Chart nối qua khoảng mất mẫu

**Nguyên nhân:** dữ liệu không có điểm null giữa hai mẫu, nên `connectNulls={false}` không có gì để
ngắt.

**Sửa:** chèn null khi hai mẫu liên tiếp cách nhau quá ngưỡng. Ngưỡng suy từ nhịp của chính chuỗi
(tứ phân vị dưới × 3, sàn 15 s) chứ không hard-code 5 s, để node cấu hình 10 s không bị ngắt mỗi gói.
Test đầu tiên của tôi cho thấy median sai — với đúng hai mẫu cách 10 phút, median *chính là* 10 phút;
đã sửa sang tứ phân vị dưới + fallback khi có dưới 2 khoảng.

### [P2] Nhánh Chronos không tạo cảnh báo/đề xuất

**Sửa:** `_payload` gọi lại `recommendations()` của engine sklearn và gắn vào
`data_quality.recommendations`. `data_quality` có thêm `co2_at_origin` (lấy từ covariate tại anchor,
`null` nếu SCD40 chưa có số) để rule thông gió hoạt động. Kiểm: breach hiện tại → `current_breach`;
dự báo vượt ngưỡng → `forecast_breach`; CO₂ 1200 → thêm `ventilation`; không có ngưỡng → không kết
luận gì. Test: `tests/test_chronos_runner.py`.

### [P2] Mức quạt bị bỏ khỏi input

**Nguyên nhân:** telemetry không chứa `fan_level` (node báo ở topic riêng) và `_build()` chỉ gọi
`cache.rows()`, nên cột fan toàn `NaN`.

**Sửa:** `ContextCache.rows_with_fan` join as-of theo **thời điểm nhận** của report quạt; mẫu cũ hơn
report đầu tiên giữ `NaN` chứ không đoán. `_build()` dùng hàm này. Đo: 720 report mức 3 → **720/720**
bước có fan_level (trước là 0/720).

### Tổng kết kiểm thử

| Tầng | Test |
| :--- | ---: |
| Backend (JUnit) | 52 |
| AI service (pytest) | 63 |
| Frontend (node:test) | 9 |

`npm test` được thêm vào `frontend/package.json`; `tsconfig.test.json` typecheck riêng thư mục
`tests/` vì `tsconfig.app.json` chỉ gồm `src/`.

## 6. Chưa kiểm chứng

- [ ] Chạy `FORECAST_ENGINE=chronos` liên tục với node thật và đo end-to-end sensor → chart.
- [ ] P95 của cả vòng (đọc cache + inference + upload + SSE) dưới 5 giây. Đã đo riêng inference:
      17 ms. Phần mạng chưa đo.
- [ ] Chạy 10 phút liên tục ở nhịp 5 giây để kiểm tra VRAM và số yêu cầu bị gộp.
- [ ] Retention 48 h dưới tải thật (job đã có, mới chạy trên bảng rỗng).
- [ ] Adapter LoRA hiện **chưa được promote** (09 §3): engine Chronos đang dùng checkpoint
      pretrained. Bật adapter là một quyết định riêng sau khi có dữ liệu thật.
- [ ] Ảnh hưởng của luật chặn lỗ 5 phút lên tỉ lệ phát dự báo trên node thật: nếu firmware hay mất
      gói theo cụm, dự báo sẽ bị từ chối thường xuyên hơn. Cần số đo trước khi nới ngưỡng.

## 7. Cách bật

```bash
cd ai-service
FORECAST_ENGINE=chronos \
CHRONOS_MODEL=amazon/chronos-2 \
CHRONOS_INFERENCE_SECONDS=5 \
BACKEND_URL=http://localhost:8080 \
PYTHONPATH=. .venv-chronos/bin/python -m app.worker_entry
```

Biến môi trường mới:

| Biến | Mặc định | Ý nghĩa |
| :--- | :--- | :--- |
| `FORECAST_ENGINE` | `sklearn` | `chronos` bật đường 360 bước |
| `CHRONOS_MODEL` | `amazon/chronos-2` | Checkpoint hoặc thư mục adapter |
| `CHRONOS_ADAPTER` | rỗng | Thư mục adapter LoRA, nếu muốn dùng |
| `CHRONOS_INFERENCE_SECONDS` | `5` | Nhịp suy luận của engine Chronos |
| `AI_PATH_RETENTION_HOURS` | `48` | Backend: tuổi tối đa của đường cong (`0` = không xoá) |

## 8. Bước tiếp theo

1. Đo end-to-end trên node thật và ghi thành cohort riêng, tách khỏi số tổng hợp.
2. Chạy dài 10 phút để có P95 và số yêu cầu bị gộp thực tế.
3. Chỉ sau đó mới bàn chuyện promote adapter dựa trên dữ liệu thật.
