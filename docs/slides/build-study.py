#!/usr/bin/env python3
"""Build offline study slides and revision notes from checked-in source excerpts."""
from pathlib import Path
from html import escape as E
import textwrap

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
JAVA = 'backend/src/main/java/com/aqss/backend/'
SLIDES = []
def add(title, lead, nodes, bullets, question, answer, sources, detail='', kind='flow'):
    SLIDES.append(dict(title=title,lead=lead,nodes=nodes,bullets=bullets,q=question,a=answer,sources=sources,detail=detail,kind=kind))
def src(path, marker, n=9): return (path,marker,n)
add('Đọc toàn bộ vòng hoạt động', 'Air Quality Supervision System · Bộ học kỹ thuật và chuẩn bị vấn đáp',
 ['Cảm biến|PWM / I²C','ESP32-S3|Snapshot → JSON','Backend|Lưu → quyết định','Quạt + UI|PWM / REST'],
 ['Học theo một mẫu đo: nguồn dữ liệu → truyền → lưu → quyết định → phản hồi.', 'Mỗi trang có sơ đồ, trích đoạn source và câu hỏi tự kiểm tra.', 'Tên hàm và số dòng lấy từ source khi dựng bộ slide.'],
 'Backend gửi lệnh xong có biết quạt thực sự quay chưa?',
 'Chưa. Node phản hồi duty đã áp dụng, nhưng code chưa đọc tachometer/RPM hoặc cảm biến lưu lượng. Lệnh và phản hồi phần mềm chưa chứng minh quạt quay hay hiệu quả lọc.',
 [src('embedded/src/mqtt_network.cpp','static void publishActuatorState()',10)],
 'Bộ này phục vụ học sâu, tách khỏi bài báo cáo 12 phút. Nhận xét về hạn chế là kết luận từ luồng source, chưa phải kết quả đo trên thiết bị. Mở phần giải thích dưới từng slide hoặc đọc tài liệu Markdown đi kèm.')
add('Khởi động: thứ tự nào quyết định an toàn?', 'Theo setup() trong main.cpp; bắt đầu bằng đặt PWM quạt về 0.',
 ['fanBegin|LEDC = 0','NVS + dataInit|Khôi phục cấu hình','Wire + OLED|Khởi tạo bus','WDT + 5 task|Chạy hai lõi'],
 ['fanBegin() chạy trước cấu hình mạng để sớm điều khiển chân PWM.', 'Wire.begin() chỉ được gọi một lần trong setup.', 'Giữ nút lúc khởi động 3 giây đặt cờ mở portal.'],
 'Vì sao không chờ Wi-Fi rồi mới khởi tạo quạt?',
 'Đầu vào PWM quạt có pull-up; cần chủ động đưa PWM về 0 sớm. Kết nối mạng có thể chậm hoặc thất bại, nên không để việc khởi tạo quạt phụ thuộc mạng.',
 [src('embedded/src/main.cpp','    fanBegin();',10)],
 'Không suy ra rằng PWM 0 bảo đảm mọi mẫu quạt dừng hoàn toàn: đây là lệnh phần mềm, khả năng dừng phụ thuộc quạt thực tế. GPIO 33–37 dành cho Octal PSRAM; pin map hiện dùng 5, 6, 7, 8, 9, 18.')
add('Đường nguồn và đường tín hiệu', 'Nguồn quạt, nguồn logic và tín hiệu điều tốc có vai trò khác nhau.',
 ['Nguồn 12 V|Quạt pin 2','LM2596|Hạ áp 5 V','ESP32-S3|I²C 8/9 · bụi 18','GPIO 5|Quạt pin 4 PWM'],
 ['Quạt dùng +12 V liên tục và GND chung; tốc độ qua chân PWM riêng.', 'SCD40 0x62 và OLED 0x3C cùng bus I²C 400 kHz.', 'Ảnh Fritzing còn nhãn GP2Y1010; firmware viết cho GP2Y1023.'],
 'Hai cảm biến Sharp có thay thế thuật toán cho nhau được không?',
 'Không. Firmware hiện đọc xung kỹ thuật số của GP2Y1023. GP2Y1010 có cách kích LED và đọc analog khác; không được dùng hình minh họa để kết luận hai loại tương đương.',
 [src('embedded/include/config.h','#define PIN_I2C_SDA',12)],
 'Cầu chia áp bụi 1 kΩ / 2 kΩ giảm mức điện áp đầu ra trước GPIO. Sơ đồ kết nối là minh họa; cần đối chiếu đúng linh kiện lắp thực tế trước khi bảo vệ. Không có đường tachometer trong pin map hiện tại.')
add('GP2Y1023: đo độ rộng xung LOW', 'Ngắt CHANGE ghi thời điểm hai cạnh; chu kỳ danh định khoảng 10 ms.',
 ['Cạnh xuống|t₀','Trong mức LOW|W = t₁ − t₀','Cạnh lên|t₁','Task bụi|Nhận W mới'],
 ['ISR có IRAM_ATTR; đồng hồ dùng esp_timer_get_time(), không analogRead().', 'Cạnh xuống phải cách khung đã nhận ít nhất 8000 µs.', 'Code chấp nhận W từ 7 đến 8000 µs; tài liệu dự án quy định trần 7000 µs.'],
 'Tại sao không đo bằng delay trong loop?',
 'Đợi theo vòng lặp dễ bỏ cạnh khi task khác hoặc mạng chiếm thời gian. Ngắt ghi dấu thời gian ở cạnh; phần trung bình và quy đổi chuyển ra task để tránh làm ISR dài.',
 [src('embedded/src/dust_sensor.cpp','static void IRAM_ATTR dustIsr()',4),src('embedded/src/dust_sensor.cpp','    } else if (s_lowValid)',9)],
 'Vo nhàn rỗi HIGH, xung cần đo là phần LOW. Khi cạnh xuống quá gần khung trước, s_lowValid bị xóa. Cạnh lên chỉ được chấp nhận khi có một cạnh xuống hợp lệ. Kiểm tra 8 ms là lọc nhiễu theo khung, không chứng minh đo đúng mọi nhiễu.',kind='wave')
add('Từ xung đến nồng độ bụi', 'W̄ → trừ baseline → chia độ nhạy → giới hạn dải.',
 ['s_widthSeq|Phát hiện dữ liệu mới','Cửa sổ 1 s|Tổng W / số mẫu','(W̄ − W₀) / 14|W₀ mặc định 1400 µs','clamp 0…500|Ghi snapshot'],
 ['Ví dụ tính toán: W̄ = 1680 µs → (1680 − 1400)/14 = 20 µg/m³.', 'Task thăm dò mỗi 5 ms; không có queue giữ toàn bộ các xung.', 'dust_saturated đặt từ W̄ ≥ 6900 µs; mất xung >3 s đặt dust_fault.'],
 'dust_fault có khiến PM2.5 gửi thành null ngay không?',
 'Không trong code hiện tại. Khi thiếu mẫu, giá trị cũ và dust_ready vẫn giữ. currentSample() dùng dust_ready làm cờ hợp lệ, nên có thể gửi lại PM cũ với timestamp mới.',
 [src('embedded/src/dust_sensor.cpp','            if (haveSamples)',10),src('embedded/src/mqtt_network.cpp','    sample.pm25_valid',3)],
 'Cửa sổ 1 s tổng hợp các giá trị mà task nhìn thấy qua biến volatile; đó không phải trung bình được bảo đảm của mọi xung. Volatile không khóa cặp width/seq thành một snapshot nguyên tử. Cần critical section hoặc queue nếu muốn bảo đảm đọc nhất quán và không mất mẫu.')
add('SCD40: periodic, data-ready, trung bình', 'Chu kỳ cảm biến 5 s khác với chu kỳ task kiểm tra 1 s.',
 ['startPeriodic|Bắt đầu đo','Poll mỗi 1 s|Data ready?','Kiểm tra số đo|Đưa vào ring 5 mẫu','Trung bình|Ghi CO₂ / T / RH'],
 ['Chưa ready → tiếp tục đợi, không tạo mẫu giả.', 'Trung bình số mẫu hiện có n ≤ 5; không chờ đủ 5 mới hiển thị.', 'Sau 5 lỗi giao tiếp liên tiếp, restartMeasurement() được gọi.'],
 'Lấy trung bình 5 mẫu có giá phải trả gì?',
 'Giảm dao động nhưng làm phản ứng hiển thị chậm hơn. Với mẫu cách nhau khoảng 5 s, năm mẫu có tuổi trải khoảng 20 s; không nên mô tả giá trị trung bình là số đo tức thời chưa lọc.',
 [src('embedded/src/scd40_sensor.cpp','        co2Ring[ringIndex]',10)],
 'Các mảng CO₂/T/RH dùng cùng ringIndex, nên một bộ số đo cập nhật cùng nhau. scd40_ready không tự xóa sau lỗi kéo dài; cần phân biệt sensor freshness với timestamp đóng gói telemetry.')
add('FreeRTOS: tác vụ, ưu tiên và mutex', 'Core 0: mqtt. Core 1: scd40, dust, fan, oled.',
 ['Task cảm biến|Ghi SensorData_t','g_dataMutex|Bảo vệ trạng thái','dataSnapshot|Sao chép rồi nhả','MQTT / OLED|Dùng bản sao'],
 ['dust/fan ưu tiên 3; scd40/mqtt 2; oled 1.', 'Mutex chỉ bảo vệ vùng dữ liệu được khóa; không tự bảo vệ mọi tài nguyên.', 'dataSnapshot tránh giữ mutex trong lúc serialize hoặc gửi mạng.'],
 'Pin hai core có biến hệ thống thành hard real-time không?',
 'Không. Còn lịch FreeRTOS, Wi-Fi, ngắt, vTaskDelay và I/O có thể chặn. ISR bắt cạnh và phân tác vụ giúp tổ chức thời gian, nhưng muốn tuyên bố deadline phải có đo latency/jitter.',
 [src('embedded/src/main.cpp','    xTaskCreatePinnedToCore(Task_ReadSCD40',5),src('embedded/src/sensor_data.cpp','SensorData_t dataSnapshot()',8)],
 'Các số stack truyền vào lần lượt 4096, 3072, 3072, 4096, 8192; không cộng chúng để kết luận bộ nhớ toàn chương trình. Data mutex không bao quanh giao dịch Wire giữa SCD40 và OLED; một lần Wire.begin không đồng nghĩa đã chứng minh tránh tranh chấp I²C.')
add('Wi-Fi, portal, NTP và MQTT', 'Bốn trạng thái kết nối cần phân biệt khi chẩn đoán.',
 ['NVS mạng|SSID / thông số','SoftAP + STA|Portal nếu cần','NTP hợp lệ|Cho gửi telemetry','MQTT TLS|Poll + publish'],
 ['Thiếu cấu hình hoặc join thất bại → portal cấu hình.', 'Wi-Fi mất lâu hơn 60 s → quay về portal; MQTT có nhịp retry riêng 5 s.', 'Telemetry không gửi hoặc buffer khi chưa có thời gian hợp lệ.'],
 'Wi-Fi kết nối được có nghĩa hệ thống đã hoạt động đầy đủ?',
 'Chưa. Cần DNS/Internet và NTP; sau đó MQTT cần TLS, tài khoản và subscribe thành công. Khi chẩn đoán phải tách Wi-Fi, NTP, MQTT, ghép phòng và số đo.',
 [src('embedded/src/mqtt_network.cpp','static bool timeIsValid()',4),src('embedded/src/mqtt_network.cpp','        if (!s_mqtt.connected())',8)],
 'Đồng bộ NTP được yêu cầu lần đầu, mỗi giờ, hoặc bằng force_ntp_sync. Backend kết nối Paho ở thread khởi tạo riêng để không chặn Spring startup. Portal dùng DNS và HTTP nội bộ trên AP; không phải trang dashboard React.')
add('MQTT: QoS, retained và LWT', 'Các bản tin hiện tại dùng QoS 1; không suy ra exactly-once.',
 ['Node|publish telemetry','Broker|QoS 1 / retained','Paho|Subscribe wildcard','Spring event|Telemetry / state / status'],
 ['telemetry và actuator/state không retained; status retained.', 'LWT OFFLINE do broker phát khi nhận diện mất kết nối bất thường.', 'Keepalive 30 s không có nghĩa UI thấy OFFLINE tức thì.'],
 'QoS 1 có ngăn số đo trùng trong DB không?',
 'Không. QoS 1 cho phép giao lại; schema telemetry không có unique(device_id, recorded_at) hay id bản tin để khử trùng. Một mẫu có thể có nhiều hàng nếu được giao lại.',
 [src('embedded/src/mqtt_network.cpp','    if (!publishPayload(kTopicTelemetry',4),src('embedded/src/mqtt_network.cpp','    s_mqtt.beginWill',7)],
 'QoS là bảo đảm truyền giữa các phía MQTT, không chứng minh transaction DB đã commit hoặc quạt đã thực thi. Backend cleanSession=true và subscribe lại khi connectComplete; không nên hứa hàng đợi lệnh bền vững xuyên mọi lần ngắt.')
add('Một mẫu telemetry đi vào backend', 'JSON snake_case → record Java → event → service.',
 ['MQTT callback|messageArrived','ObjectMapper|TelemetryEvent','@EventListener|TelemetryService','@Transactional|Lưu + onReading'],
 ['ObjectMapper dùng SNAKE_CASE nên device_id ánh xạ deviceId.', 'Event mặc định chạy đồng bộ nếu không cấu hình multicaster/async khác.', 'Code không có @Async ở luồng tiếp nhận này.'],
 'Spring event ở đây có phải message queue bền vững?',
 'Không. Đây là event trong tiến trình. Mặc định listener chạy cùng luồng phát; không có hàng đợi durable để phát lại sau khi tiến trình chết.',
 [src(JAVA+'mqtt/MqttLink.java','    public void messageArrived',10)],
 'Callback catch exception và log Dropped MQTT message. Không thấy cơ chế retry nghiệp vụ hoặc dead-letter trong code này. Phân biệt exception bị log với việc đã có backpressure hoặc retry được bảo đảm.')
add('Validation: null, zero và dải hợp lệ', 'Kiểm tra code hai phía thay vì chỉ học bảng trong tài liệu.',
 ['Firmware|Chấp nhận rộng hơn','JSON|null nếu chưa ready','Backend|Kiểm tra bốn sensor','DB|NULL hoặc lưu cả mẫu'],
 ['Backend: PM 0…500; CO₂ 400…5000; T −10…60; RH 0…100.', 'Firmware config: CO₂ 300…10000; T −40…85; hai phía đang lệch.', 'Một giá trị ngoài dải backend → loại cả bản tin, không chỉ trường đó.'],
 'CO₂ = null và CO₂ = 0 xử lý giống nhau không?',
 'Khác. inRange(null) trả true và lưu NULL. Giá trị 0 ppm ngoài dải backend nên bị loại cả bản tin. PM2.5 = 0 lại hợp lệ.',
 [src(JAVA+'service/TelemetryService.java','        if (!inRange',8),src('embedded/include/config.h','#define CO2_MIN_PPM',5)],
 'Không được nói “mọi lớp đã dùng cùng bảng validation” vì config hiện tại không khớp hợp đồng. Cờ dust_fault có trong event nhưng TelemetryService hiện không lưu cờ đó; chỉ pm25_saturated được lưu vào telemetry.')
add('PostgreSQL: quan hệ và truy vấn', 'Sơ đồ quan hệ theo migrations V1–V4; audit.device_id không có ràng buộc FK.',
 ['spaces|PK code','devices|FK space_code','telemetry|FK device_id','audit_log|Tham chiếu logic'],
 ['spaces 1–N devices; devices 1–N telemetry.', 'ON DELETE SET NULL cho liên kết phòng; xóa device CASCADE telemetry.', 'Index(device_id, recorded_at DESC) hỗ trợ latest/history.'],
 'Xóa thiết bị có giữ nguyên lịch sử telemetry không?',
 'Không theo schema hiện tại: telemetry.device_id tham chiếu devices với ON DELETE CASCADE. Audit log không có FK nên có thể còn giữ sự kiện cũ.',
 [src('backend/src/main/resources/db/migration/V1__init.sql','CREATE TABLE telemetry',12)],
 'Flyway V4 xóa predictions; còn bốn bảng ứng dụng. Hibernate ddl-auto: validate không tự cập nhật schema. Một phòng có thể có nhiều node theo DB; thuật toán chọn CO₂ phòng hiện chọn device đầu tiên, không gộp CO₂ toàn phòng.',kind='erd')
add('Ngưỡng: công thức đang tính những gì?', 'T = clamp(15 × √(24/h) × f(V) × f(vent) × f(CO₂), 10, 60).',
 ['purpose|Suy ra h','Thể tích / 20|Căn bậc ba, clamp','Thông gió + CO₂|Nhân hệ số','Làm tròn|Một chữ số thập phân'],
 ['Phòng ngủ/văn phòng 8 h, lớp học 6 h, khách 4 h, bếp 2 h.', 'f(V): 0,7…1,6; thông gió: 0,75 / 1 / 1,35.', 'Đây là chính sách công thức của dự án; không phải mô hình đã huấn luyện.'],
 'Ví dụ V=20 m³, phòng ngủ, thông gió medium, chưa có CO₂?',
 'h=8; các hệ số còn lại bằng 1; 15×√3≈25,98 và kết quả trả về 26,0 µg/m³. Không suy ra toàn bộ công thức đã được kiểm định sức khỏe.',
 [src(JAVA+'service/ThresholdService.java','        double dose =',8)],
 'Các bước nhân dùng giá trị đầy đủ, kết quả và hệ số được làm tròn khi trả Result. Nhân lại các hệ số đã làm tròn trên UI có thể không khớp tuyệt đối số cuối. Thiếu thể tích hoặc V≤0 thì volume factor=1.')
add('CO₂ tham gia ở đâu? Ngưỡng có tự đổi?', 'Preview dùng mẫu CO₂ hiện có; control loop so sánh với ngưỡng đã lưu.',
 ['GET preview|Đọc profile + CO₂','compute|Trả T và hệ số','POST threshold|Lưu source=profile','ControlLoop|Dùng thresholdPm25'],
 ['fCO₂ = 1 − 0,25 × clamp((CO₂ − 420)/800, 0, 1).', 'CO₂=820 → f=0,875; không có CO₂ → f=1.', 'Nhập ngưỡng trực tiếp qua PUT cho phép 1…500; clamp 10…60 chỉ cho ngưỡng tính.'],
 'CO₂ vừa tăng có tự làm quạt bật không?',
 'Không trực tiếp. Ngưỡng chỉ tính khi tạo phòng hoặc áp dụng kết quả tính. CO₂ mới không tự ghi đè threshold mỗi telemetry; control loop không gọi compute.',
 [src(JAVA+'service/SpaceService.java','    public ThresholdPreview applyComputedThreshold',9),src(JAVA+'service/ThresholdService.java','        double delta =',4)],
 'currentCo2() lấy mẫu mới nhất của thiết bị đầu tiên thuộc phòng, không kiểm tra tuổi mẫu. POST tính lại, không gửi lại đúng con số preview cũ. Giá trị h hiện lấy từ purpose; occupancy_hours gửi trong pair chưa được dùng để lưu/compute.')
add('ControlLoop: hai cửa vào, cùng bộ đánh giá', 'onReading kích hoạt khi đang OFF; tick kiểm tra định kỳ bằng fixedDelay.',
 ['Telemetry mới|onReading','Chưa ghép / MANUAL?|Bỏ qua','OFF hoặc tick|evaluate','PM null / cũ?|Bỏ qua trước decide'],
 ['tick dùng fixedDelay 60000: chờ sau khi lượt trước kết thúc.', 'onReading bỏ qua fan ON, nên tắt theo hysteresis chờ tick.', 'MAX_ON cũng nằm sau guard stale: số đo cũ làm bỏ qua cả decide.'],
 'Đủ 120 phút có chắc backend luôn gửi OFF?',
 'Không tuyệt đối. Phải qua các guard hợp lệ, và tick có độ trễ. Nếu reading cũ/null thì evaluate return trước max_on; khi không gia hạn được, firmware AUTO có timeout riêng.',
 [src(JAVA+'service/ControlLoop.java','    public void onReading',10),src(JAVA+'service/ControlLoop.java','        if (pm25 == null || stale)',3)],
 'Activation “ngay” nghĩa là không chờ timer 60 s; vẫn có độ trễ đo, lọc, telemetry, mạng và xử lý. Không nên nói phản ứng bằng 0 giây. elapsed(null) trả true, nên thiếu onSince cũng ảnh hưởng quyết định.')
add('State machine AUTO và hysteresis', 'Quy tắc theo decide(); min_off hiện không được dùng để chặn bật.',
 ['OFF|PM ≥ T → L3','ON|Dưới T vẫn chạy','ON ≥ 5 phút|PM < 0,7T → OFF','ON ≥ 120 phút|OFF nếu qua guard'],
 ['T=30: bật khi PM≥30, tắt khi PM<21 và đủ 5 phút.', 'PM=21 đúng ngưỡng tắt chưa thỏa vì code dùng dấu <.', 'Sau max_on, tick sau có thể bật lại ngay nếu PM vẫn≥T.'],
 'Có nghỉ tối thiểu 3 phút trước khi bật lại không?',
 'Không trong decide hiện tại. min-off-minutes có trong cấu hình, offSince có trong Inputs, nhưng nhánh fan OFF chỉ so PM với T. Nội dung UI nhắc nghỉ 3 phút đang lệch code.',
 [src(JAVA+'service/ControlLoop.java','    public static Decision decide',12)],
 'Hysteresis là hai mốc ON/OFF, kết hợp minimum ON để giảm chập chờn. Không có PID, không điều chỉnh duty theo sai số. Đây là điều khiển theo ngưỡng với state và điều kiện thời gian.',kind='state')
add('Ví dụ thời gian để tự tính quyết định', 'Ví dụ giả định T=30; không phải dữ liệu đo hiệu suất.',
 ['t=0: PM=32|OFF → ON L3','t=2 phút: PM=18|Chưa đủ min ON','t=6 phút: PM=20|Tick → OFF','Mẫu sau: PM=31|Bật lại ngay'],
 ['Đủ min ON không tự tắt nếu PM vẫn ≥21.', 'Mẫu thấp đến khi fan ON không trực tiếp kích hoạt tắt qua onReading.', 'Giới hạn max_on ưu tiên trước hysteresis trong decide.'],
 'Nếu PM thấp ngay sau bật, có tắt ngay không?',
 'Không theo hysteresis bình thường: cần ≥5 phút ON, PM<0,7T và tới lượt đánh giá timer. Nếu mất backend, timeout firmware là một nhánh OFF độc lập.',
 [src(JAVA+'service/ControlLoop.java','        boolean minOnElapsed',3)],
 'Hãy tự thay PM=21, PM=20, thời gian 4 phút, 5 phút, rồi xét mode MANUAL. Lời giải: PM=21 không thỏa <21; PM=20 và 4 phút chưa đủ; PM=20 và 5 phút đủ nếu evaluate được gọi và các guard đều hợp lệ.')
add('Lệnh quạt: ý định → broker → node', 'HTTP thành công không phải chứng chỉ quạt đã quay.',
 ['React mutation|POST command','DeviceService|applyActuator','MqttLink|publish set_actuator','Node|fanCmdSet → LEDC'],
 ['level được clamp 0…3; yêu cầu ON level 0 được backend nâng lên 2.', 'Backend ghi ý định vào Device; node phản hồi qua actuator/state.', 'Không có command_id hoặc ACK nghiệp vụ khớp từng lệnh.'],
 'Broker lỗi lúc AUTO activate thì DB có thể vẫn ghi ON không?',
 'Có thể: applyActuator cập nhật trạng thái và audit cả khi published=false; ControlLoop không kiểm tra boolean trả về. Đây là đường AUTO, khác lệnh USER ném 503 và có transaction bao ngoài.',
 [src(JAVA+'service/DeviceService.java','        boolean published = mqtt.publishCommand',10)],
 'Phản hồi actuator/state có thể điều chỉnh lại trạng thái dự kiến. Không được đồng nhất “publish thành công” với “node nhận”, “PWM đã áp”, hoặc “có lưu lượng”. Đó là bốn mức xác nhận khác nhau.',kind='seq')
add('PWM, kickstart và nút MANUAL', 'LEDC 8 bit · 25 kHz · GPIO 5; task quạt lặp khoảng 50 ms.',
 ['FanCommand snapshot|on + level + mode','OFF → ON|255 trong 250 ms','Duty yêu cầu|0 / 89 / 153 / 230','Shared state|state / duty / mode'],
 ['Duty danh định 0/35/60/90%; giá trị 8 bit có làm tròn.', 'Nút debounce 30 ms; nhấn ngắn vòng mức ở MANUAL.', 'Giữ 3 s trả AUTO và gia hạn timestamp lệnh tại node.'],
 'Mức duty thấp nhất 20% có đang tác động các level 1–3 không?',
 'Các level hiện đều ≥35%, nên phép clamp FAN_MIN_DUTY=51 chủ yếu là bảo vệ nếu bổ sung duty thấp. Kickstart chỉ thực hiện khi chuyển từ không chạy sang chạy.',
 [src('embedded/src/fan_control.cpp','    if (duty > 0 && !s_appliedOn)',8)],
 'Task ưu tiên không thể chạy vượt một lời gọi blocking bên trong nó. Xung kickstart dùng vTaskDelay nên task quạt nhường lịch trong 250 ms. Nút MANUAL chỉ ưu tiên bằng cập nhật mode/state; một lệnh AUTO đã bay trên mạng vẫn có thể đến sau thao tác nút.')
add('Phản hồi trạng thái và giới hạn bộ đếm', 'Đọc code runtimeS trong task trước khi hứa chức năng thay lõi đã đồng bộ.',
 ['fan task|runtimeS cục bộ','Cộng FAN_TICK_MS|Tích lũy thành giây','NVS mỗi 5 phút|Snapshot runtime','state MQTT|Giờ làm tròn 0,1 h'],
 ['Thời gian cộng theo tick giả định, không theo millis chênh lệch thực.', 'filter_runtime_h làm tròn 0,1 h → độ phân giải khoảng 6 phút.', 'reset_filter đặt shared/config về 0 nhưng task còn runtimeS cũ.'],
 'Vì sao reset có thể lại hiện số giờ cũ?',
 'runtimeS khởi tạo một lần trước for(;;), sau đó task ghi ngược lại config và shared state. Handler MQTT không gán runtimeS cục bộ nên giá trị 0 có thể bị ghi đè. Đây là suy luận từ source, cần tái hiện trên thiết bị.',
 [src('embedded/src/fan_control.cpp','    uint32_t runtimeS =',3),src('embedded/src/mqtt_network.cpp','    } else if (strcmp(command, "reset_filter")',8)],
 'Muốn sửa: task quạt cần sở hữu thao tác reset qua flag/event hoặc đọc bộ đếm từ một nguồn chung có đồng bộ. Không sửa firmware trong công việc làm tài liệu này. Không gọi runtime là đo thời gian chính xác khi cộng hằng 50 ms bỏ qua overhead.')
add('Mất mạng: bộ đệm thực sự nằm ở nhánh nào?', 'Vòng đệm 60 slot; NTP phải hợp lệ mới nhận mẫu vào buffer.',
 ['Wi-Fi OK|MQTT mất kết nối','bufferCurrentReading|Giữ timestamp gốc','Queue đầy|Ghi đè mẫu cũ nhất','Reconnect|flush mẫu đầu queue'],
 ['Ở chu kỳ 5 s, 60 slot giữ tối đa khoảng 5 phút.', 'flush chỉ tăng head sau khi publish thành công.', 'Nhánh !WiFi.isConnected() continue trước đoạn buffer MQTT.'],
 'Có thể nói mất Wi-Fi vẫn buffer đầy đủ 5 phút không?',
 'Không với luồng hiện tại. Nhánh Wi-Fi down bỏ qua bufferCurrentReading. Bộ đệm được gọi khi Wi-Fi vẫn có nhưng MQTT mất, hoặc khi publish live thất bại.',
 [src('embedded/src/mqtt_network.cpp','            g_sensorData.wifi_rssi = 0;',6),src('embedded/src/mqtt_network.cpp','            const RuntimeConfig offlineCfg',6)],
 'Buffer nằm trong RAM nên reboot mất mẫu. Khi Wi-Fi mất lâu, portal có thể giữ luồng mạng trong provisioningRun. Các task cảm biến vẫn chạy nhưng điều đó không đồng nghĩa task mạng đã lưu chuỗi telemetry theo thời gian.')
add('Lease AUTO và fail-safe của firmware', '5 phút gửi lại lệnh; 10 phút không có lệnh thì node tắt AUTO.',
 ['Backend ON|NONE → xem lease','≥5 phút|Gửi lại set_actuator','Node nhận lệnh|last_command_ms mới','>10 phút im lặng|AUTO ON → OFF'],
 ['Lease là timeout ứng dụng; khác MQTT keepalive và watchdog.', 'Không có reading còn mới → evaluate return, không renew lease.', 'MANUAL không bị điều kiện timeout AUTO này tắt.'],
 'Có bảo đảm đồng hồ lease backend khớp node khi publish lỗi?',
 'Không. renewLease vẫn cập nhật lastCommandAt ngay cả khi publishCommand trả false. Node chỉ cập nhật last_command_ms khi thực sự xử lý lệnh, nên node có thể timeout trong khi DB vừa ghi đã renew.',
 [src(JAVA+'service/DeviceService.java','        mqtt.publishCommand(device.getDeviceId(), "set_actuator", params);',3),src('embedded/src/fan_control.cpp','        if (cmd.mode == FAN_MODE_AUTO',7)],
 'Đây là fail-safe của ứng dụng cho liên lạc bị gián đoạn; không có nghĩa firmware vẫn lọc tự động theo cảm biến khi backend chết. Node không chứa công thức ngưỡng hoặc thuật toán AUTO dự phòng.')
add('Ghép nối và hủy liên kết hai phía', 'Pairing liên kết device với phòng; không phải cơ chế xác thực người dùng.',
 ['OLED pairing|Mã 6 ký tự','POST pair|Tìm node theo mã','DB binding|paired_at + space','set_space|NVS + OLED'],
 ['Mã suy ra từ eFuse MAC bằng phép biến đổi xác định.', 'ensure() chỉ tạo node chưa ghép; không tin space_id telemetry để bind.', 'Unpair xóa DB binding và gửi set_space rỗng để xóa NVS.'],
 'Mã pairing có phải mật khẩu bảo mật hoặc OTP không?',
 'Không. Mã có tính xác định từ định danh thiết bị, không có thời hạn OTP trong code, và REST chưa có authentication. Chức năng chính là chọn node để gắn phòng.',
 [src('embedded/src/config_store.cpp','    uint32_t hash =',9),src(JAVA+'service/DeviceService.java','        boolean notified = notifyNode',4)],
 'Pair/unpair gửi lệnh best-effort. Nếu broker down, DB có thể đã đổi nhưng node chưa nhận; không thấy cơ chế reconcile tự động mọi lần reconnect. Do ensure không tự bind, telemetry cũ không phục hồi liên kết DB đã xóa.')
add('Dashboard: polling và cache sau thao tác', 'Không có WebSocket/SSE trong luồng đang dùng.',
 ['useQuery|GET REST','Cache theo key|deviceId / hours','useMutation|POST command','onSuccess|Invalidate → refetch'],
 ['Latest/devices: 5 s; history/spaces: 60 s.', 'enabled ngăn query chạy khi thiếu deviceId; latest retry=false.', 'Lỗi 404 chưa có mẫu khác PM2.5=0; UI có trạng thái chờ.'],
 'Số đo gửi 5 s có đồng nghĩa màn hình luôn trễ đúng 5 s?',
 'Không. Hai nhịp sensor/telemetry/polling không đồng bộ; còn trễ mạng, xử lý và cache. Sau thao tác, invalidate yêu cầu refetch thay vì chờ hết chu kỳ bình thường.',
 [src('frontend/src/hooks/useAqss.ts','export function useFanCommand',8)],
 'isOnline dùng last_seen trong cửa sổ 30 s. Last_seen có thể cập nhật bởi telemetry và actuator/state; nó biểu thị liên lạc, không chứng minh từng sensor còn tạo mẫu mới. Settings ngưỡng preview không có polling 5 s mặc định.')
add('Biểu đồ: dữ liệu vẽ khác dữ liệu điều khiển', 'Hai bước giảm lượng dữ liệu: backend thưa mẫu, frontend median mỗi 5 phút.',
 ['DB history|Khoảng from/to','Backend step|size / 800','Bucket 300000 ms|Median mỗi ô','SVG + spike|Đường + tooltip'],
 ['Frontend loại PM null, gom theo floor(time/300000).', 'Median là phần tử sorted[floor(n/2)] kể cả n chẵn.', 'Bucket cao hơn cả hai hàng xóm được vẽ riêng nếu vượt 0,8T.'],
 'Làm mượt biểu đồ có trì hoãn bật quạt không?',
 'Không: ControlLoop đọc telemetry mới nhất từ DB, không dùng đường SVG hay median bucket của frontend. Biểu đồ để xem lịch sử; quyết định dựa vào luồng backend riêng.',
 [src('frontend/src/components/HistoryChart.tsx','      const cell =',5),src('frontend/src/components/HistoryChart.tsx','        return { t, v:',2)],
 'Backend step=max(1,size/800) dùng chia nguyên nên “~800 điểm” không phải giới hạn cứng 800. Ví dụ 1599 hàng thì step=1 vẫn trả 1599. Tooltip tìm mẫu gần nhất trong history, không bắt buộc đúng giá trị median trên đường.')
add('Tổng hợp các chỗ phải trả lời đúng giới hạn', 'Các nhận xét dưới đây là đọc source; không coi là kết quả kiểm thử phần cứng.',
 ['Đã triển khai|Có luồng / có code','Đã gửi|Không chứng minh đã quay','Đã tính ngưỡng|Không phải kiểm định y tế','Cần đo / tái hiện|Tách khỏi điều suy ra'],
 ['Đã thấy lệch: validation, min_off, nhãn Fritzing, occupancy_hours.', 'Cần tái hiện: reset runtime; mẫu sensor cũ có timestamp mới.', 'Cần hoàn thiện: ACK ứng dụng, khử trùng, reconcile binding, freshness mỗi sensor.'],
 'Nếu bị hỏi “hệ thống đã hoạt động đúng hoàn toàn chưa”, nên đáp thế nào?',
 'Nêu đường đã xây dựng, chỉ vào hàm và sơ đồ, rồi trình bày đúng giới hạn. Muốn khẳng định timing, độ chính xác sensor và hiệu quả lọc cần log, đo chuẩn hoặc kịch bản kiểm chứng; ảnh dashboard không thay thế các phép đo đó.',
 [src(JAVA+'service/ControlLoop.java','            return in.pm25()',3)],
 'Cách ôn: tự giải thích mỗi sơ đồ không nhìn ghi chú; chỉ được điểm đọc/ghi trạng thái; đưa một ví dụ đầu vào; sau đó nêu trường hợp lỗi và phần xác nhận còn thiếu. Phần code giữ đúng identifier gốc, tên dự án luôn dùng đầy đủ.')


def excerpt(source):
    path,marker,n=source
    lines=(ROOT/path).read_text().splitlines()
    index=next((i for i,l in enumerate(lines) if marker in l),None)
    if index is None: raise ValueError(f'Missing source marker: {path}: {marker}')
    text=textwrap.dedent('\n'.join(lines[index:index+n]))
    return path,index+1,text

def diagram(d):
    nodes=d['nodes']; count=len(nodes)
    body=['<svg viewBox="0 0 1160 210" role="img" aria-label="'+E(d['title'])+'"><defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0L8 4L0 8Z" fill="#7dd3fc"/></marker></defs>']
    if d['kind']=='wave':
        body.append('<path d="M30 40H150V105H320V40H670V105H840V40H1130" fill="none" stroke="#7dd3fc" stroke-width="3"/><text x="154" y="27">cạnh xuống t₀</text><text x="324" y="27">cạnh lên t₁</text><path d="M150 123H320" stroke="#fbbf24" marker-end="url(#arrow)"/><text x="165" y="150">W = t₁ − t₀ (µs)</text><text x="650" y="150">Khung tiếp theo khoảng 10 ms</text>')
    elif d['kind']=='seq':
        for i,label in enumerate(['React','Backend','Broker','Node']):
            x=120+i*300;body.append(f'<text x="{x}" y="20" text-anchor="middle">{label}</text><path d="M{x} 30V200" stroke="#435264" stroke-dasharray="4 4"/>')
        for a,b,y,label in [(120,420,60,'POST command'),(420,720,100,'publish'),(720,1020,140,'set_actuator'),(1020,420,185,'actuator/state (qua broker)')]:
            body.append(f'<path d="M{a} {y}H{b}" stroke="#7dd3fc" marker-end="url(#arrow)"/><text x="{(a+b)/2}" y="{y-8}" text-anchor="middle">{label}</text>')
    elif d['kind'] == 'erd':
        for x,label,desc in [(20,'spaces','PK code'),(420,'devices','FK space_code'),(820,'telemetry','FK device_id')]:
            body.append(f'<rect x="{x}" y="20" width="260" height="75" rx="8" fill="#162332" stroke="#496177"/><text x="{x+130}" y="49" text-anchor="middle">{label}</text><text x="{x+130}" y="75" text-anchor="middle" class="sub">{desc}</text>')
        body.append('<path d="M280 58H415M680 58H815" stroke="#7dd3fc" marker-end="url(#arrow)"/><text x="315" y="46">1 → N</text><text x="715" y="46">1 → N</text><rect x="420" y="126" width="260" height="63" rx="8" fill="#162332" stroke="#496177"/><text x="550" y="152" text-anchor="middle">audit_log</text><text x="550" y="178" text-anchor="middle" class="sub">device_id: không có FK</text>')
    elif d['kind'] == 'state':
        body.append('<rect x="130" y="60" width="200" height="80" rx="30" fill="#162332" stroke="#496177"/><text x="230" y="106" text-anchor="middle">OFF</text><rect x="830" y="60" width="200" height="80" rx="30" fill="#162332" stroke="#496177"/><text x="930" y="106" text-anchor="middle">ON · level 3</text><path d="M330 80H825" stroke="#7dd3fc" marker-end="url(#arrow)"/><text x="560" y="65" text-anchor="middle">PM ≥ T (không chặn min_off)</text><path d="M830 130H335" stroke="#fbbf24" marker-end="url(#arrow)"/><text x="580" y="157" text-anchor="middle">ON ≥ 120 phút, hoặc [ON ≥ 5 phút và PM &lt; 0,7T]</text><text x="580" y="195" text-anchor="middle" class="sub">Chỉ tới decide sau guard AUTO, ghép phòng và reading còn hiệu lực.</text>')
    elif d['title'] == 'ControlLoop: hai cửa vào, cùng bộ đánh giá':
        body.append('<rect x="10" y="20" width="220" height="55" rx="8" fill="#162332" stroke="#496177"/><text x="120" y="54" text-anchor="middle">onReading: fan OFF</text><rect x="10" y="130" width="220" height="55" rx="8" fill="#162332" stroke="#496177"/><text x="120" y="164" text-anchor="middle">tick: mỗi lượt 60 s</text><rect x="400" y="70" width="250" height="70" rx="8" fill="#162332" stroke="#496177"/><text x="525" y="98" text-anchor="middle">Paired + AUTO + room?</text><text x="525" y="123" text-anchor="middle" class="sub">Sai: return</text><rect x="875" y="70" width="270" height="70" rx="8" fill="#162332" stroke="#496177"/><text x="1010" y="98" text-anchor="middle">PM còn mới và khác null?</text><text x="1010" y="123" text-anchor="middle" class="sub">Đúng: decide; sai: return</text><path d="M230 48H300V105H395M230 157H300V105M650 105H870" fill="none" stroke="#7dd3fc" marker-end="url(#arrow)"/><text x="730" y="88">Đúng</text>')
    elif d['title'] == 'Mất mạng: bộ đệm thực sự nằm ở nhánh nào?':
        body.append('<rect x="20" y="70" width="220" height="70" rx="8" fill="#162332" stroke="#496177"/><text x="130" y="110" text-anchor="middle">Wi-Fi connected?</text><rect x="460" y="10" width="280" height="70" rx="8" fill="#162332" stroke="#496177"/><text x="600" y="39" text-anchor="middle">Không: retry / portal</text><text x="600" y="64" text-anchor="middle" class="sub">continue; không buffer mẫu ở đây</text><rect x="460" y="125" width="280" height="70" rx="8" fill="#162332" stroke="#496177"/><text x="600" y="154" text-anchor="middle">Có: MQTT connected?</text><text x="600" y="179" text-anchor="middle" class="sub">Không: buffer + reconnect</text><rect x="910" y="125" width="230" height="70" rx="8" fill="#162332" stroke="#496177"/><text x="1025" y="154" text-anchor="middle">Có: publish live</text><text x="1025" y="179" text-anchor="middle" class="sub">và flushOffline</text><path d="M240 90H330V45H455M240 120H330V160H455M740 160H905" fill="none" stroke="#7dd3fc" marker-end="url(#arrow)"/>')
    elif d['title'] == 'Đường nguồn và đường tín hiệu':
        body.append('<rect x="20" y="70" width="190" height="65" rx="8" fill="#162332" stroke="#496177"/><text x="115" y="110" text-anchor="middle">Nguồn 12 V</text><rect x="430" y="15" width="220" height="65" rx="8" fill="#162332" stroke="#496177"/><text x="540" y="55" text-anchor="middle">Quạt pin 2: +12 V</text><rect x="430" y="125" width="220" height="65" rx="8" fill="#162332" stroke="#496177"/><text x="540" y="165" text-anchor="middle">LM2596 → 5 V</text><rect x="880" y="125" width="260" height="65" rx="8" fill="#162332" stroke="#496177"/><text x="1010" y="165" text-anchor="middle">ESP32-S3</text><path d="M210 95H300V48H425M300 95V155H425M650 155H875" stroke="#7dd3fc" fill="none" marker-end="url(#arrow)"/><path d="M1010 125V48H655" stroke="#fbbf24" fill="none" marker-end="url(#arrow)"/><text x="820" y="30" text-anchor="middle">GPIO 5 → pin 4 PWM</text><text x="840" y="205" class="sub">Các nhánh dùng chung GND.</text>')
    else:
        for i,node in enumerate(nodes):
            x=10+i*290
            a,b=node.split('|',1)
            body.append(f'<rect x="{x}" y="55" width="258" height="90" rx="9" fill="#162332" stroke="#496177"/><text x="{x+129}" y="91" text-anchor="middle">{E(a)}</text><text x="{x+129}" y="118" text-anchor="middle" class="sub">{E(b)}</text>')
            if i<count-1:body.append(f'<path d="M{x+258} 100H{x+285}" stroke="#7dd3fc" stroke-width="2" marker-end="url(#arrow)"/>')
        if d['kind']=='erd':body.append('<text x="330" y="175">1 → N (FK)</text><text x="635" y="175">1 → N (FK)</text><text x="900" y="175">audit: không có FK</text>')
        if d['kind']=='state':body.append('<text x="20" y="180">Điều kiện chuyển trạng thái đặt trên từng ô; đây là các nhánh của decide(), không phải chuỗi chạy tuần tự.</text>')
    return ''.join(body)+'</svg>'

def main():
    html=['<!doctype html><html lang="vi"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Air Quality Supervision System — Học cách hoạt động</title><link rel="stylesheet" href="study.css"></head><body><nav><b>Air Quality Supervision System</b><a href="deck.html">Báo cáo chính</a><a href="../kien-thuc-van-dap.md">Bản học Markdown</a><span>Mở “Giải thích” dưới từng trang để học sâu · Ctrl+P xuất slide</span></nav>']
    md=['# Air Quality Supervision System — Kiến thức hoạt động và vấn đáp\n\nBộ học chi tiết đi kèm [slide HTML](slides/hoat-dong-chi-tiet.html). Mỗi mục có câu hỏi, lời giải và đoạn mã trích trực tiếp từ source. Các hạn chế là suy luận tĩnh từ code, chưa phải kết quả đo hoặc kiểm thử thực tế. Không thay thế bài nói 12 phút.\n\n## Mục lục\n']
    for i,d in enumerate(SLIDES,1):md.append(f'{i}. {d["title"]}\n')
    for i,d in enumerate(SLIDES,1):
        refs=[excerpt(r) for r in d['sources']]
        code=''.join(f'<div class="ref">{E(p)} : {line}</div><pre>{E(t)}</pre>' for p,line,t in refs)
        html.append(f'<section class="slide" id="s{i}"><header><div class="eyebrow">Air Quality Supervision System · Học chi tiết · {i:02d}/{len(SLIDES)}</div><h1>{E(d["title"])}</h1><p>{E(d["lead"])}</p></header><div class="diagram">{diagram(d)}</div><div class="content"><div class="source">{code}</div><ul>'+''.join('<li>'+E(b)+'</li>' for b in d['bullets'])+f'</ul></div><div class="question"><b>Tự trả lời:</b> {E(d["q"])}</div></section><details><summary>Giải thích và đáp án · trang {i:02d}</summary><h2>{E(d["q"])}</h2><p>{E(d["a"])}</p><p>{E(d["detail"])}</p><p>Trích đoạn có số dòng ở trên; mở file nguồn để xem toàn bộ ngữ cảnh.</p>'+''.join(f'<a href="../../{E(p)}">{E(p)} · từ dòng {line}</a><br>' for p,line,t in refs)+'</details>')
        flow = ' → '.join(x.replace('|', ': ') for x in d['nodes'])
        if d['kind'] == 'erd': flow = 'spaces 1–N devices; devices 1–N telemetry; audit_log độc lập, không có FK device_id.'
        if d['kind'] == 'state': flow = 'OFF → ON khi PM≥T; ON → OFF khi max_on hoặc đủ min_on và PM<0,7T. Các guard nằm trước decide.'
        if d['title'] == 'Đường nguồn và đường tín hiệu': flow = '12 V phân nhánh: quạt pin 2 và LM2596 → 5 V → MCU. MCU GPIO5 → quạt pin 4; GND chung.'
        md.append(f'\n## {i:02d}. {d["title"]}\n\n{d["lead"]}\n\n**Luồng:** ' + flow + '\n\n')
        md.extend('- '+b+'\n' for b in d['bullets'])
        md.append(f'\n**Câu hỏi:** {d["q"]}\n\n**Trả lời:** {d["a"]}\n\n**Giải thích thêm:** {d["detail"]}\n')
        for p,line,t in refs:md.append(f'\n[{p}](../{p}) — từ dòng {line}:\n\n```\n{t}\n```\n')
    html.append('</body></html>')
    (HERE/'hoat-dong-chi-tiet.html').write_text('\n'.join(html))
    (HERE.parent/'kien-thuc-van-dap.md').write_text('\n'.join(md))
    print(f'Built {len(SLIDES)} study slides and revision notes.')
if __name__=='__main__': main()
