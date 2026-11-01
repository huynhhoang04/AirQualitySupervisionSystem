# Layer 1 — Embedded Firmware (AQSS V2)

Firmware cho **một node tích hợp**: ESP32-S3 vừa đo (SCD40 + GP2Y1023AU0F), vừa hiển thị (SSD1306 OLED),
vừa điều khiển quạt lọc (Intel 4-wire PWM). Hợp đồng dữ liệu: [`docs/io_contract.md`](../docs/io_contract.md) §1–§4.

## Build & flash

```bash
cd embedded
~/.platformio/penv/bin/pio run                  # build
~/.platformio/penv/bin/pio run -t upload        # flash qua /dev/ttyACM0 (Native USB)
```

## First boot — node tự cấu hình mạng

Firmware **không** chứa sẵn WiFi/broker. Lần đầu cấp nguồn (hoặc khi giữ nút **3 s** lúc boot):

1. Node phát access point riêng: `AQSS-xxxx` / mật khẩu `aqss1234` (OLED hiện đúng thông tin này).
2. Điện thoại/laptop nối vào AP đó, mở `http://192.168.4.1` (captive portal tự bật).
3. Nhập **SSID + mật khẩu WiFi** và **host/port/user/pass broker**, bấm *Save*.
4. Node reboot, nối WiFi như một client rồi nối broker. Cấu hình lưu trong NVS.

**Quy tắc:** không có WiFi = chế độ setup. Node thử join 15 s, thất bại thì mở AP + portal 2 phút,
rồi tự thử lại (lặp). Đang chạy mà rớt WiFi quá 60 s cũng mở lại portal. Vừa nối được WiFi là AP tắt
và màn hình về layout chính.

Muốn đổi mạng sau này: giữ nút khi cấp nguồn, hoặc nối lại AP và sửa form.

> Có thể pre-seed cho phòng lab bằng `include/secrets.h` (copy từ `secrets.example.h`, đã gitignore).

### Lệnh qua serial 115200

Cắm USB rồi gõ trong serial monitor (không cần tháo nguồn):

| Lệnh | Việc |
| :--- | :--- |
| `setup` | mở lại portal cấu hình (AP giữ nguyên tới khi lưu) |
| `forget` | xoá WiFi + broker trong NVS rồi reboot |
| `net` | in ra ssid / broker / user / độ dài mật khẩu đang lưu |
| `cred <user> <pass>` | đổi nhanh user/pass broker (giữ host/port) rồi reboot |
| `reboot` | khởi động lại |

Nút cứng: giữ nút GPIO 6 (nút override trên breadboard, **không** phải BOOT/RST) → bấm RST → giữ 3 s.

> `pio device monitor` cần TTY. Chạy trong CI/không có TTY thì đọc trực tiếp `/dev/ttyACM0` (115200 8N1).

## Layout

| Core | Task | Prio | Chu kỳ | Nhiệm vụ |
| :---: | :--- | :---: | :--- | :--- |
| 0 | `mqtt` | 2 | 50 ms | WiFi, NTP, MQTT (QoS 1 + LWT), telemetry, command, offline buffer |
| 1 | `scd40` | 2 | 1 s poll (5 s data) | CO2/T/RH, ring trung bình 5 mẫu, lọc theo io_contract §12 |
| 1 | `dust` | 3 | 1 s window | ISR đo LOW-pulse 100 Hz → PM2.5, phát hiện saturated/fault |
| 1 | `fan` | 3 | 50 ms | LEDC 25 kHz, kickstart, min-duty, nút nhấn, fail-safe, runtime lọc |
| 1 | `oled` | 1 | 1 s | Giá trị + trạng thái quạt + dải cảnh báo sớm từ backend |

Shared state: `SensorData_t g_sensorData` + `g_fanCmd`, bảo vệ bằng `g_dataMutex` (`dataSnapshot()`).

## Công thức PM2.5 (điểm đã sửa so với firmware test)

Datasheet GP2Y1023AU0F trang 9: `Vo` là PWM của `(A/D amplified − A/D initial + A/D của 1 V)`.
Trên thang 5 V / 10-bit, 1 V ứng với `7000 µs × 204.6/1023 ≈ 1400 µs`, nên **0 µg/m³ luôn ứng với W = 1400 µs**
và độ nhạy `K = 1400 µs / 100 µg/m³ = 14 µs/(µg/m³)`:

```text
PM2.5 [µg/m³] = (W − 1400) / 14        W = độ rộng xung LOW (µs)
```

Firmware test dùng `W₀ = 1200` → đọc **cao hơn ~14 µg/m³**. W₀ được lưu NVS (`w0`) và có thể hiệu chuẩn
bằng lệnh `recalibrate_dust` (không truyền `baseline_w0` thì lấy trung bình xung hiện tại).

Giới hạn vật lý: xung 6.8–7000 µs (0–400 µg/m³ tuyến tính); từ ~6900 µs firmware bật cờ `saturated`;
không có xung > 3 s ⇒ `dust_fault` (Vo kẹt HIGH = chế độ lỗi theo datasheet).

## Hành vi chính

- Telemetry 5 s (`set_interval` đổi được, lưu NVS), QoS 1, buffer 512 B, **tạm dừng khi chưa có giờ NTP**.
- `actuator/state` publish khi đổi trạng thái + mỗi 5 s khi quạt ON.
- `status` retained `ONLINE`; **LWT** phát `OFFLINE` khi node chết bất thường.
- Fail-safe: AUTO + 10 phút không có lệnh ⇒ quạt OFF. Nút nhấn (GPIO 6) luôn thắng: nhấn ngắn = MANUAL ON/OFF,
  giữ 3 s = trả về AUTO.
- Watchdog 30 s cho mọi task; offline buffer 60 mẫu (~5 phút) trong RAM, back-fill khi có broker.
- AQI **không** tính trên thiết bị (backend NowCast).

## NVS keys (namespace `aqss`)

| Key | Kiểu | Ý nghĩa |
| :--- | :--- | :--- |
| `wifi_ssid` / `wifi_pass` | string | WiFi do portal lưu |
| `mqtt_host` / `mqtt_port` | string / uint | Broker do portal lưu |
| `mqtt_user` / `mqtt_pass` | string | Credentials broker |
| `space_id` | string | Space đang gán (mặc định `SP_201`) |
| `w0` | float | Baseline xung không bụi (µs), mặc định 1400 |
| `interval` | uint | Chu kỳ telemetry (ms), 1000–60000 |
| `filter_s` | uint | Tổng thời gian chạy quạt (s), ghi mỗi 5 phút |

## Log mẫu

```text
[AQSS_ESP32S3_01] === AQSS Layer-1 firmware 2.0.0 ===
[AQSS_ESP32S3_01] i2c: found [0x3C ] (SCD40 0x62 polled by its own task)
[AQSS_ESP32S3_01] config: space=SP_201 w0=1400us interval=5000ms filter=0.0h
[AQSS_ESP32S3_01] no credentials (first boot) -> setup portal
[AQSS_ESP32S3_01] provisioning: join 'AQSS-e87c' (pass aqss1234) then open http://192.168.4.1
...
[AQSS_ESP32S3_01] wifi: connecting to 'bulon'
[AQSS_ESP32S3_01] mqtt: connected to <broker>:8883
[AQSS_ESP32S3_01] dust: W=1445us PM2.5=3.2 ug/m3 samples=100
[AQSS_ESP32S3_01] scd40: first reading CO2=572ppm T=33.4C RH=59.2%
```

## Troubleshooting

| Hiện tượng | Nguyên nhân thường gặp |
| :--- | :--- |
| `dust: no valid pulse (edges=0 ... pin=0)` | Vo bị kéo LOW liên tục: cảm biến **mất nguồn 5 V** hoặc dây `Vo` tuột. Cầu phân áp 1k/2.2k sẽ kéo chân về 0 V khi đầu ra high-Z. Kiểm tra rail 5.0 V của LM2596 và chân `Vcc`/`VLED` của GP2Y1023. |
| `edges=0 ... pin=1` | Vo kẹt HIGH đúng chế độ lỗi datasheet: LED IR / `V_LED` không chạy (kiểm tra `150 Ω` + tụ `220 µF`). |
| `dust: W=... SATURATED` | Nồng độ > ~393 µg/m³, cảm biến tới ngưỡng bão hoà bộ khuếch đại. |
| `mqtt: telemetry paused, waiting for NTP time` | Chưa sync được NTP (thiếu internet); đổi `NTP_SERVER_1` sang NTP nội bộ nếu chạy LAN-only. |
| `wifi: not connected (status 6)` | Sai SSID/mật khẩu đã lưu — giữ nút 3 s lúc boot để mở lại portal. |
| `wifi: no link ... -> setup portal` | Router tắt/lệch mật khẩu; node mở AP 2 phút rồi thử lại. |
| OLED trắng/đen | Sai thứ tự chân `VCC/GND` của module (Type A/B) — xem setup guide. |

## Màn hình OLED

Chế độ chạy bình thường (4 dòng + thanh trạng thái):

```text
AQSS SP_201            WiFi ●   MQ ●
PM2.5 38.5 ug/m3
CO2 611ppm T32.8 H59%
FAN ON  L2  60%
AUTO  online  3h
```

| Trạng thái | Màn hình |
| :--- | :--- |
| Có WiFi | layout chính (bảng trên) |
| Không có WiFi, portal đang mở | `AQSS SETUP` + tên AP, mật khẩu, `http://192.168.4.1` |
| Không có WiFi, đang thử reconnect | `NO WIFI` + `reconnecting…` + hướng dẫn giữ nút khi boot |

Chấm `WiFi`/`MQ` đặc = đã kết nối, rỗng = mất kết nối; dòng cuối ưu tiên cảnh báo từ backend,
sau đó lỗi cảm biến bụi, bão hoà, rồi mới tới `AUTO/MANUAL + online + uptime`.

## Lưu ý phần cứng

- Fan chạy 100 % ngay khi cấp 12 V cho tới khi firmware kéo PWM về 0 (`fanBegin()` chạy đầu tiên trong `setup()`).
- Không probe I2C bằng ghi 0-byte vào `0x62` khi SCD40 đang ở chế độ periodic (reset bộ đếm 5 s) — I2C scan ở
  `setup()` bỏ qua địa chỉ này.
- Bus I2C khoá 400 kHz (`Wire.begin(8, 9, 400000)` + `u8g2.setBusClock()`), `Wire.begin()` chỉ gọi một lần.
