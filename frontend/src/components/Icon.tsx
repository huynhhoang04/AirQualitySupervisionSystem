/**
 * The eight shapes the product needs. One path each, no icon library — every mark is drawn for
 * where it sits, and every one is decorative (the label beside it carries the meaning).
 */
const PATHS = {
  wind: "M3 8h9a2.5 2.5 0 1 0-2.5-2.5M3 12h13a2.5 2.5 0 1 1-2.5 2.5M3 16h6",
  fan: "M10 10a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2zM10 7V3.2a2.6 2.6 0 0 1 0 5.2M10 13v3.8a2.6 2.6 0 0 1 0-5.2M7 10H3.2a2.6 2.6 0 0 1 5.2 0M13 10h3.8a2.6 2.6 0 0 1-5.2 0",
  filter: "M4 4h12l-4.5 6v5.5L8.5 17v-7L4 4z",
  back: "M12 5l-5 5 5 5",
  next: "M8 5l5 5-5 5",
  check: "M4 10.5l4 4 8-9",
  alert: "M10 3l7 13H3l7-13zM10 8v4M10 14.5v.5",
  close: "M5 5l10 10M15 5L5 15",
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({
  name,
  size = 18,
  className = "",
}: {
  name: IconName;
  size?: number;
  className?: string;
}) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      width={size}
      height={size}
      viewBox="0 0 20 20"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
