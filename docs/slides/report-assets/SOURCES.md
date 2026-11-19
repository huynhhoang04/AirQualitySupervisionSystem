# Nguồn slide báo cáo

- ESP32-S3 DevKitC-1: ảnh sản phẩm [Elektroweb](https://elektroweb.pl/pl/esp32/1312-modul-esp32-s3-devkitc-1-wroom-1-n16r8-16mb-flash-wifi-bluetooth-usb-c.html).
- SCD40: ảnh sản phẩm [Electrokit](https://www.electrokit.com/en/co2-sensor-i2c-400-2000ppm-scd40).
- Sharp GP2Y1023AU0F: ảnh sản phẩm [DeviceMart / Digi-Key](https://www.devicemart.co.kr/goods/view?no=6972117).
- Sơ đồ Fritzing: `docs/slides/img/image.png` của dự án, không phải ảnh chụp.
- Dashboard: ảnh chụp frontend dự án ngày 19/11/2026; dữ liệu thay đổi liên tục.
- Đối chiếu thực tế: `actual-evidence.json`, API dashboard, lấy 22:42 ngày 19/11/2026 (UTC+7), một thiết bị, cửa sổ 6 giờ; đây không phải đánh giá độc lập trên nhiều phòng.
- Đánh giá LoRA: `ai-service/models/chronos-lora/manifest.json`; dữ liệu tổng hợp, 60 cửa sổ validation, adapter không được promote.

Ảnh sản phẩm dùng cho mục đích minh họa trong báo cáo học tập; quyền ảnh thuộc chủ sở hữu tương ứng.

- Logo PTIT: [Học viện Công nghệ Bưu chính Viễn thông](https://khcn.ptit.edu.vn/), ảnh nhận diện của học viện.

- Khuyến nghị WHO 2021 về PM2.5: [WHO Global Air Quality Guidelines](https://www.who.int/publications/i/item/9789240034228/) và [bảng tóm tắt WHO](https://cdn.who.int/media/docs/default-source/who-compendium-on-health-and-environment/who_compendium_chapter2.pdf). Mức 15 µg/m³ là trung bình 24 giờ; các hệ số điều chỉnh do đồ án đề xuất.

- Kiến trúc Chronos-2: [Amazon Science – Introducing Chronos-2](https://www.amazon.science/blog/introducing-chronos-2-from-univariate-to-universal-forecasting); hình xử lý tại trang 11 là sơ đồ rút gọn.

- Bảng dữ liệu huấn luyện: 35 dòng đầu trích từ `ai-service/data/synthetic/train/train-00000-00.parquet`, ghi lại tại `du-lieu-huan-luyen-mau.csv` (dữ liệu tổng hợp).

- Đối chiếu hai mô hình trên dữ liệu cảm biến: `so-sanh-du-lieu-thuc.json`, chạy lại bằng `so-sanh-thuc-te.py` ngày 20/11/2026; 10 mốc cách nhau tối thiểu 10 phút, dự báo cùng lịch sử và ghép phép đo trong ±3 giây tại mốc 5/15/30 phút. Mô hình bổ sung được huấn luyện trên dữ liệu tổng hợp, chưa đưa vào vận hành. Đây là kiểm tra hồi cứu trên một phòng, không phải đánh giá độc lập tổng quát.

- Điều kiện đo ở trang 14: hồ sơ phòng ngủ `PHONG_NGU`, phiên bản 6, lấy từ API `GET /api/v1/spaces/PHONG_NGU`. Thể tích 10 m³, thông gió thấp, thời gian sử dụng khai báo 12 giờ/ngày, ngưỡng kích hoạt quạt 11,5 µg/m³. Khói thuốc trong phòng do người thực hiện báo cáo ghi nhận. Khoảng PM2.5 33,6–92,5 µg/m³ được tính từ 10 số đo dùng để đối chiếu dự báo trước 30 phút trong `so-sanh-du-lieu-thuc.json`.

- Bảng mô phỏng ở trang 14 dùng mục `test` của `ai-service/models/chronos-lora/manifest.json`. Hai phiên bản được chấm trên cùng 120 cửa sổ kiểm tra. Tỷ lệ giảm sai số được tính trên số liệu trước khi làm tròn.
