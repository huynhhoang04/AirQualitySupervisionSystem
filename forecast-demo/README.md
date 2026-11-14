# Air Quality Supervision System — Forecast demo

Service Python độc lập đọc lịch sử **24 giờ** qua GET telemetry của backend có sẵn, học model
**scikit-learn Ridge regression** rồi trả 6 điểm dự báo cách nhau 5 phút. Frontend vẽ kết quả.
Không cần sửa hoặc chạy migration cho backend/firmware.

## Chạy nhanh

Từ thư mục `forecast-demo`:

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
BACKEND_URL=http://localhost:8080 uvicorn app:app --host 0.0.0.0 --port 8001
```

Đặt `BACKEND_URL` bằng địa chỉ backend đang có dữ liệu. Nếu frontend chạy ở origin khác,
thêm `CORS_ORIGINS`, ví dụ `CORS_ORIGINS=http://localhost:5173,https://your-dashboard.example`.

Trong `frontend/.env.local`, thêm:

```dotenv
VITE_FORECAST_API_URL=http://localhost:8001
```

Khởi động lại Vite, mở trang chi tiết phòng. Khối **Dự báo PM2.5 · 30 phút** nằm dưới lịch sử.
Nếu mở dashboard trên điện thoại, dùng địa chỉ LAN của máy chạy service thay cho `localhost`;
dashboard HTTPS cần một URL service HTTPS để tránh bị trình duyệt chặn mixed content.

- `GET /health`: kiểm tra service.
- `GET /forecast/{device_id}`: đọc dữ liệu thật từ backend và tính dự báo.
- `/docs`: trang API tự sinh của FastAPI.

## Cách tính

Gom trung vị mỗi 5 phút, loại mẫu null/ngoài dải/bão hòa và không bù qua khoảng mất dữ liệu.
Dùng 6 giá trị trễ (30 phút trước) để dự đoán mốc kế tiếp. Pipeline StandardScaler + Ridge
được fit nhanh trên dữ liệu lấy được trong ngày; dự báo đệ quy thêm 6 bước và giới hạn 0–500.
Cần tối thiểu 6 cửa sổ huấn luyện hợp lệ cùng 6 bucket liên tục ở cuối, khoảng 60 phút dữ liệu đều.
Mẫu hợp lệ cuối cũ hơn 5 phút thì trả `stale_data`, không tạo đường giả.

Khi đủ 24 cửa sổ trở lên, service thử giữ lại 6 cửa sổ cuối, so MAE **một bước 5 phút** với
phương pháp giữ số cuối. Sau đó fit lại toàn bộ dữ liệu để dự báo. Đây là demo, chưa có kiểm định
độc lập cho chuỗi 30 phút; model có thể kém baseline và không dự đoán được nguồn bụi mới xuất hiện.

`observed`/`predicted` dùng `{at: Unix milliseconds, pm25: number}`. `predicted` có thêm điểm neo
ban đầu để nối nét liền và nét đứt. Dữ liệu không được ghi vào DB; không có lệnh gửi xuống quạt.

Chạy kiểm tra:

```bash
python -m unittest -v
```
