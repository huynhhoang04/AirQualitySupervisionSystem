# Kịch bản thuyết trình – Người 1

**Trang 1–8 · Khoảng 6 phút.** Dùng [bộ slide báo cáo 16 trang](slides/Air-Quality-Supervision-System-bao-cao.pdf). Các mốc tính từ lúc mình bắt đầu nói. Chữ in nghiêng là ghi chú thao tác.

## Trang 1 – Giới thiệu | 0:00–0:20

Em xin chào thầy cô và các bạn. Nhóm em xin trình bày đề tài **Hệ thống giám sát chất lượng không khí**, tên tiếng Anh là **Air Quality Supervision System**.

Em sẽ trình bày phần thiết bị và cách hệ thống thu thập số đo. Sau đó, bạn em sẽ trình bày cơ chế điều khiển quạt, dự báo bụi và kết quả thử nghiệm.

## Trang 2 – Tổng quan hệ thống | 0:20–1:00

Hệ thống làm bốn việc. Đầu tiên, thiết bị đặt trong phòng đo bụi PM2.5, khí CO₂, nhiệt độ và độ ẩm. Thiết bị gửi số liệu về máy chủ mỗi năm giây.

Thứ hai, máy chủ dùng thông tin của phòng để tính ngưỡng bật quạt lọc. Khi số đo bụi đạt ngưỡng, máy chủ gửi lệnh về thiết bị để chạy quạt. Người dùng có thể mở trang web để xem số đo mới, trạng thái quạt và lịch sử của phòng.

Cuối cùng, từ dữ liệu đã thu được, hệ thống dự báo bụi trong 30 phút tiếp theo. Đến khi có số đo mới tại thời điểm cần đối chiếu, nhóm em dùng số đo đó để tính sai số dự báo.

## Trang 3 – Bộ điều khiển ESP32-S3 | 1:00–1:40

ESP32-S3 là bộ điều khiển đặt tại phòng. Nhóm em chọn bo DevKitC-1 vì có Wi-Fi và đủ chân để nối cảm biến, màn hình và quạt.

Thiết bị có hai lõi xử lý. Chương trình dùng FreeRTOS để tách việc đọc cảm biến, cập nhật màn hình, điều khiển quạt và giao tiếp mạng thành các tác vụ riêng. Nhờ vậy, việc đọc cảm biến vẫn có thể tiếp tục khi tác vụ mạng đang xử lý kết nối.

ESP32-S3 vừa gửi số đo lên, vừa nhận lệnh từ máy chủ. Sau khi thực hiện lệnh, thiết bị gửi lại trạng thái quạt để giao diện hiển thị.

## Trang 4 – Cảm biến bụi Sharp | 1:40–2:15

Để lấy số đo bụi, nhóm em dùng cảm biến quang học Sharp GP2Y1023AU0F. Đầu ra của nó là xung số. ESP32-S3 nhận xung ở chân GPIO 18 và đo độ rộng xung để tính nồng độ bụi.

Số đo PM2.5 được dùng ở ba chỗ. Trang web dùng để vẽ biểu đồ, máy chủ dùng để xét bật hoặc tắt quạt, và mô hình dùng chuỗi số đo này để dự báo. Cách đổi từ độ rộng xung sang nồng độ sẽ được trình bày ở trang 7.

## Trang 5 – Cảm biến SCD40 | 2:15–2:55

Cảm biến thứ hai là SCD40. Nó đo ba chỉ số là CO₂, nhiệt độ và độ ẩm. SCD40 nối với bộ điều khiển qua đường I hai C, có địa chỉ 0x62 và cho một bộ số đo mới khoảng mỗi năm giây.

Ba chỉ số này giúp người dùng theo dõi điều kiện trong phòng. Riêng CO₂ còn được máy chủ dùng để điều chỉnh ngưỡng bật quạt theo tình trạng phòng. Màng lọc dùng để giữ hạt bụi, nên hệ thống điều khiển quạt dựa trên PM2.5. Phần điều khiển sẽ giải thích rõ sự khác nhau giữa hai vai trò này.

## Trang 6 – Sơ đồ kết nối thiết bị | 2:55–3:55

*Chỉ lần lượt đường nguồn, đường cảm biến và đường điều khiển quạt.*

Trên sơ đồ, nguồn 12 vôn cấp cho quạt. Mạch hạ áp cấp nguồn cho phần điều khiển. Quạt giữ nguồn 12 vôn, còn ESP32-S3 thay đổi tốc độ qua chân điều khiển PWM riêng với tần số 25 kilohéc. Màng HEPA H13 nằm trên đường khí ra để giữ hạt bụi.

Ở phần cảm biến, SCD40 và màn hình OLED dùng chung đường I hai C. OLED dùng để xem số đo ngay tại thiết bị. Cảm biến bụi kết nối bằng đường xung riêng về GPIO 18.

Có một chi tiết trên hình là sơ đồ cũ ghi GP2Y1010. Cảm biến dùng thực tế trong đề tài là GP2Y1023AU0F, nên phần mềm đọc độ rộng xung số như em sẽ trình bày ở trang sau.

## Trang 7 – Cách tính các thông số | 3:55–4:55

*Chỉ đoạn xung ở mức thấp rồi chỉ công thức bên phải.*

Khi xung chuyển từ mức cao xuống thấp, ESP32-S3 ghi lại thời điểm bắt đầu. Khi xung lên cao trở lại, thiết bị lấy thời điểm sau trừ thời điểm trước để ra độ rộng xung W.

Trong mỗi giây, chương trình lấy trung bình các xung hợp lệ, trừ đi độ rộng mốc khi không có bụi, rồi chia cho 14. Mốc mặc định là 1.400 microgiây. Chẳng hạn, nếu độ rộng trung bình là 1.680 microgiây thì hiệu là 280, chia cho 14 ra 20 microgam trên mét khối. Thiết bị giới hạn kết quả trong khoảng từ 0 đến 500 và đánh dấu khi cảm biến lỗi hoặc bão hòa.

SCD40 trả về trực tiếp CO₂, nhiệt độ và độ ẩm. Chương trình bỏ qua lượt đọc có giá trị ngoài phạm vi, sau đó lấy trung bình tối đa năm mẫu hợp lệ gần nhất cho mỗi chỉ số.

## Trang 8 – Từ cảm biến đến giao diện | 4:55–6:00

*Chỉ bốn bước trên hàng đầu, rồi chỉ hai nhánh ở hàng dưới.*

Cứ năm giây, ESP32-S3 gom số đo, mã thiết bị, thời gian đo và trạng thái cảm biến vào một gói dữ liệu. Thiết bị gửi gói đó qua MQTT. Máy chủ đăng ký nhận số đo qua MQTT, kiểm tra nội dung, bỏ qua bản tin bị gửi trùng và lưu bản ghi hợp lệ vào cơ sở dữ liệu.

Từ đây, số đo được dùng cho hai việc. Máy chủ kiểm tra xem có cần gửi lệnh cho quạt hay không. Khi số đo đã được lưu, máy chủ đẩy dữ liệu mới tới trang web để biểu đồ cập nhật mà người dùng không cần tải lại trang.

Dịch vụ dự báo lấy lịch sử từ máy chủ, tính dự báo cho 30 phút tiếp theo rồi gửi kết quả về để hiển thị cùng số đo thực tế.

**Chuyển lượt:** Trên đây là cách nhóm em lấy số đo và đưa lên hệ thống. Tiếp theo, em xin mời bạn **[tên người 2]** trình bày cách máy chủ dùng những số đo đó để điều khiển quạt và dự báo bụi.
