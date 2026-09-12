Báo cáo Kỹ thuật Toàn diện: Hệ thống Giám sát Chất lượng Không khí (AQSS)

1. Tổng quan Kiến trúc Hệ thống 4 Lớp

Hệ thống Giám sát Chất lượng Không khí (AQSS) được thiết kế theo triết lý "Data-Driven IoT", hướng tới một giải pháp end-to-end có khả năng tự động hóa luồng dữ liệu từ cảm biến tại biên đến các mô hình dự báo học sâu. Tầm nhìn chiến lược của AQSS không chỉ dừng lại ở việc quan trắc thụ động mà còn tạo ra các tri thức dự báo có độ chính xác cao, phục vụ công tác bảo vệ sức khỏe cộng đồng và quản lý môi trường đô thị thông minh.

Hệ thống được cấu trúc thành 4 lớp module hóa chặt chẽ:

Tầng	Lớp (Layer)	Thành phần Công nghệ (Stack)	Vai trò Chiến lược
1	Embedded	ESP32-S3, C++17, FreeRTOS	Thu thập dữ liệu biên chính xác cao, xử lý tín hiệu thời gian thực.
2	Backend	Java 21, Spring Boot 3.3, PostgreSQL 16	Quản trị hạ tầng, xử lý luồng dữ liệu lớn (Ingestion) và bảo mật API.
3	Deep Learning	PyTorch 2.2, FastAPI, Seq2Seq LSTM	Phân tích sâu, dự báo AQI 24 giờ và phát hiện bất thường cảm biến.
4	Frontend	React 18, TypeScript, TailwindCSS	Dashboard trực quan hóa, cung cấp thông tin cảnh báo tức thời cho người dùng.

Lớp ý nghĩa "So What?": Việc phân tầng này giúp tách biệt các tác vụ tính toán nặng (AI) ra khỏi luồng xử lý nghiệp vụ (Backend), đảm bảo hệ thống có thể mở rộng quy mô (Scalability) lên hàng trăm node cảm biến mà vẫn duy trì độ trễ truyền tin (latency) cực thấp (<5ms từ MQTT đến Dashboard).

2. Khảo sát Kỹ thuật Lớp Nhúng và Linh kiện Chi tiết

Độ tin cậy của toàn bộ hệ thống AQSS phụ thuộc trực tiếp vào tính trung thực của dữ liệu tại biên (Edge). Bất kỳ sai sót nào trong việc đọc tín hiệu vật lý sẽ dẫn đến các dự báo AI sai lệch, do đó lớp nhúng được thiết kế với độ khắt khe cao về quản lý tài nguyên.

2.1. Vi điều khiển Trung tâm: ESP32-S3 N16R8

Chúng tôi sử dụng biến thể ESP32-S3-WROOM-1 N16R8 với 16MB Flash và 8MB Octal PSRAM.

* Ràng buộc GPIO: Do đặc thù của Octal PSRAM, các chân GPIO 33–37 được khóa cứng cho kết nối bộ nhớ nội bộ.
* Chiến lược Phân bổ Lõi (FreeRTOS):
  * Core 0: Chạy Wi-Fi Stack, MQTT Service và quản lý NTP.
  * Core 1: Chuyên trách việc đọc cảm biến (SCD40, GP2Y1023) và render màn hình OLED.
* Quản lý Tài nguyên: Sử dụng cơ chế pinning task để tránh hiện tượng tranh chấp CPU giữa các tác vụ mạng nặng và tác vụ đọc cảm biến nhạy cảm về thời gian.

2.2. Cảm biến Bụi mịn Sharp GP2Y1023AU0F

Khác với dòng GP2Y1010 thông thường, GP2Y1023 tích hợp MCU bên trong và xuất tín hiệu kỹ thuật số PWM (100Hz).

* Xử lý Tín hiệu: Tín hiệu Vo (5V) được đưa qua mạch chia áp (1kΩ/2kΩ) để chuyển về mức 3.3V an toàn cho chân GPIO 18.
* Tránh xung đột ADC2/Wi-Fi: Trong thiết kế này, GPIO 18 được cấu hình là Digital Input, do đó hoàn toàn không bị ảnh hưởng bởi việc tắt ADC2 khi Wi-Fi hoạt động.
* Đo lường: Sử dụng ngắt IRAM_ATTR ISR kết hợp hàm micros() để đo độ rộng xung PWM với độ phân giải micro giây, thay vì dùng pulseIn() gây chặn (blocking) hệ thống.

2.3. Cảm biến CO2 Sensirion SCD40 & Hiển thị OLED SSD1306

Hai thiết bị này chia sẻ chung bus I2C trên các chân GPIO 8 (SDA) và GPIO 9 (SCL).

* Cấu hình Bus: Tốc độ được khóa cố định ở 400kHz. Địa chỉ tương ứng là 0x62 (SCD40) và 0x3C (OLED).
* An toàn luồng: Sử dụng Mutex (SemaphoreHandle_t) để đảm bảo rằng tác vụ cập nhật màn hình OLED không làm gián đoạn chu kỳ đọc của SCD40, ngăn ngừa hiện tượng treo bus I2C.

Lớp ý nghĩa : Việc lựa chọn GP2Y1023 (Digital PWM) thay vì analog giúp loại bỏ nhiễu ADC trong môi trường công nghiệp, đồng thời việc dùng linh kiện tích hợp nhiệt độ/độ ẩm ngay trong SCD40 giúp giảm đáng kể kích thước thiết bị và chi phí linh kiện (BOM cost) mà vẫn đảm bảo độ chính xác cấp nghiên cứu.

3. Giao thức Truyền thông và Hợp đồng Dữ liệu (MQTT & JSON)

Hệ thống áp dụng giao thức MQTT v3.1.1 trên nền tảng Mosquitto Broker để đảm bảo tính gọn nhẹ và tin cậy.

* Hợp đồng Dữ liệu (Data Contract): Payload JSON được chuẩn hóa dưới 384B, đẩy về topic aqss/devices/{device_id}/telemetry mỗi 5 giây.
* Quản lý Trạng thái: Sử dụng QoS 1 (At least once) để đảm bảo không mất tin và Last Will Testament (LWT) để hệ thống ghi nhận trạng thái thiết bị ngoại tuyến (Offline) ngay lập tức.
* Cơ chế Fail-safe: Tầng nhúng tích hợp một Ring Buffer 60 bản ghi. Khi mất kết nối Wi-Fi, dữ liệu 5 phút gần nhất được lưu tạm vào RAM và tự động "đẩy bù" ngay khi mạng khôi phục, đảm bảo tính liên tục của dữ liệu cho mô hình AI.

4. Hạ tầng Backend và Pipeline Xử lý Dữ liệu (Spring Boot 3.3)

Backend xây dựng trên Java 21 đóng vai trò là "xương sống" điều phối toàn bộ hệ thống với các tính năng ưu việt:

* Pipeline Ingestion Hiệu năng cao: Dữ liệu từ MQTT được đẩy vào LinkedBlockingQueue (sức chứa 10,000). Một worker định kỳ thực hiện Batch Insert vào PostgreSQL 16 với tham số reWriteBatchedInserts=true, tối ưu hóa IOPS và giảm tải cho CPU.
* Quản trị Dữ liệu (Data Governance):
  * PostgreSQL Range Partitioning: Tự động phân vùng bảng telemetry theo tháng thông qua một @Scheduled task. Các dữ liệu cũ hơn 90 ngày sẽ được lưu trữ hoặc loại bỏ để duy trì hiệu năng truy vấn.
  * Materialized Views: Sử dụng các view telemetry_hourly và telemetry_daily được làm mới định kỳ. Điều này cho phép frontend truy vấn biểu đồ lịch sử hàng triệu bản ghi trong chưa đầy 100ms.
* Bảo mật Đa lớp: Áp dụng Dual Filter Chain. Các thiết bị IoT sử dụng X-API-Key để xác thực, trong khi người dùng Dashboard sử dụng chuẩn JWT (Bearer Token) thông qua Spring Security 6.

Lớp ý nghĩa "So What?": Kiến trúc này cho phép hệ thống không chỉ vận hành ổn định ở hiện tại mà còn sẵn sàng cho các bài toán phân tích dữ liệu lớn (Big Data) trong tương lai mà không cần tái cấu trúc cơ sở dữ liệu.

5. Mô hình Trí tuệ Nhân tạo và Dự báo Sâu (PyTorch AI)

Tầng AI nâng cấp AQSS từ một công cụ hiển thị đơn thuần thành một hệ thống dự báo thông minh.

5.1. Giải quyết bài toán "Cold Start"

Do thiết bị mới triển khai chưa có đủ dữ liệu lịch sử, chúng tôi áp dụng chiến lược Transfer Learning. Mô hình được tiền huấn luyện (Pre-trained) trên các bộ dữ liệu mở quy mô lớn như UCI Beijing PM2.5 và OpenAQ (Southeast Asia). Sau đó, mô hình sẽ được tinh chỉnh (Fine-tuning) bằng dữ liệu thực tế từ các trạm ESP32-S3 để thích nghi với đặc điểm khí hậu địa phương.

5.2. Các Mô hình Cốt lõi

* Dự báo 24 giờ (Seq2Seq LSTM with Attention): Mô hình tiếp nhận dữ liệu từ 6:00 AM UTC+7 (thời điểm bắt đầu chu kỳ dân sinh) và dữ liệu 1 giờ gần nhất. Cơ chế Attention giúp mô hình "tập trung" vào các biến động bất thường như giờ cao điểm giao thông để đưa ra dự báo 24 bước tiếp theo chính xác.
* Phát hiện Bất thường (Autoencoder): Sử dụng lỗi tái tạo (Reconstruction Error) để phân biệt ô nhiễm thực và lỗi vật lý (ví dụ: bụi bẩn bám vào khe cảm biến hoặc côn trùng xâm nhập). Nếu lỗi vượt ngưỡng, hệ thống sẽ hạ thấp Reliability Score (Điểm tin cậy).

Lớp ý nghĩa "So What?": Việc kết hợp giữa dự báo và đánh giá độ tin cậy giúp các nhà quản lý tránh được các cảnh báo sai (False Alarms), từ đó đưa ra các quyết định hành động chính xác hơn về sức khỏe cộng đồng.

6. Quy tắc Kiểm chuẩn và Ràng buộc Vật lý

Hệ thống thực thi một lớp bảo mật logic nghiêm ngặt tại mọi tầng để loại bỏ "dữ liệu rác".

Thông số	Đơn vị	Dải đo Hợp lệ (Valid)	Ngưỡng Reject (Loại bỏ)	Ngưỡng Alert (Cảnh báo)
PM2.5	µg/m³	0.0 – 1000.0	< 0 hoặc > 2000	> 150 (trên 10 phút)
CO2	ppm	400 – 5000	< 300 hoặc > 10000	> 2000
Nhiệt độ	°C	-10.0 – 60.0	< -40 hoặc > 85	> 50
Độ ẩm	%	0.0 – 100.0	< 0 hoặc > 100	—
Chỉ số AQI	—	0 – 500	< 0 hoặc > 500	> 150
Wi-Fi RSSI	dBm	-100 – 0	> 0	< -80

Kết luận: Với sự phối hợp đồng bộ từ phần cứng ESP32-S3 tối ưu, hạ tầng Backend Java 21 bền bỉ và mô hình AI PyTorch hiện đại, hệ thống AQSS đã sẵn sàng triển khai thực tế, đảm bảo tính toàn vẹn của dữ liệu và giá trị dự báo vượt trội.
