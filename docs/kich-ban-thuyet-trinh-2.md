# Kịch bản thuyết trình – Người 2

**Trang 9–16 · Khoảng 6 phút.** Dùng [bộ slide báo cáo 16 trang](slides/Air-Quality-Supervision-System-bao-cao.pdf). Các mốc tính từ lúc mình nhận lượt. Chữ in nghiêng là ghi chú thao tác.

## Trang 9 – Cơ chế kích hoạt quạt lọc | 0:00–1:10

Tiếp theo, em xin trình bày cách hệ thống quyết định thời điểm bật quạt và cách nhóm em kiểm tra chất lượng dự báo.

Trong chế độ tự động, máy chủ so sánh số đo PM2.5 mới nhất với ngưỡng của phòng. Nhóm em lấy 15 microgam trên mét khối làm mốc tham chiếu. Đây là mức hướng dẫn trung bình 24 giờ của WHO năm 2021. Từ mốc đó, nhóm em xây dựng công thức điều khiển, có tính tới số giờ sử dụng phòng, thể tích, mức thông gió và CO₂. Kết quả được giới hạn từ 10 đến 60 microgam trên mét khối. Các hệ số trong công thức là lựa chọn thiết kế của nhóm.

Nếu bụi đạt hoặc vượt ngưỡng, máy chủ bật quạt ở mức ba. Để quạt không bật tắt liên tục quanh ngưỡng, hệ thống đợi quạt chạy ít nhất năm phút rồi mới tắt khi bụi giảm xuống dưới 70% ngưỡng. Quạt cũng có giới hạn chạy liên tục 120 phút. CO₂ chỉ góp phần tính ngưỡng. Quyết định điều khiển vẫn dựa trên số đo bụi.

## Trang 10 – Biểu đồ thực đo và dự báo | 1:10–1:50

*Chỉ đường xanh bên trái, đường vàng bên phải và đường đỏ.*

Đây là biểu đồ trên trang web. Đường xanh là PM2.5 đã đo trong 30 phút gần nhất. Đường vàng nét đứt ở bên phải là dự báo chính cho 30 phút tiếp theo. Hai đường vàng mờ thể hiện khoảng mô hình ước tính để người xem biết dự báo có thể dao động. Đường đỏ là ngưỡng điều khiển quạt.

Khi máy chủ nhận được mẫu đo mới, biểu đồ cập nhật phần thực đo. Phần dự báo cập nhật khi dịch vụ dự báo trả kết quả mới. Quạt tiếp tục hoạt động theo quy tắc ở trang trước.

## Trang 11 – Chronos-2 dự báo bụi | 1:50–2:30

Chronos-2 nhận tối đa 60 phút lịch sử PM2.5. Khi có dữ liệu, mô hình còn dùng thêm CO₂, nhiệt độ, độ ẩm và mức quạt đã ghi nhận.

Mô hình chia chuỗi số liệu thành những đoạn ngắn để xem mỗi chỉ số thay đổi theo thời gian ra sao và các chỉ số thay đổi cùng nhau như thế nào. Từ đó, mô hình tạo ra 360 giá trị dự báo, mỗi giá trị cách nhau năm giây.

Ví dụ, nếu mốc đo mới nhất là tám giờ, dự báo bắt đầu từ tám giờ năm giây và kết thúc lúc tám giờ ba mươi. Đây là 30 phút phía bên phải biểu đồ vừa rồi.

## Trang 12 – Dữ liệu khi hệ thống vận hành | 2:30–3:15

Khi bắt đầu, dịch vụ dự báo lấy 90 phút số đo và trạng thái quạt đã lưu. Sau đó, dịch vụ chỉ lấy thêm bản ghi mới. Trong phần lịch sử đó, mô hình dùng tối đa 60 phút gần nhất.

Trước khi dự báo, hệ thống bỏ các số đo bụi bị lỗi, bão hòa hoặc ngoài phạm vi rồi xếp dữ liệu vào các mốc cách nhau năm giây. Nếu một mốc bị thiếu, hệ thống giữ nguyên chỗ thiếu.

Với lịch sử đủ 60 phút, ít nhất 80% số mốc phải có dữ liệu hợp lệ. Hệ thống có thể bắt đầu từ 15 phút dữ liệu hợp lệ nếu độ phủ đạt 90%. Nếu số đo mới nhất đã quá 15 giây hoặc có khoảng mất dữ liệu quá năm phút, hệ thống chờ thêm số đo.

## Trang 13 – Thử nghiệm huấn luyện bổ sung | 3:15–3:55

*Chỉ hình tệp dữ liệu, không đọc từng dòng.*

Phần vừa rồi là dữ liệu cảm biến dùng để dự báo lúc vận hành. Ở trang này, nhóm em trình bày một thử nghiệm riêng. Nhóm tạo các chuỗi mô phỏng có những tình huống như bụi tăng, thông gió thay đổi hoặc quạt bật tắt. Mỗi dòng trong hình tương ứng một thời điểm, cách dòng sau năm giây.

Nhóm chia các phiên thành phần dùng để huấn luyện, phần dùng để chọn phiên bản và phần dùng để kiểm tra. Sau đó, nhóm so sánh phiên bản đã huấn luyện bổ sung với Chronos-2 gốc. Hiện hệ thống vẫn sử dụng Chronos-2 gốc để dự báo.

## Trang 14 – Sai số dự báo PM2.5 | 3:55–5:10

*Chỉ điều kiện đo trước, rồi chỉ hai nhóm cột trong bảng. Tập trung vào mốc 30 phút.*

Nhóm em đo trong phòng ngủ 10 mét khối, ít thông gió, có thời gian sử dụng khai báo là 12 giờ mỗi ngày. Trong phòng có hút thuốc. Ở các mốc dùng để đối chiếu dự báo 30 phút, nồng độ bụi đo được nằm trong khoảng 33,6 đến 92,5 microgam trên mét khối.

Cột bên trái là kết quả khi chạy lại hai phiên bản trên cùng 10 mốc số đo của phòng. Tại mốc 30 phút, sai số tuyệt đối trung bình của bản gốc là 25,20, còn bản huấn luyện bổ sung là 25,06 microgam trên mét khối. Ở mốc 5 và 15 phút, hai bản cũng gần nhau.

Cột bên phải là kết quả trên 120 mốc kiểm tra mô phỏng. Trên bộ này, sai số giảm 2,6% ở 15 phút và 3,7% ở 30 phút. Mức giảm đó chưa thể hiện rõ trên số đo phòng ngủ. Khi demo trên lớp, nguồn bụi, số người và cách thông gió có thể khác, nên dự báo có thể lệch so với số đo lúc đó.

## Trang 15 – Kết luận | 5:10–5:50

Qua đồ án này, nhóm em đã lắp được thiết bị đo không khí, hiển thị được số đo và trạng thái quạt trên trang web, đồng thời điều khiển lọc tự động theo ngưỡng của phòng. Hệ thống cũng dự báo PM2.5 trước 30 phút và đã có kết quả đối chiếu với số đo phòng ngủ.

Bước tiếp theo là đo trong nhiều ngày, ở nhiều phòng và ghi lại thời điểm mở cửa, thay đổi số người hoặc xuất hiện nguồn bụi. Khi có thêm số đo, nhóm em có thể kiểm tra cụ thể hơn dự báo thường lệch trong điều kiện nào.

## Trang 16 – Cảm ơn | 5:50–6:00

Phần trình bày của nhóm em đến đây là hết. Nhóm em xin cảm ơn thầy cô và các bạn đã lắng nghe. Nhóm em xin mời thầy cô đặt câu hỏi.
