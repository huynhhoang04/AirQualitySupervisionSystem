# 06 — Thứ tự triển khai, kiểm thử và báo cáo

Trạng thái: kế hoạch để review, chưa triển khai. Các ngày dưới đây là mốc công việc mục tiêu; chất lượng bằng chứng còn phụ thuộc thời gian thu dữ liệu thật, không thể rút ngắn bằng việc code nhanh hơn.

## 1. Các mốc cần đạt

| Mốc | Kết quả cụ thể | Điều kiện qua mốc |
|---|---|---|
| M0 — Khóa hiện trạng | Audit, backup, hợp đồng đề xuất được review | Biết phần giữ/sửa và không mất thay đổi đang có |
| M1 — Dữ liệu dùng được | Raw export, quality, actuator history, sự kiện | Một snapshot đọc lại nhất quán, thống kê coverage thật |
| M2 — Train thật | Persistence/Ridge/boosting, manifest và artifact | Chạy lại được, split đúng, có bảng baseline |
| M3 — Tích hợp | Worker local, jobs, forecast ledger, desktop | Tắt/bật AI không ảnh hưởng control; forecast trước target |
| M4 — Bằng chứng | Test đóng băng, warning metrics, phiên quan sát | Metrics tái tính được, công bố số mẫu và các khoảng thiếu |
| M5 — Báo cáo | Export charts, slide outline, demo rehearsal | Không có con số minh họa bị dùng như kết quả thật |

## 2. Lịch mục tiêu 7 ngày

### Ngày 1 — Thu dữ liệu trước

- Chốt contract mới trong `docs/io_contract.md`; chưa đổi firmware.
- Backup DB, thêm migration dữ liệu/actuator/command/profile theo tài liệu 02.
- Lưu system fields, received_at, cờ chất lượng, actuator timestamp và lịch sử.
- Có raw export và nút/event API đánh dấu hoạt động; bắt đầu thu liên tục.
- Sửa publish/state/lease ở backend vì chúng ảnh hưởng trực tiếp bằng chứng vận hành.

**Đầu ra:** kiểm tra được một gói telemetry từ MQTT đến DB, một command đến report và một export gốc. Ghi giờ bắt đầu dữ liệu có provenance mới.

### Ngày 2 — Pipeline và desktop shell

- Worker client, snapshot, feature-v1, persistence và Ridge.
- Training job thủ công, lưu artifact/manifest; model experimental nếu đủ dữ liệu.
- Desktop navigation, bảng tổng quan, trang phòng và chart từ series API.
- Bảng hiện trường thiếu/cũ/lỗi có đơn vị và timestamp.

**Đầu ra:** demo từ dữ liệu thật tới model card; chưa yêu cầu model thắng baseline.

### Ngày 3 — Model ứng viên và forecast ledger

- Train boosting với grid nhỏ, temporal validation, promotion gate.
- Inference mỗi phút và upload forecast; UI dùng API backend thay service demo trực tiếp.
- Hiển thị job progress, model version, thiếu dữ liệu và underperforming.
- Bắt đầu giai đoạn shadow để kiểm định cảnh báo.

**Đầu ra:** một forecast đã lưu được đối chiếu với target đến sau; restart worker load lại model.

### Ngày 4–5 — Đánh giá và hành động

- Trang benchmark, event warning metrics, ablation và export số liệu.
- Bật khuyến nghị sau khi đã có đoạn shadow; ghi phản hồi/hành động và phiên quan sát.
- Hoàn thiện cấu hình occupancy hours, preview factors, bảng lệnh và các giới hạn runtime hiển thị.
- Kiểm tra refresh giữ chart, command chưa xác nhận và worker offline.

**Đầu ra:** bảng so sánh có số mẫu, ít nhất một phiên quan sát nếu có diễn biến thực tế phù hợp. Không tạo dữ liệu để ép đủ số phiên.

### Ngày 6 — Đóng băng và chấm

- Khóa model báo cáo, protocol, threshold và evaluation cohort.
- Chạy retrospective test đã giữ riêng và prospective test mới; ghi rõ hai loại.
- Không tuning theo test. Nếu chưa tốt hơn baseline thì giữ baseline, chuẩn bị giải thích kết quả.
- Đối chiếu số trên dashboard, CSV và biểu đồ xuất.

**Đầu ra:** một gói metrics/figures có thể tái tính, không chỉ screenshot UI.

### Ngày 7 — Chạy thử báo cáo

- Dựng phần slide AI từ kết quả thật và cập nhật ảnh desktop.
- Demo từ overview → forecast → model card → benchmark → hành động/nhật ký.
- Backup artifact, export và dữ liệu cần cho replay; thử tắt worker/mất kết nối.
- Chạy rehearsal đủ thời lượng, chuẩn bị đáp án về thuật toán/dữ liệu/giới hạn.

**Đầu ra:** demo live và replay có nhãn, bộ hình/số liệu cuối, danh sách hạn chế đã biết.

### Nếu chỉ có ba ngày

Giữ bắt buộc: M1/M2, worker inference, forecast ledger, desktop phòng, bảng baseline và model card. Giảm bớt polish, ablation nâng cao và phân tích thí nghiệm. Cảnh báo chỉ hiển thị thử nghiệm nếu chưa đủ thời gian đánh giá. Không bỏ baseline hoặc trộn test vào train để chạy kịp.

## 3. Ma trận kiểm thử khi triển khai

Các test dưới đây thuộc công việc triển khai tương lai, không cần chạy firmware/backend test chỉ vì thêm bộ tài liệu này.

| Nhóm | Tình huống có ý nghĩa |
|---|---|
| Ingestion | Device/topic mismatch, ngoài dải, NaN, null, clock tương lai, gói gửi bù, duplicate và legacy timestamp |
| Export | Đọc nhiều trang trong lúc insert/commit gói mới; snapshot không thiếu/trùng/đổi membership |
| Actuator | Publish false, report trễ/thiếu, manual override, command superseded, không biết RPM |
| Control | Breach kích hoạt ngay, hysteresis/min-on/max-on giữ đúng, dữ liệu PM cũ không che max-on, lease fail không ghi thành công |
| Features | Bucket boundary, interval thay đổi, gap, forward-fill giới hạn, rolling causal, join context theo received_at |
| Leakage | Chèn một mẫu tương lai hoặc sự kiện ghi bổ sung không làm đổi features ở origin đã khóa |
| Train | Pipeline fit trong từng split, same-origin comparison, seed/reload artifact, fallback khi ít dữ liệu |
| Registry | Artifact thiếu/hash sai, job timeout/claim lại, worker cũ complete sau lease hết, promotion không đạt |
| Inference | Chạy khi train, worker restart, upload lặp, upload trễ, model cũ vẫn dùng trong lúc train |
| Evaluation | Tính tay MAE/RMSE trên fixture nhỏ; alert match không đếm lặp; missing/intervention/N/A đúng |
| Frontend | Desktop hai kích thước, bàn phím, chart cache, request lỗi, pending command và trạng thái offline |
| End-to-end | Một run phát hành trước target, nhận số đo thật, chấm error và export cùng kết quả |

Kiểm thử mô phỏng chứng minh phần mềm tính đúng, không thay thế số liệu hiệu quả thực tế. Với backend dùng unit test cho decision và integration test PostgreSQL cho migration/export/jobs; với AI dùng pytest cho temporal correctness, model lifecycle và metrics; frontend build/typecheck cùng kiểm tra tương tác có chọn lọc.

Không đặt yêu cầu 100% test coverage hoặc viết test chỉ để lặp lại implementation. Ưu tiên lỗi có thể làm số liệu báo cáo sai hoặc điều khiển sai.

## 4. Cấu hình chạy và quan sát

| Biến/cấu hình đề xuất | Nơi dùng |
|---|---|
| `AI_ENABLED`, `AI_WORKER_TOKEN` | Backend bật module AI và xác thực internal API |
| `BACKEND_URL`, `AI_WORKER_TOKEN`, `WORKER_ID` | Worker kết nối backend, không chứa DB credential |
| `ARTIFACT_DIR`, `SNAPSHOT_DIR` | Worker lưu model/dataset local |
| `POLL_SECONDS=10`, `INFERENCE_SECONDS=60` | Nhịp job/inference |
| `TRAIN_INTERVAL_HOURS=6`, `MAX_HISTORY_DAYS=14` | Tự train khi đủ dữ liệu mới |
| `REPORT_FREEZE` / evaluation cohort ID | Khóa promotion và dataset cho đợt báo cáo |

`VITE_API_URL` tiếp tục trỏ backend. Sau khi chuyển xong frontend không cần gọi `VITE_FORECAST_API_URL` local nữa; giữ đường rollback trong giai đoạn chuyển, không chạy hai panel cạnh nhau với nhãn mơ hồ.

Logs gắn device/job/run/model ID; không ghi secret. Trạng thái vận hành tối thiểu: số telemetry nhận/lỗi, lag nhận/đo, worker heartbeat, last inference, train duration, pending/failed jobs, forecast stale, command publish fail/unconfirmed.

Không giả định free backend luôn thức. Chạy thử backend cloud/broker/node trước buổi báo cáo và ghi downtime vào coverage. Không dùng worker poll để che việc backend không còn bảo đảm control liên tục.

## 5. Kịch bản demo 5–6 phút

1. **Tổng quan:** số đo thật, tuổi dữ liệu, quạt report và ngưỡng hiện có.
2. **Phòng:** forecast ba horizon; bật/tắt các đường baseline; chỉ rõ đây là dự báo, không phải dữ liệu đo.
3. **Trung tâm AI:** mở model card, raw → buckets → origins, training jobs và model đang dùng.
4. **Đánh giá:** chọn test cố định, đọc MAE/baseline gain/số mẫu; mở một đoạn model sai để nói giới hạn.
5. **Cảnh báo và đề xuất:** xem event thật hoặc replay có nhãn, phản hồi người dùng, command và report. Không hứa trong vài phút demo sẽ có một đợt bụi mới.
6. **Export:** mở biểu đồ/CSV của cùng evaluation ID; chứng minh số trên slide truy về dữ liệu được.

Replay chỉ dùng dữ liệu đã thu, hiển thị rõ ngày giờ và chữ “Phát lại dữ liệu đã ghi”. Thao tác replay không phát lệnh vào node thật và không ghi đè bảng vận hành.

## 6. Những slide AI cần bổ sung sau khi có kết quả

| Slide | Nội dung chính | Hình/số liệu cần có |
|---|---|---|
| 1 — Bài toán AI | Biết gì sớm hơn hệ thống theo ngưỡng | Dữ liệu đầu vào, ba horizon, sơ đồ luồng |
| 2 — Học từ dữ liệu thật | Tự train, temporal split, model version | Data card, raw/bucket/origin counts, train timeline |
| 3 — So sánh có đối chứng | Model nào tốt hơn và ở đâu | Actual-vs-forecast, MAE theo horizon, persistence/Ridge/boosting |
| 4 — Giá trị vận hành | Cảnh báo và hành động | Lead-time timeline, TP/FP/FN, phiên quan sát và giới hạn |

Không nhét bảng tham số model dài lên slide chính. Đưa feature schema, grid, split và metrics chi tiết vào phụ lục để trả lời khi được hỏi.

### Câu hỏi cần trả lời được

- StandardScaler và Ridge khác nhau thế nào?
- Model đang dự báo cái gì, label lấy từ đâu, một training row gồm gì?
- Bao nhiêu gói telemetry, bao nhiêu bucket, bao nhiêu origins; tại sao chúng không độc lập?
- Vì sao chọn gradient boosting, và khi nào Ridge/persistence tốt hơn?
- Làm sao tránh dùng tương lai khi split, rolling, preprocessing và join actuator?
- Dự báo tốt hơn có tự chứng minh chất lượng không khí tốt hơn không?
- AI tắt hoặc model không thắng baseline thì hệ thống vận hành thế nào?
- Vì sao không cho model tự thay ngưỡng hoặc tự bật/tắt quạt trong phiên bản này?

## 7. Giới hạn và hướng phát triển sau phiên bản đầu

| Hướng sau 7 ngày | Dữ liệu cần thêm | Bằng chứng bắt buộc trước khi quảng bá |
|---|---|---|
| Prediction intervals | Nhiều trạng thái và test dài hơn | Coverage và độ rộng ngoài train, không chỉ dải tô đẹp |
| Mô hình đáp ứng theo mức quạt | Nhiều phiên thay mức có ghi điều kiện | Kiểm định theo phiên độc lập, kiểm soát confounding; forecast hiện tại chưa phải counterfactual |
| Phát hiện bất thường | Log lỗi hoặc sự kiện được xác minh | Tỷ lệ báo nhầm/bỏ sót; tách sensor fault và biến động thật |
| Điều khiển dự báo | Response model đáng tin, mô phỏng và thử giới hạn | So với rule controller trên cùng protocol, fallback và an toàn vận hành |
| Học đa phòng | Dữ liệu nhiều phòng thật, đặc trưng volume/purpose có biến thiên | Holdout theo phòng chưa thấy, không chỉ chia ngẫu nhiên trong một phòng |
| Tối ưu năng lượng | Công suất/điện năng đo được | So Wh trong điều kiện tương đương, không suy từ duty tuyến tính |

Các hướng này giữ thành roadmap, không đưa vào slide như chức năng đã hoàn thành. Phiên bản đầu thành công khi tạo được vòng dữ liệu → học → dự báo → đánh giá có thể kiểm tra, đồng thời giữ phần đo và điều khiển vốn đã hoạt động.
