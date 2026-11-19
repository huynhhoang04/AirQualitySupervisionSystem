from pptx import Presentation
from pptx.util import Inches,Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
from PIL import Image
import json, os
root=os.path.abspath(os.path.join(os.path.dirname(__file__),'../..')); assets=root+'/docs/slides/report-assets'; out=root+'/docs/slides/Air-Quality-Supervision-System-bao-cao.pptx'
r=Presentation(); r.slide_width=Inches(13.333); r.slide_height=Inches(7.5)
BG='FFFFFF'; PANEL='F1F5F8'; WHITE='172B3B'; MUTED='546B7B'; CYAN='007B91'; LIME='287B55'; AMBER='A36109'; RED='AF3949'
def color(h):return RGBColor.from_string(h)
def box(sl,x,y,w,h,fill=PANEL, radius=True, line=None):
 sh=sl.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE if radius else MSO_SHAPE.RECTANGLE, Inches(x),Inches(y),Inches(w),Inches(h));sh.fill.solid();sh.fill.fore_color.rgb=color(fill);sh.line.fill.background() if not line else None
 if line:sh.line.color.rgb=color(line)
 if radius:
  try: sh.adjustments[0]=0.08
  except: pass
 return sh
def txt(sl,x,y,w,h,s,size=17,c=WHITE,b=False,font='Noto Sans',align=None):
 sh=sl.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h)); tf=sh.text_frame;tf.clear();tf.word_wrap=True;tf.margin_left=Pt(0);tf.margin_right=Pt(0);tf.margin_top=Pt(1);tf.margin_bottom=Pt(0)
 p=tf.paragraphs[0];p.text=s;p.font.name=font;p.font.size=Pt(size);p.font.bold=b;p.font.color.rgb=color(c)
 if align is not None:p.alignment=align
 return sh
def slide(k,title,sub=''):
 sl=r.slides.add_slide(r.slide_layouts[6]);sl.background.fill.solid();sl.background.fill.fore_color.rgb=color(BG)
 txt(sl,.55,.22,10.8,.2,k.upper(),10,CYAN,True);txt(sl,.55,.54,11.5,1.0 if '\n' in title else .55,title,27,CYAN,True)
 imgfit(sl,assets+'/ptit.png',11.8,.13,.85,.85)
 if sub:txt(sl,.56,1.16,12.0,.42,sub,13,MUTED)
 box(sl,.55,7.16,12.2,.012,'CFDCE3',False);txt(sl,.55,7.22,11,.17,'AIR QUALITY SUPERVISION SYSTEM  /  BÁO CÁO CUỐI KỲ',8,MUTED);txt(sl,12.3,7.18,.4,.2,str(len(r.slides)).zfill(2),9,MUTED,align=PP_ALIGN.RIGHT)
 return sl
def card(sl,x,y,w,h,label,body,accent=CYAN):
 box(sl,x,y,w,h);txt(sl,x+.2,y+.2,w-.4,.34,label,16,accent,True);txt(sl,x+.2,y+.7,w-.4,h-.8,body,14,WHITE)
def imgfit(sl,path,x,y,w,h):
 im=Image.open(path);iw,ih=im.size;ratio=min(w/iw,h/ih);nw=iw*ratio;nh=ih*ratio;sl.shapes.add_picture(path,Inches(x+(w-nw)/2), Inches(y+(h-nh)/2),width=Inches(nw),height=Inches(nh))
def arrow(sl,x,y):txt(sl,x,y,.5,.4,'→',24,CYAN,True)
def caption(sl,s):txt(sl,.58,6.83,12.1,.23,s,9,MUTED)
# cover
s=slide('Đồ án cuối kỳ','')
txt(s,.7,1.75,11.9,1.7,'HỆ THỐNG GIÁM SÁT\nCHẤT LƯỢNG KHÔNG KHÍ',32,CYAN,True,align=PP_ALIGN.CENTER)
txt(s,1.0,3.7,11.3,.7,'AIR QUALITY SUPERVISION SYSTEM',20,MUTED,True,align=PP_ALIGN.CENTER)
txt(s,1.0,5.75,11.3,.5,'BÁO CÁO ĐỒ ÁN CUỐI KỲ',15,WHITE,True,align=PP_ALIGN.CENTER)
# overview
s=slide('Tổng quan','Hệ thống làm được những gì?','Giám sát chất lượng không khí trong phòng và điều khiển quạt lọc theo số liệu đo.')
card(s,.6,1.8,5.85,2.05,'ĐO KHÔNG KHÍ','Thiết bị đo bụi PM2.5, CO₂, nhiệt độ và độ ẩm; gửi số liệu về máy chủ mỗi 5 giây.')
card(s,6.8,1.8,5.85,2.05,'ĐIỀU KHIỂN QUẠT','Máy chủ tính ngưỡng theo đặc điểm phòng; khi bụi vượt ngưỡng, hệ thống bật quạt lọc.',LIME)
card(s,.6,4.1,5.85,2.05,'THEO DÕI TRÊN WEB','Xem chỉ số mới nhất, biểu đồ theo thời gian, trạng thái quạt và lịch sử hoạt động.')
card(s,6.8,4.1,5.85,2.05,'DỰ BÁO BỤI','Chronos-2 dự báo PM2.5 trong 30 phút tới; kết quả được đối chiếu với số đo sau đó.',LIME)
# component slides
def hardware(title,image,name,description,details,role,reason,source):
 s=slide('Phần cứng',title)
 box(s,.6,1.65,4.1,4.65,'F1F5F8');imgfit(s,assets+'/'+image,.85,1.9,3.6,3.25);txt(s,.85,5.35,3.6,.5,name,16,CYAN,True,align=PP_ALIGN.CENTER)
 txt(s,5.0,1.68,7.6,.35,'THÀNH PHẦN',14,CYAN,True);txt(s,5.0,2.1,7.55,.77,description,16)
 txt(s,5.0,2.95,7.6,.35,'THÔNG SỐ SỬ DỤNG',14,CYAN,True);txt(s,5.0,3.35,7.55,.77,details,16)
 txt(s,5.0,4.22,7.6,.35,'VAI TRÒ TRONG HỆ THỐNG',14,CYAN,True);txt(s,5.0,4.62,7.55,.7,role,16)
 txt(s,5.0,5.46,7.6,.35,'LÝ DO LỰA CHỌN',14,CYAN,True);txt(s,5.0,5.86,7.55,.68,reason,16)
 caption(s,'Ảnh minh họa linh kiện: '+source+'; xem nguồn ảnh trong tài liệu đi kèm.')
hardware('Bộ điều khiển ESP32-S3','esp32.jpg','ESP32-S3 DevKitC-1','Vi điều khiển đặt tại phòng, kết nối các cảm biến và quạt lọc.','Hai lõi xử lý, Wi-Fi; các tác vụ được quản lý bằng FreeRTOS.','Đọc số đo, gửi dữ liệu về máy chủ và nhận lệnh để điều khiển quạt.','Có kết nối mạng và đủ chân giao tiếp cho cảm biến, màn hình và quạt.','Elektroweb')
hardware('Cảm biến bụi Sharp GP2Y1023AU0F','dust.jpg','Sharp GP2Y1023AU0F','Cảm biến bụi quang học, cung cấp tín hiệu để tính nồng độ PM2.5.','Đầu ra xung PWM nối GPIO 18; độ nhạy 14 µs/(µg/m³).','Cung cấp giá trị PM2.5 cho biểu đồ, tính trạng thái vượt ngưỡng và dự báo.','PM2.5 là thông số trực tiếp dùng để quyết định bật hoặc tắt quạt lọc.','Digi-Key / DeviceMart')
hardware('Cảm biến CO₂, nhiệt độ và độ ẩm SCD40','scd40.jpg','Sensirion SCD40','Cảm biến đo đồng thời CO₂, nhiệt độ và độ ẩm trong phòng.','Giao tiếp I²C, địa chỉ 0x62; đo định kỳ 5 giây một lần.','Cung cấp số liệu môi trường cho giao diện; CO₂ là một yếu tố tính ngưỡng bụi.','Bổ sung thông tin về tình trạng thông khí và điều kiện sinh hoạt trong phòng.','Electrokit')
# assembly
s=slide('Phần cứng','Sơ đồ kết nối thiết bị','Sơ đồ lắp ráp của nhóm: nguồn, cảm biến, màn hình và quạt được nối với bộ điều khiển.')
box(s,.6,1.72,7.7,4.8,'FFFFFF');imgfit(s,root+'/docs/slides/img/image.png',.68,1.8,7.54,4.64)
txt(s,8.6,1.8,4.05,.45,'CÁC KẾT NỐI CHÍNH',16,CYAN,True)
txt(s,8.6,2.38,4.05,3.9,'• Nguồn 12 V cấp cho quạt; mạch hạ áp cấp nguồn cho thiết bị.\n\n• SCD40 và OLED dùng chung đường I²C. Cảm biến bụi đưa xung về GPIO 18.\n\n• Quạt giữ nguồn 12 V; tốc độ được điều khiển qua chân PWM 25 kHz.\n\n• Màng HEPA H13 đặt trên đường khí ra để giữ hạt bụi.',14,WHITE)
caption(s,'Sơ đồ cũ ghi GP2Y1010: cảm biến dùng trong dự án là GP2Y1023AU0F, xuất tín hiệu PWM.')
# data
s=slide('Cảm biến','Cách tính các thông số không khí','Đo độ rộng xung từ cảm biến bụi; đọc trực tiếp ba chỉ số còn lại từ SCD40.')
box(s,.6,1.65,12.1,3.15)
txt(s,.85,1.82,11.5,.35,'1. NỒNG ĐỘ BỤI PM2.5 — SHARP GP2Y1023AU0F',15,CYAN,True)
# Sơ đồ xung được vẽ bằng các đường thẳng PowerPoint để dễ chỉnh sửa.
from pptx.enum.shapes import MSO_CONNECTOR
def line(x1,y1,x2,y2,col=CYAN,width=2.5):
 sh=s.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1), Inches(x2), Inches(y2));sh.line.color.rgb=color(col);sh.line.width=Pt(width)
txt(s,.85,2.38,.6,.25,'CAO',10,MUTED,True);txt(s,.85,3.18,.6,.25,'THẤP',10,MUTED,True)
for x1,y1,x2,y2 in [(1.5,2.48,2.25,2.48),(2.25,2.48,2.25,3.28),(2.25,3.28,4.0,3.28),(4.0,3.28,4.0,2.48),(4.0,2.48,5.15,2.48),(5.15,2.48,5.15,3.28),(5.15,3.28,5.75,3.28)]:line(x1,y1,x2,y2)
line(2.25,3.58,4.0,3.58,AMBER,1.5);line(2.25,3.48,2.25,3.68,AMBER,1.5);line(4.0,3.48,4.0,3.68,AMBER,1.5)
txt(s,2.48,3.68,1.65,.3,'W (µs)',13,AMBER,True,align=PP_ALIGN.CENTER)
txt(s,1.55,4.14,4.55,.35,'Cạnh xuống → đo W → cạnh lên',12,MUTED)
txt(s,6.25,2.35,5.9,.4,'PM2.5 = (W trung bình − W₀) / 14',17,WHITE,True)
txt(s,6.25,2.96,5.9,1.45,'W trung bình: độ rộng xung thấp trung bình trong 1 giây.\nW₀ = 1.400 µs mặc định; 14 là độ nhạy µs/(µg/m³).\nGiá trị được giới hạn 0–500 µg/m³; lỗi và bão hòa được gắn cờ.',13,WHITE)
box(s,.6,5.0,12.1,1.4)
txt(s,.85,5.16,11.4,.35,'2. CO₂, NHIỆT ĐỘ VÀ ĐỘ ẨM — SCD40',14,LIME,True)
txt(s,.85,5.58,11.3,.74,'SCD40 trả ba chỉ số qua I²C mỗi 5 giây. Firmware kiểm tra phạm vi và lấy trung bình tối đa 5 mẫu hợp lệ gần nhất; lượt đọc ngoài phạm vi bị bỏ qua.',14,WHITE)
caption(s,'Xung minh họa, không theo tỉ lệ; firmware đo W bằng thời gian giữa hai cạnh.')
# pipeline
s=slide('Truyền và xử lý dữ liệu','Từ số đo cảm biến đến giao diện','Một gói dữ liệu mới được thiết bị gửi định kỳ mỗi 5 giây.')
for i,(l,b) in enumerate([('1  THIẾT BỊ','Thu thập số đo, thời gian, mã máy và trạng thái cảm biến.'),('2  MQTT','Gửi gói JSON lên chủ đề telemetry của thiết bị.'),('3  MÁY CHỦ','Nhận, kiểm tra dữ liệu và loại bản tin trùng.'),('4  CƠ SỞ DỮ LIỆU','Lưu số đo và thời điểm đo; cập nhật trạng thái máy.')]):
 x=.55+i*3.2;card(s,x,1.9,2.86,2.45,l,b)
 if i<3:arrow(s,x+2.83,2.83)
box(s,.6,4.68,5.85,1.65)
txt(s,.82,4.84,5.4,.3,'ĐIỀU KHIỂN VÀ HIỂN THỊ',14,CYAN,True)
txt(s,.82,5.24,5.35,.85,'Máy chủ xét ngưỡng, gửi lệnh quạt khi cần; sau khi lưu, đẩy số đo mới tới web qua SSE.',13)
box(s,6.8,4.68,5.85,1.65)
txt(s,7.02,4.84,5.4,.3,'DỰ BÁO',14,LIME,True)
txt(s,7.02,5.24,5.35,.85,'Dịch vụ AI lấy lịch sử qua API, dự báo 30 phút và gửi kết quả về máy chủ để hiển thị.',13)
# control
s=slide('Điều khiển','Cơ chế kích hoạt quạt lọc','Máy chủ tính ngưỡng theo phòng và so sánh với nồng độ PM2.5 mới nhất.')
box(s,.6,1.65,12.1,2.55)
txt(s,.83,1.83,11.5,.35,'1. TÍNH NGƯỠNG KÍCH HOẠT QUẠT LỌC TỰ ĐỘNG',15,CYAN,True)
txt(s,.83,2.3,11.5,.45,'T = giới hạn [ 15 × √(24 / h) × fᵥ × fₜg × fCO₂ ] từ 10 đến 60',17,WHITE,True)
txt(s,.83,2.88,11.4,1.0,'h: số giờ sử dụng phòng/ngày; fᵥ: hệ số thể tích (căn bậc ba của V/20, giới hạn 0,7–1,6); fₜg: mức thông gió; fCO₂: hệ số theo CO₂. Ví dụ: h = 24 giờ, V = 20 m³, thông gió trung bình, CO₂ ≤ 420 ppm → T = 15 µg/m³.',13,WHITE)
box(s,.6,4.38,7.2,1.65)
txt(s,.83,4.56,6.75,.3,'2. SO SÁNH VÀ ĐIỀU KHIỂN',14,LIME,True)
txt(s,.83,4.98,6.7,.83,'PM2.5 ≥ T: bật mức 3. Nếu quạt đã chạy ≥ 5 phút và PM2.5 < 0,7T: tắt. Tự dừng sau tối đa 120 phút.',14,WHITE)
box(s,8.0,4.38,4.7,1.65)
txt(s,8.23,4.56,4.2,.3,'CƠ SỞ THAM CHIẾU',14,AMBER,True)
txt(s,8.23,4.98,4.2,.83,'WHO 2021: PM2.5 trung bình 24 giờ là 15 µg/m³. Các hệ số còn lại do đồ án thiết kế.',13,WHITE)
caption(s,'WHO: who.int/publications/i/item/9789240034228/  ·  Công thức: ThresholdService của đồ án.')
txt(s,.65,6.48,12,.28,'Công thức là quy tắc điều khiển thử nghiệm, không phải công thức hay ngưỡng tức thời của WHO.',10,MUTED)
# chart
s=slide('Giao diện','Biểu đồ bụi thực đo và dự báo','Theo dõi 30 phút đã qua và dự báo 30 phút tiếp theo trên cùng trục thời gian.')
box(s,.55,1.78,8.2,3.7,'F1F5F8');imgfit(s,assets+'/dashboard-chart.png',.65,1.87,8.0,3.5)
txt(s,9.04,1.78,3.65,.3,'CÁCH ĐỌC BIỂU ĐỒ',15,CYAN,True)
txt(s,9.04,2.23,3.7,3.5,'• Xanh: số đo PM2.5 của cảm biến trong 30 phút gần nhất.\n\n• Vàng nét đứt: giá trị trung vị được dự báo cho 30 phút tiếp theo.\n\n• Hai đường vàng mờ: phân vị 10% và 90%, thể hiện độ bất định.\n\n• Đỏ: ngưỡng dùng để điều khiển quạt.',13,WHITE)
box(s,.55,5.72,12.18,.82)
txt(s,.78,5.91,11.75,.48,'Mẫu mới được đẩy qua SSE; đường dự báo cập nhật khi dịch vụ AI gửi kết quả mới.',14,WHITE)
caption(s,'Ảnh chụp biểu đồ trên dashboard thực tế ngày 19/11/2026; đường dự báo không bảo đảm giá trị tương lai.')
# ai
s=slide('Dự báo bằng AI','Chronos-2 dự báo bụi như thế nào?','Mô hình dựa vào số đo đã có để ước tính PM2.5 trong 30 phút tiếp theo.')
box(s,.6,1.7,3.75,3.65)
txt(s,.82,1.9,3.3,.35,'1. NHẬN SỐ LIỆU',15,CYAN,True)
txt(s,.82,2.38,3.3,2.6,'Nhận lịch sử PM2.5; có thể dùng thêm CO₂, nhiệt độ, độ ẩm và mức quạt đã ghi nhận.\n\nTối đa 60 phút lịch sử, mỗi mẫu cách nhau 5 giây.',14,WHITE)
box(s,4.8,1.7,3.75,3.65)
txt(s,5.02,1.9,3.3,.35,'2. PHÂN TÍCH XU HƯỚNG',15,CYAN,True)
txt(s,5.02,2.38,3.3,2.6,'Mô hình chia chuỗi số liệu thành các đoạn nhỏ, xét sự thay đổi theo thời gian và mối liên hệ giữa các chỉ số.\n\nMô hình đã được huấn luyện sẵn.',14,WHITE)
box(s,9.0,1.7,3.75,3.65)
txt(s,9.22,1.9,3.3,.35,'3. ĐƯA RA DỰ BÁO',15,CYAN,True)
txt(s,9.22,2.38,3.3,2.6,'Trả về 360 giá trị, mỗi giá trị cách nhau 5 giây, tương ứng 30 phút tiếp theo.\n\nGiao diện vẽ đường dự báo nối sau số đo mới nhất.',14,WHITE)
arrow(s,4.34,3.15);arrow(s,8.55,3.15)
box(s,.6,5.65,12.1,.78)
txt(s,.85,5.85,11.5,.4,'Ví dụ: dữ liệu đến 08:00 → dự báo lần lượt từ 08:00:05 đến 08:30.',14,LIME,True)
caption(s,'Mô hình hiện dùng: Chronos-2 gốc. Các bước được trình bày rút gọn để giải thích hoạt động.')
# prep
s=slide('Dữ liệu dự báo','Chuẩn bị dữ liệu để dự báo khi vận hành','Số đo thực tế được lấy liên tục từ thiết bị và kiểm tra trước khi dự báo.')
card(s,.6,1.72,5.85,2.15,'1. THU THẬP VÀ LƯU TRỮ','Thiết bị gửi PM2.5, CO₂, nhiệt độ, độ ẩm và thời gian đo mỗi 5 giây. Máy chủ kiểm tra, loại bản tin trùng và lưu vào cơ sở dữ liệu.')
card(s,6.8,1.72,5.85,2.15,'2. LẤY LỊCH SỬ','Dịch vụ AI lấy 90 phút số đo và trạng thái quạt gần nhất; sau đó chỉ tải thêm bản ghi mới. Mô hình sử dụng tối đa 60 phút.',LIME)
card(s,.6,4.08,5.85,2.15,'3. LÀM SẠCH VÀ SẮP XẾP','Loại số đo bụi lỗi, bão hòa hoặc ngoài phạm vi; sắp xếp theo thời gian và đưa về các mốc cách 5 giây. Khoảng thiếu được giữ là thiếu.')
card(s,6.8,4.08,5.85,2.15,'4. KIỂM TRA TRƯỚC DỰ BÁO','Cửa sổ 60 phút cần ≥ 80% mẫu hợp lệ. Với lịch sử ngắn: tối thiểu 15 phút, độ phủ ≥ 90%. Mẫu mới nhất ≤ 15 giây; không có khoảng trống quá 5 phút.',AMBER)
caption(s,'Nếu chưa đủ điều kiện, dịch vụ AI chờ thêm dữ liệu; thời gian lấy mẫu của thiết bị có thể được cấu hình.')
# training
s=slide('Thử nghiệm huấn luyện','Dữ liệu dùng để huấn luyện bổ sung','Thử nghiệm dùng dữ liệu tổng hợp; dữ liệu dự báo khi vận hành được trình bày ở trang trước.')
box(s,.6,1.7,7.3,3.25,'F1F5F8');imgfit(s,assets+'/du-lieu-huan-luyen-mau.png',.92,1.89,6.65,2.83)
txt(s,.78,5.16,6.9,.55,'Mỗi dòng là một thời điểm, cách nhau 5 giây. Ảnh hiển thị 35 dòng trích từ dữ liệu tổng hợp.',13,MUTED)
txt(s,8.16,1.8,4.45,.35,'CÁCH THỰC HIỆN',15,CYAN,True)
txt(s,8.16,2.28,4.35,3.55,'1. Tạo dữ liệu mô phỏng các tình huống bụi, thông gió và hoạt động của quạt.\n\n2. Chia riêng các phiên cho huấn luyện, chọn mô hình và kiểm tra.\n\n3. Huấn luyện bổ sung từ Chronos-2 gốc, sau đó so sánh sai số với mô hình gốc.',14,WHITE)
box(s,.6,6.02,12.1,.58)
txt(s,.82,6.15,11.65,.27,'Kết quả: chưa đạt tiêu chí chọn mô hình; hệ thống đang dùng Chronos-2 gốc.',12,AMBER,True)
caption(s,'Nguồn: dữ liệu tổng hợp của dự án và kết quả thử nghiệm tại ai-service/models/chronos-lora/manifest.json.')
# compare
with open(assets + '/so-sanh-du-lieu-thuc.json', encoding='utf-8') as evidence:
 comparison = json.load(evidence)

s = slide('Thử nghiệm', 'Sai số dự báo PM2.5')
txt(s,.6,1.36,12.0,.31,'Điều kiện đo',16,CYAN,True)
txt(s,.6,1.82,12.0,.33,'Phòng ngủ 10 m³, ít thông gió, sử dụng 12 giờ/ngày. Ngưỡng bật quạt theo hồ sơ là 11,5 µg/m³.',14,WHITE)
txt(s,.6,2.23,12.0,.36,'Có hút thuốc trong phòng. Tại các mốc đối chiếu sau 30 phút, PM2.5 đo được từ 33,6 đến 92,5 µg/m³.',14,WHITE)

txt(s,.6,2.88,12.0,.32,'Sai số tuyệt đối trung bình (µg/m³)',16,CYAN,True)
table = s.shapes.add_table(5,5,Inches(.6),Inches(3.36),Inches(12.1),Inches(2.06)).table
for column, width in zip(table.columns, (1.75, 2.35, 2.82, 2.35, 2.83)):
 column.width = Inches(width)
for row in table.rows:
 row.height = Inches(.41)

def format_decimal(value, digits=2):
 return f'{value:.{digits}f}'.replace('.', ',')

# Kết quả trên tập kiểm tra mô phỏng đã tách riêng, theo manifest Chronos-2 (mục test).
synthetic_original = (1.5342, 2.7937, 3.8096)
synthetic_trained = (1.5278, 2.7206, 3.6694)
values = [
 ('Bộ dữ liệu', 'Số đo phòng ngủ · 10 mốc', '', 'Mô phỏng · 120 mốc kiểm tra', ''),
 ('Dự báo trước', 'Chronos-2 gốc', 'Sau huấn luyện bổ sung', 'Chronos-2 gốc', 'Sau huấn luyện bổ sung'),
]
for i, minutes in enumerate(comparison['horizons_minutes']):
 values.append((f'{minutes} phút',
                format_decimal(comparison['mae_ug_m3']['base'][i]),
                format_decimal(comparison['mae_ug_m3']['trained'][i]),
                format_decimal(synthetic_original[i]),
                format_decimal(synthetic_trained[i])))

table.cell(0,1).merge(table.cell(0,2))
table.cell(0,3).merge(table.cell(0,4))
for row_index, row in enumerate(values):
 for column_index, value in enumerate(row):
  cell = table.cell(row_index, column_index)
  if cell.is_spanned:
   continue
  cell.fill.solid()
  if row_index < 2:
   cell.fill.fore_color.rgb = color('E4EDF1')
  else:
   cell.fill.fore_color.rgb = color('F4F7F9' if row_index % 2 == 0 else BG)
  cell.vertical_anchor = MSO_ANCHOR.MIDDLE
  cell.margin_top = cell.margin_bottom = Pt(0)
  paragraph = cell.text_frame.paragraphs[0]
  paragraph.text = value
  paragraph.font.name = 'Noto Sans'
  paragraph.font.size = Pt(11 if row_index == 1 else 12)
  highlight = row_index >= 3 and column_index == 4
  paragraph.font.bold = row_index < 2 or highlight
  paragraph.font.color.rgb = color(CYAN if row_index < 2 else LIME if highlight else WHITE)

gains = [100 * (1 - tuned / original) for original, tuned in zip(synthetic_original, synthetic_trained)]
txt(s,.6,5.68,12.1,.33,
    f'Trên tập mô phỏng, sai số giảm {format_decimal(gains[1],1)}% ở mốc 15 phút và {format_decimal(gains[2],1)}% ở mốc 30 phút.',
    13,WHITE)
txt(s,.6,6.09,12.1,.35,'Trên số đo phòng ngủ đêm 19–20/11/2026, sai số hai phiên bản gần như nhau.',12,MUTED)
caption(s,'Khi demo trên lớp, điều kiện phòng khác nên dự báo có thể lệch so với số đo.')
# limits
s = slide('Kết luận', 'Kết quả và công việc tiếp theo')

conclusion_columns = [
 (.6, 'Hệ thống đã làm được gì', CYAN, [
  ('Đo và theo dõi chất lượng không khí',
   'Đo PM2.5, CO₂, nhiệt độ và độ ẩm. Trang web hiển thị số đo mới và trạng thái quạt.'),
  ('Bật và tắt quạt tự động',
   'Máy chủ tính ngưỡng theo hồ sơ phòng, bật quạt khi bụi đạt ngưỡng và tắt khi bụi giảm đủ thấp.'),
  ('Dự báo trước 30 phút',
   'Đã đối chiếu dự báo với số đo ở phòng ngủ tại ba mốc 5, 15 và 30 phút.'),
 ]),
 (6.82, 'Cần kiểm tra thêm', LIME, [
  ('Đo lâu hơn và ở nhiều phòng',
   'Thu số liệu trong nhiều ngày, ở phòng ngủ và lớp học có điều kiện thông gió khác nhau.'),
  ('Ghi lại lúc điều kiện phòng thay đổi',
   'Ghi thời điểm mở cửa, thay đổi số người hoặc xuất hiện nguồn bụi để biết khi nào dự báo dễ lệch.'),
  ('Đánh giá lại bằng số đo mới',
   'So sánh sai số ở từng mốc dự báo để xem hệ thống dùng tốt trong điều kiện nào và cần cải thiện ở đâu.'),
 ]),
]
for x, heading, accent, rows in conclusion_columns:
 txt(s,x,1.65,5.75,.39,heading,19,accent,True)
 box(s,x,2.19,5.75,.018,'CFDCE3',False)
 for y, (label, description) in zip((2.43,3.68,4.93),rows):
  txt(s,x,y,5.75,.32,label,15,WHITE,True)
  txt(s,x,y+.4,5.75,.66,description,14,MUTED)

# close
s=slide('16 / Kết thúc','Xin cảm ơn');txt(s,.6,2.1,9.8,1.15,'Xin cảm ơn thầy cô và các bạn đã lắng nghe.',26,CYAN,True);txt(s,.6,4.5,9,.5,'Hệ thống giám sát chất lượng không khí',16,MUTED)
r.save(out);print(out,len(r.slides))
