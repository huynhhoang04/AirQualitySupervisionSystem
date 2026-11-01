# Deploy & CI/CD — Render + Neon + HiveMQ

> Tài liệu này gồm hai phần: **kế hoạch/quyết định** (mục 1–3) và **runbook thao tác** (mục 4–8).
> Kiến trúc chạy thật:

```text
ESP32-S3 (mạng bất kỳ: WiFi nhà, 4G, hotspot)
   │  MQTT over TLS 8883 (user/pass)
   ▼
HiveMQ Cloud ────────────────┐
   │  subscribe telemetry / status / actuator-state
   ▼                          │
Render (Docker, Spring Boot)  │  publish set_actuator / set_space / ...
   │                          │
   ▼                          │
Neon Postgres                 │
   ▲                          │
   └──── dashboard (REST) ────┘
```

Không mở port nào: node và backend đều **dial ra** broker.

---

## 1. Quyết định (đã chốt)

| Vấn đề | Chọn | Lý do |
| :--- | :--- | :--- |
| Broker | **HiveMQ Cloud free** (`ssl://…:8883`) | Render không mở TCP nên không thể host MQTT; free tier có TLS + auth |
| Backend | **Render** + `backend/render.yaml` (Docker, plan free) | Dockerfile đa tầng đã có, healthcheck `/actuator/health` |
| Database | **Neon free**, endpoint **direct** (không `-pooler`) | Flyway + JPA ổn định; Hikari pool đặt 5 cho vừa hạn mức |
| CD | Render `autoDeploy: false` + GitHub Actions gọi **deploy hook** sau khi CI xanh | Không bao giờ deploy một commit đỏ |
| Layer 3 | **Không nằm trong repo**; giữ contract `io_contract.md` §8 | `AiClient` vẫn gọi 3 endpoint khi service có mặt |
| Firmware | Build trong CI, phát hành theo tag; cấu hình qua SoftAP | Không nhúng credential vào firmware |

## 2. Những gì phải thay đổi để deploy được

| Hạng mục | Trước | Sau |
| :--- | :--- | :--- |
| Backend | 125 file / 4.5 kLOC, auth JWT, STOMP, partition, MapStruct | **23 file / 1.3 kLOC**: 6 component, 5 bảng, 1 migration |
| Router/DB | Postgres 16 + partition tháng + materialized view | Neon, bảng thường + index |
| MQTT | Spring Integration + `tcp://` | 1 Paho client + `ssl://…:8883` |
| Auth | JWT/RBAC/device API key | Không auth (xem rủi ro §3) |
| Layer 3 | `dl_analyst/` (68 MB) trong repo | Xoá; chỉ còn contract |
| Firmware | WiFi/broker hardcode trong `config.h` | SoftAP portal ghi vào NVS, TLS 8883 |
| CI | backend + layer3 + bruno + compose | backend, Docker image, firmware, bruno |
| CD | Render auto-deploy mỗi push | Deploy hook chỉ khi CI xanh |

## 3. Rủi ro phải biết trước

| Rủi ro | Ảnh hưởng | Xử lý |
| :--- | :--- | :--- |
| **Render free ngủ sau 15 phút** | Vòng điều khiển 60 s không chạy → không ra lệnh quạt; node tự fail-safe OFF sau 10 phút | Giữ dashboard mở khi demo, hoặc pinger 5 phút gọi `/actuator/health`, hoặc nâng plan |
| Neon free suspend | Request đầu chậm ~1 s | Chấp nhận (pool 5) |
| HiveMQ free: 100 connection / 10 GB-tháng | Telemetry 5 s ≈ 3.5 MB/ngày → thoải mái | Không cần làm gì |
| REST mở, không auth | Ai biết URL Render đều bật/tắt quạt được | Tuỳ chọn: thêm header `X-Device-Key` so với DB (không cần JWT) |
| Đổi WiFi nhưng quên broker host | `mqtt: connect … failed (-2)` | Giữ nút 3 s khi cấp nguồn để mở lại portal |
| Render build chậm ở lần deploy đầu | 5–8 phút (Maven tải dependency trong Docker) | Bình thường |

---

## 4. Chuẩn bị

| Thứ cần | Lấy ở đâu |
| :--- | :--- |
| Repo GitHub | nhánh `main` là nhánh deploy |
| Neon | neon.tech (free) |
| HiveMQ Cloud | console.hivemq.cloud (free) |
| Render | render.com (free) |

## 5. Neon (Postgres)

1. Tạo project, chọn region gần (Singapore).
2. Copy connection string `postgresql://user:pass@ep-xxx.ap-southeast-1.aws.neon.tech/aqss_db?sslmode=require`.
3. Đổi sang JDBC, **giữ nguyên** `sslmode`:

```text
jdbc:postgresql://ep-xxx.ap-southeast-1.aws.neon.tech/aqss_db?sslmode=require
```

4. Dùng endpoint **direct** (không có `-pooler`) — Flyway chạy ổn định hơn.
5. Không tạo bảng tay: `V1__init.sql` tạo schema và seed `SP_201` + device `AQSS_ESP32S3_01`.

## 6. HiveMQ Cloud (broker)

1. Tạo **Free Serverless Cluster**, region gần.
2. **Access Management** → tạo credential (user/pass), ghi lại.
3. Host: `xxxxxxxx.s1.eu.hivemq.cloud`, cổng **8883** (TLS).
4. Backend dùng `MQTT_BROKER_URL=ssl://xxxxxxxx.s1.eu.hivemq.cloud:8883`.
5. Firmware nhập cùng host/user/pass qua portal (mục 8).

## 7. Render + GitHub

1. Render → **New + → Blueprint** → chọn repo → **Blueprint Path: `backend/render.yaml`**.
2. Điền các biến `sync: false`:

| Biến | Giá trị |
| :--- | :--- |
| `DB_URL` | JDBC URL ở mục 5 |
| `DB_USER` / `DB_PASS` | user/pass Neon |
| `MQTT_BROKER_URL` | `ssl://<host>:8883` |
| `MQTT_USER` / `MQTT_PASS` | credential HiveMQ |
| `AI_BASE_URL` | bỏ trống hoặc URL Layer 3 |
| `CORS_ORIGIN` | origin dashboard (tạm `http://localhost:5173`) |

3. Deploy lần đầu, log phải có: `Successfully applied 1 migration` → `MQTT connected to ssl://…` → `Started BackendApplication`.
4. Copy URL `https://<service>.onrender.com` và **Settings → Deploy Hook** (URL hook).
5. GitHub → **Settings → Secrets and variables → Actions**:
   - Secret `RENDER_DEPLOY_HOOK` = hook ở trên.
   - Variable `APP_URL` = `https://<service>.onrender.com`.

### CI/CD chạy thế nào

| Workflow | Trigger | Việc làm |
| :--- | :--- | :--- |
| `ci.yml` | mỗi push/PR vào `main` | backend (`mvnw verify` + Testcontainers), Docker image + `compose config`, firmware (`pio run`, artifact `.bin`), Bruno workspace |
| `deploy.yml` | khi `ci.yml` trên `main` kết thúc **thành công** | gọi Render deploy hook → chờ `/actuator/health` trả 200 (tối đa 3 phút) |
| `firmware-release.yml` | tag `fw-v*` | build và đính `firmware.bin`, `bootloader.bin`, `partitions.bin` vào Release |

> `render.yaml` đặt `autoDeploy: false` đúng để deploy chỉ đi qua `deploy.yml`.

## 8. Firmware (node)

1. Bật TLS trong `embedded/include/config.h`:

```c
#define MQTT_USE_TLS 1        // 1 = cổng 8883 + ISRG Root X1; mặc định 0 cho broker LAN
```

2. Nạp:

```bash
cd embedded
~/.platformio/penv/bin/pio run -e esp32-s3-devkitc-1 -t upload
```

3. Lần đầu cấp nguồn, node phát `AQSS-xxxx` / `aqss1234` (OLED hiện `AQSS SETUP`):
   - điện thoại nối AP đó → mở `http://192.168.4.1`
   - **SSID + mật khẩu WiFi**
   - **host** `xxxxxxxx.s1.eu.hivemq.cloud`, **port 8883**, **user/pass** HiveMQ
   - *Save and reboot*
4. Đổi mạng/broker sau này: giữ nút khi cấp nguồn 3 s để mở lại portal.

---

## 9. Kiểm tra sau deploy

```bash
APP=https://<service>.onrender.com

curl -s $APP/actuator/health                     # {"status":"UP"}
curl -s $APP/api/v1/devices                      # thấy device + last_seen_at
sleep 6
curl -s $APP/api/v1/devices/AQSS_ESP32S3_01/latest

curl -s -X POST $APP/api/v1/devices/AQSS_ESP32S3_01/command \
  -H 'Content-Type: application/json' \
  -d '{"command":"set_actuator","params":{"state":"ON","level":2}}'

curl -s "$APP/api/v1/audit?limit=10"             # DEVICE/STATUS, USER/ACTIVATE...
```

Tiêu chí hoàn thành:

- [ ] CI xanh 4 job trên PR.
- [ ] Push `main` → `deploy.yml` chạy sau khi CI xanh.
- [ ] `GET /actuator/health` = `UP`.
- [ ] Node ở **mạng bất kỳ** publish telemetry → `last_seen_at` mới, `latest` có số liệu.
- [ ] `POST …/command` → quạt đổi tốc độ, OLED đổi `FAN`.
- [ ] `GET /api/v1/audit` có `DEVICE/STATUS` và `USER/ACTIVATE`.

## 10. Sự cố thường gặp

| Triệu chứng | Nguyên nhân | Xử lý |
| :--- | :--- | :--- |
| Backend `502` ngay sau deploy | Render free đang ngủ/vừa restart | đợi ~40 s rồi gọi lại health |
| Log Render `MQTT unavailable` | sai URL/credential | `MQTT_BROKER_URL` phải là `ssl://…:8883` |
| Node `mqtt: connect … failed (-2)` | sai host/port, chưa bật TLS, hoặc mạng chặn 8883 | provision lại; bật `MQTT_USE_TLS 1`, port 8883 |
| Node `mqtt: connect … failed (-4)` | sai user/pass HiveMQ | sửa trong portal |
| Flyway `checksum mismatch` | DB còn schema cũ | xoá schema trên Neon rồi deploy lại |
| Control loop không chạy | Render free ngủ | mở dashboard hoặc pinger 5 phút |
