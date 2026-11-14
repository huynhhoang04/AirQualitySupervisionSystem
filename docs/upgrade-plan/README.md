# Kế hoạch nâng cấp Air Quality Supervision System

Ngày lập: 14/11/2026. Trạng thái: **đề xuất để review, chưa triển khai**.

Bộ tài liệu này mô tả phiên bản tiếp theo dựa trên code đang có trong repository. Các API, bảng dữ liệu và màn hình được đề xuất bên dưới **chưa tồn tại**, trừ khi được ghi rõ là hiện trạng. Việc ghi bộ kế hoạch không thay đổi firmware, backend, frontend, database hay slide.

## 1. Kết quả cần đạt

Chuyển từ dashboard theo dõi và điều khiển theo ngưỡng sang hệ thống có dự báo học từ dữ liệu thật, cảnh báo sớm, đề xuất hành động và trang đánh giá có thể kiểm tra lại.

Khi báo cáo, phải trả lời được bằng số liệu:

1. Dự báo PM2.5 tốt hơn giữ nguyên số đo cuối bao nhiêu, ở mốc 5/15/30 phút?
2. Cảnh báo sớm được bao lâu; có bao nhiêu lần đúng, bỏ sót và báo nhầm?
3. Người dùng đã làm gì sau đề xuất, và diễn biến thực đo sau hành động ra sao?
4. Model được train từ dữ liệu nào, có bao nhiêu mẫu hợp lệ, đã kiểm định thế nào?

Không có yêu cầu làm model phức tạp bằng mọi giá. Có yêu cầu **train thật, lưu model thật, kiểm định thật và công bố cả trường hợp chưa tốt hơn baseline**.

## 2. Các quyết định đã chốt

| Hạng mục | Quyết định |
|---|---|
| Thời gian | Phiên bản chính trong 3–7 ngày; thu dữ liệu song song từ ngày đầu |
| Dữ liệu | Một node thật chạy liên tục; ghi sự kiện quạt, cửa, hoạt động trong phòng |
| Embedded | Giữ firmware và MQTT wire format hiện tại trong phiên bản chính |
| Backend | Tiếp tục Spring Boot/PostgreSQL; nâng chất lượng dữ liệu và bổ sung API cho AI |
| AI | Service Python riêng chạy local, CPU là cấu hình chuẩn; chưa cần GPU |
| Quyền AI | Dự báo, cảnh báo, đề xuất; không tự vượt quyền điều khiển của backend |
| Frontend | Desktop web là thiết kế chính, đồng thời giữ khả năng xem trên màn hình nhỏ |
| Bảng thông số | Nâng cả cách lưu trong database và cách trình bày/lọc/so sánh trên web |
| Giao tiếp AI | Worker local chủ động gọi backend cloud; trình duyệt chỉ dùng API backend |
| Bằng chứng | Baseline, test theo thời gian, forecast phát hành trước ground truth, thí nghiệm có nhật ký |
| Tên hiển thị | Dùng đầy đủ **Air Quality Supervision System** trong tài liệu, giao diện và slide |

Các identifier, package, topic và tên file cũ có chữ viết tắt được giữ để không phá tương thích.

## 3. Thứ tự đọc

| Tài liệu | Quyết định cần review |
|---|---|
| [01 — Đánh giá hệ thống hiện tại](01-current-system-audit.md) | Giữ gì, sửa gì, code hiện tại thực sự làm được gì |
| [02 — Backend và dữ liệu](02-backend-and-data.md) | Schema, nguồn dữ liệu, command lifecycle, API và kiến trúc cloud/local |
| [03 — Service AI](03-ai-service.md) | Features, labels, thuật toán, tự train, inference, lựa chọn model |
| [04 — Đánh giá và thí nghiệm](04-evaluation-and-experiments.md) | Phép đo, tránh leakage, đối chứng, cách kết luận từ dữ liệu |
| [05 — Desktop web](05-desktop-web.md) | Các màn hình, wireframe, bảng thông số và tương tác |
| [06 — Triển khai và báo cáo](06-delivery-and-report.md) | Thứ tự công việc, test, chạy demo và nội dung slide |

Nên review 01 → 03 → 04 trước: phần AI chỉ có ý nghĩa khi dữ liệu và cách chứng minh đã đúng.

## 4. Kiến trúc đích

```mermaid
flowchart LR
    Node[ESP32-S3 hiện tại] -->|Telemetry và actuator report| Broker[MQTT broker]
    Broker --> Backend[Spring Boot backend]
    Backend -->|Lệnh theo luật hoặc thao tác người dùng| Broker
    Broker --> Node
    Backend <--> DB[(PostgreSQL)]
    Web[Desktop web] <-->|REST| Backend
    Worker[AI worker local] -->|Lấy job và raw data| Backend
    Worker -->|Gửi model metadata, forecast, metrics| Backend
    Worker <--> Files[(Snapshot và model local)]
```

Backend cloud không truy cập `localhost` trên laptop. Worker mở kết nối outbound đến backend, vì vậy không cần public port hoặc tunnel vào máy cá nhân. Giao diện vẫn đọc được kết quả đã lưu khi laptop tắt, nhưng phải báo forecast đã cũ và worker offline.

AI không nằm trên đường xử lý bắt buộc của telemetry/control. Tắt AI không được làm mất chức năng đo, điều khiển theo ngưỡng, pair thiết bị hoặc chỉnh cấu hình.

## 5. Định nghĩa hoàn thành

- [ ] Giữ các chức năng đo và điều khiển hiện tại hoạt động.
- [ ] Lưu được dữ liệu gốc, actuator history, cấu hình và sự kiện có timestamp.
- [ ] Train một mô hình trên dữ liệu node thật, lưu artifact và manifest để chạy lại.
- [ ] Dự báo định kỳ không train lại theo request của trình duyệt.
- [ ] Có bảng so persistence, Ridge và model ứng viên trên cùng khoảng đánh giá.
- [ ] Lưu forecast trước khi target xảy ra và đối chiếu với số đo đến sau.
- [ ] Có cảnh báo, phản hồi đề xuất, biểu đồ và export phục vụ slide.
- [ ] Có demo desktop hoàn chỉnh ở 1440×900 và 1280×800.
- [ ] Phân biệt kết quả đo được, mục tiêu chưa đạt và giả thuyết cần thu thêm dữ liệu.

## 6. Ranh giới phiên bản đầu

Chưa đưa vào cam kết 7 ngày: deep learning, chatbot, dự báo đa phòng từ một node, tự tối ưu điều khiển bằng reinforcement learning, tính điện năng khi không có công suất đo, hoặc suy ra tuổi thọ HEPA từ runtime như một phép đo chính xác.

Ngưỡng theo hồ sơ phòng tiếp tục là chính sách tính bằng công thức. Dự báo PM2.5 là phần học máy. Đề xuất hành động là quy tắc sử dụng forecast; tài liệu và giao diện phải nói rõ sự khác nhau.

[Hợp đồng hiện tại](../io_contract.md) vẫn là nguồn chính thức cho hệ thống đang chạy. Khi triển khai, cập nhật hợp đồng trước các thay đổi liên tầng; các contract trong bộ kế hoạch này là đề xuất cho phiên bản tiếp theo.
