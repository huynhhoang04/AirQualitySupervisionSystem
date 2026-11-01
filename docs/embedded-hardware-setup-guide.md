# 🛠️ Hướng Dẫn Lắp Đặt & Kiểm Thử Thực Tế — Layer 1 (Embedded Node)

> **Tài liệu thực chiến (Field Deployment & Hardware Bring-up Guide)** được đúc kết trực tiếp từ quá trình lắp ráp, đo kiểm và chạy thử nghiệm thực tế toàn bộ phần cứng **AQSS Layer 1**.
> Tài liệu này bổ sung cho bản thiết kế lý thuyết tại [`docs/embedded-layer.md`](./embedded-layer.md) và [`docs/hardware_datasheet_reference.md`](./hardware_datasheet_reference.md), đồng thời chỉ rõ **các điểm khác biệt giữa lý thuyết và thực tế phần cứng** cần áp dụng khi viết firmware chính thức.

---

## 📋 1. Danh Mục Linh Kiện & Phụ Kiện Thực Tế (BOM)

| # | Nhóm | Linh kiện thực tế | Số lượng | Lưu ý mua sắm & lắp đặt thực tế |
| :---: | :--- | :--- | :---: | :--- |
| 1 | **MCU** | `ESP32-S3-DevKitC-1` (`WROOM-1 N16R8`) | 1 | Bản 44 chân (bề ngang 25.4 mm chiếm 10 lỗ ngang breadboard). Có 2 cổng Type-C (`USB` & `UART`). |
| 2 | **Sensor CO2** | Module `Sensirion SCD40` (I2C `0x62`) | 1 | Breakout 4 chân (`VCC`, `GND`, `SCL`, `SDA`). **Bắt buộc phải hàn chân rào 2.54 mm** (gài hờ sẽ chập chờn khi rung bàn). |
| 3 | **Sensor Bụi** | `Sharp GP2Y1023AU0F` (Digital PWM) | 1 | Giắc **5-pin JST GH 1.25 mm** (`SM05B-GHS`). Nếu dùng cáp `MX1.25 5P` sẽ hơi khít vỏ nhựa — cắm 1 lần cố định luôn trên cảm biến, chỉ tháo lắp đầu bên breadboard. |
| 4 | **Màn hình** | `OLED 0.96" SSD1306` 128×64 (I2C `0x3C`) | 1 | Kiểm tra kỹ chữ in trên mạch: lô thực tế của dự án có thứ tự **`VCC - GND - SCL - SDA`** (Type B). |
| 5 | **Actuator** | Quạt tản nhiệt `Intel CPU Stock Fan` 12V (4-pin PWM) | 1 | Dây quạt đời mới cả 4 sợi đều màu đen. Giắc `TKG 2510` bước chân 2.54 mm cắm vừa kim dây Dupont. |
| 6 | **Màng lọc** | Lõi lọc hình trụ `HEPA H13` (tích hợp than hoạt tính) | 1 | Đường kính miệng trên của lõi lọc trụ vừa khít vòng khung quạt Intel. Đặt quạt úp lên miệng lõi lọc và dán kín viền. |
| 7 | **Nguồn 12V** | Nguồn tổ ong `12V - 400W` + Dây nguồn AC 220V | 1 | Tháo bỏ mọi tải phụ không liên quan (như sò lạnh Peltier). Lắp cầu chì ống `5×20 mm 2A` nối tiếp trên đường `+12V`. |
| 8 | **Hạ áp 5V** | Module Buck `LM2596S` (bản có LED 7 đoạn báo áp) | 1 | Hạ từ `12V` xuống chuẩn **`05.0V`**. Có nút bấm chuyển đổi hiển thị `IN` / `OUT`. |
| 9 | **Breadboard** | `Breadboard MB-102` (830 lỗ) | **2** | **Bắt buộc dùng 2 cái ghép đôi** (tháo bỏ thanh nguồn ở khe giữa) mới cắm vừa bề ngang của `ESP32-S3-DevKitC-1`. |
| 10 | **Linh kiện rời** | Trở `150Ω`, `220Ω`, `1kΩ`, `2.2kΩ` (`2K2`), `10kΩ` (1/4W) | Mỗi loại 1 túi | Dùng `1kΩ + 2.2kΩ` làm cầu phân áp chân `Vo`; `150Ω` lọc nguồn `VLED`; `220Ω` hạn dòng `Status LED`. |
| 11 | **Tụ & Nút** | Tụ hóa `220µF 16V`, Nút nhấn 4 chân `6×6×5mm`, LED 5mm | 1 bộ | Tụ phân cực (vạch trắng `-` là chân ngắn nối `GND`, chân dài `+` nối `VLED`). |
| 12 | **Dây nối** | Dây đôi `20AWG` Đỏ-Đen (2m), Dây Dupont (`M-M`, `M-F`, `F-F`), Header đực `1×40p` | 1 bộ | Các mối nối với dây đồng tơ nhỏ (như dây 5P của cảm biến bụi) nên cắt bỏ đầu Cái Dupont, **xoắn trực tiếp lõi đồng và quấn băng keo điện** để tiếp xúc chắc chắn 100%. |

---

## ⚠️ 2. So Sánh Giữa Kế Hoạch (`embedded-layer.md`) & Thực Tế Triển Khai

Trong quá trình lắp đặt và kiểm thử thực tế, có **6 điểm khác biệt quan trọng** so với tài liệu thiết kế ban đầu [`docs/embedded-layer.md`](./embedded-layer.md) mà đội phát triển firmware cần đặc biệt lưu ý:

| # | Hạng mục | Trong `docs/embedded-layer.md` (Lý thuyết) | Thực tế phần cứng & Datasheet gốc (Đã kiểm chứng) | Hành động cần sửa khi viết Firmware |
| :---: | :--- | :--- | :--- | :--- |
| **1** | **Cực tính xung PWM của `GP2Y1023AU0F`** | Mục `§4.2` đo độ rộng xung mức **HIGH** (`if (digitalRead == HIGH) start = now; else width = now - start;`). | Theo **Trang 9 `GP2Y1023AU0F Specs.pdf`** và đo thực tế: Chân `Vo` ở trạng thái nghỉ là **HIGH (`~4.8V`)**, khi phát xung đo bụi sẽ **kéo xuống LOW (`~0.2V`) trong khoảng thời gian $W$** (`6.8 µs – 7000 µs`). Thực tế: Không khí phòng `LOW = 1448 µs` (`17.7 µg/m³`), thổi khói thuốc `LOW = 7437 µs` (`445.5 µg/m³`). | Trong hàm ngắt `IRAM_ATTR dustPWM_ISR()`, **bắt buộc phải đo thời gian mức `LOW`** (từ sườn xuống `FALLING` đến sườn lên `RISING`), đọc trực tiếp thanh ghi `REG_READ(GPIO_IN_REG)` để bắt kịp xung ngắn tới `7 µs`, và reset `_dustPulseStart = 0` sau mỗi xung. |
| **2** | **Thứ tự chân vật lý `1..5` trên giắc `GP2Y1023AU0F`** | Mục `§2.2` chỉ liệt kê bảng chân `Pin 1..5` mà không chỉ rõ chiều nhìn trên vỏ cảm biến. | Theo bản vẽ `CY15280i02` (**Trang 6 `GP2Y1023AU0F Specs.pdf`**), khi nhìn thẳng vào mặt vỏ kim loại (giắc cắm hướng lên trên): **Bên TRÁI (phía ốc vít & 4 lỗ nhỏ) là Chân ⑤ (`Vcc`)**, còn **bên PHẢI (phía góc vát chéo) mới là Chân ① (`Vo`)**! | Đấu dây theo đúng chiều từ Trái sang Phải: `⑤ Vcc (+5V)` $\rightarrow$ `④ VLED` $\rightarrow$ `③ GND` $\rightarrow$ `② LED-GND` $\rightarrow$ `① Vo`. |
| **3** | **API điều khiển PWM (`LEDC`) trên Arduino ESP32 v3.x** | Mục `§7.2` dùng API cũ của core v2.x: `ledcSetup(ch, freq, res)` + `ledcAttachPin(pin, ch)` + `ledcWrite(ch, duty)`. | PlatformIO hiện tải `espressif32 @ 8.0.0` (`framework-arduinoespressif32 @ 3.3.12` - **Arduino Core v3.x**). Các hàm `ledcSetup` và `ledcAttachPin` **đã bị xóa**, gây lỗi biên dịch. | Đổi sang API mới của Arduino ESP32 v3.x:<br>`ledcAttach(FAN_PWM_PIN, 25000, 8);`<br>`ledcWrite(FAN_PWM_PIN, duty);` |
| **4** | **Cấu hình `platformio.ini` cho cổng USB Native (`/dev/ttyACM0`)** | Mục `§3.1` chỉ khai báo `board = esp32-s3-devkitc-1`. | Khi cắm cáp vào cổng Native USB (`303a:1001`), nếu không bật cờ `USB_CDC_ON_BOOT` thì `Serial.print` sẽ không xuất ra `/dev/ttyACM0`. Ngoài ra sau khi flash lần đầu ở Download Mode cần `watchdog-reset` hoặc bấm `RST`. | Thêm vào `platformio.ini`:<br>`build_flags = -DARDUINO_USB_MODE=1 -DARDUINO_USB_CDC_ON_BOOT=1` |
| **5** | **Đứt mạch giữa chừng trên thanh nguồn Breadboard (`Số 30`)** | Không đề cập trong tài liệu lý thuyết. | Trên Breadboard `MB-102 830 lỗ`, đường nguồn Đỏ (`+`) và Xanh dương (`-`) **bị ngắt đôi ở vạch số `30`**. Nếu cắm `LM2596` / `OLED GND` ở nửa Trái (`31–60`) và `ESP32-S3 GND` ở nửa Phải (`0–30`) thì toàn bộ sensor bị mất Mass (`BUS_STUCK_LOW`). | **Gom toàn bộ dây nguồn `+5V` và `GND` về cùng một nửa (`0–30`)** hoặc cắm dây bắc cầu nối liền qua vết đứt ở số `30`. |
| **6** | **Hành vi Quạt Intel khi khởi động / chưa có xung PWM** | Mục `§11` ghi kiểm tra quạt đứng im khi boot. | Quạt Intel 4-pin chuẩn công nghiệp có điện trở kéo lên (pull-up) nội bộ trên chân số 4 (`PWM`). Khi `ESP32-S3` chưa khởi tạo chân `GPIO 5` (hoặc đang ở chế độ nạp), quạt sẽ **tự động chạy 100% tốc độ (Fail-safe ON của Intel)** cho đến khi `ledcWrite(5, 0)` kéo duty về `0%`. | Khởi tạo `ledcAttach(5, 25000, 8); ledcWrite(5, 0);` ngay đầu hàm `setup()` để đưa quạt về trạng thái `OFF` ngay khi MCU vừa boot. |
| **7** | **Hiện tượng "Nguồn nuôi giả" (`SCD: OK` nhưng số `0.0`) & Chu kỳ 5 giây của `SCD40`** | Gọi `stopPeriodicMeasurement()` + `startPeriodicMeasurement()` một lần ở `setup()`. | Ở trạng thái nghỉ (Idle), `SCD40` chỉ tiêu thụ **`15 µA`**, nên dù chân `VCC` bị lỏng hoặc cắm vào đường nguồn không có điện, dòng rò từ `SDA / SCL (3.3V)` vẫn đủ nuôi chip báo `0x62 OK`. Tuy nhiên ngay khi bật đèn hồng ngoại NDIR để đo CO2 (mỗi 5 giây), `SCD40` hút dòng đỉnh **`205 mA`** khiến áp sập về `0V` (Brown-out reset) làm kết quả luôn là `0.0`. Ngoài ra không được quét đè gói 0-byte hoặc gọi `0x3F86` liên tục trong `loop()` vì sẽ làm reset bộ đếm 5 giây. | **Bắt buộc cấp nguồn `VCC` của `SCD40` trực tiếp từ chân `3V3` của `ESP32-S3`** (đủ dòng đỉnh `>205 mA`), hàn chắc 4 chân header của `SCD40`, và trong `loop()` chỉ poll bằng lệnh `get_data_ready_status (0xE4B8)`. |

---

## 🔧 3. Quy Trình Lắp Đặt Từng Bước (Đã Kiểm Chứng)

### Bước 1: Ghép đôi Breadboard & Định vị `ESP32-S3`
1. Lấy **2 tấm Breadboard `MB-102`**, tháo bỏ **cả 2 thanh ray nguồn (`+ / -`) ở phần khe ghép giữa** để 2 thân chính của Breadboard sát lại với nhau (chỉ giữ lại 2 thanh nguồn ở 2 mép ngoài cùng: 1 thanh ở mép trên cùng, 1 thanh ở mép dưới cùng).
2. Đặt `ESP32-S3-DevKitC-1` bắc ngang qua khe giữa của 2 tấm Breadboard (2 cổng USB Type-C quay về một phía).
3. Căn thẳng đứng tất cả 44 chân kim vào đúng lỗ, dùng hai ngón cái **ấn nhấp nhả luân phiên từng đầu (đầu USB $\leftrightarrow$ đầu ăng-ten)** trên mặt bàn phẳng cho đến khi phần nhựa vàng ngập sát mặt Breadboard.

### Bước 2: Đấu khối Nguồn `12V PSU` $\rightarrow$ `LM2596` & Khóa áp `05.0V`
> ⚠️ **TUYỆT ĐỐI KHÔNG** nối đầu ra `OUT` của `LM2596` vào Breadboard trước khi chỉnh xong `05.0V`!
> ⚠️ **TUYỆT ĐỐI KHÔNG** đặt mạch trần `LM2596` nằm đè lên vỏ nhôm kim loại của nguồn tổ ong `12V` (chân hàn mặt đáy sẽ chạm vỏ gây chập nổ!).

1. Trên **Nguồn tổ ong `12V - 400W`**:
   - Giữ nguyên 2 dây nguồn AC 220V ở chân số 1 & 2 (`L` và `N`).
   - Đấu dây **Đen (`20AWG`)** vào ốc có ký hiệu **`-` (`V-`)** $\rightarrow$ nối sang cổng **`IN-`** của mạch `LM2596`.
   - Đấu dây **Đỏ (`20AWG`)** (qua cầu chì 2A) vào ốc có ký hiệu **`+` (`V+`)** $\rightarrow$ nối sang cổng **`IN+`** của mạch `LM2596`.
2. Cắm điện 220V, bấm nút nhỏ trên mạch `LM2596` sao cho đèn báo nhỏ chuyển sang sáng ở vị trí **`OUT`**.
3. Dùng tua-vít xoay ốc đồng trên biến trở xanh (`W103`) **ngược chiều kim đồng hồ (~15–20 vòng)** cho đến khi màn hình LED 7 đoạn hiển thị chuẩn **`05.0`**, sau đó rút điện 220V ra.

### Bước 3: Quy hoạch Thanh Nguồn (`+5V` và `GND`) trên Breadboard
Để tránh bẫy **đứt mạch ở vạch số `30`** và tránh nhầm chiều vạch màu giữa 2 tấm Breadboard:
- **Quy tắc 1:** Chỉ sử dụng **nửa bên Phải (từ số `0` đến số `28`, phía đầu có cắm `ESP32-S3`)** của cả 2 thanh nguồn Trên và Dưới.
- **Quy tắc 2:** Luôn nhìn đúng **vạch sơn màu** in trên nhựa:
  - **Vạch Đỏ (`+`)** = Đường **`+5.0V`** (nối với `OUT+` của `LM2596`, nối bắc cầu từ Vạch Đỏ dưới lên Vạch Đỏ trên).
  - **Vạch Xanh dương (`-`)** = Đường **`GND` chung** (nối với `OUT-` của `LM2596`, nối với chân `GND` của `ESP32-S3`, và nối bắc cầu từ Vạch Xanh dương dưới lên Vạch Xanh dương trên).
- **Quy tắc 3 (Khi cắm cáp USB Type-C vào máy tính để nạp code/debug):**
  - **Rút riêng đầu dây ở chân `5V` của `ESP32-S3` ra** (để nguồn 5V từ cổng USB máy tính không xung đột với nguồn 5.0V từ `LM2596`).
  - **Giữ nguyên dây `GND` của `ESP32-S3`** nối với Vạch Xanh dương (`-`) để toàn mạch chung Mass.

### Bước 4: Lắp các cụm Linh kiện lên Breadboard

#### 4.1. Cụm Status LED (`GPIO 7`) & Nút nhấn Manual Override (`GPIO 6`)
- **Status LED:** Chân **`7`** (`ESP32-S3`) $\rightarrow$ Trở **`220Ω`** $\rightarrow$ Chân dài (`+`) của **LED Xanh lá** $\rightarrow$ Chân ngắn (`-`) của LED cắm vào **Vạch Xanh dương (`-` / GND)**.
- **Nút nhấn 4 chân:** Cắm bắc ngang qua rãnh giữa của tấm Breadboard dưới.
  - 1 chân nối về chữ **`6`** trên `ESP32-S3`.
  - Chân phía bên kia nối về **Vạch Xanh dương (`-` / GND)**.

#### 4.2. Cụm Màn hình `OLED 0.96" SSD1306` (`0x3C`) & Cảm biến `SCD40` (`0x62`)
- **Cách cắm OLED không bị che lỗ:** Cắm 4 chân của OLED vào **Hàng `E` (mép trên của rãnh giữa)** để mặt kính màn hình vắt xuống phía dưới rãnh giữa, còn các lỗ **Hàng `A - B - C - D`** ngay phía trên đầu 4 chân OLED lộ thiên hoàn toàn.
- **Đi dây chung Bus I2C (`GPIO 8` & `GPIO 9`) cho cả OLED và SCD40:**

| Tên chân trên OLED & SCD40 | Điểm đấu nối tới `ESP32-S3` / Breadboard |
| :--- | :--- |
| **`VCC` (hoặc `VIN`)** | Nối vào chân **`3V3`** trên `ESP32-S3` |
| **`GND`** | Nối vào **Vạch Xanh dương (`-` / GND)** (cùng nửa `0–28`) |
| **`SCL`** | Nối vào chân số **`9`** trên `ESP32-S3` |
| **`SDA`** | Nối vào chân số **`8`** trên `ESP32-S3` |

#### 4.3. Cụm Cảm biến bụi `Sharp GP2Y1023AU0F` (`GPIO 18`)
1. **Mạch lọc nguồn LED (trên tấm Breadboard bên kia, cách ly khỏi chân ESP32-S3):**
   - Chọn 1 hàng 5 lỗ trống (ví dụ Hàng `35` cột `f-g-h-i-j`):
   - **Trở `150Ω`:** 1 chân vào **Vạch Đỏ (`+5V`)**, 1 chân vào **Hàng `35`**.
   - **Tụ hóa `220µF 16V`:** Chân **Dài (`+`)** vào **Hàng `35`**, chân **Ngắn (`-`, có sọc trắng)** vào **Vạch Xanh dương (`-` / GND)**.
2. **Mạch cầu phân áp tín hiệu (`5V` $\rightarrow$ `3.4V` bảo vệ GPIO):**
   - Tại chân số **`18`** của `ESP32-S3`:
     - Cắm 1 chân của **Trở `2.2kΩ` (`2K2`)** vào hàng chân **`18`**, chân kia của `2K2` cắm thẳng ra **Vạch Xanh dương (`-` / GND)**.
     - Cắm 1 chân của **Trở `1kΩ` (`1K`)** vào hàng chân **`18`**, chân kia của `1K` cắm sang 1 hàng trống để chờ đón dây `Vo` từ cảm biến bụi.
   - *(Lưu ý: Uốn tách rời thân trở `1K/2K2` của chân `18` khỏi trở `220Ω` của chân `7` để không chạm chập trên không trung).*
3. **Đấu 5 dây từ giắc `GP2Y1023AU0F` (Nhìn mặt vỏ kim loại, giắc cắm ở phía trên, từ Trái qua Phải theo đúng Trang 6 `GP2Y1023AU0F Specs.pdf`):**
   - **Chân ⑤ (Ngoài cùng bên TRÁI - sát ốc vít) — `Vcc`:** Cắm vào **Vạch Đỏ (`+5V`)**.
   - **Chân ④ (Thứ 2 từ trái sang) — `VLED`:** Cắm vào **Hàng `35`** (điểm nối chung giữa Trở `150Ω` và cực Dương `+` của Tụ `220µF`).
   - **Chân ③ (Chính giữa) — `GND`:** Cắm vào **Vạch Xanh dương (`-` / GND)**.
   - **Chân ② (Thứ 4 từ trái sang) — `LED-GND`:** Cắm vào **Vạch Xanh dương (`-` / GND)** (ngay sát chân âm Tụ `220µF`).
   - **Chân ① (Ngoài cùng bên PHẢI - sát góc vát chéo) — `Vo`:** Cắm vào **chân chờ của Trở `1kΩ`** (đầu vào cầu phân áp `GPIO 18`).

#### 4.4. Cụm Quạt `Intel 4-Wire PWM` (`GPIO 5`) + Lõi lọc `HEPA H13`
Nhìn vào mặt có chữ **`2510 TKG`** và **mũi tên in nổi `↓`** trên đầu giắc nhựa đen 4 lỗ của quạt Intel:
1. **Lỗ 1 (Ngoài cùng bên có mũi tên `↓`) — `GND`:** Nối về **Vạch Xanh dương (`-` / GND)**.
2. **Lỗ 2 (Sát cạnh Lỗ 1) — `+12V`:** Nối trực tiếp về ốc **`IN+` (`+12V`)** trên mạch `LM2596` (hoặc ốc `+` trên Nguồn tổ ong). **Tuyệt đối không cắm dây 12V này xuống Breadboard 5V!**
3. **Lỗ 3 (Giữa, dưới chữ `TKG`) — `Tach`:** **BỎ TRỐNG** (cách ra 1 lỗ trống).
4. **Lỗ 4 (Ngoài cùng bên chữ `2510`) — `PWM`:** Nối vào hàng chân số **`5`** trên `ESP32-S3`.

---

## 💻 4. Thiết Lập Môi Trường Nạp & Đo Kiểm Trên Linux

### 4.1. Cấp quyền cổng Serial cố định (`udev rule`)
Khi cắm vào cổng Native USB của `ESP32-S3`, Linux nhận thiết bị tại `/dev/ttyACM0` (`ID 303a:1001 Espressif USB JTAG/serial debug unit`). Để không bị mất quyền truy cập mỗi khi mạch reset, chạy 1 lần lệnh sau:

```bash
echo 'KERNEL=="ttyACM*", MODE="0666"' | sudo tee /etc/udev/rules.d/99-esp32.rules \
  && sudo udevadm control --reload-rules \
  && sudo chmod 666 /dev/ttyACM*
```

### 4.2. Chuyển vào chế độ Nạp lần đầu (Download Mode)
Nếu mạch đang chạy firmware gốc từ nhà máy chưa bật CDC:
1. Nhấn giữ nút **`BOOT`** trên `ESP32-S3`.
2. Nhấp thả nút **`RST`** 1 cái.
3. Thả nút **`BOOT`** $\rightarrow$ Nạp code bằng PlatformIO (`~/.platformio/penv/bin/pio run -t upload`).
4. Sau khi nạp xong, nếu mạch vẫn dừng ở `waiting for download`, bấm nhẹ nút **`RST`** trên mạch (hoặc chạy `esptool --port /dev/ttyACM0 --after watchdog-reset chip-id`) để boot vào chương trình chính.

---

## 📊 5. Kết Quả Thực Nghiệm Đã Kiểm Chứng (Baseline Chuẩn)

Hệ thống sau khi lắp đặt hoàn chỉnh đã vượt qua bài **Full Hardware Self-Test** với các thông số thực tế ghi nhận tại phòng thử nghiệm:

```text
[FULL-TEST] I2C(SDA8=1,SCL9=1,cnt=2:[0x3C 0x62 ]) | OLED_0x3C=OK | SCD40_0x62=DATA_OK(CO2=961ppm,T=30.1C,RH=75.6%) | DUST_18(L=1448us,PM25=17.7) | BTN6=OPEN | FAN5=90%
```

- **Bus I2C (`GPIO 8 / 9`):** Nhận diện chính xác đồng thời cả 2 địa chỉ **`0x3C` (`OLED SSD1306`)** và **`0x62` (`Sensirion SCD40`)**.
- **Cảm biến `SCD40`:** Trả về gói dữ liệu định kỳ mỗi 5 giây (`CO2 ≈ 961 ppm`, `T ≈ 30.1 °C`, `RH ≈ 75.6 %`).
- **Cảm biến bụi `GP2Y1023AU0F`:**
  - Trạng thái không khí phòng bình thường: Độ rộng xung âm `L = 1448 µs` (nằm chính giữa dải chuẩn nhà máy `1250–1550 µs`, Typ `1400 µs` của Sharp), tương ứng **`PM2.5 = 17.7 µg/m³`**.
  - Thử nghiệm độ nhạy với khói thuốc lá: Độ rộng xung lập tức tăng vọt lên `L = 7437 µs`, tương ứng **`PM2.5 = 445.5 µg/m³`**, chứng minh mạch lọc RC (`150Ω + 220µF`) và cầu phân áp (`1kΩ + 2.2kΩ`) hoạt động hoàn hảo.
- **Điều tốc Quạt (`GPIO 5`):** Nhận xung `25 kHz` mượt mà ở cả 4 mức `0% - 35% - 60% - 90%`.
