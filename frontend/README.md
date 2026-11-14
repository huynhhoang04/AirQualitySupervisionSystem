# Air Quality Supervision System — Dashboard (React + TypeScript)

Giao diện desktop-first cho backend: tổng quan thiết bị, phân tích một phòng, trung tâm AI, trang
đánh giá và nhật ký. Forecast đọc từ backend (`GET /api/v1/devices/{id}/forecast`); worker local
(`ai-service/`) huấn luyện và tải run lên, trình duyệt không gọi trực tiếp service AI.

## Chạy

```bash
cd frontend
npm install
echo 'VITE_API_URL=http://localhost:8080' > .env.local   # hoặc URL Render
npm run dev        # http://localhost:5173
npm run build      # tsc -b + vite build → dist/
npm run lint       # oxlint
```

`VITE_API_URL` là base URL của backend (mặc định `http://localhost:8080`). Khi deploy dashboard,
thêm origin đó vào `CORS_ORIGIN` của backend.

## Cấu trúc

```text
src/
├── api/          client.ts (fetch + endpoint), types.ts (khớp io_contract §7 + §11)
├── hooks/        useAqss.ts — devices/latest/series/summary/samples/forecast/AI/commands
├── components/   AppShell, SeriesChart, ForecastPanel, FanSelector, Reading, Icon
├── lib/          room.ts (ngưỡng, trạng thái, quạt), format.ts
├── pages/        RoomsPage, RoomPage, RoomSettingsPage, DevicesPage,
│                 AiCenterPage, EvaluationPage, AuditPage, ConnectPage, GuidePage
├── App.tsx       layout route + các màn hình
└── main.tsx      QueryClientProvider
```

## Route

| Route | Màn hình |
| :--- | :--- |
| `/` | Tổng quan: KPI, chart PM2.5 24 h, bảng thiết bị |
| `/room/:deviceId` | Phân tích phòng, điều khiển, forecast, bảng số đo |
| `/room/:deviceId/settings` | Ngưỡng, lõi lọc, unpair |
| `/ai` | Trung tâm AI: worker, model card, benchmark, jobs |
| `/evaluation` | Test metrics, promotion, cảnh báo |
| `/devices` | Thiết bị + hồ sơ phòng |
| `/audit` | Nhật ký |
| `/connect`, `/guide` | Luồng kết nối, hướng dẫn (không dùng shell) |

## Nhịp cập nhật

| Dữ liệu | Chu kỳ |
| :--- | :--- |
| `/devices`, `/devices/{id}/latest`, commands, actuator history | 5 s |
| `/devices/{id}/series`, `/summary`, `/samples`, `/forecast`, `/alerts` | 60 s |
| `/ai/status` | 15 s |
| `/ai/jobs` | 5 s |
| `/audit` | 30 s |
| `/spaces` | 60 s |

## Ghi chú

- Không dùng STOMP/WebSocket: backend chỉ có REST; polling 5 s khớp nhịp telemetry.
- Điều khiển quạt: bấm AUTO/OFF/L1/L2/L3 → `POST /devices/{id}/command`. UI phân biệt **lệnh đã
  gửi** (`PUBLISHED`), **máy đã báo khớp** (`REPORT_MATCHED`) và **publish lỗi**; không đổi trạng
  thái thành “đã bật” chỉ vì HTTP 2xx.
- Chart dùng `/series`: mỗi bucket có median + min/max, nên spike không bị bỏ âm thầm.
- Ngưỡng PM2.5 lưu trên space; trang AI/Evaluation không tự đổi ngưỡng.
