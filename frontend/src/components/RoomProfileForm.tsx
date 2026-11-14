import { useState } from "react";

import type { Space } from "../api/types";
import { useSaveSpace } from "../hooks/useAqss";
import { ROOM_TEMPLATES } from "../lib/room";

export function RoomProfileForm({ space }: { space: Space }) {
  const preset = ROOM_TEMPLATES.find((item) => item.purpose === space.purpose) ?? ROOM_TEMPLATES[0];
  const [purpose, setPurpose] = useState(space.purpose ?? preset.purpose);
  const [volume, setVolume] = useState(space.volume_m3?.toString() ?? "");
  const [hours, setHours] = useState(space.occupancy_hours?.toString() ?? "");
  const [ventilation, setVentilation] = useState(space.ventilation_estimate ?? "");
  const save = useSaveSpace(space.code);
  const valid = Number.isFinite(Number(volume)) && Number(volume) > 0 &&
    Number.isInteger(Number(hours)) && Number(hours) >= 1 && Number(hours) <= 24 && ventilation !== "";
  const complete = space.volume_m3 != null && space.occupancy_hours != null && space.ventilation_estimate != null;

  return (
    <section className="card mt-6 p-5" aria-label="Hồ sơ tính ngưỡng">
      <h2 className="font-medium">Hồ sơ tính ngưỡng</h2>
      <p className="mt-1 text-sm leading-relaxed text-muted">
        {complete ? "Điều chỉnh thông số theo phòng thực tế." : "Hồ sơ đang thiếu thông số. Chọn mẫu để điền gợi ý, sau đó kiểm tra và lưu."}
        {" "}Thông số mẫu là giả định, có thể sửa. Lưu hồ sơ xong, xem và áp dụng ngưỡng bên dưới.
      </p>
      <div className="mt-4 flex flex-wrap gap-2">
        {ROOM_TEMPLATES.filter((item) => item.key !== "custom").map((item) => (
          <button key={item.key} type="button" className="btn-ghost px-3 py-2 text-sm"
            onClick={() => {
              setPurpose(item.purpose);
              setVolume(String(item.volumeM3));
              setHours(String(item.occupancyHours));
              setVentilation(item.ventilation);
            }}>
            Điền mẫu {item.label.toLowerCase()}
          </button>
        ))}
      </div>
      <form className="mt-4 grid gap-4 sm:grid-cols-2" onSubmit={(event) => {
        event.preventDefault();
        if (valid) save.mutate({ purpose, volume_m3: Number(volume), occupancy_hours: Number(hours), ventilation_estimate: ventilation });
      }}>
        <label className="text-sm">Công năng
          <select className="field mt-1.5 py-2" value={purpose} onChange={(event) => setPurpose(event.target.value)}>
            {[...new Set([...ROOM_TEMPLATES.map((item) => item.purpose), "office", "kitchen", "dorm", purpose])].map((value) => (
              <option key={value} value={value}>{ROOM_TEMPLATES.find((item) => item.purpose === value)?.label ?? ({office:"Văn phòng", kitchen:"Bếp", dorm:"Ký túc xá"}[value] ?? value)}</option>
            ))}
          </select>
        </label>
        <label className="text-sm">Thể tích (m³)
          <input className="field mt-1.5 py-2" type="number" min="0.1" step="any" required value={volume} onChange={(event) => setVolume(event.target.value)} />
        </label>
        <label className="text-sm">Giờ có người mỗi ngày
          <input className="field mt-1.5 py-2" type="number" min="1" max="24" step="1" required value={hours} onChange={(event) => setHours(event.target.value)} />
        </label>
        <label className="text-sm">Thông gió
          <select className="field mt-1.5 py-2" required value={ventilation} onChange={(event) => setVentilation(event.target.value)}>
            <option value="">Chọn mức thông gió</option><option value="low">Kín / ít thông gió</option>
            <option value="medium">Trung bình</option><option value="high">Thoáng / nhiều thông gió</option>
          </select>
        </label>
        <div className="sm:col-span-2">
          <button className="btn-primary" type="submit" disabled={!valid || save.isPending}>{save.isPending ? "Đang lưu…" : "Lưu hồ sơ phòng"}</button>
          {save.isSuccess && <span className="ml-3 text-sm text-good" role="status">Đã lưu. Có thể xem lại ngưỡng đề xuất.</span>}
          {save.isError && <p className="mt-2 text-sm text-bad" role="alert">{save.error.message}</p>}
        </div>
      </form>
    </section>
  );
}
