TÀI LIỆU KHẢO SÁT LINH KIỆN NHÚNG & KIẾN TRÚC MẠNG LƯỚI NODE CẢM BIẾN (AQSS)

Trong hệ sinh thái Giám sát Chất lượng Không khí (AQSS), lớp nhúng (Embedded Layer) đóng vai trò là "tiền tuyến" thu thập dữ liệu thô. Sự phối hợp giữa vi điều khiển hiệu năng cao và các cảm biến chuyên dụng không chỉ đơn thuần là vấn đề kết nối vật lý, mà là yếu tố then chốt đảm bảo tính toàn vẹn và độ chính xác của dữ liệu đầu vào. Đây là nền tảng bắt buộc để các mô hình học sâu (Deep Learning) ở lớp trên có thể thực hiện dự báo chính xác, tránh hiện tượng "Garbage In, Garbage Out".

MỤC 1: CHI TIẾT KỸ THUẬT & HƯỚNG DẪN ĐẤU NỐI LINH KIỆN NHÚNG

1.1. Vi điều khiển trung tâm: ESP32-S3-DevKitC-1 (WROOM-1 N16R8)

ESP32-S3 là SoC hiệu năng cao được thiết kế cho AIoT với các tập lệnh tăng tốc tính toán vector. Phiên bản N16R8 cung cấp tài nguyên dồi dào để quản lý đa nhiệm phức tạp.

Phân bổ tài nguyên và Cơ chế xử lý:

* Dual-Core LX7 (240MHz): Hệ thống vận hành trên FreeRTOS.
  * Core 0 (PRO_CPU): Chuyên trách ngăn xếp kết nối (Wi-Fi, MQTT Stack, NTP Sync).
  * Core 1 (APP_CPU): Xử lý logic cảm biến, tính toán chỉ số AQI thời gian thực và render màn hình OLED.
* Bộ nhớ chiến lược: 16MB Flash và 8MB Octal PSRAM.
  * So What? Việc sở hữu 8MB PSRAM là bắt buộc để duy trì bộ đệm ngoại tuyến (Offline Buffer) dung lượng lớn mà không xâm lấn vào Heap nội bộ của Wi-Fi Stack, tránh gây treo hệ thống khi thực hiện tuần tự hóa (serialization) các bản tin JSON phức tạp bằng ArduinoJson v7.

Hướng dẫn đấu nối & Danh mục chân hạn chế (Restricted Pins):

Cảm biến/Thiết bị	Chân tín hiệu	Chân ESP32-S3	Giao thức
SCD40 & OLED	SDA / SCL	GPIO 8 / GPIO 9	I2C (Shared Bus)
GP2Y1023	Vo (Output)	GPIO 18	Digital PWM

Ràng buộc phần cứng nghiêm ngặt:

* Vùng cấm địa (Off-limits): Tuyệt đối không sử dụng GPIO 33-37 (chiếm dụng bởi Octal PSRAM), GPIO 19, 20 (dành cho USB D-/D+) và GPIO 43, 44 (UART0 dùng để nạp/debug).
* Xung đột Wi-Fi & ADC2: Nhóm chân GPIO 11-20 thuộc ADC2 sẽ bị vô hiệu hóa khi bật Wi-Fi.
  * So What? Mặc dù GPIO 18 nằm trong nhóm này, nhưng hệ thống vẫn sử dụng an toàn vì cấu hình nó ở chế độ Digital Input (đọc PWM), không sử dụng bộ chuyển đổi Analog-to-Digital nội bộ.

1.2. Cảm biến bụi mịn: Sharp GP2Y1023AU0F

Đây là dòng cảm biến tích hợp MCU nội bộ, xuất tín hiệu Digital PWM, ưu việt hơn hẳn dòng analog GP2Y1010/1014 về khả năng chống nhiễu.

Quy trình đấu nối (Cáp JST GH 5-pin):

1. Pin 1 (Vo): Tới GPIO 18 (Bắt buộc qua mạch chia áp 1kΩ / 2kΩ để hạ mức logic 5V xuống 3.3V).
2. Pin 2 (LED-GND): Nối GND (Star ground tách biệt).
3. Pin 3 (GND): Nối GND hệ thống.
4. Pin 4 (V_LED): Cấp 5V qua mạch lọc RC tách rời (Trở 150Ω và tụ hóa 220µF) để bù dòng đỉnh cho LED hồng ngoại.
5. Pin 5 (Vcc): Cấp 5V định mức (±5%).

Thuật toán xử lý tín hiệu:

* Công thức: PM2.5 = (W - W0) / 14.0.
  * Trong đó W là độ rộng xung mức cao tính bằng micro giây (\mu s).
  * Hệ số nhạy tiêu chuẩn là 14 \mu s / (\mu g/m^3).
* Chu kỳ xung: Cảm biến vận hành với chu kỳ cố định 10ms (100Hz).
  * So What? Kỹ sư bắt buộc phải sử dụng ngắt ngoài (IRAM_ATTR ISR) kết hợp hàm micros() để đo thời gian thực. Việc sử dụng analogRead() hoặc pulseIn() thông thường sẽ gây block CPU và làm "rách" dữ liệu thời gian của các tác vụ khác.

1.3. Cảm biến khí CO2: Sensirion SCD40

SCD40 sử dụng nguyên lý quang âm (NDIR Photoacoustic) cho độ chính xác vượt trội trong kích thước siêu nhỏ.

Yêu cầu lắp đặt kỹ thuật:

* Điểm đấu nối cốt yếu: Chân số 2 (VDDH) phải được nối tắt trực tiếp với VDD ngay trên PCB. Việc để nổi chân này là nguyên nhân phổ biến khiến cảm biến không khởi động.
* Mạch lọc nguồn: Sử dụng cặp tụ decoupling (100nF + 10µF) đặt sát chân nguồn cảm biến để xử lý đỉnh dòng 205 mA. Nguồn cấp phải đạt độ gợn sóng (ripple) < 30 mV p-p.

Lớp phân tích chiến lược: Sau khi gửi lệnh start_periodic_measurement (địa chỉ I2C 0x62), hệ thống phải thực hiện lệnh vTaskDelay ít nhất 5 giây.

* So What? Khoảng nghỉ này cho phép bóng phát tia IR đạt trạng thái ổn định nhiệt động lực học. Việc đọc dữ liệu sớm hơn sẽ dẫn đến sai số hệ thống (systemic bias) không thể khắc phục bằng phần mềm.

1.4. Màn hình hiển thị: OLED SSD1306 0.96 inch

Cấu hình Bus I2C dùng chung:

* Chia sẻ bus với SCD40 trên GPIO 8/9. Tần số bus phải khóa cố định ở 400 kHz.
* So What? Việc ép xung (overclock) bus I2C hoặc nhiễu do điện dung đường dây quá lớn sẽ gây lỗi kiểm tra mã dư vòng (CRC) trên SCD40, khiến bus bị treo và làm mất cửa sổ lấy mẫu 5 giây, gây gián đoạn chuỗi dữ liệu gửi về Layer 3.
* Cảnh báo an toàn: Kiểm tra kỹ thứ tự chân nguồn. Màn hình Type A (GND-VCC) và Type B (VCC-GND) có ngoại hình giống hệt nhau; đấu ngược sẽ gây cháy chip driver SSD1306 ngay lập tức.

MỤC 2: KIẾN TRÚC MẠNG LƯỚI KẾT NỐI CÁC NODE NHÚNG

Hệ thống AQSS dịch chuyển từ mô hình thiết bị đơn lẻ sang mạng lưới node cảm biến thời gian thực, tối ưu hóa cho khả năng mở rộng và chịu lỗi.

2.1. Mô hình mạng và Giao thức MQTT

Mạng lưới vận hành theo cấu trúc hình sao (Hub-and-Spoke):

* Transport: Wi-Fi 2.4 GHz (802.11b/g/n).
* Protocol: MQTT v3.1.1 qua Broker Eclipse Mosquitto (Port 1883).
* So What? MQTT vượt trội hơn HTTP nhờ cơ chế giữ kết nối (Keep-alive) và overhead bản tin cực thấp, giúp giảm tải cho CPU ESP32-S3 khi phải xử lý đồng thời nhiều cảm biến.

2.2. Luồng dữ liệu Telemetry (Node -> Server)

Bản tin JSON được đóng gói và xuất bản (Publish) với QoS 1 để đảm bảo máy chủ nhận được ít nhất một lần.

Cấu trúc bản tin Telemetry JSON:

{
  "device_id": "AQSS_ESP32S3_01",
  "timestamp": 1715603400,
  "sensors": {
    "pm25": 12.5,
    "co2": 450,
    "temperature": 28.4,
    "humidity": 65.2
  },
  "computed": {
    "aqi_index": 45,
    "aqi_level": "Good"
  },
  "system": {
    "wifi_rssi": -65,
    "uptime_sec": 3600,
    "free_heap": 185400,
    "min_free_heap": 124000
  }
}


Phân tích chiến lược: Giám sát Min Free Heap quan trọng hơn Free Heap hiện tại.

* So What? Trên ESP32-S3, Min Free Heap cho thấy mức đỉnh sử dụng bộ nhớ trong quá trình serialization JSON. Nếu giá trị này tiệm cận 0, thiết bị sẽ crash do lỗi cấp phát bộ nhớ (Allocation Failed) dù Free Heap trung bình vẫn cao.

2.3. Luồng điều khiển Commands (Server -> Node)

Node lắng nghe tại aqss/devices/{device_id}/command để thực thi các lệnh: reboot, set_interval, recalibrate_dust.

Cơ chế hiệu chuẩn chiến lược: Lệnh recalibrate_dust yêu cầu node ghi lại giá trị W trong môi trường không khí sạch (Clean Air) làm mốc W0 mới. Giá trị này được lưu vào Non-Volatile Storage (NVS/EEPROM).

* So What? Việc lưu trữ vào NVS giúp cảm biến duy trì độ chính xác sau khi mất nguồn, chống lại hiện tượng trôi (drift) dữ liệu do bụi bám vào thấu kính hồng ngoại sau thời gian dài vận hành.

2.4. Cơ chế chịu lỗi và Offline Buffer (Ring Buffer)

Khi mất kết nối Wi-Fi, node kích hoạt Ring Buffer lưu trữ 60 bản ghi (tương đương 5 phút dữ liệu) vào vùng nhớ PSRAM.

Lớp phân tích "So What?": Cơ chế Batch-upload sau khi có mạng trở lại là yếu tố sống còn cho Layer 3. Các mô hình Seq2Seq LSTM dự báo không khí cực kỳ nhạy cảm với hiện tượng "rách" dữ liệu (missing temporal steps). Một khoảng trống 5 phút không có dữ liệu sẽ làm sai lệch hoàn toàn vector trạng thái ẩn (hidden state) của mô hình, dẫn đến dự báo AQI sai lầm.

Tổng kết: Tài liệu này xác lập quy chuẩn kỹ thuật từ lớp vật lý đến lớp mạng. Việc tuân thủ khắt khe các ràng buộc về GPIO, mạch lọc nguồn và cơ chế đệm dữ liệu trên PSRAM là điều kiện tiên quyết để xây dựng một hệ thống AQSS ổn định, sẵn sàng cho các bài toán phân tích dữ liệu lớn và AI.
