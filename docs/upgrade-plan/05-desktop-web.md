# 05 — Giao diện desktop web và bảng thông số

Trạng thái: thiết kế đề xuất, chưa sửa frontend. Giữ React/Vite, React Router, TanStack Query, Tailwind và Recharts hiện có.

## 1. Trải nghiệm chính

Giao diện phải trả lời nhanh: phòng đang thế nào, thiết bị thực sự báo gì, 30 phút tới dự kiến ra sao, có cần làm gì và bằng chứng model tốt nằm ở đâu.

Desktop là bố cục chính: 1440×900 và 1280×800. Sidebar 240 px, header 64 px, vùng nội dung co giãn, grid 12 cột, gap 16 px. Bỏ các giới hạn chiều rộng kiểu trang điện thoại ở màn hình chính. Dưới 1024 px chuyển sidebar thành drawer; mobile vẫn đọc/điều khiển được nhưng không quyết định bố cục desktop.

Phong cách: dashboard dữ liệu, bề mặt phẳng, mật độ vừa đủ, chart lớn và bảng có thể so sánh. Giữ nền tối của dự án, không thêm hero khẩu hiệu, glass effect hoặc animation chiếm chỗ thông tin.

### Tokens đề xuất

| Token | Giá trị/mục đích |
|---|---|
| Canvas / surface / raised | `#0F172A` / `#172033` / `#1E293B` |
| Text / secondary | `#F8FAFC` / `#CBD5E1` |
| Actual / forecast / baseline | Cyan `#67E8F9` / amber `#FBBF24` / slate `#94A3B8` |
| Threshold / warning / success | Rose `#FB7185` / amber / green `#4ADE80` |
| Typography | Noto Sans cho tiếng Việt; tabular numerals cho số liệu; font mono chỉ ở ID/code |
| Size | Body 16 px, bảng 14 px, metadata tối thiểu 12 px; số KPI 28–32 px |
| Shape / motion | Border rõ, radius 8–12 px; transition 150 ms, tôn trọng reduced motion |

Màu chart luôn kèm line style/label, không mã hóa ý nghĩa chỉ bằng màu. Kiểm tra contrast thực tế của text/focus sau implementation.

## 2. Điều hướng

| Mục | Route đích | Nội dung |
|---|---|---|
| Tổng quan | `/` | Phòng, thiết bị, cảnh báo, trạng thái hệ thống |
| Phân tích phòng | `/room/:deviceId` | Số đo, chart, forecast, sự kiện và điều khiển |
| Trung tâm AI | `/ai?device_id=...` | Data/model/training/evaluation summary |
| Thí nghiệm & đánh giá | `/evaluation?device_id=...` | Benchmark, warning quality, thí nghiệm và export |
| Thiết bị & cấu hình | `/devices`, route settings cũ | Bảng thiết bị, cấu hình phòng/node/lõi lọc |
| Nhật ký | `/audit?device_id=...` | Quyết định, command lifecycle, report và sự kiện |

Giữ `/connect`, `/guide` và `/room/:deviceId/settings`. Bộ lọc device/khoảng thời gian nằm trong URL để mở lại đúng ngữ cảnh. Không xóa deep link cũ khi chuyển shell.

## 3. Tổng quan

```text
┌───────────────────────┬──────────────────────────────────────────────────────────┐
│ Air Quality           │ Tổng quan        [Phòng/thiết bị ▾] [Khoảng thời gian ▾] │
│ Supervision System    ├──────────────────────────────────────────────────────────┤
│                       │ [Node online] [Cảnh báo] [Coverage] [AI worker + model]  │
│ Tổng quan             ├──────────────────────────────────┬───────────────────────┤
│ Phân tích phòng       │ PM2.5: thực đo + forecast         │ Cảnh báo/đề xuất mới │
│ Trung tâm AI          │                                  │ và tuổi dữ liệu      │
│ Thí nghiệm & đánh giá ├──────────────────────────────────┴───────────────────────┤
│ Thiết bị & cấu hình   │ Bảng phòng/thiết bị: thông số + trạng thái + hành động  │
│ Nhật ký               ├──────────────────────────────────────────────────────────┤
│                       │ Hoạt động gần nhất / lỗi dữ liệu / lệnh chưa xác nhận   │
└───────────────────────┴──────────────────────────────────────────────────────────┘
```

Một node vẫn hiển thị một hàng thật; không sinh phòng giả để lấp dashboard. Empty state dẫn tới kết nối thiết bị hoặc thu dữ liệu, không thay bằng chỉ số mẫu.

Bảng tổng quan: phòng, device, PM2.5, CO₂, T, RH, tuổi telemetry, ngưỡng/source, reported fan/mode, command pending, cảnh báo, model status. Tên và status sticky trái; scroll ngang chỉ nằm trong bảng khi cần.

## 4. Phân tích phòng

```text
┌─────────────────────────────────────────────────────────────────────────────────┐
│ Phòng [Tên] · [Device ID]     Online: gói nhận 4s trước    [Cài đặt] [Ghi sự kiện]│
├───────────────────┬───────────────────┬───────────────────┬─────────────────────┤
│ PM2.5 + tuổi mẫu  │ CO₂ + trạng thái  │ Nhiệt độ/độ ẩm    │ Ngưỡng + nguồn      │
├─────────────────────────────────────────────────────┬───────────────────────────┤
│ Chart PM2.5 lớn: actual / baseline / forecast / T    │ Điều khiển và trạng thái │
│ [1h] [6h] [24h] [Tùy chọn] [Zoom] [CSV]             │ Thiết bị báo: ON L3     │
│                                                     │ Yêu cầu mới: chờ report │
├─────────────────────────────────────────────────────┤ AUTO/MANUAL và lý do    │
│ Timeline: fan level / mode / event / threshold      ├───────────────────────────┤
│                                                     │ AI: +5/+15/+30          │
├─────────────────────────────────────────────────────┤ Data/model quality      │
│ CO₂, T, RH ở các plot riêng đồng bộ trục thời gian  │ Đề xuất và phản hồi     │
├─────────────────────────────────────────────────────┴───────────────────────────┤
│ Tabs: [Bảng số đo] [Cảnh báo] [Sự kiện] [Lịch sử lệnh]                            │
└─────────────────────────────────────────────────────────────────────────────────┘
```

Chart PM tối thiểu cao 320 px ở desktop. Không đưa CO₂ và PM lên cùng một trục số; các plot riêng đồng bộ cursor/time range. Threshold có thể thay đổi theo lịch sử, không kẻ ngưỡng hiện tại xuyên toàn quá khứ như thể nó chưa từng đổi.

Tooltip ghi time, aggregate type/count, min/max, actual/forecast/model version nếu liên quan. Một phút thiếu dữ liệu là gap, không nối thành đoạn đường liên tục. Dự báo chỉ có ba target thật; điểm nối không được coi là forecast mỗi phút.

Điều khiển giữ workflow hiện tại: người dùng chọn mức và AUTO/MANUAL. Bên cạnh nút có reported state, request state, thời gian publish và xác nhận quan sát. Không đổi trạng thái quạt trên UI thành thành công ngay sau HTTP 2xx nếu thiết bị chưa báo.

## 5. Trung tâm AI

```text
┌─────────────────────────────────────────────────────────────────────────────────┐
│ Trung tâm AI · [Device ▾]      Worker: online     [Train lại] [Lịch sử model]    │
├──────────────────────────┬──────────────────────────┬───────────────────────────┤
│ Dữ liệu: giờ/coverage    │ Model đang phục vụ       │ Benchmark: gain và số mẫu│
├──────────────────────────┴──────────────────────────┴───────────────────────────┤
│ Tabs: [Dữ liệu học] [Training jobs] [Model versions] [Giải thích]                │
│ Coverage/gaps; model card; MAE theo horizon; ablation; training tiến độ          │
└─────────────────────────────────────────────────────────────────────────────────┘
```

Data tab hiển thị raw packets → valid minute buckets → training origins, cùng thời gian quan sát. Không chỉ ghi “học từ 17.000 mẫu” mà bỏ qua sau aggregation còn bao nhiêu mẫu học.

Model card: algorithm, features version, train window/cutoff, số origins từng horizon, validation/test loại nào, model đang phục vụ hay experimental, lý do chọn/không chọn và giới hạn.

Training table: job ID, device, trigger manual/scheduled, snapshot, queued/claimed/training/evaluating/completed/failed, progress, started/finished, lỗi có thể hiểu được. Không lấy progress giả theo timer; dùng phase và số việc hoàn thành worker gửi.

Model table: version, thời gian, algorithm, data range, MAE5/15/30, baseline gain, status, artifact availability. Model không thắng baseline vẫn xuất hiện và có lý do rõ.

## 6. Thí nghiệm & đánh giá

Ba tabs chính:

1. **Dự báo:** retrospective/prospective chọn riêng; actual-vs-prediction, MAE/RMSE theo horizon, error theo thời gian và nhóm vận hành.
2. **Cảnh báo:** timeline, TP/FP/FN, lead time, false alarms/day; valid hours, pending/intervention-affected và số event luôn nằm cạnh tỷ lệ.
3. **Thí nghiệm:** danh sách phiên, điều kiện đầu, hành động, thời gian vượt ngưỡng, diện tích vượt ngưỡng, hồi phục và trạng thái chưa đủ theo dõi.

Nút export tạo job và tải gói khi hoàn tất. Nếu worker offline, nói job đang chờ worker; không hiện “đang tính toán” vô hạn. Biểu đồ xuất có bản nền sáng cho slide/in và bản nền tối cho demo; dữ liệu và metrics phải giống nhau.

## 7. Thiết kế lại các bảng thông số

| Bảng/nhóm | Cột/nội dung phải có |
|---|---|
| Số đo | Recorded time, received time, PM, CO₂, T/RH, RSSI, bucket/raw, count, quality |
| Chẩn đoán node | Uptime, heap/min heap, dust fault/saturation, packet age, missing sensor fields |
| Phòng | Volume, purpose, occupancy hours thực được lưu, ventilation, threshold/source, profile version |
| Công thức ngưỡng | Input, từng factor, giá trị cuối/clamp, CO₂ dùng tính và tuổi của nó, preview/apply riêng |
| Quạt/lệnh | Requested state/level/mode, reported state/level/duty/mode, timestamps, publish result, match status |
| Lõi lọc | Replaced_at, reported runtime, ghi chú độ phân giải/giới hạn, lịch sử thay lõi |
| AI | Model, snapshot, horizon, forecast/actual/error, split, sample count, eligibility/status |

Không làm thanh “tuổi thọ lõi lọc còn 83%” nếu chưa có mô hình hoặc dữ liệu hiệu chuẩn tương ứng. Runtime firmware và thời gian ON suy ra phía server là hai nguồn khác nhau, phải có nhãn và khoảng không xác định riêng.

### Hành vi bảng chung

- Sticky header, sort rõ, filter thời gian/device/status, chọn cột và phân trang server cho bảng dài.
- `null` hiển thị “Chưa có mẫu”; lỗi/out-of-range hiển thị lý do; 0 hợp lệ vẫn là 0.
- Đơn vị nằm trong header/tooltip; không trộn ppm và µg/m³ hoặc epoch giây/millisecond.
- Không giả độ chính xác: PM một chữ số thập phân, T/RH một chữ số, CO₂ ppm nguyên; giữ raw gốc khi export.
- Có bảng thay thế cho chart, keyboard focus và label cho nút icon. Trạng thái quan trọng có chữ, không chỉ chấm màu.

## 8. Polling và trạng thái cập nhật

| Dữ liệu | Nhịp đề xuất |
|---|---|
| Device/latest/commands pending | 5 giây khi trang đang mở |
| Series/history/forecast | 60 giây; invalidate sau thao tác có liên quan |
| AI worker/jobs đang chạy | 5 giây |
| Model/metrics đã đóng băng | Theo thao tác hoặc hoàn tất job, không poll nhanh |

Giữ query cache và chart cũ trong background refresh. Nếu đang fetch response: “Đang cập nhật dữ liệu…”. Chỉ ghi “Đang tính toán dự báo…” khi backend có trạng thái inference đang chạy; không giả vờ GET cache là đang train model.

Forecast cũ vẫn xem được kèm issued time, tuổi input và cảnh báo stale. Lỗi refresh không thay toàn bộ panel thành trang lỗi. Cold start phân biệt: chưa có node, chưa có mẫu, chưa đủ history, chưa train, worker offline và request thất bại.

UI phân biệt ba thời điểm: thiết bị ghi timestamp bản tin, backend nhận, model phát hành forecast. Sensor freshness riêng chưa có thì nói rõ, không suy từ việc query vừa hoàn tất.

## 9. Tiêu chí nghiệm thu giao diện

- [ ] Hai kích thước desktop xem được chart chính và điều khiển mà không bị ép thành cột hẹp.
- [ ] Không tràn ngang toàn trang; bảng dài có vùng scroll riêng; route cũ vẫn hoạt động.
- [ ] Nhìn rõ actual/baseline/forecast/threshold không cần dựa riêng vào màu.
- [ ] Train/refresh lỗi không làm mất kết quả cũ; số liệu cũ có nhãn tuổi và trạng thái.
- [ ] Control publish fail hoặc chưa có report không hiển thị thành “quạt đã bật”.
- [ ] Model underperform và N/A metrics không bị giấu hoặc biến thành 0.
- [ ] Bảng và chart dùng cùng nguồn/cửa sổ/aggregation; export tái hiện được số trên màn hình.
- [ ] Tên dự án đầy đủ trong app shell và report; không thêm tên viết tắt mới vào nội dung người dùng.
