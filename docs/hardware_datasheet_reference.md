# 📋 Tài Liệu Tham Khảo Datasheet — 4 Linh Kiện Phần Cứng AQSS

> Tài liệu tổng hợp từ datasheet chính thức của nhà sản xuất (Espressif, Sensirion, Sharp, Solomon Systech).
> Dùng làm cơ sở kỹ thuật cho toàn bộ Plan Lớp 1 (Embedded System).

---

# 1. ESP32-S3-DevKitC-1 (WROOM-1 N16R8)

## 1.1 Tổng Quan Module

| Thông số | Giá trị |
| :--- | :--- |
| **SoC** | ESP32-S3 (Dual-core Xtensa LX7 @ 240 MHz, FPU, Vector AI/DSP) |
| **Module** | ESP32-S3-WROOM-1 (N16R8) |
| **Flash** | 16 MB Quad SPI (QSPI), 3.3V |
| **PSRAM** | 8 MB Octal SPI (OPI), 3.3V DDR |
| **SRAM** | 512 KB on-chip |
| **RTC SRAM** | 16 KB (8KB FAST + 8KB SLOW) |
| **Wi-Fi** | 802.11 b/g/n, 2.4 GHz, HT40 lên tới 150 Mbps |
| **Bluetooth** | BLE 5.0 (1M/2M/Coded PHY), Mesh |
| **USB** | USB 2.0 OTG Full-Speed (12 Mbps) + USB Serial/JTAG |
| **Nguồn VDD** | 3.0V – 3.6V (Nom 3.3V), cấp qua USB 5V hoặc pin 3V3 |
| **Logic Level** | 3.3V LVTTL — **KHÔNG chịu được 5V** (Max 3.6V) |
| **Board** | ESP32-S3-DevKitC-1, 44 pin, 2 cổng USB (Native + UART Bridge) |

## 1.2 ADC — Bộ Chuyển Đổi Tương Tự Sang Số

| Thông số | Giá trị |
| :--- | :--- |
| **Số lượng** | 2 SAR ADC (ADC1: 10 kênh, ADC2: 10 kênh) |
| **Độ phân giải** | 12-bit (0 – 4095) |
| **Tốc độ lấy mẫu** | Lên tới 100 kSPS (single-shot) |
| **Điện áp tham chiếu** | ~1100 mV nội bộ |

### Bảng ADC1 Channel → GPIO (AN TOÀN dùng khi Wi-Fi bật):

| Kênh | GPIO | Ghi chú |
| :---: | :---: | :--- |
| ADC1_CH0 | GPIO 1 | |
| ADC1_CH1 | GPIO 2 | |
| ADC1_CH2 | GPIO 3 | ⚠️ Strapping pin |
| ADC1_CH3 | GPIO 4 | |
| ADC1_CH4 | GPIO 5 | |
| ADC1_CH5 | GPIO 6 | |
| ADC1_CH6 | GPIO 7 | |
| ADC1_CH7 | GPIO 8 | Arduino Default I2C SDA |
| ADC1_CH8 | GPIO 9 | Arduino Default I2C SCL |
| ADC1_CH9 | GPIO 10 | |

### Attenuation & Khoảng đo:

| Attenuation | Khoảng điện áp đầu vào |
| :--- | :--- |
| ADC_ATTEN_DB_0 (0 dB) | 0 – 950 mV |
| ADC_ATTEN_DB_2_5 | 0 – 1250 mV |
| ADC_ATTEN_DB_6 | 0 – 1750 mV |
| ADC_ATTEN_DB_11 / DB_12 | 0 – 3100 mV (Full 3.3V) |

> **⚠️ ADC2 BỊ KHÓA khi Wi-Fi/BLE đang hoạt động.** Luôn dùng ADC1 (GPIO 1–10) cho sensor.

## 1.3 I2C

| Thông số | Giá trị |
| :--- | :--- |
| **Số controller** | 2 (I2C0, I2C1) |
| **Tốc độ** | Standard 100 kHz, Fast 400 kHz, Fast+ lên tới 1 MHz |
| **Default Pins (Arduino)** | SDA = GPIO 8, SCL = GPIO 9 |
| **Pin Mapping** | Bất kỳ GPIO nào qua GPIO Matrix |
| **Pull-up nội** | ~45 kΩ (quá yếu) — cần pull-up ngoài **2.2 kΩ – 4.7 kΩ** |

## 1.4 PCNT (Pulse Counter)

| Thông số | Giá trị |
| :--- | :--- |
| **Số unit** | 4 (Unit 0–3) |
| **Kênh/Unit** | 2 → Tổng 8 kênh |
| **Counter** | 16-bit có dấu (−32768 → +32767) |
| **GPIO hỗ trợ** | Bất kỳ GPIO input nào qua GPIO Matrix |
| **Tần số tối đa** | 40 MHz |
| **Glitch Filter** | Lập trình được (1–1023 chu kỳ APB clock) |

## 1.5 LEDC (PWM Output)

| Thông số | Giá trị |
| :--- | :--- |
| **Số kênh** | 8 kênh độc lập |
| **Timer** | 4 timer phần cứng |
| **Duty Resolution** | 1-bit đến 14-bit (tối đa 20-bit ở tần số thấp) |
| **Tần số** | < 1 Hz đến 40 MHz |

## 1.6 FreeRTOS Dual-Core

| Core | Hardware ID | Vai trò mặc định |
| :--- | :--- | :--- |
| **Core 0** | PRO_CPU | Wi-Fi/BLE driver, lwIP TCP/IP, System Events |
| **Core 1** | APP_CPU | `app_main()` / Arduino `setup()` + `loop()`, Sensor & UI |

## 1.7 Strapping Pins — KHÔNG NÊN dùng cho sensor

| GPIO | Chức năng Strapping | Quy tắc |
| :--- | :--- | :--- |
| **GPIO 0** | Boot Mode (HIGH = SPI Boot, LOW = Download) | Nối nút BOOT, không kéo LOW cố định |
| **GPIO 3** | JTAG Source Select | Giữ LOW hoặc thả nổi |
| **GPIO 45** | VDD_SPI Voltage (LOW = 3.3V, HIGH = 1.8V) | **NGUY HIỂM**: kéo HIGH → không boot. Giữ LOW/NC |
| **GPIO 46** | Boot Log Print | Weak pull-down nội |

## 1.8 GPIO KHÔNG dùng được trên N16R8

| GPIO | Lý do |
| :--- | :--- |
| GPIO 26–32 | Nội bộ SPI Flash |
| GPIO 33–34 | Nội bộ Octal PSRAM |
| **GPIO 35, 36, 37** | **Octal PSRAM (TUYỆT ĐỐI không dùng trên N16R8)** |
| GPIO 19, 20 | Native USB D-/D+ (đã dùng cho USB port) |
| GPIO 43, 44 | UART0 TX/RX (USB-to-UART Bridge) |

## 1.9 Công Suất Tiêu Thụ

| Chế độ | Dòng điện |
| :--- | :--- |
| Active (Wi-Fi TX 802.11n HT40) | 260–280 mA (Peak) |
| Active (CPU 240 MHz, RF Off) | 40–70 mA |
| Modem-Sleep (CPU 80 MHz, Wi-Fi connected) | 14–17 mA |
| Light-Sleep | ~240 µA |
| Deep-Sleep (RTC Timer + RAM) | 7–8 µA |

---

# 2. Sensirion SCD40 — Cảm Biến CO2, Nhiệt Độ, Độ Ẩm

## 2.1 Tổng Quan

| Thông số | Giá trị |
| :--- | :--- |
| **Công nghệ** | Photoacoustic NDIR (PASens®) |
| **I2C Address** | **0x62** (cố định, không thay đổi được) |
| **I2C Speed** | Standard 100 kHz, Fast **400 kHz** |
| **Nguồn VDD** | **2.4V – 5.5V** (Nom 3.3V hoặc 5.0V) |
| **Kích thước** | 10.1 × 10.1 × 6.5 mm |

## 2.2 Thông Số Đo Lường

| Đại lượng | Khoảng đo | Độ chính xác | Thời gian đáp ứng (τ63%) |
| :--- | :--- | :--- | :--- |
| **CO2** | 400 – 2,000 ppm (Output: 0 – 40,000) | ±(50 ppm + 5% reading) | 60 giây |
| **Nhiệt độ** | −10°C đến +60°C | ±0.8°C (15–35°C) | 120 giây |
| **Độ ẩm** | 0 – 100 %RH | ±6 %RH (15–35°C, 20–65%RH) | 90 giây |

## 2.3 Chế Độ Hoạt Động

| Chế độ | Chu kỳ đo | Dòng trung bình (3.3V) | Command |
| :--- | :--- | :--- | :--- |
| **Periodic (Mặc định)** | **5 giây** | 15–18 mA | `0x21B1` |
| **Low Power Periodic** | **30 giây** | 3.2–3.5 mA | `0x21AC` |
| **Idle** | Không đo | 0.2–0.5 mA | `0x3F86` (stop) |
| **Single-Shot** | Theo yêu cầu | — | ⚠️ **CHỈ SCD41**, SCD40 KHÔNG hỗ trợ |

> **⚠️ Quan trọng:** SCD40 **KHÔNG** hỗ trợ Single-Shot mode. Chỉ có Periodic (5s) hoặc Low Power Periodic (30s).

## 2.4 I2C Commands Chính

| Command | Hex Code | Thời gian thực thi | Chế độ cho phép |
| :--- | :--- | :--- | :--- |
| `start_periodic_measurement` | `0x21B1` | Tức thì | Idle |
| `read_measurement` | `0xEC05` | 1 ms | Periodic / LP |
| `stop_periodic_measurement` | `0x3F86` | 500 ms | Periodic / LP |
| `get_data_ready_status` | `0xE4B8` | 1 ms | Mọi chế độ |
| `set_temperature_offset` | `0x241D` | 1 ms | Idle |
| `set_sensor_altitude` | `0x2427` | 1 ms | Idle |
| `set_ambient_pressure` | `0xE000` | 1 ms | Mọi chế độ |
| `perform_forced_recalibration` | `0x362F` | 400 ms | Periodic (>3 phút) |
| `get_serial_number` | `0x3682` | 1 ms | Idle |
| `perform_self_test` | `0x3639` | 10,000 ms | Idle |
| `perform_factory_reset` | `0x3632` | 1,200 ms | Idle |

## 2.5 Công Thức Chuyển Đổi Dữ Liệu (`read_measurement` → 9 bytes)

$$CO_2\,[\text{ppm}] = \text{word}[0]$$

$$T\,[°C] = -45 + 175 \times \frac{\text{word}[1]}{2^{16} - 1}$$

$$RH\,[\%] = 100 \times \frac{\text{word}[2]}{2^{16} - 1}$$

## 2.6 CRC-8 Checksum

| Thông số | Giá trị |
| :--- | :--- |
| Polynomial | `0x31` ($x^8 + x^5 + x^4 + 1$) |
| Init | `0xFF` |
| Test Vector | Input `0xBEEF` → CRC `0x92` |

## 2.7 Lưu Ý Quan Trọng

- **Power-on → I2C sẵn sàng:** Chờ **1000 ms** sau khi cấp nguồn.
- **Dữ liệu đầu tiên sẵn sàng:** ~5 giây (Periodic), ~30 giây (LP).
- **Ổn định nhiệt:** Chờ **3–5 phút** trước khi tin tưởng giá trị tuyệt đối.
- **EEPROM:** `persist_settings` chỉ chịu được **2,000 lần ghi** → không gọi trong vòng lặp.
- **Dòng peak 175–205 mA** khi phát xung quang — cần tụ lọc 100nF + 10µF sát chân VDD.
- **VDD và VDDH phải nối chung** trên PCB.
- **Cách ly nhiệt:** Đặt xa MCU/LDO, cắt rãnh PCB quanh sensor nếu có thể.
- **Tránh rung/ồn âm thanh** gần sensor (nguyên lý quang âm nhạy cảm cơ học).

---

# 3. Sharp GP2Y1023AU0F — Cảm Biến Bụi Mịn PM2.5

## 3.1 Tổng Quan & Phân Biệt Với GP2Y1010AU0F

> **⚠️ ĐÂY LÀ THÔNG TIN QUAN TRỌNG NHẤT:**

| Đặc điểm | GP2Y1023AU0F (CỦA CHÚNG TA) | GP2Y1010AU0F / GP2Y1014AU0F |
| :--- | :--- | :--- |
| **Đầu ra** | **PWM kỹ thuật số** (Pulse Width) | Analog (Điện áp 0–3.7V) |
| **Vi xử lý tích hợp** | **CÓ** (tự kích LED, tự ADC nội bộ) | KHÔNG (cần MCU ngoài điều khiển) |
| **Chân điều khiển LED** | **KHÔNG CÓ** — Tự động hoàn toàn | CÓ (Pin 3, cần xung 320µs/10ms) |
| **Connector** | **5-pin JST GH** (1.25mm pitch) | 6-pin JST ZR (1.5mm pitch) |
| **Cách đọc** | `pulseIn()` hoặc Interrupt đo pulse width | `analogRead()` + kích xung LED GPIO |

## 3.2 Pinout (5-Pin JST GH — SM05B-GHS-TB)

| Pin | Ký hiệu | Chức năng | Kết nối |
| :---: | :---: | :--- | :--- |
| **1** | **Vo** | **PWM Digital Output** | → Voltage divider → ESP32 GPIO |
| **2** | **LED-GND** | Ground riêng cho LED | → GND (star ground) |
| **3** | **GND (S-GND)** | Signal Ground / Vỏ kim loại | → GND (star ground) |
| **4** | **V_LED** | Nguồn LED (qua R + C ngoài) | → 150Ω → VCC 5V |
| **5** | **VCC** | Nguồn chính | → +5V regulated |

### Mạch ngoài bắt buộc:
- **R = 150Ω** (1/4W) nối giữa VCC 5V và Pin 4 (V_LED)
- **C = 220µF** điện phân nối giữa Pin 4 (V_LED) và Pin 2 (LED-GND)

> **⚠️ Ground Separation:** Giữ LED-GND (Pin 2) và S-GND (Pin 3) tách riêng trên PCB, chỉ nối chung tại 1 điểm star ground gần nguồn.

## 3.3 Thông Số Điện

| Thông số | Min | Typ | Max | Đơn vị |
| :--- | :---: | :---: | :---: | :---: |
| **Nguồn VCC** | 4.75 | 5.00 | 5.25 | V |
| **Dòng trung bình** | — | 15 | 25 | mA |
| **Dòng peak (xung IR)** | — | — | 200 | mA |
| **VOH (Output HIGH)** | 3.5 | 4.8 | VCC | V |
| **VOL (Output LOW)** | — | 0.2 | 1.3 | V |
| **Nhiệt độ hoạt động** | −10 | — | +65 | °C |

## 3.4 Tín Hiệu PWM Output

| Thông số | Giá trị |
| :--- | :--- |
| **Chu kỳ (Period)** | T = 10.0 ms ± 0.2 ms (Tần số 100 Hz) |
| **Pulse Width tối đa** | 7.0 ms (Duty 70%) khi ADC nội bộ = 1023 |
| **Pulse Width tối thiểu** | 6.8 µs khi ADC nội bộ = 1 |
| **Baseline (không khí sạch)** | W₀ ≈ 1200 – 1400 µs |
| **Sensitivity** | **K = 1400 µs / (0.1 mg/m³) = 14.0 µs/(µg/m³)** |
| **Power-on** | Output giữ HIGH liên tục trong **1.0 giây** đầu tiên |

### Công thức chuyển đổi:

$$\text{Dust}\ (\mu g/m^3) = \frac{W - W_0}{14.0}$$

Trong đó:
- $W$ = Pulse width đo được (µs)
- $W_0$ = Baseline không khí sạch (µs), hiệu chỉnh riêng mỗi sensor
- Nếu $W - W_0 < 0$ → đặt = 0

## 3.5 Khoảng Đo & Hạt

| Thông số | Giá trị |
| :--- | :--- |
| **Khoảng phát hiện** | 0 – 500 µg/m³ (bão hòa ~750 µg/m³) |
| **Kích thước hạt** | ≥ 0.5 µm đến ~10 µm |
| **Tốc độ cập nhật** | 100 Hz (mỗi 10ms) |

## 3.6 Kết Nối Với ESP32 (3.3V) — BẮT BUỘC DÙNG VOLTAGE DIVIDER

Output HIGH của GP2Y1023AU0F là **~4.8V (logic 5V)** → **VƯỢ QUÁ** giới hạn 3.6V của ESP32.

**Mạch chia áp (Voltage Divider):**
```
GP2Y1023 Vo (Pin 1) ──── R1=1.0kΩ ──── ESP32 GPIO ──── R2=2.0kΩ ──── GND
```
- HIGH: 5.0V × 2.0/(1.0+2.0) = **3.33V** (OK, ESP32 VIH_min = 2.475V)
- LOW: < 0.8V (OK, ESP32 VIL_max = 0.825V)

## 3.7 Lưu Ý Quan Trọng

- **KHÔNG chỉnh VR** (biến trở nội bộ) → phá hỏng hiệu chỉnh nhà máy.
- **Hiệu chỉnh zero-point:** Đặt sensor trong môi trường sạch 5 phút → ghi lại W₀ trung bình.
- **Độ ẩm >80% RH:** Gây đọc sai cao do ngưng tụ ẩm trên hạt bụi.
- **Ánh sáng trực tiếp:** Tránh để ánh nắng/đèn sáng chiếu vào lỗ cảm biến.

---

# 4. OLED SSD1306 0.96" — Màn Hình Hiển Thị 128×64

## 4.1 Tổng Quan

| Thông số | Giá trị |
| :--- | :--- |
| **Driver IC** | Solomon Systech SSD1306 |
| **Độ phân giải** | 128 × 64 pixel |
| **Kích thước** | 0.96 inch chéo (Active: 21.74 × 10.86 mm) |
| **Giao tiếp** | I2C (SCL, SDA) |
| **I2C Address** | **0x3C** (mặc định), có thể đổi sang 0x3D |
| **Nguồn Module** | 3.3V – 5.0V (LDO XC6206 trên board) |
| **Logic Level** | 3.3V (tương thích trực tiếp ESP32-S3) |
| **GDDRAM** | 1,024 Bytes (128×64/8) |
| **Dòng tiêu thụ** | 10–20 µA (Sleep), 10–15 mA (Dashboard), 32–38 mA (Full White) |
| **Kích thước PCB** | 27.3 × 27.8 × 4.1 mm |

## 4.2 I2C Speed & Cùng Bus Với SCD40

| Thông số | Giá trị |
| :--- | :--- |
| Standard Mode | 100 kHz |
| Fast Mode | **400 kHz** (khuyến nghị) |
| Fast Mode+ / Overclock | 800 kHz – 1 MHz (KHÔNG dùng khi chia bus với SCD40) |

> **Quy tắc:** Khi SSD1306 (0x3C) và SCD40 (0x62) cùng bus I2C → **khóa bus ở 400 kHz**.
> Overclock 800 kHz+ gây lỗi CRC trên SCD40.

## 4.3 Pinout Module 4-Pin

> **⚠️ CẢNH BÁO:** Có 2 kiểu đấu chân (Type A: GND-VCC-SCL-SDA, Type B: VCC-GND-SCL-SDA). Luôn kiểm tra silkscreen trên PCB trước khi cấp nguồn.

| Pin | Tên | Kết nối ESP32-S3 |
| :---: | :---: | :--- |
| 1 | GND | GND |
| 2 | VCC | 3V3 |
| 3 | SCL | GPIO 9 |
| 4 | SDA | GPIO 8 |

## 4.4 Pull-Up Resistor Khi Chia Bus

- Module OLED thường có pull-up onboard 4.7kΩ hoặc 10kΩ.
- Module SCD40 cũng có pull-up tương tự.
- Song song: 2 × 4.7kΩ = **~2.35kΩ** → nằm trong khoảng khuyến nghị 2.0–4.7kΩ cho 400 kHz.
- **Không cần thêm pull-up ngoài** nếu cả 2 module đều có sẵn.

## 4.5 Refresh Rate & Bandwidth

| I2C Speed | Thời gian/frame | FPS lý thuyết | FPS khuyến nghị |
| :---: | :---: | :---: | :---: |
| 100 kHz | ~92.5 ms | 10.8 | 1–5 |
| **400 kHz** | **~23.1 ms** | **43.2** | **10–20** |
| 800 kHz | ~11.6 ms | 86.4 | 30–50 |

## 4.6 Thư Viện Khuyến Nghị cho ESP32-S3: **U8g2**

- Full Framebuffer (1 KB RAM — chỉ chiếm 0.2% SRAM của ESP32-S3).
- Hơn 1000 font, hỗ trợ UTF-8 (tiếng Việt, dấu, ký hiệu).
- Tích hợp Hardware I2C ESP32-S3.
- Constructor: `U8G2_SSD1306_128X64_NONAME_F_HW_I2C`

## 4.7 Chống Burn-in OLED

1. **Display Sleep Timeout:** Tắt (`0xAE`) sau 1–3 phút không tương tác.
2. **Pixel Shifting:** Dịch tọa độ ±1–2 pixel mỗi 15–30 phút.
3. **Giảm sáng ban đêm:** `0x81, 0x10` (contrast thấp).

## 4.8 Power Management

- **Sleep (< 20 µA):** `0xAE` (Display OFF) → `0x8D, 0x10` (Disable Charge Pump).
- **Wake:** `0x8D, 0x14` (Enable Charge Pump) → chờ 100 ms → `0xAF` (Display ON).
- GDDRAM được giữ nguyên khi có nguồn, kể cả trong sleep.

---

# 5. Bảng Tóm Tắt I2C Bus Chung

| Thiết bị | I2C Address | Tốc độ hỗ trợ | GPIO SDA | GPIO SCL |
| :--- | :---: | :---: | :---: | :---: |
| **SCD40** | `0x62` | 100–400 kHz | GPIO 8 | GPIO 9 |
| **SSD1306 OLED** | `0x3C` | 100–400 kHz (max 1 MHz) | GPIO 8 | GPIO 9 |

**Không xung đột địa chỉ** → dùng chung 1 bus I2C an toàn ở **400 kHz**.
