# Layer 4 — Dashboard (React + TypeScript)

Bảng điều khiển cho backend Layer 2: số đo mới nhất, biểu đồ 24 giờ, điều khiển quạt, sửa ngưỡng
của phòng, nhật ký hệ thống. **Layer 3 chưa có → khối dự báo hiển thị `unavailable`.**

## Chạy

```bash
cd frontend
npm install
echo 'VITE_API_URL=http://localhost:8080' > .env.local   # hoặc URL Render
npm run dev        # http://localhost:5173
npm run build      # tsc -b + vite build → dist/
```

`VITE_API_URL` là base URL của backend (mặc định `http://localhost:8080`). Khi deploy dashboard,
thêm origin đó vào `CORS_ORIGIN` của backend.

## Cấu trúc

```text
src/
├── api/          client.ts (fetch + các endpoint), types.ts (khớp io_contract §7)
├── hooks/        useAqss.ts — TanStack Query: devices/latest/history/forecast/spaces/audit
├── components/   Header, SensorPanel, FanPanel, HistoryChart, ForecastPanel, ThresholdCard, AuditTable
├── lib/          format.ts — thời gian, màu theo ngưỡng, trạng thái online
├── App.tsx       chọn thiết bị, polling, bố cục
└── main.tsx      QueryClientProvider
```

## Nhịp cập nhật

| Dữ liệu | Chu kỳ |
| :--- | :--- |
| `/devices` (trạng thái quạt) | 5 s |
| `/devices/{id}/latest` | 5 s |
| `/devices/{id}/telemetry` (24 h) | 30 s |
| `/devices/{id}/forecast` | 60 s (nút "yêu cầu dự báo" gọi `POST`) |
| `/audit` | 15 s |
| `/spaces` (ngưỡng) | 60 s |

## Ghi chú

- Không dùng STOMP/WebSocket: backend hiện chỉ có REST, polling 5 s khớp nhịp telemetry.
- Điều khiển quạt: bấm L1/L2/L3 hoặc OFF → `POST /devices/{id}/command` với `set_actuator`; backend
  đặt `fan_mode = MANUAL`. Vòng điều khiển tự động chỉ can thiệp khi quạt ở `AUTO`.
- Ngưỡng PM2.5 lưu trên space (`PUT /spaces/{code}`); backend đọc lại mỗi 60 s.
