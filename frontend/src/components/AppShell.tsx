import { useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";

import { useDevices } from "../hooks/useAqss";
import { Icon, type IconName } from "./Icon";

interface NavItem {
  to: string;
  label: string;
  icon: IconName;
  active?: (pathname: string) => boolean;
}

/**
 * Desktop-first shell: a fixed 240 px rail, a 64 px header, and a fluid content area. Under 1024 px
 * the rail becomes a drawer, because a phone still has to be able to read and control a room — it
 * just does not decide the layout.
 */
export function AppShell() {
  const location = useLocation();
  const devices = useDevices();
  const firstDevice = devices.data?.[0]?.device_id;
  const [open, setOpen] = useState(false);

  const roomHref = firstDevice ? `/room/${encodeURIComponent(firstDevice)}` : "/devices";
  const nav: NavItem[] = [
    { to: "/", label: "Tổng quan", icon: "overview", active: (p) => p === "/" },
    { to: roomHref, label: "Phân tích phòng", icon: "wind", active: (p) => p.startsWith("/room") },
    { to: "/ai", label: "Trung tâm AI", icon: "ai", active: (p) => p.startsWith("/ai") },
    { to: "/evaluation", label: "Thí nghiệm & đánh giá", icon: "chart", active: (p) => p.startsWith("/evaluation") },
    { to: "/devices", label: "Thiết bị & cấu hình", icon: "chip", active: (p) => p.startsWith("/devices") },
    { to: "/audit", label: "Nhật ký", icon: "list", active: (p) => p.startsWith("/audit") },
  ];

  const title = nav.find((item) => item.active?.(location.pathname))?.label ?? "Tổng quan";

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[240px_minmax(0,1fr)]">
      {open && (
        <button
          type="button"
          aria-label="Đóng menu"
          onClick={() => setOpen(false)}
          className="fixed inset-0 z-30 bg-black/50 lg:hidden"
        />
      )}

      <aside
        className={`fixed inset-y-0 left-0 z-40 flex w-[240px] flex-col border-r border-line/70 bg-surface/95 backdrop-blur transition-transform lg:sticky lg:top-0 lg:h-screen lg:translate-x-0 ${
          open ? "translate-x-0" : "-translate-x-full"
        }`}
      >
        <Link to="/" className="flex items-center gap-2.5 px-5 py-5" onClick={() => setOpen(false)}>
          <span className="flex h-9 w-9 items-center justify-center rounded-xl border border-line text-info">
            <Icon name="wind" size={19} />
          </span>
          <span className="min-w-0">
            <span className="block truncate text-[13px] font-semibold leading-tight">
              Air Quality
            </span>
            <span className="block truncate text-[13px] font-semibold leading-tight text-slate-300">
              Supervision System
            </span>
          </span>
        </Link>

        <nav className="mt-2 flex-1 space-y-0.5 px-3" aria-label="Điều hướng chính">
          {nav.map((item) => {
            const active = item.active?.(location.pathname) ?? location.pathname === item.to;
            return (
              <NavLink
                key={item.label}
                to={item.to}
                onClick={() => setOpen(false)}
                className={`flex items-center gap-3 rounded-lg px-3 py-2.5 text-[13px] transition-colors ${
                  active
                    ? "bg-white/[0.08] font-medium text-white"
                    : "text-slate-400 hover:bg-white/[0.04] hover:text-slate-200"
                }`}
              >
                <Icon name={item.icon} size={17} className={active ? "text-info" : ""} />
                {item.label}
              </NavLink>
            );
          })}
        </nav>

        <div className="border-t border-line/60 px-5 py-4 text-[10px] leading-relaxed text-muted">
          V6 · đo → học → dự báo → đánh giá
          <div className="mt-1">
            <Link to="/guide" className="hover:text-slate-300">
              Hướng dẫn
            </Link>
            <span className="mx-1.5">·</span>
            <Link to="/connect" className="hover:text-slate-300">
              Kết nối máy
            </Link>
          </div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-col">
        <header className="sticky top-0 z-20 flex h-16 items-center gap-3 border-b border-line/60 bg-canvas/90 px-4 backdrop-blur lg:px-8">
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="rounded-lg p-2 text-muted hover:text-white lg:hidden"
            aria-label="Mở menu"
          >
            <Icon name="menu" />
          </button>
          <h1 className="min-w-0 flex-1 truncate text-[15px] font-medium">{title}</h1>
          <div className="flex items-center gap-2">
            <Link to="/connect" className="btn-ghost px-3 py-2 text-[12px]">
              + Kết nối máy
            </Link>
          </div>
        </header>

        <main className="min-w-0 flex-1 px-4 pb-16 pt-6 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
