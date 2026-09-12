### 1. Kiến Trúc Truyền Thông Chủ Đạo: Giao Thức MQTT v3.1.1
Mối liên kết trực tiếp giữa các node cảm biến nhúng ESP32-S3 và máy chủ Spring Boot được thực hiện qua giao thức **MQTT v3.1.1**. Đây là giao thức truyền thông điệp dạng Publish/Subscribe gọn nhẹ nhất, tối ưu hóa băng thông truyền tải qua Wi-Fi và giảm thiểu điện năng tiêu thụ cho chip nhúng.

*   **Intermediary Middleware (Broker):** Hệ thống sử dụng **Eclipse Mosquitto Broker** (cấu hình mặc định chạy trên Port **1883**) làm máy chủ trung gian để tiếp nhận và phân phối các gói tin.
*   **Thư viện hiện thực phần cứng (Client-side):** Sử dụng thư viện **PubSubClient** trên PlatformIO để lập trình điều khiển kết nối MQTT trên vi điều khiển ESP32-S3.
*   **Hạ tầng tiếp nhận phía máy chủ (Server-side):** Backend Spring Boot sử dụng module **Spring Integration MQTT** kết hợp với client **Eclipse Paho v3** cấu hình cơ chế tự động khôi phục kết nối (`automaticReconnect=true`, chu kỳ thử lại `recoveryInterval=5000ms`) để đảm bảo hệ thống vận hành liên tục.
---

### 2. Sơ Đồ Thiết Kế Các Topic MQTT & Chất Lượng Dịch Vụ (QoS)
Để đảm bảo tính toàn vẹn dữ liệu và phân tách rõ ràng giữa dữ liệu đo đạc (Telemetry) với lệnh điều khiển (Commands), bus truyền thông MQTT được chia thành 3 kênh topic chính:

| Tên Topic | Hướng Đi | Mức Độ QoS | Tần Suất | Mô Tả Chức Năng |
| :--- | :--- | :--- | :--- | :--- |
| `aqss/devices/{device_id}/telemetry` | Node \\(\rightarrow\\) Broker \\(\rightarrow\\) Server | **QoS 1 (At Least Once)** | Mỗi **5 giây/lần** | Gửi dữ liệu đo đạc thời gian thực từ cảm biến. |
| `aqss/devices/{device_id}/status` | Node \\(\rightarrow\\) Broker \\(\rightarrow\\) Server | **QoS 1** | Khi trạng thái thay đổi | Báo nhịp tim (heartbeat) hoạt động của thiết bị hoặc kích hoạt cơ chế *Last Will and Testament* (L وصية cuối cùng) gửi trạng thái `OFFLINE` nếu node đột ngột mất nguồn/mất mạng. |
| `aqss/devices/{device_id}/command` | Server \\(\rightarrow\\) Broker \\(\rightarrow\\) Node | **QoS 1** | Bất đồng bộ (On-demand) | Nhận lệnh điều khiển trực tiếp từ Admin Dashboard của Backend. |

*Lưu ý: Mức độ tin cậy **QoS 1** bắt buộc phải sử dụng để đảm bảo mọi gói tin telemetry và lệnh điều khiển đều phải được xác nhận phân phối thành công thông qua gói tin bắt tay `PUBACK`, ngăn ngừa hiện tượng mất mát dữ liệu do nhiễu sóng Wi-Fi.*

---

### 3. Cấu Trúc Gói Tin JSON Telemetry (Payload Schema)
Để giảm tải điện năng tính toán xử lý chuỗi trên ESP32-S3, bản tin JSON được thiết kế ở dạng phẳng (flat structure) với tổng dung lượng nén dưới **384 bytes**:

```json
{
  "device_id": "AQSS_ESP32S3_01",
  "timestamp": 1787265035,
  "sensors": {
    "pm25": 35.5,
    "co2": 820.0,
    "temperature": 28.4,
    "humidity": 65.2
  },
  "computed": {
    "aqi_index": 98,
    "aqi_level": "Moderate"
  },
  "system": {
    "wifi_rssi": -65,
    "uptime_sec": 7200,
    "free_heap": 285420
  }
}
```

#### Ràng Buộc Kiểm Chuẩn Vật Lý (Physical Boundary Validation Rules) tại Backend:
Khi nhận được payload JSON từ cổng MQTT, Backend Spring Boot sẽ thực thi bộ lọc kiểm chuẩn dữ liệu trước khi chèn vào Database:
*   **PM2.5:** Phải nằm trong dải \\([0.0 - 1000.0]\text{ }\mu\text{g/m}^3\\). Gói tin sẽ bị **loại bỏ** (Reject) lập tức nếu dưới `0` hoặc vượt quá `2000`. Nếu giá trị vượt quá `150` liên tiếp trong **10 phút**, Backend tự động trigger một thông báo cảnh báo nguy hiểm.
*   **CO2:** Phải nằm trong dải \\(\text{ ppm}\\). Bị loại bỏ nếu `< 300` hoặc `> 10000`. Kích hoạt cảnh báo nguy cấp nếu nồng độ vượt ngưỡng `2000 ppm`.
*   **Nhiệt độ & Độ ẩm:** Nhiệt độ giới hạn \\([-10.0 - 60.0]^\circ\text{C}\\), độ ẩm \\([0.0 - 100.0]\%\\). Tránh các giá trị rác khi cảm biến khởi động hoặc đo đạc sai lệch.
*   **Wi-Fi RSSI:** Giới hạn \\([-100 - 0]\text{ dBm}\\). Nếu chỉ số RSSI rơi xuống dưới `-80 dBm` (sóng quá yếu), hệ thống sẽ ghi nhận cảnh báo chất lượng kết nối của node nhúng chập chập.

---

### 4. Giao Thức Nhận Lệnh Điều Khiển Phía Nhúng (Commands Schema)
Node nhúng lắng nghe trên topic `aqss/devices/{device_id}/command`. Khi Admin kích hoạt hành động qua REST API trên Backend, máy chủ sẽ publish gói tin JSON ra topic này:

*   **Lệnh Reboot Thiết Bị:**
    *   Payload: `{"command": "reboot", "params": {}}`
    *   Hành vi của Node: Firmware parse JSON, gọi hàm `ESP.restart()` của Arduino core để khởi động lại chip.
*   **Lệnh Thay Đổi Chu Kỳ Đo/Gửi Dữ Liệu:**
    *   Payload: `{"command": "set_interval", "params": {"interval_ms": 10000}}`
    *   Hành vi của Node: Cập nhật biến cấu hình chu kỳ gửi trong Task FreeRTOS từ mặc định 5000ms thành 10000ms.
*   **Lệnh Hiệu Chuẩn Lại Cảm Biến Bụi Mịn:**
    *   Payload: `{"command": "recalibrate_dust", "params": {"baseline_w0": 1350}}`
    *   Hành vi của Node: Thay đổi thông số độ rộng xung nền \\(W_0\\) (Zero-Point Calibration) lưu trữ cố định vào phân vùng bộ nhớ không bay màu (EEPROM/Flash) để tự động bù sai số lệch cảm biến.
*   **Lệnh Đồng Bộ NTP Lập Tức:**
    *   Payload: `{"command": "force_ntp_sync", "params": {}}`
    *   Hành vi của Node: Ép buộc kích hoạt hàm đồng bộ thời gian từ pool máy chủ NTP trung tâm (`pool.ntp.org`) để đảm bảo timestamp của gói tin chính xác tuyệt đối.

---

### 5. Thiết Kế Đường Ống Thu Thập & Chịu Tải Phía Backend (Spring Boot Ingestion Pipeline)
Để hệ thống không bị nghẽn (database write bottleneck) khi số lượng node nhúng mở rộng quy mô lớn (hơn 100 node hoạt động đồng thời gửi gói tin mỗi 5 giây), Spring Boot thiết lập kiến trúc Ingestion Pipeline bất đồng bộ:

```
[ESP32-S3 Node] --(MQTT QoS 1)--> [Mosquitto Broker] --(MqttPahoMessage)--> [Spring Boot Ingestion]
                                                                                   |
                                                 +---------------------------------+---------------------------------+
                                                 |                                                                   |
                                    (WebSocket Bridge - Immediate)                                     (Asynchronous Batch Write)
                                                 |                                                                   |
                                                 v                                                                   v
                                     [STOMP WebSocket /topic]                                            [LinkedBlockingQueue]
                                                 |                                                                   |
                                                 v (<5ms Latency)                                            (Flush 1s or 500 records)
                                         [React Dashboard]                                                   |
                                                                                                             v (Hibernate Batch)
                                                                                                         [PostgreSQL Database]
```

*   **Cầu Nối Thời Gian Thực Cực Thấp (WebSocket Bridge):** 
    Ngay khi thông điệp MQTT Telemetry vừa được Adapter tiếp nhận ở lớp ứng dụng, Backend sẽ thực thi hàm truyền phát `SimpMessagingTemplate.convertAndSend()` để đẩy trực tiếp dữ liệu đo đạc lên kênh WebSocket STOMP `/topic/devices/{device_id}`. Quá trình này hoàn tất trong thời gian **dưới 5ms**, giúp giao diện React của khách hàng cập nhật tức thời số liệu mà không cần đợi Backend hoàn thành việc ghi dữ liệu vào đĩa cứng vật lý.
*   **Hàng Đợi Lưu Trữ Không Chặn (Non-blocking Ingest Buffer):**
    Dữ liệu sau khi kiểm chuẩn sẽ không ghi trực tiếp từng dòng vào cơ sở dữ liệu (vì ghi đơn lẻ sẽ làm nghẽn kết nối I/O của PostgreSQL). Thay vào đó, dữ liệu được gom vào một hàng đợi an toàn đa luồng **`LinkedBlockingQueue`** (sức chứa tối đa 10,000 bản ghi).
*   **Tối Ưu Ghi Lô Hàng Loạt (PostgreSQL Batch Writes):**
    Định kỳ mỗi **1 giây** hoặc khi hàng đợi chạm mốc **500 bản ghi**, một tác vụ lập lịch `@Scheduled` của Spring Boot sẽ tự động drain (rút) sạch hàng đợi và thực thi câu lệnh ghi theo lô (batch insert) thông qua JPA/Hibernate. 
    Tại file cấu hình kết nối database `application.yml`, Backend khóa cứng thuộc tính `reWriteBatchedInserts=true` giúp ép PostgreSQL gộp hàng trăm câu lệnh `INSERT` riêng rẽ thành một truy vấn chèn lô duy nhất (`INSERT INTO telemetry_data VALUES (...), (...), (...)`), nâng tốc độ ghi thực tế của Database lên **gấp 10 lần**.

---

### 6. Giao Thức Dự Phòng Tránh Mất Mát Dữ Liệu Ngoại Tuyến (Offline Ring Buffer)
Khi node nhúng gặp sự cố đứt kết nối mạng Wi-Fi hoặc Broker Mosquitto đột ngột dừng hoạt động:
1.  Node ESP32-S3 tự động nhận diện mất kết nối và kích hoạt chế độ dự phòng rủi ro.
2.  Node lưu trữ tạm thời các gói telemetry đo đạc được vào một cấu trúc dữ liệu **Bộ đệm vòng (Ring Buffer)** khai báo tĩnh trên phân vùng bộ nhớ SRAM. Bộ đệm này được cấu hình giới hạn tối đa lưu trữ **60 bản ghi** liên tiếp (tương đương dung lượng lưu trữ dự phòng cho **5 phút hoạt động ngoại tuyến**).
3.  Khi đường truyền mạng hồi phục, node nhúng tự động tái kết nối Broker MQTT, tiến hành đóng gói hàng loạt các gói dữ liệu tạm thời trong bộ đệm và gửi đồng loạt (Batch Upload) lên server.
4.  Nhờ có trường `timestamp` (Unix Epoch dạng số nguyên 32-bit lấy từ lúc đo đạc thực tế) lưu trong JSON, Backend sẽ bóc tách và ghi nhận chính xác thời gian xảy ra dữ liệu trong quá khứ xuống các phân vùng (monthly partitioning) của database PostgreSQL. Cơ chế này triệt tiêu hoàn toàn hiện tượng "rách dữ liệu lịch sử" (data gaps) - một yếu tố chí mạng giúp đảm bảo độ chính xác cho mô hình Học sâu (Layer 3 PyTorch) khi phân tích xu hướng chuỗi thời gian.

---

### 7. Bảo Mật Lớp Kết Nối API & Quyền Hạn (Device Auth & REST API)
Mặc dù luồng telemetry chính đi qua MQTT, node nhúng vẫn có thể kết nối gián tiếp qua giao thức HTTP REST cho một số tác vụ đặc biệt (như gọi API đăng ký node tự động hoặc nạp file Firmware nhị phân từ xa qua Wi-Fi bằng giao thức OTA).

*   **Cơ Chế Xác Thực Kép (Dual Filter Chain):**
    Hệ thống Spring Boot triển khai một bộ lọc bảo mật kép. Trong khi người dùng truy cập Dashboard qua trình duyệt sử dụng token **JWT** (`Authorization: Bearer <JWT>`), các thiết bị nhúng sẽ thực hiện xác thực thông qua HTTP Header **`X-API-Key`**.
*   **Bảo Mật API Key Phần Cứng:**
    API Key được cấp phát duy nhất cho từng thiết bị nhúng khi Admin kích hoạt đăng ký node mới trên hệ thống. Phía máy chủ chỉ lưu trữ chuỗi băm **SHA-256** của API Key này (`devices.api_key_hash`) để triệt tiêu nguy cơ rò rỉ mã nguồn hoặc lộ thông tin bảo mật của node nhúng từ cơ sở dữ liệu.

---
