# Air Quality Supervision System — Kiến thức hoạt động và vấn đáp

Bộ học chi tiết đi kèm [slide HTML](slides/hoat-dong-chi-tiet.html). Mỗi mục có câu hỏi, lời giải và đoạn mã trích trực tiếp từ source. Các hạn chế là suy luận tĩnh từ code, chưa phải kết quả đo hoặc kiểm thử thực tế. Không thay thế bài nói 12 phút.

## Mục lục

1. Đọc toàn bộ vòng hoạt động

2. Khởi động: thứ tự nào quyết định an toàn?

3. Đường nguồn và đường tín hiệu

4. GP2Y1023: đo độ rộng xung LOW

5. Từ xung đến nồng độ bụi

6. SCD40: periodic, data-ready, trung bình

7. FreeRTOS: tác vụ, ưu tiên và mutex

8. Wi-Fi, portal, NTP và MQTT

9. MQTT: QoS, retained và LWT

10. Một mẫu telemetry đi vào backend

11. Validation: null, zero và dải hợp lệ

12. PostgreSQL: quan hệ và truy vấn

13. Ngưỡng: công thức đang tính những gì?

14. CO₂ tham gia ở đâu? Ngưỡng có tự đổi?

15. ControlLoop: hai cửa vào, cùng bộ đánh giá

16. State machine AUTO và hysteresis

17. Ví dụ thời gian để tự tính quyết định

18. Lệnh quạt: ý định → broker → node

19. PWM, kickstart và nút MANUAL

20. Phản hồi trạng thái và giới hạn bộ đếm

21. Mất mạng: bộ đệm thực sự nằm ở nhánh nào?

22. Lease AUTO và fail-safe của firmware

23. Ghép nối và hủy liên kết hai phía

24. Dashboard: polling và cache sau thao tác

25. Biểu đồ: dữ liệu vẽ khác dữ liệu điều khiển

26. Tổng hợp các chỗ phải trả lời đúng giới hạn


## 01. Đọc toàn bộ vòng hoạt động

Air Quality Supervision System · Bộ học kỹ thuật và chuẩn bị vấn đáp

**Luồng:** Cảm biến: PWM / I²C → ESP32-S3: Snapshot → JSON → Backend: Lưu → quyết định → Quạt + UI: PWM / REST


- Học theo một mẫu đo: nguồn dữ liệu → truyền → lưu → quyết định → phản hồi.

- Mỗi trang có sơ đồ, trích đoạn source và câu hỏi tự kiểm tra.

- Tên hàm và số dòng lấy từ source khi dựng bộ slide.


**Câu hỏi:** Backend gửi lệnh xong có biết quạt thực sự quay chưa?

**Trả lời:** Chưa. Node phản hồi duty đã áp dụng, nhưng code chưa đọc tachometer/RPM hoặc cảm biến lưu lượng. Lệnh và phản hồi phần mềm chưa chứng minh quạt quay hay hiệu quả lọc.

**Giải thích thêm:** Bộ này phục vụ học sâu, tách khỏi bài báo cáo 12 phút. Nhận xét về hạn chế là kết luận từ luồng source, chưa phải kết quả đo trên thiết bị. Mở phần giải thích dưới từng slide hoặc đọc tài liệu Markdown đi kèm.


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 185:

```
static void publishActuatorState() {
    const SensorData_t d = dataSnapshot();

    JsonDocument doc;
    doc["device_id"] = AQSS_DEVICE_ID;
    doc["timestamp"] = d.timestamp;
    doc["state"] = d.fan_state ? "ON" : "OFF";
    doc["level"] = d.fan_level;
    doc["duty_pct"] = d.fan_duty_pct;
    doc["mode"] = d.fan_mode == FAN_MODE_AUTO ? "AUTO" : "MANUAL";
```


## 02. Khởi động: thứ tự nào quyết định an toàn?

Theo setup() trong main.cpp; bắt đầu bằng đặt PWM quạt về 0.

**Luồng:** fanBegin: LEDC = 0 → NVS + dataInit: Khôi phục cấu hình → Wire + OLED: Khởi tạo bus → WDT + 5 task: Chạy hai lõi


- fanBegin() chạy trước cấu hình mạng để sớm điều khiển chân PWM.

- Wire.begin() chỉ được gọi một lần trong setup.

- Giữ nút lúc khởi động 3 giây đặt cờ mở portal.


**Câu hỏi:** Vì sao không chờ Wi-Fi rồi mới khởi tạo quạt?

**Trả lời:** Đầu vào PWM quạt có pull-up; cần chủ động đưa PWM về 0 sớm. Kết nối mạng có thể chậm hoặc thất bại, nên không để việc khởi tạo quạt phụ thuộc mạng.

**Giải thích thêm:** Không suy ra rằng PWM 0 bảo đảm mọi mẫu quạt dừng hoàn toàn: đây là lệnh phần mềm, khả năng dừng phụ thuộc quạt thực tế. GPIO 33–37 dành cho Octal PSRAM; pin map hiện dùng 5, 6, 7, 8, 9, 18.


[embedded/src/main.cpp](../embedded/src/main.cpp) — từ dòng 38:

```
fanBegin();

configBegin();
const RuntimeConfig cfg = configSnapshot();
dataInit(cfg.space_id, cfg.dust_baseline_us, cfg.filter_runtime_s);

// Button held on power-up (3 s) re-opens the Wi-Fi setup portal.
pinMode(PIN_BTN, INPUT_PULLUP);
if (digitalRead(PIN_BTN) == LOW) {
    const uint32_t held = millis();
```


## 03. Đường nguồn và đường tín hiệu

Nguồn quạt, nguồn logic và tín hiệu điều tốc có vai trò khác nhau.

**Luồng:** 12 V phân nhánh: quạt pin 2 và LM2596 → 5 V → MCU. MCU GPIO5 → quạt pin 4; GND chung.


- Quạt dùng +12 V liên tục và GND chung; tốc độ qua chân PWM riêng.

- SCD40 0x62 và OLED 0x3C cùng bus I²C 400 kHz.

- Ảnh Fritzing còn nhãn GP2Y1010; firmware viết cho GP2Y1023.


**Câu hỏi:** Hai cảm biến Sharp có thay thế thuật toán cho nhau được không?

**Trả lời:** Không. Firmware hiện đọc xung kỹ thuật số của GP2Y1023. GP2Y1010 có cách kích LED và đọc analog khác; không được dùng hình minh họa để kết luận hai loại tương đương.

**Giải thích thêm:** Cầu chia áp bụi 1 kΩ / 2 kΩ giảm mức điện áp đầu ra trước GPIO. Sơ đồ kết nối là minh họa; cần đối chiếu đúng linh kiện lắp thực tế trước khi bảo vệ. Không có đường tachometer trong pin map hiện tại.


[embedded/include/config.h](../embedded/include/config.h) — từ dòng 19:

```
#define PIN_I2C_SDA 8
#define PIN_I2C_SCL 9
#define PIN_DUST_PWM 18
#define PIN_FAN_PWM 5
#define PIN_BTN 6
#define PIN_STATUS_LED 7

// ─── I2C bus (SCD40 + SSD1306 share it; lock at 400 kHz) ─────────────────────
#define I2C_CLOCK_HZ 400000
#define I2C_ADDR_OLED 0x3C
#define I2C_ADDR_SCD40 0x62

```


## 04. GP2Y1023: đo độ rộng xung LOW

Ngắt CHANGE ghi thời điểm hai cạnh; chu kỳ danh định khoảng 10 ms.

**Luồng:** Cạnh xuống: t₀ → Trong mức LOW: W = t₁ − t₀ → Cạnh lên: t₁ → Task bụi: Nhận W mới


- ISR có IRAM_ATTR; đồng hồ dùng esp_timer_get_time(), không analogRead().

- Cạnh xuống phải cách khung đã nhận ít nhất 8000 µs.

- Code chấp nhận W từ 7 đến 8000 µs; tài liệu dự án quy định trần 7000 µs.


**Câu hỏi:** Tại sao không đo bằng delay trong loop?

**Trả lời:** Đợi theo vòng lặp dễ bỏ cạnh khi task khác hoặc mạng chiếm thời gian. Ngắt ghi dấu thời gian ở cạnh; phần trung bình và quy đổi chuyển ra task để tránh làm ISR dài.

**Giải thích thêm:** Vo nhàn rỗi HIGH, xung cần đo là phần LOW. Khi cạnh xuống quá gần khung trước, s_lowValid bị xóa. Cạnh lên chỉ được chấp nhận khi có một cạnh xuống hợp lệ. Kiểm tra 8 ms là lọc nhiễu theo khung, không chứng minh đo đúng mọi nhiễu.


[embedded/src/dust_sensor.cpp](../embedded/src/dust_sensor.cpp) — từ dòng 25:

```
static void IRAM_ATTR dustIsr() {
    const uint32_t now = (uint32_t)esp_timer_get_time();
    s_edgeCount = s_edgeCount + 1;

```


[embedded/src/dust_sensor.cpp](../embedded/src/dust_sensor.cpp) — từ dòng 38:

```
} else if (s_lowValid) {
    const uint32_t width = now - s_lowStartUs;
    s_lowValid = false;

    if (width >= DUST_PULSE_MIN_US && width <= DUST_PULSE_ACCEPT_MAX_US) {
        s_widthUs = width;
        s_widthSeq = s_widthSeq + 1;
    } else {
        s_glitchCount = s_glitchCount + 1;
```


## 05. Từ xung đến nồng độ bụi

W̄ → trừ baseline → chia độ nhạy → giới hạn dải.

**Luồng:** s_widthSeq: Phát hiện dữ liệu mới → Cửa sổ 1 s: Tổng W / số mẫu → (W̄ − W₀) / 14: W₀ mặc định 1400 µs → clamp 0…500: Ghi snapshot


- Ví dụ tính toán: W̄ = 1680 µs → (1680 − 1400)/14 = 20 µg/m³.

- Task thăm dò mỗi 5 ms; không có queue giữ toàn bộ các xung.

- dust_saturated đặt từ W̄ ≥ 6900 µs; mất xung >3 s đặt dust_fault.


**Câu hỏi:** dust_fault có khiến PM2.5 gửi thành null ngay không?

**Trả lời:** Không trong code hiện tại. Khi thiếu mẫu, giá trị cũ và dust_ready vẫn giữ. currentSample() dùng dust_ready làm cờ hợp lệ, nên có thể gửi lại PM cũ với timestamp mới.

**Giải thích thêm:** Cửa sổ 1 s tổng hợp các giá trị mà task nhìn thấy qua biến volatile; đó không phải trung bình được bảo đảm của mọi xung. Volatile không khóa cặp width/seq thành một snapshot nguyên tử. Cần critical section hoặc queue nếu muốn bảo đảm đọc nhất quán và không mất mẫu.


[embedded/src/dust_sensor.cpp](../embedded/src/dust_sensor.cpp) — từ dòng 83:

```
if (haveSamples) {
    float pm25 = (avgUs - g_sensorData.dust_baseline_us) / DUST_SENSITIVITY_US_PER_UGM3;
    if (pm25 < 0.0f) {
        pm25 = 0.0f;
    }
    if (pm25 > PM25_MAX_UGM3) {
        pm25 = PM25_MAX_UGM3;
    }
    g_sensorData.pm25_ugm3 = pm25;
    g_sensorData.dust_pulse_us = avgUs;
```


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 145:

```
sample.pm25_valid = d.dust_ready;
sample.co2_valid = d.scd40_ready;
sample.pm25_saturated = d.dust_saturated;
```


## 06. SCD40: periodic, data-ready, trung bình

Chu kỳ cảm biến 5 s khác với chu kỳ task kiểm tra 1 s.

**Luồng:** startPeriodic: Bắt đầu đo → Poll mỗi 1 s: Data ready? → Kiểm tra số đo: Đưa vào ring 5 mẫu → Trung bình: Ghi CO₂ / T / RH


- Chưa ready → tiếp tục đợi, không tạo mẫu giả.

- Trung bình số mẫu hiện có n ≤ 5; không chờ đủ 5 mới hiển thị.

- Sau 5 lỗi giao tiếp liên tiếp, restartMeasurement() được gọi.


**Câu hỏi:** Lấy trung bình 5 mẫu có giá phải trả gì?

**Trả lời:** Giảm dao động nhưng làm phản ứng hiển thị chậm hơn. Với mẫu cách nhau khoảng 5 s, năm mẫu có tuổi trải khoảng 20 s; không nên mô tả giá trị trung bình là số đo tức thời chưa lọc.

**Giải thích thêm:** Các mảng CO₂/T/RH dùng cùng ringIndex, nên một bộ số đo cập nhật cùng nhau. scd40_ready không tự xóa sau lỗi kéo dài; cần phân biệt sensor freshness với timestamp đóng gói telemetry.


[embedded/src/scd40_sensor.cpp](../embedded/src/scd40_sensor.cpp) — từ dòng 96:

```
co2Ring[ringIndex] = (float)co2;
tempRing[ringIndex] = temperature;
humRing[ringIndex] = humidity;
ringIndex = (uint8_t)((ringIndex + 1) % SCD40_RING_SIZE);
if (ringCount < SCD40_RING_SIZE) {
    ringCount++;
}

float co2Avg = 0.0f;
float tempAvg = 0.0f;
```


## 07. FreeRTOS: tác vụ, ưu tiên và mutex

Core 0: mqtt. Core 1: scd40, dust, fan, oled.

**Luồng:** Task cảm biến: Ghi SensorData_t → g_dataMutex: Bảo vệ trạng thái → dataSnapshot: Sao chép rồi nhả → MQTT / OLED: Dùng bản sao


- dust/fan ưu tiên 3; scd40/mqtt 2; oled 1.

- Mutex chỉ bảo vệ vùng dữ liệu được khóa; không tự bảo vệ mọi tài nguyên.

- dataSnapshot tránh giữ mutex trong lúc serialize hoặc gửi mạng.


**Câu hỏi:** Pin hai core có biến hệ thống thành hard real-time không?

**Trả lời:** Không. Còn lịch FreeRTOS, Wi-Fi, ngắt, vTaskDelay và I/O có thể chặn. ISR bắt cạnh và phân tác vụ giúp tổ chức thời gian, nhưng muốn tuyên bố deadline phải có đo latency/jitter.

**Giải thích thêm:** Các số stack truyền vào lần lượt 4096, 3072, 3072, 4096, 8192; không cộng chúng để kết luận bộ nhớ toàn chương trình. Data mutex không bao quanh giao dịch Wire giữa SCD40 và OLED; một lần Wire.begin không đồng nghĩa đã chứng minh tránh tranh chấp I²C.


[embedded/src/main.cpp](../embedded/src/main.cpp) — từ dòng 71:

```
xTaskCreatePinnedToCore(Task_ReadSCD40, "scd40", 4096, nullptr, 2, nullptr, 1);
xTaskCreatePinnedToCore(Task_ReadDustPWM, "dust", 3072, nullptr, 3, nullptr, 1);
xTaskCreatePinnedToCore(Task_FanControl, "fan", 3072, nullptr, 3, nullptr, 1);
xTaskCreatePinnedToCore(Task_UpdateOLED, "oled", 4096, nullptr, 1, nullptr, 1);
xTaskCreatePinnedToCore(Task_MQTT_Network, "mqtt", 8192, nullptr, 2, nullptr, 0);
```


[embedded/src/sensor_data.cpp](../embedded/src/sensor_data.cpp) — từ dòng 42:

```
SensorData_t dataSnapshot() {
    SensorData_t copy;
    dataLock();
    copy = g_sensorData;
    dataUnlock();
    return copy;
}

```


## 08. Wi-Fi, portal, NTP và MQTT

Bốn trạng thái kết nối cần phân biệt khi chẩn đoán.

**Luồng:** NVS mạng: SSID / thông số → SoftAP + STA: Portal nếu cần → NTP hợp lệ: Cho gửi telemetry → MQTT TLS: Poll + publish


- Thiếu cấu hình hoặc join thất bại → portal cấu hình.

- Wi-Fi mất lâu hơn 60 s → quay về portal; MQTT có nhịp retry riêng 5 s.

- Telemetry không gửi hoặc buffer khi chưa có thời gian hợp lệ.


**Câu hỏi:** Wi-Fi kết nối được có nghĩa hệ thống đã hoạt động đầy đủ?

**Trả lời:** Chưa. Cần DNS/Internet và NTP; sau đó MQTT cần TLS, tài khoản và subscribe thành công. Khi chẩn đoán phải tách Wi-Fi, NTP, MQTT, ghép phòng và số đo.

**Giải thích thêm:** Đồng bộ NTP được yêu cầu lần đầu, mỗi giờ, hoặc bằng force_ntp_sync. Backend kết nối Paho ở thread khởi tạo riêng để không chặn Spring startup. Portal dùng DNS và HTTP nội bộ trên AP; không phải trang dashboard React.


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 60:

```
static bool timeIsValid() {
    return (uint32_t)time(nullptr) > TIME_VALID_EPOCH;
}

```


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 538:

```
if (!s_mqtt.connected()) {
    // Still sample while offline: otherwise the buffer only ever fills when a publish
    // fails on a live connection, which is the one case that rarely happens.
    const RuntimeConfig offlineCfg = configSnapshot();
    if ((uint32_t)(now - lastTelemetryMs) >= offlineCfg.telemetry_interval_ms) {
        lastTelemetryMs = now;
        bufferCurrentReading();
    }
```


## 09. MQTT: QoS, retained và LWT

Các bản tin hiện tại dùng QoS 1; không suy ra exactly-once.

**Luồng:** Node: publish telemetry → Broker: QoS 1 / retained → Paho: Subscribe wildcard → Spring event: Telemetry / state / status


- telemetry và actuator/state không retained; status retained.

- LWT OFFLINE do broker phát khi nhận diện mất kết nối bất thường.

- Keepalive 30 s không có nghĩa UI thấy OFFLINE tức thì.


**Câu hỏi:** QoS 1 có ngăn số đo trùng trong DB không?

**Trả lời:** Không. QoS 1 cho phép giao lại; schema telemetry không có unique(device_id, recorded_at) hay id bản tin để khử trùng. Một mẫu có thể có nhiều hàng nếu được giao lại.

**Giải thích thêm:** QoS là bảo đảm truyền giữa các phía MQTT, không chứng minh transaction DB đã commit hoặc quạt đã thực thi. Backend cleanSession=true và subscribe lại khi connectComplete; không nên hứa hàng đợi lệnh bền vững xuyên mọi lần ngắt.


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 179:

```
if (!publishPayload(kTopicTelemetry, payload, false, 1)) {
    AQSS_LOG("mqtt: telemetry publish failed, buffering offline");
    bufferOffline(sample);
}
```


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 422:

```
s_mqtt.beginWill(kTopicStatus, (unsigned short)strlen(will), true, 1);
s_mqtt.write((const uint8_t*)will, strlen(will));
if (!s_mqtt.endWill()) {
    AQSS_LOG("mqtt: endWill failed");
    return false;
}

```


## 10. Một mẫu telemetry đi vào backend

JSON snake_case → record Java → event → service.

**Luồng:** MQTT callback: messageArrived → ObjectMapper: TelemetryEvent → @EventListener: TelemetryService → @Transactional: Lưu + onReading


- ObjectMapper dùng SNAKE_CASE nên device_id ánh xạ deviceId.

- Event mặc định chạy đồng bộ nếu không cấu hình multicaster/async khác.

- Code không có @Async ở luồng tiếp nhận này.


**Câu hỏi:** Spring event ở đây có phải message queue bền vững?

**Trả lời:** Không. Đây là event trong tiến trình. Mặc định listener chạy cùng luồng phát; không có hàng đợi durable để phát lại sau khi tiến trình chết.

**Giải thích thêm:** Callback catch exception và log Dropped MQTT message. Không thấy cơ chế retry nghiệp vụ hoặc dead-letter trong code này. Phân biệt exception bị log với việc đã có backpressure hoặc retry được bảo đảm.


[backend/src/main/java/com/aqss/backend/mqtt/MqttLink.java](../backend/src/main/java/com/aqss/backend/mqtt/MqttLink.java) — từ dòng 104:

```
public void messageArrived(String topic, MqttMessage message) {
    String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
    try {
        if (topic.endsWith("/actuator/state")) {
            events.publishEvent(mapper.readValue(payload, ActuatorStateEvent.class));
        } else if (topic.endsWith("/telemetry")) {
            events.publishEvent(mapper.readValue(payload, TelemetryEvent.class));
        } else if (topic.endsWith("/status")) {
            events.publishEvent(mapper.readValue(payload, StatusEvent.class));
        }
```


## 11. Validation: null, zero và dải hợp lệ

Kiểm tra code hai phía thay vì chỉ học bảng trong tài liệu.

**Luồng:** Firmware: Chấp nhận rộng hơn → JSON: null nếu chưa ready → Backend: Kiểm tra bốn sensor → DB: NULL hoặc lưu cả mẫu


- Backend: PM 0…500; CO₂ 400…5000; T −10…60; RH 0…100.

- Firmware config: CO₂ 300…10000; T −40…85; hai phía đang lệch.

- Một giá trị ngoài dải backend → loại cả bản tin, không chỉ trường đó.


**Câu hỏi:** CO₂ = null và CO₂ = 0 xử lý giống nhau không?

**Trả lời:** Khác. inRange(null) trả true và lưu NULL. Giá trị 0 ppm ngoài dải backend nên bị loại cả bản tin. PM2.5 = 0 lại hợp lệ.

**Giải thích thêm:** Không được nói “mọi lớp đã dùng cùng bảng validation” vì config hiện tại không khớp hợp đồng. Cờ dust_fault có trong event nhưng TelemetryService hiện không lưu cờ đó; chỉ pm25_saturated được lưu vào telemetry.


[backend/src/main/java/com/aqss/backend/service/TelemetryService.java](../backend/src/main/java/com/aqss/backend/service/TelemetryService.java) — từ dòng 46:

```
if (!inRange(sensors.pm25(), 0f, 500f)
        || !inRange(sensors.co2(), 400f, 5000f)
        || !inRange(sensors.temperature(), -10f, 60f)
        || !inRange(sensors.humidity(), 0f, 100f)) {
    log.warn("Rejected out-of-range telemetry from {}: {}", event.deviceId(), sensors);
    return;
}

```


[embedded/include/config.h](../embedded/include/config.h) — từ dòng 49:

```
#define CO2_MIN_PPM 300
#define CO2_MAX_PPM 10000
#define TEMP_MIN_C (-40.0f)
#define TEMP_MAX_C 85.0f
#define HUM_MIN_PCT 0.0f
```


## 12. PostgreSQL: quan hệ và truy vấn

Sơ đồ quan hệ theo migrations V1–V4; audit.device_id không có ràng buộc FK.

**Luồng:** spaces 1–N devices; devices 1–N telemetry; audit_log độc lập, không có FK device_id.


- spaces 1–N devices; devices 1–N telemetry.

- ON DELETE SET NULL cho liên kết phòng; xóa device CASCADE telemetry.

- Index(device_id, recorded_at DESC) hỗ trợ latest/history.


**Câu hỏi:** Xóa thiết bị có giữ nguyên lịch sử telemetry không?

**Trả lời:** Không theo schema hiện tại: telemetry.device_id tham chiếu devices với ON DELETE CASCADE. Audit log không có FK nên có thể còn giữ sự kiện cũ.

**Giải thích thêm:** Flyway V4 xóa predictions; còn bốn bảng ứng dụng. Hibernate ddl-auto: validate không tự cập nhật schema. Một phòng có thể có nhiều node theo DB; thuật toán chọn CO₂ phòng hiện chọn device đầu tiên, không gộp CO₂ toàn phòng.


[backend/src/main/resources/db/migration/V1__init.sql](../backend/src/main/resources/db/migration/V1__init.sql) — từ dòng 31:

```
CREATE TABLE telemetry (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices (device_id) ON DELETE CASCADE,
    recorded_at TIMESTAMPTZ NOT NULL,
    pm25        REAL,
    co2         REAL,
    temperature REAL,
    humidity    REAL,
    wifi_rssi   SMALLINT
);

CREATE INDEX idx_telemetry_device_time ON telemetry (device_id, recorded_at DESC);
```


## 13. Ngưỡng: công thức đang tính những gì?

T = clamp(15 × √(24/h) × f(V) × f(vent) × f(CO₂), 10, 60).

**Luồng:** purpose: Suy ra h → Thể tích / 20: Căn bậc ba, clamp → Thông gió + CO₂: Nhân hệ số → Làm tròn: Một chữ số thập phân


- Phòng ngủ/văn phòng 8 h, lớp học 6 h, khách 4 h, bếp 2 h.

- f(V): 0,7…1,6; thông gió: 0,75 / 1 / 1,35.

- Đây là chính sách công thức của dự án; không phải mô hình đã huấn luyện.


**Câu hỏi:** Ví dụ V=20 m³, phòng ngủ, thông gió medium, chưa có CO₂?

**Trả lời:** h=8; các hệ số còn lại bằng 1; 15×√3≈25,98 và kết quả trả về 26,0 µg/m³. Không suy ra toàn bộ công thức đã được kiểm định sức khỏe.

**Giải thích thêm:** Các bước nhân dùng giá trị đầy đủ, kết quả và hệ số được làm tròn khi trả Result. Nhân lại các hệ số đã làm tròn trên UI có thể không khớp tuyệt đối số cuối. Thiếu thể tích hoặc V≤0 thì volume factor=1.


[backend/src/main/java/com/aqss/backend/service/ThresholdService.java](../backend/src/main/java/com/aqss/backend/service/ThresholdService.java) — từ dòng 84:

```
double dose = DAILY_TARGET_UGM3 * Math.sqrt(24.0 / occupancyHours);
// The room's own dilution capacity matters before the fan ever runs: a large, well-ventilated
// room reaches the same concentration more slowly and clears sooner.
double threshold = dose * ventilation * volume * co2Factor;
threshold = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, threshold));

return new Result(
        round(threshold),
```


## 14. CO₂ tham gia ở đâu? Ngưỡng có tự đổi?

Preview dùng mẫu CO₂ hiện có; control loop so sánh với ngưỡng đã lưu.

**Luồng:** GET preview: Đọc profile + CO₂ → compute: Trả T và hệ số → POST threshold: Lưu source=profile → ControlLoop: Dùng thresholdPm25


- fCO₂ = 1 − 0,25 × clamp((CO₂ − 420)/800, 0, 1).

- CO₂=820 → f=0,875; không có CO₂ → f=1.

- Nhập ngưỡng trực tiếp qua PUT cho phép 1…500; clamp 10…60 chỉ cho ngưỡng tính.


**Câu hỏi:** CO₂ vừa tăng có tự làm quạt bật không?

**Trả lời:** Không trực tiếp. Ngưỡng chỉ tính khi tạo phòng hoặc áp dụng kết quả tính. CO₂ mới không tự ghi đè threshold mỗi telemetry; control loop không gọi compute.

**Giải thích thêm:** currentCo2() lấy mẫu mới nhất của thiết bị đầu tiên thuộc phòng, không kiểm tra tuổi mẫu. POST tính lại, không gửi lại đúng con số preview cũ. Giá trị h hiện lấy từ purpose; occupancy_hours gửi trong pair chưa được dùng để lưu/compute.


[backend/src/main/java/com/aqss/backend/service/SpaceService.java](../backend/src/main/java/com/aqss/backend/service/SpaceService.java) — từ dòng 98:

```
public ThresholdPreview applyComputedThreshold(String code) {
    Space space = require(code);
    ThresholdService.Result result = thresholds.compute(space, currentCo2(space));
    space.setThresholdPm25(validateThreshold((float) result.thresholdPm25()));
    space.setThresholdSource("profile");
    space.setUpdatedAt(Instant.now());
    spaces.save(space);
    audit.log("BACKEND", "THRESHOLD", null, java.util.Map.of(
            "space", space.getCode(),
```


[backend/src/main/java/com/aqss/backend/service/ThresholdService.java](../backend/src/main/java/com/aqss/backend/service/ThresholdService.java) — từ dòng 151:

```
    double delta = Math.max(0.0, co2Ppm - CO2_BASELINE_PPM);
    double sealed = Math.min(1.0, delta / CO2_SEALED_DELTA);
    return 1.0 - CO2_MAX_CORRECTION * sealed;
}
```


## 15. ControlLoop: hai cửa vào, cùng bộ đánh giá

onReading kích hoạt khi đang OFF; tick kiểm tra định kỳ bằng fixedDelay.

**Luồng:** Telemetry mới: onReading → Chưa ghép / MANUAL?: Bỏ qua → OFF hoặc tick: evaluate → PM null / cũ?: Bỏ qua trước decide


- tick dùng fixedDelay 60000: chờ sau khi lượt trước kết thúc.

- onReading bỏ qua fan ON, nên tắt theo hysteresis chờ tick.

- MAX_ON cũng nằm sau guard stale: số đo cũ làm bỏ qua cả decide.


**Câu hỏi:** Đủ 120 phút có chắc backend luôn gửi OFF?

**Trả lời:** Không tuyệt đối. Phải qua các guard hợp lệ, và tick có độ trễ. Nếu reading cũ/null thì evaluate return trước max_on; khi không gia hạn được, firmware AUTO có timeout riêng.

**Giải thích thêm:** Activation “ngay” nghĩa là không chờ timer 60 s; vẫn có độ trễ đo, lọc, telemetry, mạng và xử lý. Không nên nói phản ứng bằng 0 giây. elapsed(null) trả true, nên thiếu onSince cũng ảnh hưởng quyết định.


[backend/src/main/java/com/aqss/backend/service/ControlLoop.java](../backend/src/main/java/com/aqss/backend/service/ControlLoop.java) — từ dòng 52:

```
public void onReading(Device device) {
    // Only the trigger is immediate. Stopping still waits for the timer, because hysteresis and
    // the minimum run time are deliberately slow.
    if (device.getPairedAt() == null
            || !"AUTO".equals(device.getFanMode())
            || device.getSpaceCode() == null
            || "ON".equals(device.getFanState())) {
        return;
    }
    evaluate(device, settings.control());
```


[backend/src/main/java/com/aqss/backend/service/ControlLoop.java](../backend/src/main/java/com/aqss/backend/service/ControlLoop.java) — từ dòng 82:

```
if (pm25 == null || stale) {
    return;   // a stale reading must not drive the fan
}
```


## 16. State machine AUTO và hysteresis

Quy tắc theo decide(); min_off hiện không được dùng để chặn bật.

**Luồng:** OFF → ON khi PM≥T; ON → OFF khi max_on hoặc đủ min_on và PM<0,7T. Các guard nằm trước decide.


- T=30: bật khi PM≥30, tắt khi PM<21 và đủ 5 phút.

- PM=21 đúng ngưỡng tắt chưa thỏa vì code dùng dấu <.

- Sau max_on, tick sau có thể bật lại ngay nếu PM vẫn≥T.


**Câu hỏi:** Có nghỉ tối thiểu 3 phút trước khi bật lại không?

**Trả lời:** Không trong decide hiện tại. min-off-minutes có trong cấu hình, offSince có trong Inputs, nhưng nhánh fan OFF chỉ so PM với T. Nội dung UI nhắc nghỉ 3 phút đang lệch code.

**Giải thích thêm:** Hysteresis là hai mốc ON/OFF, kết hợp minimum ON để giảm chập chờn. Không có PID, không điều chỉnh duty theo sai số. Đây là điều khiển theo ngưỡng với state và điều kiện thời gian.


[backend/src/main/java/com/aqss/backend/service/ControlLoop.java](../backend/src/main/java/com/aqss/backend/service/ControlLoop.java) — từ dòng 142:

```
public static Decision decide(Inputs in) {
    if (!in.fanOn()) {
        return in.pm25() >= in.threshold() ? Decision.ACTIVATE : Decision.NONE;
    }
    if (elapsed(in.onSince(), in.now(), in.maxOnMinutes())) {
        return Decision.DEACTIVATE;   // max ON protects the motor
    }
    boolean minOnElapsed = elapsed(in.onSince(), in.now(), in.minOnMinutes());
    return in.pm25() < in.threshold() * in.hysteresisRatio() && minOnElapsed
            ? Decision.DEACTIVATE : Decision.NONE;
}

```


## 17. Ví dụ thời gian để tự tính quyết định

Ví dụ giả định T=30; không phải dữ liệu đo hiệu suất.

**Luồng:** t=0: PM=32: OFF → ON L3 → t=2 phút: PM=18: Chưa đủ min ON → t=6 phút: PM=20: Tick → OFF → Mẫu sau: PM=31: Bật lại ngay


- Đủ min ON không tự tắt nếu PM vẫn ≥21.

- Mẫu thấp đến khi fan ON không trực tiếp kích hoạt tắt qua onReading.

- Giới hạn max_on ưu tiên trước hysteresis trong decide.


**Câu hỏi:** Nếu PM thấp ngay sau bật, có tắt ngay không?

**Trả lời:** Không theo hysteresis bình thường: cần ≥5 phút ON, PM<0,7T và tới lượt đánh giá timer. Nếu mất backend, timeout firmware là một nhánh OFF độc lập.

**Giải thích thêm:** Hãy tự thay PM=21, PM=20, thời gian 4 phút, 5 phút, rồi xét mode MANUAL. Lời giải: PM=21 không thỏa <21; PM=20 và 4 phút chưa đủ; PM=20 và 5 phút đủ nếu evaluate được gọi và các guard đều hợp lệ.


[backend/src/main/java/com/aqss/backend/service/ControlLoop.java](../backend/src/main/java/com/aqss/backend/service/ControlLoop.java) — từ dòng 149:

```
boolean minOnElapsed = elapsed(in.onSince(), in.now(), in.minOnMinutes());
return in.pm25() < in.threshold() * in.hysteresisRatio() && minOnElapsed
        ? Decision.DEACTIVATE : Decision.NONE;
```


## 18. Lệnh quạt: ý định → broker → node

HTTP thành công không phải chứng chỉ quạt đã quay.

**Luồng:** React mutation: POST command → DeviceService: applyActuator → MqttLink: publish set_actuator → Node: fanCmdSet → LEDC


- level được clamp 0…3; yêu cầu ON level 0 được backend nâng lên 2.

- Backend ghi ý định vào Device; node phản hồi qua actuator/state.

- Không có command_id hoặc ACK nghiệp vụ khớp từng lệnh.


**Câu hỏi:** Broker lỗi lúc AUTO activate thì DB có thể vẫn ghi ON không?

**Trả lời:** Có thể: applyActuator cập nhật trạng thái và audit cả khi published=false; ControlLoop không kiểm tra boolean trả về. Đây là đường AUTO, khác lệnh USER ném 503 và có transaction bao ngoài.

**Giải thích thêm:** Phản hồi actuator/state có thể điều chỉnh lại trạng thái dự kiến. Không được đồng nhất “publish thành công” với “node nhận”, “PWM đã áp”, hoặc “có lưu lượng”. Đó là bốn mức xác nhận khác nhau.


[backend/src/main/java/com/aqss/backend/service/DeviceService.java](../backend/src/main/java/com/aqss/backend/service/DeviceService.java) — từ dòng 275:

```
boolean published = mqtt.publishCommand(deviceId, "set_actuator", params);

boolean wasOn = "ON".equals(device.getFanState());
Instant now = Instant.now();
device.setFanState(on ? "ON" : "OFF");
device.setFanLevel((short) (on ? clamped : 0));
device.setFanMode(manual ? "MANUAL" : "AUTO");
if (published) {
    device.setLastCommandAt(now);
}
```


## 19. PWM, kickstart và nút MANUAL

LEDC 8 bit · 25 kHz · GPIO 5; task quạt lặp khoảng 50 ms.

**Luồng:** FanCommand snapshot: on + level + mode → OFF → ON: 255 trong 250 ms → Duty yêu cầu: 0 / 89 / 153 / 230 → Shared state: state / duty / mode


- Duty danh định 0/35/60/90%; giá trị 8 bit có làm tròn.

- Nút debounce 30 ms; nhấn ngắn vòng mức ở MANUAL.

- Giữ 3 s trả AUTO và gia hạn timestamp lệnh tại node.


**Câu hỏi:** Mức duty thấp nhất 20% có đang tác động các level 1–3 không?

**Trả lời:** Các level hiện đều ≥35%, nên phép clamp FAN_MIN_DUTY=51 chủ yếu là bảo vệ nếu bổ sung duty thấp. Kickstart chỉ thực hiện khi chuyển từ không chạy sang chạy.

**Giải thích thêm:** Task ưu tiên không thể chạy vượt một lời gọi blocking bên trong nó. Xung kickstart dùng vTaskDelay nên task quạt nhường lịch trong 250 ms. Nút MANUAL chỉ ưu tiên bằng cập nhật mode/state; một lệnh AUTO đã bay trên mạng vẫn có thể đến sau thao tác nút.


[embedded/src/fan_control.cpp](../embedded/src/fan_control.cpp) — từ dòng 29:

```
if (duty > 0 && !s_appliedOn) {
    ledcWrite(PIN_FAN_PWM, 255);  // kickstart a stalled BLDC
    vTaskDelay(pdMS_TO_TICKS(FAN_KICKSTART_MS));
}
ledcWrite(PIN_FAN_PWM, duty);

s_appliedOn = duty > 0;
s_appliedDuty = duty;
```


## 20. Phản hồi trạng thái và giới hạn bộ đếm

Đọc code runtimeS trong task trước khi hứa chức năng thay lõi đã đồng bộ.

**Luồng:** fan task: runtimeS cục bộ → Cộng FAN_TICK_MS: Tích lũy thành giây → NVS mỗi 5 phút: Snapshot runtime → state MQTT: Giờ làm tròn 0,1 h


- Thời gian cộng theo tick giả định, không theo millis chênh lệch thực.

- filter_runtime_h làm tròn 0,1 h → độ phân giải khoảng 6 phút.

- reset_filter đặt shared/config về 0 nhưng task còn runtimeS cũ.


**Câu hỏi:** Vì sao reset có thể lại hiện số giờ cũ?

**Trả lời:** runtimeS khởi tạo một lần trước for(;;), sau đó task ghi ngược lại config và shared state. Handler MQTT không gán runtimeS cục bộ nên giá trị 0 có thể bị ghi đè. Đây là suy luận từ source, cần tái hiện trên thiết bị.

**Giải thích thêm:** Muốn sửa: task quạt cần sở hữu thao tác reset qua flag/event hoặc đọc bộ đếm từ một nguồn chung có đồng bộ. Không sửa firmware trong công việc làm tài liệu này. Không gọi runtime là đo thời gian chính xác khi cộng hằng 50 ms bỏ qua overhead.


[embedded/src/fan_control.cpp](../embedded/src/fan_control.cpp) — từ dòng 65:

```
uint32_t runtimeS = configSnapshot().filter_runtime_s;
uint32_t runtimeAccumMs = 0;
uint32_t lastPersistMs = millis();
```


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 307:

```
} else if (strcmp(command, "reset_filter") == 0) {
    configSetFilterRuntime(0);
    configPersistFilterRuntime();
    dataLock();
    g_sensorData.filter_runtime_s = 0;
    dataUnlock();
    AQSS_LOG("cmd: reset_filter — fan hours counter cleared");

```


## 21. Mất mạng: bộ đệm thực sự nằm ở nhánh nào?

Vòng đệm 60 slot; NTP phải hợp lệ mới nhận mẫu vào buffer.

**Luồng:** Wi-Fi OK: MQTT mất kết nối → bufferCurrentReading: Giữ timestamp gốc → Queue đầy: Ghi đè mẫu cũ nhất → Reconnect: flush mẫu đầu queue


- Ở chu kỳ 5 s, 60 slot giữ tối đa khoảng 5 phút.

- flush chỉ tăng head sau khi publish thành công.

- Nhánh !WiFi.isConnected() continue trước đoạn buffer MQTT.


**Câu hỏi:** Có thể nói mất Wi-Fi vẫn buffer đầy đủ 5 phút không?

**Trả lời:** Không với luồng hiện tại. Nhánh Wi-Fi down bỏ qua bufferCurrentReading. Bộ đệm được gọi khi Wi-Fi vẫn có nhưng MQTT mất, hoặc khi publish live thất bại.

**Giải thích thêm:** Buffer nằm trong RAM nên reboot mất mẫu. Khi Wi-Fi mất lâu, portal có thể giữ luồng mạng trong provisioningRun. Các task cảm biến vẫn chạy nhưng điều đó không đồng nghĩa task mạng đã lưu chuỗi telemetry theo thời gian.


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 511:

```
g_sensorData.wifi_rssi = 0;
g_sensorData.wifi_connected = false;
g_sensorData.mqtt_connected = false;
dataUnlock();
vTaskDelay(pdMS_TO_TICKS(200));
continue;
```


[embedded/src/mqtt_network.cpp](../embedded/src/mqtt_network.cpp) — từ dòng 541:

```
const RuntimeConfig offlineCfg = configSnapshot();
if ((uint32_t)(now - lastTelemetryMs) >= offlineCfg.telemetry_interval_ms) {
    lastTelemetryMs = now;
    bufferCurrentReading();
}
dataLock();
```


## 22. Lease AUTO và fail-safe của firmware

5 phút gửi lại lệnh; 10 phút không có lệnh thì node tắt AUTO.

**Luồng:** Backend ON: NONE → xem lease → ≥5 phút: Gửi lại set_actuator → Node nhận lệnh: last_command_ms mới → >10 phút im lặng: AUTO ON → OFF


- Lease là timeout ứng dụng; khác MQTT keepalive và watchdog.

- Không có reading còn mới → evaluate return, không renew lease.

- MANUAL không bị điều kiện timeout AUTO này tắt.


**Câu hỏi:** Có bảo đảm đồng hồ lease backend khớp node khi publish lỗi?

**Trả lời:** Không. renewLease vẫn cập nhật lastCommandAt ngay cả khi publishCommand trả false. Node chỉ cập nhật last_command_ms khi thực sự xử lý lệnh, nên node có thể timeout trong khi DB vừa ghi đã renew.

**Giải thích thêm:** Đây là fail-safe của ứng dụng cho liên lạc bị gián đoạn; không có nghĩa firmware vẫn lọc tự động theo cảm biến khi backend chết. Node không chứa công thức ngưỡng hoặc thuật toán AUTO dự phòng.


[backend/src/main/java/com/aqss/backend/service/DeviceService.java](../backend/src/main/java/com/aqss/backend/service/DeviceService.java) — từ dòng 338:

```
mqtt.publishCommand(device.getDeviceId(), "set_actuator", params);
device.setLastCommandAt(Instant.now());
devices.save(device);
```


[embedded/src/fan_control.cpp](../embedded/src/fan_control.cpp) — từ dòng 99:

```
if (cmd.mode == FAN_MODE_AUTO && cmd.on &&
    (uint32_t)(now - cmd.last_command_ms) > FAN_FAILSAFE_MS) {
    fanCmdFailSafeOff();
    cmd = fanCmdSnapshot();
    AQSS_LOG("failsafe: no backend command for %lus -> fan OFF",
             (unsigned long)(FAN_FAILSAFE_MS / 1000));
}
```


## 23. Ghép nối và hủy liên kết hai phía

Pairing liên kết device với phòng; không phải cơ chế xác thực người dùng.

**Luồng:** OLED pairing: Mã 6 ký tự → POST pair: Tìm node theo mã → DB binding: paired_at + space → set_space: NVS + OLED


- Mã suy ra từ eFuse MAC bằng phép biến đổi xác định.

- ensure() chỉ tạo node chưa ghép; không tin space_id telemetry để bind.

- Unpair xóa DB binding và gửi set_space rỗng để xóa NVS.


**Câu hỏi:** Mã pairing có phải mật khẩu bảo mật hoặc OTP không?

**Trả lời:** Không. Mã có tính xác định từ định danh thiết bị, không có thời hạn OTP trong code, và REST chưa có authentication. Chức năng chính là chọn node để gắn phòng.

**Giải thích thêm:** Pair/unpair gửi lệnh best-effort. Nếu broker down, DB có thể đã đổi nhưng node chưa nhận; không thấy cơ chế reconcile tự động mọi lần reconnect. Do ensure không tự bind, telemetry cũ không phục hồi liên kết DB đã xóa.


[embedded/src/config_store.cpp](../embedded/src/config_store.cpp) — từ dòng 127:

```
uint32_t hash = (uint32_t)(ESP.getEfuseMac() & 0xFFFFFFFFULL) ^ 0x5A17C3E9UL;
size_t written = 0;
for (int i = 0; i < PAIRING_CODE_LEN && written < len - 1; i++) {
    out[written++] = kPairingAlphabet[hash & 0x1F];
    hash = (hash >> 5) ^ (hash * 2654435761UL);
    if (hash == 0) {
        hash = 0x9E3779B9UL;
    }
}
```


[backend/src/main/java/com/aqss/backend/service/DeviceService.java](../backend/src/main/java/com/aqss/backend/service/DeviceService.java) — từ dòng 178:

```
boolean notified = notifyNode(deviceId, "set_space", Map.of("space_id", ""));
audit.log("USER", "UNPAIR", deviceId, Map.of(
        "previous_space_code", String.valueOf(previous),
        "node_notified", notified));
```


## 24. Dashboard: polling và cache sau thao tác

Không có WebSocket/SSE trong luồng đang dùng.

**Luồng:** useQuery: GET REST → Cache theo key: deviceId / hours → useMutation: POST command → onSuccess: Invalidate → refetch


- Latest/devices: 5 s; history/spaces: 60 s.

- enabled ngăn query chạy khi thiếu deviceId; latest retry=false.

- Lỗi 404 chưa có mẫu khác PM2.5=0; UI có trạng thái chờ.


**Câu hỏi:** Số đo gửi 5 s có đồng nghĩa màn hình luôn trễ đúng 5 s?

**Trả lời:** Không. Hai nhịp sensor/telemetry/polling không đồng bộ; còn trễ mạng, xử lý và cache. Sau thao tác, invalidate yêu cầu refetch thay vì chờ hết chu kỳ bình thường.

**Giải thích thêm:** isOnline dùng last_seen trong cửa sổ 30 s. Last_seen có thể cập nhật bởi telemetry và actuator/state; nó biểu thị liên lạc, không chứng minh từng sensor còn tạo mẫu mới. Settings ngưỡng preview không có polling 5 s mặc định.


[frontend/src/hooks/useAqss.ts](../frontend/src/hooks/useAqss.ts) — từ dòng 41:

```
export function useFanCommand(deviceId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: FanCommand) => api.command(deviceId!, body),
    onSuccess: () => invalidate(queryClient, deviceId),
  });
}

```


## 25. Biểu đồ: dữ liệu vẽ khác dữ liệu điều khiển

Hai bước giảm lượng dữ liệu: backend thưa mẫu, frontend median mỗi 5 phút.

**Luồng:** DB history: Khoảng from/to → Backend step: size / 800 → Bucket 300000 ms: Median mỗi ô → SVG + spike: Đường + tooltip


- Frontend loại PM null, gom theo floor(time/300000).

- Median là phần tử sorted[floor(n/2)] kể cả n chẵn.

- Bucket cao hơn cả hai hàng xóm được vẽ riêng nếu vượt 0,8T.


**Câu hỏi:** Làm mượt biểu đồ có trì hoãn bật quạt không?

**Trả lời:** Không: ControlLoop đọc telemetry mới nhất từ DB, không dùng đường SVG hay median bucket của frontend. Biểu đồ để xem lịch sử; quyết định dựa vào luồng backend riêng.

**Giải thích thêm:** Backend step=max(1,size/800) dùng chia nguyên nên “~800 điểm” không phải giới hạn cứng 800. Ví dụ 1599 hàng thì step=1 vẫn trả 1599. Tooltip tìm mẫu gần nhất trong history, không bắt buộc đúng giá trị median trên đường.


[frontend/src/components/HistoryChart.tsx](../frontend/src/components/HistoryChart.tsx) — từ dòng 29:

```
  const cell = Math.floor(t / 300_000) * 300_000;
  const list = buckets.get(cell) ?? [];
  list.push(row.pm25 as number);
  buckets.set(cell, list);
}
```


[frontend/src/components/HistoryChart.tsx](../frontend/src/components/HistoryChart.tsx) — từ dòng 38:

```
  return { t, v: sorted[Math.floor(sorted.length / 2)] };
});
```


## 26. Tổng hợp các chỗ phải trả lời đúng giới hạn

Các nhận xét dưới đây là đọc source; không coi là kết quả kiểm thử phần cứng.

**Luồng:** Đã triển khai: Có luồng / có code → Đã gửi: Không chứng minh đã quay → Đã tính ngưỡng: Không phải kiểm định y tế → Cần đo / tái hiện: Tách khỏi điều suy ra


- Đã thấy lệch: validation, min_off, nhãn Fritzing, occupancy_hours.

- Cần tái hiện: reset runtime; mẫu sensor cũ có timestamp mới.

- Cần hoàn thiện: ACK ứng dụng, khử trùng, reconcile binding, freshness mỗi sensor.


**Câu hỏi:** Nếu bị hỏi “hệ thống đã hoạt động đúng hoàn toàn chưa”, nên đáp thế nào?

**Trả lời:** Nêu đường đã xây dựng, chỉ vào hàm và sơ đồ, rồi trình bày đúng giới hạn. Muốn khẳng định timing, độ chính xác sensor và hiệu quả lọc cần log, đo chuẩn hoặc kịch bản kiểm chứng; ảnh dashboard không thay thế các phép đo đó.

**Giải thích thêm:** Cách ôn: tự giải thích mỗi sơ đồ không nhìn ghi chú; chỉ được điểm đọc/ghi trạng thái; đưa một ví dụ đầu vào; sau đó nêu trường hợp lỗi và phần xác nhận còn thiếu. Phần code giữ đúng identifier gốc, tên dự án luôn dùng đầy đủ.


[backend/src/main/java/com/aqss/backend/service/ControlLoop.java](../backend/src/main/java/com/aqss/backend/service/ControlLoop.java) — từ dòng 144:

```
    return in.pm25() >= in.threshold() ? Decision.ACTIVATE : Decision.NONE;
}
if (elapsed(in.onSince(), in.now(), in.maxOnMinutes())) {
```
