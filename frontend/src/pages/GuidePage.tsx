import { Link } from "react-router-dom";

import { Icon } from "../components/Icon";

/**
 * The page a person needs when nothing works and there is nobody to ask.
 *
 * Written for someone holding a machine and a phone, not for someone reading a schematic: every
 * step says what they will see, and every failure mode says what to do about it.
 */
export function GuidePage() {
  return (
    <div className="mx-auto min-h-screen max-w-lg px-5 pb-20 pt-6">
      <header className="flex items-center gap-3">
        <Link to="/" className="rounded-lg p-1.5 text-muted hover:text-white" aria-label="Quay lại">
          <Icon name="back" />
        </Link>
        <div>
          <h1 className="text-[15px] font-medium">Hướng dẫn</h1>
          <div className="text-[10px] text-muted">từ lúc cắm điện đến lúc xem được số đo</div>
        </div>
      </header>

      <Section n="1" title="Cắm điện cho máy" aside="lần đầu">
        <p>
          Cắm máy vào ổ điện. Quạt sẽ chạy thử vài giây rồi dừng — đó là bình thường. Màn hình nhỏ
          trên máy sáng lên sau khoảng 3 giây.
        </p>
        <p className="mt-2">
          Nếu màn hình trắng hoặc không sáng, rút ra cắm lại. Nếu vẫn vậy, kiểm tra đầu cáp nguồn.
        </p>
      </Section>

      <Section n="2" title="Nối máy vào Wi-Fi" aside="chỉ một lần">
        <p>
          Lần đầu bật, máy phát ra một mạng Wi-Fi riêng tên dạng{" "}
          <Mono>AQSS-1A2B</Mono>, mật khẩu <Mono>aqss1234</Mono>. Màn hình máy hiện đúng tên và mật
          khẩu này.
        </p>

        <Steps
          items={[
            "Trên điện thoại, mở Cài đặt → Wi-Fi.",
            <>
              Chọn mạng <Mono>AQSS-xxxx</Mono>, nhập mật khẩu <Mono>aqss1234</Mono>.
            </>,
            "Điện thoại có thể báo “mạng không có internet” — cứ giữ kết nối.",
            <>
              Mở trình duyệt, vào <Mono>http://192.168.4.1</Mono>.
            </>,
            "Nhập tên và mật khẩu Wi-Fi nhà bạn, rồi nhập mật khẩu của máy chủ (nếu được hỏi).",
            "Bấm Lưu. Máy tự khởi động lại và kết nối Wi-Fi nhà bạn.",
          ]}
        />

        <Note>
          Sau bước này màn hình máy chuyển từ <Mono>AQSS SETUP</Mono> sang một mã 6 ký tự. Mã đó
          dùng để kết nối với trang web.
        </Note>
      </Section>

      <Section n="3" title="Kết nối với trang web" aside="một lần cho mỗi máy">
        <p>
          Mở trang web này trên điện thoại hoặc máy tính (cùng mạng Wi-Fi hoặc dùng 4G đều được).
          Bấm <b>Kết nối máy lọc</b>.
        </p>

        <Steps
          items={[
            <>
              Đọc mã 6 ký tự trên màn hình máy, ví dụ <Mono>X35AJK</Mono>.
            </>,
            "Nhập mã vào ô, bấm Tiếp tục.",
            "Chọn mẫu phòng (ngủ, khách, lớp học, sảnh) hoặc “Khác” để tự đặt tên.",
            "Đặt tên cho máy — tên này hiện trên danh sách máy.",
            "Kiểm tra lại thông tin, bấm Hoàn tất.",
          ]}
        />

        <Note>
          Màn hình máy chuyển từ mã sang số đo trong khoảng 10 giây. Nếu vẫn hiện mã, xem mục
          “Máy không hiện số” bên dưới.
        </Note>
      </Section>

      <Section n="4" title="Dùng hằng ngày" aside="">
        <p>
          Trang chủ liệt kê tất cả máy. Máy đang lọc nằm trên cùng, máy mất kết nối nằm dưới.
          Bấm vào một máy để xem số đo và điều khiển quạt.
        </p>

        <dl className="mt-3 space-y-2.5">
          <Term term="Tốt">Bụi dưới ngưỡng. Không cần làm gì.</Term>
          <Term term="Đang lọc">Quạt đang chạy. Dòng dưới nói vì sao và từ lúc nào.</Term>
          <Term term="Cần chú ý">Bụi đã trên ngưỡng và quạt chưa bật. Câu dưới nói còn bao lâu.</Term>
          <Term term="AUTO">Máy tự quyết định. Hệ thống kiểm tra mỗi 60 giây.</Term>
          <Term term="1 · 2 · 3">Bạn tự chọn mức. Máy giữ nguyên cho tới khi bạn đổi lại.</Term>
        </dl>

        <Note>
          Đổi sang một mức tay rồi quay lại AUTO thì hệ thống mới tiếp quản ở lần kiểm tra kế tiếp
          — tối đa 60 giây, và có thể lâu hơn 3 phút nếu quạt vừa tắt (máy cần thời gian nghỉ để
          bảo vệ động cơ).
        </Note>
      </Section>

      <Section n="5" title="Khi có sự cố" aside="tra nhanh">
        <div className="space-y-4">
          <Fail
            symptom="Màn hình hiện AQSS SETUP, không có mã"
            cause="Máy chưa vào được Wi-Fi."
            fix="Làm lại mục 2. Giữ nút trên máy 3 giây khi cấp nguồn để mở lại trang cài đặt."
          />
          <Fail
            symptom="Nhập mã nhưng báo “chưa có máy nào dùng mã này”"
            cause="Máy chưa gửi dữ liệu lên máy chủ — thường do Wi-Fi yếu hoặc chưa kết nối."
            fix="Đợi 10 giây rồi thử lại. Vẫn lỗi thì kiểm tra mạng Wi-Fi của máy, và xem màn hình máy có đang ở trạng thái mã hay không."
          />
          <Fail
            symptom="Trang web báo “không gửi dữ liệu”"
            cause="Máy đã mất kết nối Wi-Fi hoặc mất điện."
            fix="Kiểm tra máy còn bật không, và Wi-Fi nhà bạn còn hoạt động không. Khi máy kết nối lại, dữ liệu tự về."
          />
          <Fail
            symptom="Ngắt kết nối rồi nhưng trang web vẫn hiện máy"
            cause="Trang đang hiển thị dữ liệu cũ trong bộ nhớ tạm."
            fix="Tải lại trang. Nếu vẫn còn, bấm ngắt kết nối lại và đợi 5 giây."
          />
          <Fail
            symptom="Số đo không thay đổi"
            cause="Máy gửi 5 giây một lần; trang tự làm mới cùng nhịp."
            fix="Nếu quá 30 giây không đổi, coi như mất kết nối. Xem mục trên."
          />
          <Fail
            symptom="Số PM2.5 đứng ở ~393 µg/m³"
            cause="Cảm biến bụi đã bão hoà — không khí quá bẩn để đo chính xác."
            fix="Đây là giới hạn của cảm biến, không phải lỗi. Số thật còn cao hơn. Mở cửa thông gió."
          />
        </div>
      </Section>

      <Section n="6" title="Bảo trì" aside="vài tháng một lần">
        <p>
          Lõi lọc có tuổi thọ khoảng <b>2000 giờ chạy quạt</b>. Trang chủ hiện số giờ còn lại. Khi
          còn dưới 100 giờ, dòng đó chuyển màu vàng.
        </p>
        <p className="mt-2">
          Thay lõi xong, vào <b>Cài đặt</b> của máy và bấm <b>Đã thay lõi mới</b>. Đồng hồ đặt lại
          về 0 trên cả máy và trang web.
        </p>
        <p className="mt-2">
          Vệ sinh mặt trước cảm biến bụi mỗi 2–3 tháng bằng khăn khô. Không tháo cảm biến.
        </p>
      </Section>

      <div className="mt-10 rounded-xl border border-line/70 p-4 text-center">
        <p className="text-sm text-slate-300">Vẫn chưa được?</p>
        <Link to="/connect" className="btn-primary mt-3 w-full py-3">
          Thử kết nối lại
        </Link>
      </div>
    </div>
  );
}

function Section({
  n,
  title,
  aside,
  children,
}: {
  n: string;
  title: string;
  aside: string;
  children: React.ReactNode;
}) {
  return (
    <section className="mt-9 border-t border-line/60 pt-6">
      <div className="flex items-baseline gap-2">
        <span className="font-mono text-[11px] text-info">{n}</span>
        <h2 className="text-[15px] font-semibold tracking-tight">{title}</h2>
        {aside && <span className="ml-auto text-[10px] text-muted">{aside}</span>}
      </div>
      <div className="mt-3 text-sm leading-relaxed text-slate-300">{children}</div>
    </section>
  );
}

function Steps({ items }: { items: React.ReactNode[] }) {
  return (
    <ol className="mt-3 space-y-2">
      {items.map((item, index) => (
        <li key={index} className="flex gap-2.5">
          <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-line font-mono text-[10px] text-muted">
            {index + 1}
          </span>
          <span className="text-[13px] leading-relaxed text-slate-300">{item}</span>
        </li>
      ))}
    </ol>
  );
}

function Note({ children }: { children: React.ReactNode }) {
  return (
    <p className="mt-3 rounded-lg border border-line/60 bg-surface px-3 py-2.5 text-[12px] leading-relaxed text-muted">
      {children}
    </p>
  );
}

function Fail({ symptom, cause, fix }: { symptom: string; cause: string; fix: string }) {
  return (
    <div className="rounded-lg border border-line/60 px-3 py-2.5">
      <div className="flex items-start gap-2">
        <span className="mt-0.5 text-warn">
          <Icon name="alert" size={14} />
        </span>
        <div className="min-w-0">
          <div className="text-[13px] font-medium text-slate-200">{symptom}</div>
          <div className="mt-1 text-[12px] leading-relaxed text-muted">
            <span className="text-slate-400">Nguyên nhân:</span> {cause}
            <br />
            <span className="text-slate-400">Cách xử lý:</span> {fix}
          </div>
        </div>
      </div>
    </div>
  );
}

function Term({ term, children }: { term: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="inline font-mono text-[12px] text-slate-200">{term}</dt>
      <dd className="mt-0.5 text-[12px] leading-relaxed text-muted">{children}</dd>
    </div>
  );
}

function Mono({ children }: { children: React.ReactNode }) {
  return <span className="font-mono text-[12px] text-slate-200">{children}</span>;
}
