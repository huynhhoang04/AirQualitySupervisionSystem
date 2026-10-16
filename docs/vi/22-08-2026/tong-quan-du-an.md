BÁO CÁO NGHIÊN CỨU & THIẾT KẾ SƠ BỘ HỆ THỐNG GIÁM SÁT CHẤT LƯỢNG KHÔNG KHÍ THỜI GIAN THỰC TÍCH HỢP AIoT (AQSS)

1. GIỚI THIỆU DỰ ÁN & ĐẶT VẤN ĐỀ (PROJECT INTRODUCTION & PROBLEM STATEMENT)

Trong kỷ nguyên đô thị thông minh, việc giám sát chất lượng không khí đã chuyển dịch từ nhu cầu đo đạc cơ bản sang yêu cầu chiến lược về bảo vệ sức khỏe cộng đồng và tối ưu hóa hiệu suất vận hành công trình. Hệ thống AQSS (Air Quality Supervision System) không được thiết kế như một thiết bị ngoại vi đơn thuần, mà là một thực thể AIoT phức hợp, tích hợp chặt chẽ giữa cảm biến nhúng (Embedded), hạ tầng dữ liệu (Backend) và trí tuệ nhân tạo (Deep Learning).

Định nghĩa hệ thống & Phạm vi dữ liệu (Scope): AQSS là hệ thống giám sát, đánh giá và dự báo đa tầng, tập trung quản lý 04 đại lượng vật lý cốt lõi: Nồng độ bụi mịn PM2.5 (µg/m³), nồng độ khí CO2 (ppm), Nhiệt độ (°C) và Độ ẩm (%). Hệ thống được xây dựng dựa trên 03 trụ cột công nghệ:

* Phần cứng nhúng thông minh: Trạm thu thập dữ liệu tại biên (Edge) với khả năng xử lý thời gian thực.
* Hạ tầng đám mây (Backend): Trung tâm điều phối Telemetry Pipeline và quản lý lưu trữ quy mô lớn.
* Mô hình học sâu (Deep Learning): Bộ não phân tích dự báo và tự kiểm chứng độ tin cậy dữ liệu.

Phân tích thực trạng & Tính cấp thiết: Các giải pháp giám sát hiện nay thường bộc lộ những lỗ hổng kỹ thuật nghiêm trọng dẫn đến thất bại trong vận hành thực tế:

* Tính thụ động & Thiếu cảnh báo: Phần lớn chỉ dừng lại ở việc hiển thị chỉ số tĩnh. Hệ thống AQSS đặt ra quy chuẩn bắt buộc về ngưỡng cảnh báo (Alert thresholds): Alert if PM2.5 > 150 µg/m³ (duy trì trên 10 phút) hoặc CO2 > 2000 ppm. Các hệ thống truyền thống thường bỏ qua yếu tố thời gian, dẫn đến việc không đưa ra được phản ứng kịp thời.
* Lỗ hổng độ tin cậy (Sensor Drift): Các cảm biến giá rẻ dễ bị nhiễu do bụi bẩn, côn trùng hoặc suy hao vật lý, tạo ra các cảnh báo giả. AQSS giải quyết vấn đề này bằng cơ chế tự đánh giá (Evaluator) thay vì tin tưởng tuyệt đối vào dữ liệu thô.

Mục tiêu cốt lõi: Hệ thống bắt buộc tuân thủ kiến trúc 4 lớp đồng bộ để đảm bảo khả năng cung cấp "tầm nhìn" dự báo trong 24 giờ tới và thiết lập cơ chế tự lọc dữ liệu rác (Reject) nếu thông số vượt khỏi ranh giới vật lý (ví dụ: PM2.5 > 2000 hoặc CO2 > 10000).

2. KIẾN TRÚC HỆ THỐNG SƠ BỘ 4 LỚP (FOUR-LAYER ARCHITECTURE OVERVIEW)

Kiến trúc AQSS được thiết kế theo tư duy "phân tầng chức năng" (Functional Layering), đảm bảo tính độc lập giữa các module nhưng tối ưu hóa về tốc độ truyền dẫn và khả năng mở rộng (Scalability).

Bảng phân tích kiến trúc (Architecture Stack)

Lớp	Công nghệ chủ đạo	Vai trò cốt lõi	Differentiator (Điểm khác biệt)
1. Embedded	ESP32-S3 (N16R8), FreeRTOS	Thu thập và xử lý tại biên (Edge Computing).	Xử lý đa nhiệm trên 2 Core; lọc nhiễu số tại biên.
2. Backend	Java 21, Spring Boot 3.3, PostgreSQL 16	Quản lý Telemetry Pipeline & Ingestion.	Native Range Partitioning (Monthly) & Materialized Views cho hàng triệu records.
3. Deep Learning	Python 3.11, PyTorch 2.2, FastAPI	Phân tích dự báo và phát hiện bất thường.	Dual-Engine: Predictor (Seq2Seq Attention) & Evaluator (LSTM Autoencoder).
4. Frontend	React 18, TailwindCSS, Vite	Trực quan hóa dữ liệu thời gian thực.	Live Dashboard đồng bộ qua STOMP WebSocket với độ trễ <5ms.

Phân tích dòng chảy dữ liệu (Data Flow): Dữ liệu thô từ cảm biến được đóng gói JSON và đẩy lên Broker qua MQTT (QoS 1) mỗi 5 giây. Tại lớp Backend, thay vì sử dụng cơ chế Polling (truy vấn định kỳ) gây trễ và tốn tài nguyên, hệ thống sử dụng STOMP WebSocket để đẩy trực tiếp telemetry từ Broker đến người dùng cuối ngay khi nhận được, đảm bảo tính tức thời tối đa.

3. LỚP THIẾT BỊ NHÚNG & PHÂN TÍCH LINH KIỆN (EMBEDDED LAYER RATIONALE)

Lớp nhúng là nền tảng thu thập "nhiên liệu" cho AI. Mọi sai sót ở lớp này sẽ làm hỏng toàn bộ logic dự báo phía sau.

Vi điều khiển ESP32-S3-DevKitC-1 (N16R8): Sử dụng CPU dual-core 240 MHz với 16MB Flash và 8MB Octal PSRAM.

* Chiến lược phân bổ tài nguyên (FreeRTOS):
  * Core 0 (PRO_CPU): Cô lập các tác vụ mạng (Wi-Fi, MQTT Stack, NTP) để xử lý jitter.
  * Core 1 (APP_CPU): Chuyên trách đọc cảm biến SCD40, giải mã PWM bụi mịn và render OLED SSD1306.
  * Lưu ý: GPIO 33-37 bị khóa hoàn toàn cho Octal PSRAM, tuyệt đối không sử dụng cho ngoại vi.

Phân tích cảm biến & Quy tắc phần cứng bắt buộc:

1. Sensirion SCD40 (CO2): Sử dụng công nghệ quang âm (NDIR PASens®).
  * Yêu cầu lắp đặt: Nếu dùng cảm biến SMD trần, bắt buộc nối VDDH với VDD. Nếu dùng Module mua sẵn thì chỉ cần cấp nguồn VCC; sử dụng cặp tụ lọc (100nF và 10µF) sát chân nguồn để đảm bảo độ ổn định I2C (địa chỉ 0x62).
2. Sharp GP2Y1023AU0F (Dust):
  * Cảnh báo kỹ thuật: Đây là dòng cảm biến tích hợp MCU nội bộ, xuất tín hiệu Digital PWM. Tuyệt đối không cấp xung cho chân LED vì cảm biến tự điều khiển LED nội bộ.
  * Cơ chế đọc: Sử dụng ngắt ngoài (IRAM_ATTR ISR) kết hợp hàm micros() trên GPIO 18. Nghiêm cấm sử dụng analogRead().
3. Hạn chế ADC2: Tuyệt đối tránh sử dụng các chân thuộc nhóm ADC2 (GPIO 11-20) cho các tác vụ analog khi Wi-Fi đang hoạt động để tránh treo hệ thống. GPIO 18 được dùng làm Digital Input nên đảm bảo an toàn.

Hardware Wiring Rules (I/O Contract):

* I2C Bus: Sử dụng GPIO 8 (SDA) và GPIO 9 (SCL). Tốc độ bus phải được khóa cứng ở 400kHz để phục vụ đồng thời SCD40 và OLED.
* Mạch chia áp (Voltage Divider): Bắt buộc sử dụng trở R1=1kΩ và R2=2kΩ để hạ áp tín hiệu Vo từ 5V xuống 3.3V trước khi đưa vào GPIO 18.

4. Ý TƯỞNG THUẬT TOÁN HỌC SÂU (DEEP LEARNING MODULE CONCEPTS)

Deep Learning chuyển đổi hệ thống từ giám sát bị động sang khả năng tự trị và cảnh báo sớm.

Chiến lược giải quyết 'Cold Start' & Tiền xử lý: Sử dụng Transfer Learning trên các tập dữ liệu lớn (UCI Beijing, OpenAQ) để pre-train trọng số mô hình. Dữ liệu đầu vào bắt buộc được chuẩn hóa qua RobustScaler (thay vì MinMaxScaler) để đảm bảo mô hình không bị "lệch" bởi các điểm outliers (nhiễu cảm biến) vốn rất phổ biến trong môi trường thực tế.

Predictive Engine (Bộ dự báo): Sử dụng kiến trúc Seq2Seq LSTM tích hợp cơ chế Attention.

* Mốc 6:00 AM: Đây là tham số trọng yếu trong mô hình. Dưới góc độ khí tượng, 6:00 AM là thời điểm phá vỡ lớp nghịch nhiệt biên (boundary layer inversion breaking) và bắt đầu chu kỳ giao thông. Cơ chế Attention tập trung trọng số vào mốc này để dự báo chính xác các biến động AQI trong 24 giờ tiếp theo.

Evaluation Engine (Bộ đánh giá & Phát hiện lỗi): Sử dụng mô hình LSTM Autoencoder để tính toán Reliability Score (Điểm tin cậy).

* Hệ thống học cách tái cấu trúc dữ liệu "sạch". Nếu dữ liệu đầu vào có Sai số tái cấu trúc (Reconstruction Error) vượt ngưỡng, hệ thống sẽ gán điểm tin cậy thấp và tự động loại bỏ (Reject) dữ liệu này khỏi bộ dự báo, ngăn chặn việc kích hoạt cảnh báo giả do lỗi vật lý cảm biến.

5. LỘ TRÌNH THỰC THI & KẾ HOẠCH TRIỂN KHAI (PROJECT ROADMAP)

Hệ thống được triển khai theo 4 pha nghiêm ngặt để kiểm soát chất lượng từ lớp vật lý đến lớp ứng dụng.

* Pha 1 (Embedded & HIL Verification): Thiết kế PCB, triển khai cơ chế Zero-point calibration cho cảm biến bụi. Kiểm thử tích hợp phần cứng (Hardware-in-the-Loop).
* Pha 2 (Backend Core & Telemetry Ingestion): Thiết lập PostgreSQL 16 với Flyway để quản lý version. Cấu hình Task Scheduling để tự động tạo partition mới hàng tháng. Tối ưu hóa Batch Write (reWriteBatchedInserts=true).
* Pha 3 (DL Model Training & MLOps): Huấn luyện mô hình trên PyTorch 2.2. Đóng gói Docker với CPU-only PyTorch để tối ưu hóa chi phí hạ tầng và đảm bảo tính di động.
* Pha 4 (Frontend Integration & QA): Xây dựng Dashboard hỗ trợ Dark-mode. Kiểm thử chịu tải (Load Test) với mục tiêu: 100+ thiết bị MQTT hoạt động đồng thời và phục vụ 500+ Client WebSocket với độ trễ <5ms.

Cam kết chất lượng: AQSS cam kết vận hành 24/7 với dữ liệu được kiểm chứng qua hai tầng (Vật lý & AI). Đây là một giải pháp AIoT công nghiệp toàn diện, sẵn sàng cho việc triển khai thực tiễn trong các hạ tầng đô thị hiện đại.
