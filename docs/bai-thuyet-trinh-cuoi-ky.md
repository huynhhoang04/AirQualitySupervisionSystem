# Bài thuyết trình cuối kỳ — Air Quality Supervision System

**Thời lượng dự kiến:** khoảng 12 phút, chưa tính hỏi đáp.  
**Slide:** [docs/slides/deck.html](slides/deck.html), 13 trang.  
**Cách dùng:** các đoạn dưới mỗi slide là lời nói; dòng *Thao tác* là ghi chú khi trình bày. Bản này dùng ngôi “em”, tập trung vào mã nguồn đang triển khai. Không cần đọc tên file, tên hàm hay toàn bộ bảng thông số thành tiếng.

## Phân bổ thời gian

| Slide | Nội dung | Thời lượng | Mốc chuyển slide |
| :---: | :--- | :---: | :---: |
| 1 | Giới thiệu | 25 giây | 00:25 |
| 2 | Kiến trúc ba lớp | 45 giây | 01:10 |
| 3 | Kết nối phần cứng | 50 giây | 02:00 |
| 4 | Thu thập và xử lý cảm biến | 85 giây | 03:25 |
| 5 | FreeRTOS và đồng bộ dữ liệu | 65 giây | 04:30 |
| 6 | Giao tiếp MQTT | 60 giây | 05:30 |
| 7 | Backend và PostgreSQL | 60 giây | 06:30 |
| 8 | Tính và lưu ngưỡng | 85 giây | 07:55 |
| 9 | Vòng điều khiển tự động | 75 giây | 09:10 |
| 10 | PWM và bộ đếm giờ lọc | 55 giây | 10:05 |
| 11 | Ghép nối và phục hồi kết nối | 55 giây | 11:00 |
| 12 | Tích hợp frontend | 45 giây | 11:45 |
| 13 | Cảm ơn | 15 giây | 12:00 |

## Slide 1 — Giới thiệu

*Thao tác: mở trang bìa; chưa chuyển sang giao diện demo.*

Em xin chào thầy cô. Em xin trình bày báo cáo cuối kỳ về Air Quality Supervision System, hệ thống giám sát và điều khiển chất lượng không khí trong nhà.

Hệ thống gồm node ESP32-S3, backend Spring Boot và dashboard React. Trong phần trình bày này, em tập trung vào cách thu thập cảm biến, tổ chức firmware đa tác vụ, truyền dữ liệu và thực hiện vòng điều khiển quạt lọc theo ngưỡng của từng phòng.

## Slide 2 — Thiết bị, backend và dashboard

*Thao tác: chỉ lần lượt ba khối, sau đó chỉ chiều truyền dữ liệu và chiều gửi lệnh.*

Kiến trúc được chia thành ba lớp với trách nhiệm cụ thể.

Ở lớp thiết bị, ESP32-S3 đọc cảm biến, hiển thị OLED và thực hiện lệnh điều khiển quạt. Thiết bị gửi số đo cùng trạng thái vận hành lên backend qua MQTT.

Ở lớp backend, Spring Boot kiểm tra và lưu dữ liệu, tính ngưỡng phòng, sau đó quyết định bật hoặc tắt quạt. PostgreSQL lưu số đo, cấu hình phòng, trạng thái thiết bị và nhật ký.

Ở lớp giao diện, React gọi REST API để lấy dữ liệu và gửi thao tác của người dùng. Khi có lệnh điều khiển, backend chuyển lệnh qua MQTT; node áp dụng PWM rồi gửi trạng thái trở lại. Như vậy, hệ thống có cả đường truyền số đo lên và đường phản hồi trạng thái sau khi chấp hành.

## Slide 3 — Sơ đồ kết nối thiết bị

*Thao tác: chỉ đường nguồn trước, sau đó chỉ ESP32-S3, cảm biến và màn hình trên sơ đồ.*

Về phần cứng, bộ điều khiển là ESP32-S3 bản N16R8, có 16 megabyte Flash và 8 megabyte PSRAM.

Nguồn 12 volt cấp liên tục cho quạt bốn dây. Mạch LM2596 hạ áp xuống 5 volt cho nhánh điều khiển. Tín hiệu điều tốc đi riêng vào chân PWM của quạt, vì vậy thay đổi tốc độ không thực hiện bằng cách đóng cắt đường nguồn 12 volt.

Cảm biến SCD40 và màn hình SSD1306 dùng chung bus I²C, với SDA ở GPIO 8, SCL ở GPIO 9, tốc độ 400 kilohertz.

Cấu hình firmware hiện dùng GP2Y1023 cho bụi mịn. Tín hiệu PWM của cảm biến đi qua cầu chia áp 1 kiloohm và 2 kiloohm trước khi vào GPIO 18. Sơ đồ Fritzing hiện còn nhãn GP2Y1010; phần thuật toán em trình bày tiếp theo là phần đang viết cho GP2Y1023 trong firmware.

## Slide 4 — Đo xung PM2.5 và lọc dữ liệu SCD40

*Thao tác: chỉ nhánh GP2Y1023, công thức quy đổi, rồi chuyển sang nhánh SCD40. Đọc chậm các con số 1400 và 14.*

Hai cảm biến có cách thu thập khác nhau.

Với GP2Y1023, đầu ra là xung PWM kỹ thuật số. Firmware gắn ngắt theo cả hai cạnh tại GPIO 18. Khi xuất hiện cạnh xuống, ISR ghi thời điểm bắt đầu xung; khi có cạnh lên, lấy hiệu hai thời điểm để có độ rộng xung mức thấp. Thời gian được lấy bằng hàm esp timer get time, có đơn vị micro giây.

Để giảm nhiễu cạnh trên đường tín hiệu, chương trình không nhận một cạnh xuống mới nếu nó cách khung trước dưới 8 mili giây. Task bụi tổng hợp các độ rộng xung nhận được trong cửa sổ một giây rồi lấy trung bình.

Nồng độ bụi được tính bằng độ rộng trung bình trừ baseline, sau đó chia cho hệ số 14. Baseline mặc định là 1400 micro giây, có thể hiệu chỉnh và lưu NVS. Kết quả được giới hạn trong khoảng 0 đến 500 microgam trên mét khối. Nếu quá ba giây không có xung hợp lệ, firmware đặt cờ lỗi cảm biến.

Để minh họa phép đổi đơn vị, nếu độ rộng trung bình là 1680 micro giây và baseline là 1400, chênh lệch 280 chia cho 14 sẽ ra 20 microgam trên mét khối. Đây chỉ là ví dụ tính toán để giải thích thuật toán, không phải kết quả đo kiểm chuẩn cảm biến.

Với SCD40, cảm biến chạy chế độ đo định kỳ năm giây. Task kiểm tra cờ sẵn sàng mỗi giây, chỉ đọc khi có mẫu mới. Các số đo hợp lệ được đưa vào vòng đệm tối đa năm mẫu để tính trung bình CO₂, nhiệt độ và độ ẩm. Sau năm lỗi giao tiếp liên tiếp, chương trình khởi động lại chu kỳ đo.

## Slide 5 — Lập lịch tác vụ và đồng bộ dữ liệu

*Thao tác: chỉ cột Core và ưu tiên; không đọc lần lượt mọi giá trị stack.*

Firmware được tổ chức thành năm task FreeRTOS và ghim cố định vào hai lõi.

Core 0 chạy task mạng, xử lý Wi-Fi, MQTT, đồng bộ thời gian và nhận lệnh. Core 1 chạy bốn task còn lại: SCD40, cảm biến bụi, điều khiển quạt và màn hình OLED. Task bụi và quạt có mức ưu tiên ba; task SCD40 có mức hai; task OLED có mức một.

Các task cần chia sẻ số đo và trạng thái quạt, nên chương trình sử dụng mutex để bảo vệ cấu trúc dữ liệu chung. Khi task mạng cần gửi telemetry, nó lấy một bản sao trạng thái qua hàm snapshot, nhả khóa rồi mới đóng gói JSON. Cách này giữ phần thực thi trong vùng khóa ngắn và không giữ khóa trong lúc giao tiếp mạng.

Việc tách task cũng giúp xác định rõ nơi ghi và nơi đọc dữ liệu: task cảm biến cập nhật số đo, task quạt cập nhật trạng thái chấp hành, còn task mạng và OLED chủ yếu lấy snapshot. Khi xử lý lỗi, có thể kiểm tra từng luồng qua log thay vì truy tìm trong một vòng lặp lớn.

Bus I²C được khởi tạo một lần trong hàm setup, sau đó truyền cho các thành phần sử dụng. Ngoài ra, từng task đăng ký với watchdog và nạp lại bộ đếm trong vòng lặp. Timeout watchdog hiện cấu hình là 30 giây.

## Slide 6 — Topic, payload và cơ chế kết nối

*Thao tác: chỉ bốn topic và ví dụ JSON. Chỉ giải thích ý nghĩa trường, không đọc dấu ngoặc.*

MQTT sử dụng cấu trúc topic theo mã thiết bị để tách dữ liệu giữa các node.

Topic telemetry gửi số đo mỗi năm giây. Topic status mang trạng thái online hoặc offline. Topic actuator state phản hồi trạng thái quạt, mức điều khiển, duty và giờ chạy bộ lọc. Topic command dùng cho lệnh từ backend xuống node. Các bản tin này sử dụng QoS một; trạng thái kết nối được giữ lại bằng retained message và có Last Will để broker thông báo khi node mất kết nối bất thường.

Firmware dùng ArduinoMqttClient với kết nối TLS tại cổng 8883. Backend dùng Paho và quản lý một kết nối trong MqttLink.

Payload lệnh gồm hai phần: command xác định thao tác, params chứa tham số. Ví dụ trên slide yêu cầu bật quạt mức ba trong chế độ AUTO. Firmware kiểm tra JSON và trường command trước khi xử lý.

Bản tin telemetry chứa định danh thiết bị, mã phòng, mã ghép nối và thời gian. Số đo nằm trong nhóm sensors; trạng thái hệ thống như RSSI, uptime, bộ nhớ trống và cờ lỗi nằm trong nhóm system. Việc tách nhóm này giúp backend phân biệt dữ liệu môi trường với thông tin chẩn đoán node.

Telemetry được đóng gói bằng ArduinoJson trong bộ đệm 512 byte. Timestamp dùng thời gian Unix đồng bộ qua NTP; thiết bị chờ thời gian hợp lệ trước khi gửi telemetry.

## Slide 7 — Từ MQTT callback đến PostgreSQL

*Thao tác: lần theo pipeline phía trên, sau đó chỉ bảng lưu trữ.*

Ở backend, callback MQTT chuyển JSON thành Spring event. TelemetryService nhận event và xử lý trong transaction.

Bước đầu tiên là kiểm tra mã thiết bị cùng các số đo. Ví dụ, backend nhận PM2.5 trong khoảng 0 đến 500, CO₂ trong khoảng 400 đến 5000. Một trường null được hiểu là chưa có mẫu và vẫn được lưu thành SQL NULL. Nếu một giá trị có mặt nhưng nằm ngoài dải kiểm tra, bản tin đó bị loại.

Sau khi hợp lệ, backend đăng ký node nếu chưa tồn tại, cập nhật mã ghép nối và thời điểm nhận dữ liệu. Tiếp theo là lưu bản ghi telemetry rồi gọi vòng điều khiển để đánh giá khả năng bật quạt ngay từ mẫu vừa nhận.

Nếu timestamp từ thiết bị chưa hợp lệ, backend dùng thời gian tiếp nhận. Khi trả dữ liệu lịch sử, API nhận khoảng thời gian bắt đầu và kết thúc, mặc định là 24 giờ; chuỗi dài được giảm mẫu để hạn chế dung lượng JSON và số điểm mà trình duyệt phải vẽ.

Dữ liệu được tổ chức trong bốn bảng: spaces lưu hồ sơ và ngưỡng phòng; devices lưu liên kết cùng trạng thái; telemetry lưu chuỗi số đo; audit log lưu các sự kiện và quyết định. Index theo mã thiết bị và thời gian hỗ trợ lấy mẫu mới nhất và lịch sử. Schema được quản lý bằng Flyway, còn Hibernate chỉ kiểm tra tính tương thích khi khởi động.

## Slide 8 — Tính và lưu ngưỡng theo hồ sơ phòng

*Thao tác: chỉ từng thành phần công thức; dừng ngắn trước phần “thời điểm tính lại”.*

Ngưỡng PM2.5 được tính trong ThresholdService bằng công thức trên slide.

Công thức bắt đầu từ giá trị tham chiếu 15, nhân với căn bậc hai của 24 chia thời gian sử dụng phòng, rồi nhân các hệ số thể tích, thông gió và CO₂.

Trong code hiện tại, thời gian sử dụng được suy ra từ loại phòng: phòng ngủ là tám giờ, lớp học sáu giờ, phòng khách bốn giờ và bếp hai giờ. Hệ số thể tích là căn bậc ba của thể tích phòng chia cho 20, được giới hạn từ 0,7 đến 1,6. Nếu chưa có thể tích thì dùng hệ số một.

Hệ số thông gió có ba mức: thấp là 0,75, trung bình là một và cao là 1,35. Hệ số CO₂ giảm từ một xuống 0,75 khi CO₂ tăng từ 420 đến 1220 ppm. Sau khi nhân các thành phần, kết quả cuối cùng được giới hạn trong 10 đến 60 microgam trên mét khối.

Ví dụ, phòng ngủ có thời gian sử dụng tám giờ, thể tích 20 mét khối, thông gió trung bình và chưa có CO₂ sẽ có các hệ số thể tích, thông gió, CO₂ đều bằng một. Kết quả là 15 nhân căn ba, xấp xỉ 26. Ví dụ này cho thấy có thể kiểm tra lại từng thành phần thay vì chỉ nhận một con số đầu ra.

Đây là công thức cấu hình của dự án; các hệ số không phải kết quả học từ dữ liệu vận hành.

Về thời điểm áp dụng, API preview trả về ngưỡng và từng hệ số để xem trước. Khi người dùng bấm áp dụng, backend mới lưu kết quả và đánh dấu nguồn là profile. Nếu tự chọn ngưỡng trên giao diện, nguồn được đánh dấu manual. Vòng điều khiển luôn dùng giá trị đã lưu, không tự tính lại ngưỡng sau mỗi bản tin CO₂.

## Slide 9 — Quy tắc bật và tắt quạt trong ControlLoop

*Thao tác: chỉ từng hàng của bảng điều khiển. Nhấn mạnh sự khác nhau giữa thời điểm bật và thời điểm tắt.*

Vòng điều khiển tự động chỉ xử lý thiết bị đã ghép phòng, đang ở AUTO và có số đo còn hiệu lực.

Khi quạt đang tắt và PM2.5 lớn hơn hoặc bằng ngưỡng phòng, backend gửi lệnh bật mức ba và ghi lý do threshold breach. Điều kiện này được đánh giá ngay khi telemetry mới đến, nên không phải chờ timer định kỳ.

Sau khi quạt đã chạy, có hai điều kiện tắt. Thứ nhất, tổng thời gian bật đạt 120 phút. Thứ hai, PM2.5 xuống dưới 70 phần trăm ngưỡng và quạt đã chạy ít nhất năm phút. Hai lý do tương ứng được lưu là max on và hysteresis off.

Việc dùng ngưỡng tắt thấp hơn ngưỡng bật tạo khoảng hysteresis, giúp hạn chế bật tắt liên tục khi số đo dao động sát ngưỡng. Chẳng hạn, với ngưỡng bật 30 thì ngưỡng tắt là dưới 21, kèm điều kiện thời gian chạy tối thiểu.

Khi gửi một quyết định, backend cập nhật lý do cùng thời điểm để giao diện có thể giải thích trạng thái quạt. Cần phân biệt lần ra quyết định này với bản tin gia hạn: gia hạn chỉ duy trì lệnh AUTO đang có, không biểu thị một lần vượt ngưỡng mới.

Timer 60 giây kiểm tra điều kiện tắt và các trường hợp chưa được xử lý bởi bản tin. Số đo cũ hơn năm phút bị bỏ qua. Khi người dùng chuyển sang MANUAL, vòng tự động không ghi đè mức quạt do người dùng chọn. CO₂ chỉ tham gia tính ngưỡng, không trực tiếp kích hoạt quạt lọc.

## Slide 10 — Điều khiển quạt bằng PWM và đếm giờ lọc

*Thao tác: chỉ bảng mức quạt rồi chỉ chuỗi áp lệnh.*

Ở phía node, quạt được điều khiển bằng ngoại vi LEDC trên GPIO 5, tần số 25 kilohertz và độ phân giải tám bit.

Bốn mức quạt tương ứng duty danh định 0, 35, 60 và 90 phần trăm. Các giá trị ghi vào LEDC là 0, 89, 153 và 230 trên thang 255.

Task quạt lấy snapshot lệnh, chuẩn hóa trạng thái và mức từ không đến ba. Khi chuyển từ tắt sang bật, chương trình phát xung khởi động duty 100 phần trăm trong 250 mili giây, sau đó áp mức yêu cầu.

Task chạy theo chu kỳ 50 mili giây, đồng thời đọc nút nhấn và tích lũy thời gian quạt hoạt động. Runtime được lưu NVS mỗi năm phút và gửi về backend qua bản tin trạng thái. Nút nhấn ngắn chuyển lần lượt các mức MANUAL; giữ nút đưa thiết bị về AUTO.

## Slide 11 — Ghép nối, NVS và xử lý mất kết nối

*Thao tác: chỉ bảng đồng bộ backend–node, sau đó chỉ phần bộ đệm và gia hạn lệnh.*

Liên kết thiết bị với phòng được quản lý tại backend. Khi nhận telemetry lần đầu, backend tạo một thiết bị chưa ghép và ghi nhận mã pairing.

Khi người dùng ghép nối, backend cập nhật phòng, đặt thời điểm ghép và gửi set space về node. Node lưu bản sao vào NVS. Khi hủy liên kết, backend gửi mã phòng rỗng để node xóa bản sao đó. Telemetry mang mã phòng cũ không được dùng để tự ghép lại thiết bị.

NVS lưu mã phòng, trạng thái ghép nối, baseline bụi, chu kỳ telemetry và cấu hình mạng. Việc lưu các giá trị này cho phép node khôi phục cấu hình sau mất nguồn, trong khi backend vẫn là nơi quyết định thiết bị đang thuộc phòng nào.

Nếu mất kết nối MQTT, node có vòng đệm RAM 60 mẫu, tương đương năm phút ở chu kỳ mặc định. Mẫu giữ timestamp gốc và được gửi bù khi kết nối lại. Nếu đầy thì ghi đè mẫu cũ nhất; bộ đệm này không tồn tại qua khởi động lại.

Đối với quạt AUTO đang chạy, backend gia hạn lệnh mỗi năm phút. Nếu quá mười phút không nhận lệnh, firmware tắt quạt. Đây là cơ chế giới hạn thời gian vận hành khi liên lạc với backend bị gián đoạn.

## Slide 12 — Tích hợp REST và quản lý trạng thái React

*Thao tác: chỉ phần điều khiển trên ảnh, sau đó chỉ các nhịp polling. Ảnh là bản lưu giao diện, không giới thiệu như một phép đo đang chạy trực tiếp.*

Frontend dùng React và TanStack Query để quản lý dữ liệu từ REST API.

Danh sách thiết bị và số đo mới nhất được tải lại mỗi năm giây. Lịch sử cùng thông tin phòng được cập nhật mỗi 60 giây. Các truy vấn theo thiết bị chỉ được kích hoạt khi đã có mã thiết bị.

Khi gửi lệnh quạt thành công, frontend đánh dấu các cache liên quan cần tải lại, gồm thiết bị, mẫu mới nhất và lịch sử. Dữ liệu lịch sử được vẽ bằng SVG với đường PM2.5, đường ngưỡng và tooltip theo thời gian.

Giao diện cũng xử lý riêng trường hợp chưa có mẫu, dữ liệu đã cũ và thiết bị mất kết nối. Toàn bộ luồng từ thu thập, truyền dữ liệu, lưu trữ đến điều khiển và hiển thị được kết nối qua các thành phần vừa trình bày.

## Slide 13 — Cảm ơn

*Thao tác: chuyển sang trang cảm ơn, dừng nhìn thầy cô trước khi kết thúc.*

Phần trình bày của em đến đây là kết thúc. Em cảm ơn thầy cô đã lắng nghe. Em xin tiếp nhận câu hỏi và góp ý về phần thiết kế phần cứng, xử lý dữ liệu và điều khiển của hệ thống.

---

## Ghi chú khi tập nói — không đọc trên sân khấu

Tài liệu học sâu: [26 slide cách hoạt động](slides/hoat-dong-chi-tiet.html) và [kiến thức vấn đáp kèm code](kien-thuc-van-dap.md).

- Dùng bảng thời gian để tập một lượt có bấm giờ. Thời lượng thực tế phụ thuộc nhịp nói và thời gian chỉ sơ đồ; ưu tiên giữ mốc slide 4, 8 và 9 vì đây là ba phần giải thích kỹ thuật chính.
- Nếu chậm giờ, rút phần đọc danh sách công nghệ ở slide 2 và các thông số lặp lại ở slide 6, 10. Giữ phần cách đo PWM, mutex, thời điểm lưu ngưỡng và điều kiện bật/tắt.
- Ví dụ ngưỡng 30 và ngưỡng tắt 21 ở slide 9 là ví dụ tính toán, không phải số đo đánh giá hiệu quả lọc. Không suy ra độ chính xác cảm biến hoặc tốc độ làm sạch từ ảnh giao diện.
- Ảnh Fritzing còn nhãn cảm biến khác với cấu hình firmware. Nếu được hỏi, nêu rõ sơ đồ cần đồng bộ; không trình bày sơ đồ này như bản đấu nối GP2Y1023 đã được xác nhận đầy đủ.
- Không dùng các mô tả cũ về dự báo 24 giờ, dịch vụ AI, hoặc chờ ba phút trước khi bật AUTO. Luồng bật hiện tại được gọi ngay từ telemetry; thời gian năm phút tối thiểu áp dụng cho điều kiện tắt theo hysteresis.

## Nguồn đối chiếu kỹ thuật

| Phần trình bày | Mã nguồn đang dùng |
| :--- | :--- |
| Thu thập cảm biến | [dust_sensor.cpp](../embedded/src/dust_sensor.cpp), [scd40_sensor.cpp](../embedded/src/scd40_sensor.cpp) |
| Tác vụ và mutex | [main.cpp](../embedded/src/main.cpp), [sensor_data.cpp](../embedded/src/sensor_data.cpp) |
| MQTT và bộ đệm | [mqtt_network.cpp](../embedded/src/mqtt_network.cpp), [MqttLink.java](../backend/src/main/java/com/aqss/backend/mqtt/MqttLink.java) |
| Tiếp nhận và lưu số đo | [TelemetryService.java](../backend/src/main/java/com/aqss/backend/service/TelemetryService.java), [Flyway migrations](../backend/src/main/resources/db/migration) |
| Tính và áp dụng ngưỡng | [ThresholdService.java](../backend/src/main/java/com/aqss/backend/service/ThresholdService.java), [SpaceService.java](../backend/src/main/java/com/aqss/backend/service/SpaceService.java) |
| Quy tắc AUTO | [ControlLoop.java](../backend/src/main/java/com/aqss/backend/service/ControlLoop.java), [application.yml](../backend/src/main/resources/application.yml) |
| PWM, runtime và NVS | [fan_control.cpp](../embedded/src/fan_control.cpp), [config_store.cpp](../embedded/src/config_store.cpp) |
| Ghép nối và hủy liên kết | [DeviceService.java](../backend/src/main/java/com/aqss/backend/service/DeviceService.java) |
| Giao diện và dữ liệu | [useAqss.ts](../frontend/src/hooks/useAqss.ts), [client.ts](../frontend/src/api/client.ts), [HistoryChart.tsx](../frontend/src/components/HistoryChart.tsx) |
