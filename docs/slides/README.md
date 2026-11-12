# Air Quality Supervision System — Slide báo cáo và bộ học kỹ thuật

- [Bài báo cáo chính](deck.html): 13 trang, tương ứng [bài nói khoảng 12 phút](../bai-thuyet-trinh-cuoi-ky.md).
- [Bộ slide học cách hoạt động](hoat-dong-chi-tiet.html): 26 trang với sơ đồ, code thật và câu hỏi tự trả lời. Mở HTML bằng trình duyệt; bấm **Giải thích và đáp án** dưới mỗi trang để đọc thêm. Ctrl+P chỉ in các slide, ẩn phần đáp án.
- [Bản kiến thức vấn đáp](../kien-thuc-van-dap.md): cùng nội dung, có đáp án, giải thích thêm và liên kết file nguồn.

## Cách học

Đọc một trang, che đáp án và giải thích đường đi của dữ liệu bằng lời. Sau đó mở đoạn code tương ứng để xác định điều kiện, biến trạng thái và nơi ghi dữ liệu. Cuối cùng tự trả lời câu hỏi ở cuối trang, rồi đối chiếu đáp án.

Ưu tiên các trang 4–7 về firmware, 10–18 về backend và điều khiển, 20–23 về các trường hợp lỗi. Phần hạn chế ghi theo source hiện tại; cần tái hiện bằng chạy thử trước khi gọi đó là lỗi đã được xác nhận trên phần cứng.

## Dựng lại

Chạy từ thư mục gốc repository:

```bash
python3 docs/slides/build.py
python3 docs/slides/build-study.py
```

`build-study.py` trích đoạn trực tiếp theo marker, tính số dòng và tạo HTML cùng Markdown. Nếu đổi tên hàm hoặc sửa phần code làm marker không còn tồn tại, script báo lỗi để cập nhật phần đối chiếu. Nội dung giải thích cần được rà lại sau khi sửa nghiệp vụ; dựng lại chỉ cập nhật trích đoạn, không tự đánh giá tính đúng của lời giải.

Sơ đồ là SVG trong HTML, font nằm trong thư mục `fonts/`; không cần tải thư viện sơ đồ từ mạng. Identifier như topic MQTT và tên file được giữ nguyên để đối chiếu source; tên dự án trên tài liệu dùng đầy đủ.
