import type { Config } from "tailwindcss";

export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        // Semantic tokens from the design system: dark tech, status green. Components never use a
        // raw hex, so a palette change is one edit here.
        canvas: "#0F172A",
        surface: "#1B2336",
        muted: "#94A3B8",
        line: "#334155",
        good: "#22C55E",
        warn: "#F59E0B",
        bad: "#EF4444",
        info: "#38BDF8",
        ai: "#A78BFA",
      },
      fontFamily: {
        sans: ["Fira Sans", "system-ui", "sans-serif"],
        // Every number that can change while you look at it uses the mono face so digits do not
        // jitter and columns stay aligned.
        mono: ["Fira Code", "ui-monospace", "monospace"],
      },
      borderRadius: {
        xl: "0.875rem",
      },
      keyframes: {
        pulseSoft: {
          "0%, 100%": { opacity: "1" },
          "50%": { opacity: "0.45" },
        },
      },
      animation: {
        pulseSoft: "pulseSoft 2s ease-in-out infinite",
      },
    },
  },
  plugins: [],
} satisfies Config;
