# 09 — Bước 3: benchmark Chronos-2, dữ liệu tổng hợp và fine-tune LoRA

Ngày: 18/11/2026. **Kết quả đo trên máy hiện tại.** Không có số nào trong tài liệu này là cam kết
trước khi đo; mọi con số đều lấy từ file manifest trong `ai-service/artifacts/` và
`ai-service/models/chronos-lora/manifest.json`.

Đọc cùng [07 — Dashboard AI trực tiếp](07-ai-live-dashboard-v2.md) và
[08 — Dữ liệu, phân tích và huấn luyện](08-ai-data-analysis-and-training.md).

## 1. Benchmark (cổng nghiệm thu của 07 §4)

| Hạng mục | GPU | CPU |
| :--- | :--- | :--- |
| Thiết bị | RTX 3050 Laptop, 3.768 MiB khả dụng | cùng máy |
| Model | `amazon/chronos-2`, 119,5 M tham số | giống |
| Context → prediction | 720 → 360 bước 5 giây | giống |
| Latency P50 | **0,017 s** | 0,090 s |
| Latency P95 | **0,017 s** | 0,092 s |
| VRAM đỉnh | **466 MiB** | — |
| Thời gian load | 1,0 s (sau khi cache) | 0,9 s |

Cổng đặt ra là P95 ≤ 3 s cho inference warm. **Đạt, dư biên rất lớn** — GPU chỉ dùng 466/3.768 MiB,
nên còn chỗ cho serving + training cùng lúc nếu cần.

Kiểm tra tính đúng đắn (không chỉ đo tốc độ): chuỗi phẳng 50 µg/m³ → dự báo phẳng 50; chuỗi tăng
20→60 → dự báo tiếp 60→80. Model thực sự đọc context, không trả hằng số.

Lưu ý cách đo: mỗi lần lặp dùng **input khác nhau** và `torch.cuda.synchronize()` trước khi lấy
thời gian, nên con số không phải cache hit.

## 2. Dữ liệu tổng hợp

Sinh theo `configs/air_quality_synthetic.yaml`, seed `20261114`:

| Split | Phiên | Dòng | Shard |
| :--- | ---: | ---: | ---: |
| train | 1.400 | 24.192.000 | 1.400 |
| validation | 300 | 5.184.000 | 300 |
| test | 300 | 5.184.000 | 300 |
| **tổng** | **2.000** | **34.560.000** | 2.000 |

- 17.280 bước × 5 giây mỗi phiên 24 giờ, đúng như plan 08 §4.
- 10 kịch bản: `background`, `dust_ramp`, `dust_pulse`, `fan_policy`, `ventilation_shift`,
  `occupied_room`, `sensor_faults`, `mixed`, và hai kịch bản **chỉ có ở test** (`unseen_spike`,
  `long_gap`).
- Split theo **phiên nguyên**, không cắt cửa sổ chồng lấn giữa các tập.
- Dung lượng 1,4 GB Parquet (zstd), đọc lazy từng shard.
- Sinh lại được bằng seed; mỗi shard có `sha256` trong `manifest.json`.

### Vật lý của generator

Cân bằng khối lượng `C_next = max(0, C + dt·(G/V + a·(C_out − C) − k(level)·C) + noise)`, với
`dt = 5/3600` giờ. Kiểm chứng trực tiếp: với nguồn 20 µg/(m³·h), 30 phút, PM đi từ 50 xuống
38,2 (k=1), 16,3 (k=3); không có nguồn thì 50 → 30,3 (k=1), 11,1 (k=3).

CO₂ có nguồn theo người và mất do thông gió; **HEPA không bao giờ làm giảm CO₂** trong generator.
`k(level)` là **tham số kịch bản**, không phải CADR đo được của quạt thật.

### EDA (plan 08 §6)

`reports/eda/eda.json` + 5 hình:

| Nội dung | Kết quả |
| :--- | :--- |
| Coverage PM hợp lệ | trung vị 99,3 %, thấp nhất 94,4 % |
| Nhịp gói | P50 = P95 = 5,0 s; 0 % khoảng > 10 s |
| ACF PM (5 s) | lag 1 = 0,21; lag 60 (5 phút) = 0,18; lag 720 (60 phút) = 0,02 |
| Tương quan PM–fan | Pearson −0,36 (quạt chạy nhiều khi PM cao — **feedback**, không phải nhân quả) |
| Tương quan PM–CO₂ | Pearson −0,18 |
| Slope PM trước/sau khi tăng quạt | +0,089 → **−0,245** µg/m³/phút |

Hình `fan-episodes.png` cố ý vẽ **độ dốc** chứ không vẽ trung bình trước/sau: nguồn bụi làm quạt
bật thường vẫn đang hoạt động, nên trung bình hai bên đều cao và dễ bị đọc thành "quạt làm bụi
tăng". Độ dốc trả lời đúng câu hỏi "phòng đang đầy lên hay đang được lọc".

## 3. Fine-tune LoRA

Cấu hình chạy: LoRA r=8, alpha=16, dropout 0,05, context 720, prediction 360, learning rate
`1e-5`, 500 optimizer steps, batch 8 × gradient accumulation 4, FP16, một GPU.

| Hạng mục | Giá trị |
| :--- | :--- |
| Tham số trainable | 589.824 / 120.067.488 (**0,49 %**) |
| Thời gian train | 171 s |
| Adapter | `models/chronos-lora/trainer/adapter/adapter_model.safetensors` (2,3 MB) |
| SHA-256 | `d67f566c0c9bdf7694e47721ddf1eab81b2afec1d4dc6adcbe561b36808137b7` |
| Windows | 1.500 train / 60 validation / 120 test |
| VRAM | vừa 3.768 MiB với batch 8 × accum 4 |

### Kết quả

Test split đóng băng, chấm một lần cho cả ba model:

| Model | MAE 5 phút | MAE 15 phút | MAE 30 phút | Trung bình 15/30 |
| :--- | ---: | ---: | ---: | ---: |
| Persistence | 2,186 | 3,202 | 3,857 | 3,529 |
| Chronos-2 pretrained | **1,534** | 2,794 | 3,810 | 3,302 |
| Chronos-2 + LoRA | 1,561 | **2,721** | **3,669** | **3,195** |

Đơn vị µg/m³, nguồn: `models/chronos-lora/manifest.json`.

Lần chấm độc lập thứ hai (`tools/evaluate_chronos.py`, 90 windows từ 30 phiên) cho cùng kết luận:
gain **3,42 %** trên trung bình MAE(15,30) ở test, MAE 5 phút cải thiện nhẹ (−1,03 % regression,
tức là tốt hơn). Hai cohort khác nhau nên hai con số không giống hệt — đó là lý do mỗi lần chấm
phải ghi số windows và số phiên.

### Cổng promotion: **REJECTED**

Validation cải thiện **4,2 %** trên trung bình MAE(15,30); ngưỡng của plan 08 §8 là **≥ 10 %**.
MAE 5 phút không xấu đi quá 5 %. Vì vậy:

- Adapter **được lưu** và báo cáo, không bị xoá.
- Model phục vụ vẫn là pretrained Chronos-2, không phải adapter này.
- Không có tuyên bố "fine-tune làm model tốt hơn 10 %".

Đây là kết quả đúng quy trình, không phải lỗi. Có ba cách cải thiện hợp lệ trong lần sau, đều phải
đo lại chứ không được giả định:

1. Nhiều windows hơn mỗi phiên (hiện 1.500 windows từ 1.400 phiên — rất ít so với 34,56 M dòng).
2. Tăng `num_steps` hoặc learning rate có kiểm soát, chọn checkpoint theo validation.
3. Thêm covariates có thông tin thật; hiện `fan_level` là covariate mạnh nhất trong dữ liệu tổng
   hợp, có thể chiếm phần lớn tín hiệu học được.

## 4. Điều đã kiểm chứng và điều chưa

Đã kiểm chứng:

- [x] Model 120 M load và chạy trên RTX 3050 4 GB; P95 17 ms cho 720 → 360.
- [x] Adapter LoRA thay weights thật (589.824 tham số, file 2,3 MB, hash riêng).
- [x] Generator tái tạo bằng seed; 34,56 M dòng; 10 kịch bản; test có kịch bản riêng.
- [x] Readiness từ chối context thiếu (44 % coverage) và chấp nhận 86 %.
- [x] Sample trễ/già, saturated và fault bị loại khỏi input.
- [x] Gate promotion chạy trên validation và từ chối đúng ngưỡng.

Chưa kiểm chứng (không được tuyên bố):

- [ ] Độ chính xác trên **thiết bị thật**. Toàn bộ số ở trên là dữ liệu tổng hợp.
- [ ] Fine-tune thắng pretrained trên dữ liệu thật.
- [ ] End-to-end sensor → forecast trên UI dưới 5 giây với worker thật (mới đo inference).
- [ ] Mô hình có theo kịp nhịp 5 giây khi chạy liên tục 10 phút cùng lúc với serving.
- [ ] `forecast_paths` (đường 360 bước) đã lưu vào backend; hiện backend vẫn chỉ có 3 mốc.

## 5. Cách chạy lại

```bash
cd ai-service
.venv-chronos/bin/python tools/benchmark_chronos.py --device cuda \
    --output artifacts/benchmark/chronos2-cuda-720-360.json
.venv-chronos/bin/python tools/generate_air_quality.py \
    --config configs/air_quality_synthetic.yaml --output data/synthetic
.venv-chronos/bin/python tools/analyze_air_quality.py \
    --synthetic data/synthetic --output reports/eda
PYTHONPATH=. .venv-chronos/bin/python tools/train_chronos.py \
    --data data/synthetic --output models/chronos-lora --steps 500
PYTHONPATH=. .venv-chronos/bin/python tools/evaluate_chronos.py \
    --data data/synthetic --model models/chronos-lora --output reports/chronos-eval
```

Môi trường `.venv-chronos` là **riêng** (Python 3.12, torch 2.15.0+cu132); nó không thay
`.venv` của worker đang chạy.

## 6. Bước tiếp theo

1. Nối worker với `app/chronos_model.py`: mỗi telemetry → context → forecast 360 bước, upload
   `forecast_paths`. Đây là phần còn thiếu để UI có đường dự báo liên tục.
2. Đo end-to-end trên thiết bị thật và ghi lại như cohort riêng, tách khỏi số tổng hợp.
3. Chỉ sau khi có dữ liệu thật mới bàn lại chuyện promotion.
