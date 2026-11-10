# Layer 3 — Frontend Dashboard (as built)

> **Status:** implemented. Four screens. Stack: React 19 · TypeScript · Vite · TailwindCSS · TanStack Query.
> Contracts: [`io_contract.md`](./io_contract.md) §7.

---

## 1. The premise

The earlier dashboard was a data viewer: a device selector, a threshold form permanently open beside
the gauge, five buttons for four mutually exclusive fan states, and no answer to "why is the fan
running". It showed numbers well and helped nobody decide anything.

This version is organised around what a person holding a filter machine actually does:

| Moment | Screen | Question it answers |
| :--- | :--- | :--- |
| Power on | *(the OLED shows a code)* | — |
| Open the app | `/connect` | *How do I connect this machine?* |
| Every day | `/` — machines | *Which one needs attention?* |
| Tap a machine | `/room/:id` | *Is this room okay, and what is the system doing?* |
| Rarely | `/room/:id/settings` | *Change the threshold, the filter, or disconnect.* |
| When stuck | `/guide` | *Why is nothing working?* |

## 2. Screens

### 2.1 `/connect` — three steps

1. **The code.** One field, six characters, normalised as typed (uppercase, strips dashes). The
   button enables at exactly six. Below it: what to do when the OLED shows `AQSS SETUP` instead.
2. **Where.** Five room templates — bedroom, living room, classroom, lobby, other. Each shows the
   numbers it will use (**volume, hours occupied, suggested threshold**) so the choice is informed,
   not guessed. "Other" replaces the numbers with inputs for the operator's own.
3. **Confirm.** Code → name → template → threshold, then finish.

Errors stay on screen and say which code was not found; a wrong code never silently resets the form.

### 2.2 `/` — machines

One row per device, not per room. A node that lost its room, or never finished pairing, stays
visible. Sorted by urgency: filtering → running → fine → silent.

Each row: a status dot, the name, one sentence of state, and the current PM2.5. It fetches its own
reading, so one slow room never blocks the rest.

A failed request is not an empty account: it says the system is unreachable and offers a retry.

### 2.3 `/room/:id` — the room

```
Không khí
Đang lọc          92.7 µg/m³
tự bật 5 phút trước · vì bụi vượt ngưỡng   AUTO
[═══ scale with the threshold as a notch ═══]
[ Điều khiển quạt: AUTO | TẮT | 1 | 2 | 3 ]
[ CO₂ · Nhiệt độ · Độ ẩm ]
[ 24 h chart, 5-minute medians, spikes drawn separately ]
[ Lõi lọc: còn 1 999 giờ ]
```

The status word comes first, the number second, and one sentence says what the machine is doing and
why. The reason comes from `devices.last_reason`, which the loop writes on every decision.

The fan control is five mutually exclusive positions. AUTO compares on mode alone, because a fan can
be running under AUTO — a level comparison would leave nothing highlighted.

### 2.4 `/room/:id/settings`

- **Ngưỡng bật máy** — two modes. *Tự chọn* (three sensitivity positions) and *Tự động*, which
  previews the computed threshold and lists every factor that produced it: dose target, volume
  factor, ventilation factor, CO₂ factor. Applying it sets `threshold_source = profile`.
- **Lõi lọc** — hours remaining out of 2000, with a confirmed reset that clears the counter on both
  sides.
- **Máy lọc** — last contact, mode, state, and unpair as the only destructive action. It navigates
  back to the machines list, not into the connect flow.

### 2.5 `/guide` — the manual

Six sections: power, Wi-Fi setup through the SoftAP portal, connecting to the web, daily use,
troubleshooting, maintenance. Troubleshooting is written as **symptom → cause → fix**, covering the
six failures that actually happen: no code on the OLED, code not found, "not sending data", stale
page after unpair, unchanging readings, and a saturated dust sensor.

## 3. Implementation notes

| Concern | Decision |
| :--- | :--- |
| Wire format | **snake_case** everywhere. `ComputedThreshold` was once typed camelCase; `undefined.toFixed()` then unmounted the tree and showed a blank page. Types are copied from real responses now, and a `num()` helper formats defensively |
| Charts | Hand-drawn SVG, not a chart library. The series is thinned to ~800 points by the backend, and the viewBox is fixed so layout does not jump while data refetches |
| Chart semantics | 5-minute medians for trend; a one-sample spike is drawn as a **separate hairline**, so a dust mote cannot become a trend |
| Status vocabulary | `lib/room.ts` is the single place a number becomes a word: `Tốt` / `Đang lọc` / `Cần chú ý` |
| Fan control | Optimistic while the command is in flight; the server's answer is the truth once it lands |
| Polling | devices/latest 5 s, history 60 s, spaces 60 s — matched to the two rhythms of the system |
| Accessibility | Visible focus ring on every control, `aria-pressed` on toggles, `role="meter"` on the scale, `aria-live` on the status word, `prefers-reduced-motion` honoured |

## 4. Removed

| Removed | Why |
| :--- | :--- |
| Recharts / forecast panel | No forecast exists; the chart is 200 lines of SVG and 300 kB lighter |
| Audit table (JSON rows) | Replaced by one sentence on the room screen. The full trail stays in the API |
| Threshold form beside the gauge | Configuration moved to settings; the room screen only reads |
| Device selector in the header | The machines list *is* the selector |
| `device_id`, `telemetry_interval_ms`, `dust_baseline_us`, `wifi_rssi`, firmware, uptime in the UI | Operator-facing screens show what an operator acts on |
