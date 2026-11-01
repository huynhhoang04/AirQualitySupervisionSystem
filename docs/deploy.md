# Deploy — Render + Neon + HiveMQ

Kiến trúc chạy thật:

```text
ESP32-S3 (mạng bất kỳ, 4G/WiFi)
   │  MQTT over TLS 8883 (user/pass)
   ▼
HiveMQ Cloud  ──────────────┐
   │  subscribe telemetry/status/actuator-state
   ▼                        │
Render (Docker, Spring Boot)│  publish set_actuator / set_sensor
   │                        │
   ▼                        │
Neon Postgres               │
   ▲                        │
   └── dashboard (REST) ────┘
```

Không mở port nào: node và backend đều **dial ra** broker.

---

## 0. Chuẩn bị

| Thứ cần | Lấy ở đâu |
| :--- | :--- |
| Repo trên GitHub | nhánh `main` là nhánh deploy |
| Tài khoản Neon | neon.tech (free) |
| Tài khoản HiveMQ Cloud | console.hivemq.cloud (free) |
| Tài khoản Render | render.com (free) |

---

## 1. Neon (Postgres)

1. Tạo project → chọn region gần (Singapore).
2. Copy **connection string** dạng `postgresql://user:pass@ep-xxx.ap-southeast-1.aws.neon.tech/aqss_db?sslmode=require`.
3. Đổi sang JDBC và **giữ nguyên** `sslmode`:

```text
jdbc:postgresql://ep-xxx.ap-southeast-1.aws.neon.tech/aqss_db?sslmode=require
```

4. Dùng endpoint **direct** (không có `-pooler`) — Flyway chạy ổn định hơn.
5. Không cần tạo bảng: Flyway `V1__init.sql` tự tạo schema + seed `SP_201` và device `AQSS_ESP32S3_01`.

---

## 2. HiveMQ Cloud (broker)

1. Tạo **Free Serverless Cluster**, chọn region gần.
2. Mục **Access Management** → tạo credential (username + password). Ghi lại.
3. Lấy host: `xxxxxxxx.s1.eu.hivemq.cloud`, cổng **8883** (TLS).
4. Backend sẽ dùng `MQTT_BROKER_URL=ssl://xxxxxxxx.s1.eu.hivemq.cloud:8883`.
5. Firmware dùng cùng host/user/pass, nhập qua portal (bước 4).

---

## 3. Render (backend)

1. Render → **New + → Blueprint** → chọn repo.
2. **Blueprint Path**: `backend/render.yaml`.
3. Render hỏi các biến `sync: false`, điền:

| Biến | Giá trị |
| :--- | :--- |
| `DB_URL` | JDBC URL ở bước 1 |
| `DB_USER` / `DB_PASS` | user/pass của Neon |
| `MQTT_BROKER_URL` | `ssl://<host>:8883` |
| `MQTT_USER` / `MQTT_PASS` | credential HiveMQ |
| `AI_BASE_URL` | để trống hoặc URL Layer 3 (chưa có thì `http://localhost:8000`) |
| `CORS_ORIGIN` | origin của dashboard (tạm `http://localhost:5173`) |

4. Deploy lần đầu → mở log, phải thấy:
   `Successfully applied 1 migration` → `MQTT connected to ssl://…` → `Started BackendApplication`.
5. Copy URL (`https://<service>.onrender.com`).
6. **Settings → Deploy Hook** → copy URL hook.

> `render.yaml` đã đặt `autoDeploy: false`: deploy đi qua GitHub Actions, không phải mỗi lần push.

---

## 4. GitHub (CI/CD)

1. **Settings → Secrets and variables → Actions**
   - Secret: `RENDER_DEPLOY_HOOK` = URL hook ở bước 3.6.
   - Variable: `APP_URL` = `https://<service>.onrender.com` (dùng cho health check sau deploy).
2. `CI` chạy mỗi push/PR: backend (Maven + Testcontainers), Docker image + compose, firmware (PlatformIO), Bruno workspace.
3. `Deploy` chạy khi **CI trên `main` xanh** → gọi deploy hook → chờ `/actuator/health` trả 200.
4. Phát hành firmware: `git tag fw-v2.1.0 && git push --tags` → workflow `Firmware release` đính `.bin` vào Release.

---

## 5. Firmware (node)

1. Bật TLS trong `embedded/include/config.h` (hoặc build flag):

```c
#define MQTT_USE_TLS 1        // mặc định 0; 1 = dùng cổng 8883 + ISRG Root X1
```

2. Nạp:

```bash
cd embedded
~/.platformio/penv/bin/pio run -e esp32-s3-devkitc-1 -t upload
```

3. Lần đầu cấp nguồn, node phát `AQSS-xxxx` / `aqss1234` (OLED hiện `AQSS SETUP`):
   - điện thoại nối AP đó → mở `http://192.168.4.1`
   - **SSID + mật khẩu WiFi**
   - **Broker host**: `xxxxxxxx.s1.eu.hivemq.cloud`, **port 8883**, **user/pass** HiveMQ
   - *Save and reboot*
4. Đổi mạng/broker sau này: giữ nút khi cấp nguồn 3 s để mở lại portal.

---

## 6. Kiểm tra

```bash
APP=https://<service>.onrender.com

curl -s $APP/actuator/health                     # {"status":"UP"}
curl -s $APP/api/v1/devices                      # thấy device + last_seen_at
sleep 6
curl -s $APP/api/v1/devices/AQSS_ESP32S3_01/latest

# Ra lệnh quạt
curl -s -X POST $APP/api/v1/devices/AQSS_ESP32S3_01/command \
  -H 'Content-Type: application/json' \
  -d '{"command":"set_actuator","params":{"state":"ON","level":2}}'

curl -s "$APP/api/v1/audit?limit=10"             # DEVICE/STATUS, USER/ACTIVATE...
```

Trên node: log `mqtt: connected`, OLED `WiFi ●  MQ ●`, `FAN ON L2 60%`.

---

## 7. Sự cố thường gặp

| Triệu chứng | Nguyên nhân | Xử lý |
| :--- | :--- | :--- |
| Backend `502` sau deploy | Render free đang ngủ / vừa restart | đợi ~40 s, gọi lại health |
| `MQTT unavailable` trong log Render | sai URL/credential HiveMQ | kiểm tra `MQTT_BROKER_URL` = `ssl://…:8883` |
| Node `mqtt: connect … failed (-2)` | host/port sai, chưa bật TLS, hoặc WiFi chặn 8883 | provision lại; thử `MQTT_USE_TLS 1` + port 8883 |
| Node `mqtt: connect … failed (-4)` | sai user/pass HiveMQ | sửa trong portal |
| Flyway lỗi `checksum mismatch` | DB đã có schema cũ | xoá schema trên Neon rồi deploy lại (dữ liệu test) |
| Control loop không chạy | Render free ngủ sau 15 phút | mở dashboard, hoặc pinger 5 phút gọi `/actuator/health` |
