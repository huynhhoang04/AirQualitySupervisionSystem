/**
 * A small inline icon set (hand-drawn paths, 20×20 grid). Icons are decorative: the visible label
 * or the button's aria-label carries the meaning, and every icon is aria-hidden.
 */

export type IconName =
  | "wind"
  | "thermometer"
  | "droplet"
  | "co2"
  | "bell"
  | "power"
  | "link"
  | "unlink"
  | "settings"
  | "chevronLeft"
  | "chevronRight"
  | "refresh"
  | "check"
  | "alert"
  | "plus"
  | "trash"
  | "clock"
  | "chart";

const PATHS: Record<IconName, string> = {
  wind: "M3 8h9a2.5 2.5 0 1 0-2.5-2.5M3 12h13a2.5 2.5 0 1 1-2.5 2.5M3 16h6",
  thermometer: "M10 3v9.5M10 3a1.5 1.5 0 0 1 1.5 1.5v8a3 3 0 1 1-3 0v-8A1.5 1.5 0 0 1 10 3z",
  droplet: "M10 3s5 5.2 5 8.5a5 5 0 0 1-10 0C5 8.2 10 3 10 3z",
  co2: "M4 15V7a3 3 0 0 1 5.2-2M16 5v10M12 10h8",
  bell: "M10 3a5 5 0 0 1 5 5v3l1.5 3H3.5L5 11V8a5 5 0 0 1 5-5zM8 17a2 2 0 0 0 4 0",
  power: "M10 3v7M6.5 5.5a6 6 0 1 0 7 0",
  link: "M8 12a4 4 0 0 1 0-5.7l2-2a4 4 0 0 1 5.7 5.7l-1 1M12 8a4 4 0 0 1 0 5.7l-2 2A4 4 0 0 1 4.3 10l1-1",
  unlink: "M8 12a4 4 0 0 1 0-5.7l1-1M12 8a4 4 0 0 1 0 5.7l-1 1M3 3l14 14",
  settings: "M10 12.5A2.5 2.5 0 1 0 10 7.5a2.5 2.5 0 0 0 0 5zM10 2v2M10 16v2M2 10h2M16 10h2M4.3 4.3l1.4 1.4M14.3 14.3l1.4 1.4M15.7 4.3l-1.4 1.4M5.7 14.3l-1.4 1.4",
  chevronLeft: "M12 5l-5 5 5 5",
  chevronRight: "M8 5l5 5-5 5",
  refresh: "M16 10a6 6 0 1 1-1.8-4.3M16 3v4h-4",
  check: "M4 10l4 4 8-8",
  alert: "M10 3l7 13H3l7-13zM10 8v4M10 14v.5",
  plus: "M10 4v12M4 10h12",
  trash: "M4 6h12M8 6V4h4v2M6 6l1 11h6l1-11",
  clock: "M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM10 6v4l3 2",
  chart: "M3 16l4-6 3 3 4-7 3 4",
};

interface Props {
  name: IconName;
  className?: string;
  size?: number;
}

export function Icon({ name, className = "", size = 18 }: Props) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
