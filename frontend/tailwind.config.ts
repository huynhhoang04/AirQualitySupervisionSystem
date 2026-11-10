import type { Config } from "tailwindcss";

export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        // A quiet, slightly blue-shifted dark surface. The status colours carry all the emphasis,
        // so the chrome around them stays almost colourless.
        canvas: "#0C1016",
        surface: "#151A22",
        line: "#2A323E",
        muted: "#8A96A8",
        good: "#4ADE80",
        warn: "#FBBF24",
        bad: "#FB7185",
        info: "#7DD3FC",
      },
      fontFamily: {
        sans: ["Instrument Sans", "system-ui", "sans-serif"],
        mono: ["JetBrains Mono", "ui-monospace", "monospace"],
      },
      keyframes: {
        breathe: { "0%, 100%": { opacity: "1" }, "50%": { opacity: "0.55" } },
      },
      animation: { breathe: "breathe 3s ease-in-out infinite" },
    },
  },
  plugins: [],
} satisfies Config;
