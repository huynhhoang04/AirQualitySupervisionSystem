# Render — biến môi trường

Giá trị dưới đây điền vào **Render → Settings → Environment** (hoặc lúc tạo Blueprint với
`backend/render.yaml`). **Không commit mật khẩu vào repo** — file này chỉ ghi tên biến và nơi lấy giá trị.

## 1. Bảng biến

| Biến | Giá trị | Nguồn |
| :--- | :--- | :--- |
| `DB_URL` | `jdbc:postgresql://<host-neon>/<db>?sslmode=require` | Neon → Dashboard → Connection string (endpoint **direct**, không `-pooler`) |
| `DB_USER` | `neondb_owner` (hoặc user Neon tạo) | Neon |
| `DB_PASS` | *(mật khẩu Neon)* | Neon |
| `DB_POOL_MAX` | `5` | cố định — Neon free có hạn mức connection |
| `DB_POOL_MIN` | `2` | cố định |
| `MQTT_BROKER_URL` | `ssl://vc1f2107.ala.asia-southeast1.emqxsl.com:8883` | EMQX Cloud Serverless (chỉ mở 8883/8443 TLS) |
| `MQTT_USER` | `aqss-admin` | EMQX → Access Control → Authentication |
| `MQTT_PASS` | *(mật khẩu MQTT của `aqss-admin`)* | như trên |
| `MQTT_QOS` | `1` | cố định |
| `AI_BASE_URL` | `http://localhost:8000` | Layer 3 chưa deploy — để tạm |
| `AI_ENABLED` | `false` | khi Layer 3 lên thì đổi `true` + URL thật |
| `CORS_ORIGIN` | `http://localhost:5173` | đổi thành URL frontend khi deploy |
| `LOG_LEVEL_AQSS` | `INFO` | |
| `PORT` | *(Render tự inject)* | **không** đặt tay; app đọc `SERVER_PORT` rồi tới `PORT` |

Giá trị cố định trong `application.yml`, không cần đặt:
`ddl-auto=validate`, Jackson SNAKE_CASE, Flyway bật, Hikari mặc định 5/2 nếu không có `DB_POOL_*`.

## 2. Dán nhanh vào Render

```text
DB_URL=jdbc:postgresql://<host-neon>/<db>?sslmode=require
DB_USER=<neon-user>
DB_PASS=<neon-password>
DB_POOL_MAX=5
DB_POOL_MIN=2
MQTT_BROKER_URL=ssl://vc1f2107.ala.asia-southeast1.emqxsl.com:8883
MQTT_USER=aqss-admin
MQTT_PASS=<mqtt-password>
MQTT_QOS=1
AI_BASE_URL=http://localhost:8000
AI_ENABLED=false
CORS_ORIGIN=http://localhost:5173
LOG_LEVEL_AQSS=INFO
```

## 3. Kiểm tra sau khi deploy

```bash
APP=https://<service>.onrender.com
curl -s $APP/actuator/health          # {"status":"UP"}
grep -E "MQTT connected|Successfully applied" <render logs>
```

Log đúng phải là:

```text
Successfully applied 1 migration to schema "public", now at version v1
MQTT connected to ssl://vc1f2107.ala.asia-southeast1.emqxsl.com:8883 (telemetry + status + actuator state)
Started BackendApplication
```

## 4. Ghi chú về chứng chỉ

EMQX Serverless dùng chứng chỉ **DigiCert Global Root G2** (`…ala.asia-southeast1.emqxsl.com`).
Firmware pin đúng root này (`MQTT_ROOT_CA` trong `embedded/include/config.h`, PEM ở
`embedded/include/tls_certs.h`). Nếu đổi sang broker khác dùng Let's Encrypt thì đổi thành
`kCaIsrgX1` và build lại.

## 5. Bảo mật

- Mật khẩu Neon/EMQX chỉ nằm trong Render env + NVS của node; **không** đưa vào git.
- Node chỉ lưu **mật khẩu** broker; host/port/username là hằng số trong `embedded/include/config.h`.
- REST hiện không auth: ai biết URL Render đều gửi lệnh được. Nếu cần chặn, thêm header `X-Device-Key`
  (so với `devices.device_id` trong DB) — chưa làm.
