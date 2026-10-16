# Layer 4 — Frontend Web Dashboard: Detailed Implementation Plan

> **Version:** 2.0 — Space-centric control dashboard
> **Stack:** React 18 · TypeScript · Vite · TailwindCSS · Zustand · TanStack Query · Recharts
> **Reference:** [aqss-concept-v2.md](./aqss-concept-v2.md) · [io_contract.md](./io_contract.md)
>
> **V2 changes:** Space replaces Device as the primary selector · Space Management + Space Control View pages · actuator AUTO/MANUAL panel · decision timeline · threshold band on charts · quantile forecast (both bands drawn) · **authentication actually attached** to REST and STOMP · mockup image swap fixed.

---

## 1. UI/UX Vision

The dashboard is the operator's window into the closed loop: what the room measures, what the system decided, and why. Design priorities: **data density, clarity of the decision, and instant situational awareness** — dark-mode-first, professional monitoring aesthetic.

### 1.1 Visual Mockups

#### Screen 1 — Main Real-Time Dashboard
Sticky header, AQI gauge (NowCast, display-only), sparkline cards (PM2.5, CO2, Temp, Hum), 24-h history, early-warning banner.

![Main Dashboard UI Mockup](ui_mockups/aqss_dashboard_main.jpg)

#### Screen 2 — Space Control View
PM2.5 vs space threshold (with uncertainty band), 24-h quantile forecast overlay, actuator AUTO/MANUAL panel, decision timeline.

![Forecasting UI Mockup](ui_mockups/aqss_forecasting_view.jpg)

> **V1 bug fixed:** the two mockup images were swapped relative to their captions.

### 1.2 What the operator sees

| Signal | Source | Meaning |
| :--- | :--- | :--- |
| Early-warning banner | `hours_to_breach ≤ 2` from the DL q90 forecast | "PM2.5 will breach the space threshold around 01:00" |
| Threshold line + band | `space_thresholds` (ML#1 + GP σ) | The space-specific limit, **not** a global constant |
| Fan state chip | `/topic/actuators/{id}` | ON/OFF, level, AUTO/MANUAL |
| Decision timeline | `GET /api/v1/spaces/{id}/decisions` | Every intervention with its trigger and dosage |
| CO2 advisory | `co2_alert` flag | Alert only — "open the window" (HEPA cannot remove CO2) |

---

## 2. Technology Stack

| Category | Technology | Rationale |
| :--- | :--- | :--- |
| Core | React 18 + Vite | Fast HMR, optimized builds |
| Language | TypeScript (strict) | Type-safe API/WS payloads |
| Styling | TailwindCSS v3 + `clsx`/`tailwind-merge` | Utility-first, dark mode |
| Client state | Zustand | Theme, sidebar, active space |
| Server state | TanStack Query | Caching, refetching, pagination |
| Real-time | `@stomp/stompjs` + `sockjs-client` | Spring Boot STOMP broker |
| Charts | Recharts | Composable SVG charts |
| Routing | React Router v6 | Nested layouts |

---

## 3. Routing

| Path | Component | Description |
| :--- | :--- | :--- |
| `/` | `Dashboard` | Real-time overview of the active Space + early warning |
| `/spaces` | `SpaceManagement` | CRUD spaces, threshold & dosage profile, assign nodes |
| `/spaces/:id` | `SpaceControlView` | PM2.5 vs threshold, forecast, actuator panel, decision timeline |
| `/devices` | `DeviceList` | All nodes, online/offline, firmware |
| `/devices/:id` | `DeviceDetail` | Node detail + history |
| `/forecasting` | `Forecasting` | Quantile forecast explorer |

### 3.1 Directory Layout (`frontend/src/`)

```text
frontend/src/
├── assets/
├── components/
│   ├── ui/            # Buttons, Inputs, Cards, Badge
│   ├── charts/        # AqiGauge, Pm25ThresholdChart, ForecastChart, Sparkline
│   ├── control/       # ActuatorPanel, DecisionTimeline, EarlyWarningBanner (NEW)
│   └── layout/        # Sidebar, Header, PageContainer
├── hooks/
│   ├── useStomp.ts          # STOMP connection + subscriptions (auth-aware)
│   ├── useAirQuality.ts     # TanStack Query hooks for Layer 2
│   ├── useSpaces.ts         # Space CRUD + threshold + decisions (NEW)
│   └── useForecast.ts
├── pages/             # Dashboard/ · Spaces/ · Devices/ · Forecasting/ · Settings/
├── store/useAppStore.ts
├── types/             # api.types.ts · telemetry.types.ts · space.types.ts
├── utils/             # colorMapping.ts · format.ts
├── App.tsx
└── main.tsx
```

---

## 4. Authentication (V1 shipped none — fixed)

Every REST call carries the JWT, and the STOMP CONNECT frame carries it too — otherwise the backend (which requires auth on both) rejects everything.

```typescript
// api/client.ts
import axios from 'axios';
import { useAppStore } from '../store/useAppStore';

export const api = axios.create({ baseURL: import.meta.env.VITE_API_BASE ?? '/api/v1' });

api.interceptors.request.use((config) => {
  const token = useAppStore.getState().accessToken;   // Zustand, not a module global
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});
```

```env
# .env.development
VITE_API_BASE=/api/v1
VITE_WS_URL=http://localhost:8080/ws
```

```ts
// vite.config.ts — dev proxy avoids the need for CORS in development
export default defineConfig({
  server: { proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } } },
});
```

> V1 hardcoded `http://localhost:8080/ws`, sent no `Authorization` header on REST, and set no STOMP `connectHeaders` — nothing would have authenticated.

---

## 5. State Management

### 5.1 Server state (TanStack Query)

```typescript
// hooks/useSpaces.ts
export const useSpace = (id: string) =>
  useQuery({
    queryKey: ['space', id],
    queryFn: async () => (await api.get<SpaceDetail>(`/spaces/${id}`)).data,
    staleTime: 60_000,
  });

export const useDecisions = (id: string) =>
  useQuery({
    queryKey: ['decisions', id],
    queryFn: async () => (await api.get<ControlDecision[]>(`/spaces/${id}/decisions`)).data,
  });
```

### 5.2 Real-time state (STOMP) — ref-based, as the strategy demands

V1's code contradicted its own text (it stored every 5-second message in `useState`, re-rendering the tree each time). V2 buffers into a ref and publishes through Zustand only at a throttled cadence.

```typescript
// hooks/useStomp.ts
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { useEffect, useRef } from 'react';
import { useAppStore } from '../store/useAppStore';

export const useStomp = (spaceId: string) => {
  const clientRef = useRef<Client | null>(null);
  const buffer = useRef<TelemetryPayload[]>([]);

  useEffect(() => {
    const token = useAppStore.getState().accessToken;
    const client = new Client({
      webSocketFactory: () => new SockJS(import.meta.env.VITE_WS_URL!),
      reconnectDelay: 5000,
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},   // REQUIRED by the backend
    });

    client.onConnect = () => {
      client.subscribe(`/topic/spaces/${spaceId}/decision`, (m) =>
        useAppStore.getState().pushDecision(JSON.parse(m.body)));
      client.subscribe(`/topic/actuators/${spaceId}`, (m) =>
        useAppStore.getState().pushActuatorState(JSON.parse(m.body)));
      client.subscribe(`/topic/devices/${spaceId}`, (m) => {
        buffer.current.push(JSON.parse(m.body));
      });
    };
    client.onDisconnect = () => useAppStore.getState().setConnected(false);

    // Throttle: commit buffered telemetry to the store once per second
    const flush = setInterval(() => {
      if (buffer.current.length) {
        useAppStore.getState().pushTelemetry(buffer.current.splice(0));
      }
    }, 1000);

    clientRef.current = client;
    client.activate();
    return () => { clearInterval(flush); client.deactivate(); };
  }, [spaceId]);
};
```

---

## 6. Key Screens

### 6.1 Space Management (`/spaces`)

- CRUD form: name, area, ceiling height, purpose, region, ventilation estimate.
- After save: shows the ML#1 threshold with its **GP uncertainty band** and the ML#2 dosage profile.
- Node assignment (`{device_id, role}`).
- "Recompute threshold" button (`POST /api/v1/spaces/{id}/threshold`, ADMIN).

### 6.2 Space Control View (`/spaces/:id`)

| Element | Data |
| :--- | :--- |
| PM2.5 chart with **threshold line + uncertainty band** | `space_thresholds` |
| Quantile forecast overlay (q10/q50/q90) | `/api/v1/forecast/{id}` |
| **ActuatorPanel**: AUTO/MANUAL toggle, level slider (0–3), state chip | `POST /spaces/{id}/actuator` + `/topic/actuators/{id}` |
| **DecisionTimeline**: trigger · threshold used · dosage · timestamp | `/spaces/{id}/decisions` |
| Early-warning banner | q90 breach within 2 h |

```tsx
// components/control/ActuatorPanel.tsx (excerpt)
const setActuator = (mode: 'AUTO' | 'MANUAL', state: 'ON' | 'OFF', level?: number) =>
  api.post(`/spaces/${spaceId}/actuator`, { mode, state, level });

// MANUAL only writes to the backend; the node's physical button always wins.
```

### 6.3 Dashboard

- Space selector (replaces the V1 device selector).
- AQI gauge — **NowCast, display-only** (the backend computes it; the device no longer does).
- CO2 advisory chip: `co2_alert === true` → "Open a window — filtration does not remove CO2".

---

## 7. Design System

### 7.1 Tailwind config — **`tailwind.config.ts` (ESM/TS), not CommonJS**

V1 shipped `tailwind.config.js` with `module.exports` in a Vite + TypeScript stack.

```ts
// tailwind.config.ts
import type { Config } from 'tailwindcss';

export default {
  theme: {
    extend: {
      colors: {
        aqi: {
          good: '#00E400', moderate: '#FFFF00', sensitive: '#FF7E00',
          unhealthy: '#FF0000', veryUnhealthy: '#8F3F97', hazardous: '#7E0023',
        },
        zinc: { 850: '#202022' },
      },
      fontFamily: {
        sans: ['Inter', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
    },
  },
} satisfies Config;
```

### 7.2 AQI colour mapping (boundary-exact)

```typescript
// utils/colorMapping.ts
export const getAqiColor = (aqi: number): string => {
  if (aqi <= 50) return 'text-aqi-good';
  if (aqi <= 100) return 'text-aqi-moderate';
  if (aqi <= 150) return 'text-aqi-sensitive';
  if (aqi <= 200) return 'text-aqi-unhealthy';
  if (aqi <= 300) return 'text-aqi-veryUnhealthy';
  return 'text-aqi-hazardous';
};
```

---

## 8. Charts

### 8.1 Forecast + threshold (both CI bands drawn)

V1 drew only `confidence_upper` — a one-sided "interval" that misleads. V2 draws the band with **both** bounds plus the space threshold line.

```tsx
const data = useMemo(() => mergeHistoryAndForecast(history, forecast), [history, forecast]);

<ComposedChart data={data}>
  <XAxis dataKey="timestamp" />
  <YAxis domain={[0, 'dataMax + 20']} />
  <Tooltip content={<CustomTooltip />} />

  {/* Space-specific threshold — the core V2 idea made visible */}
  <ReferenceLine y={threshold} stroke="#FF7E00" strokeDasharray="4 4"
                 label={{ value: `Threshold ${threshold} µg/m³`, position: 'insideTopRight' }} />
  {/* GP uncertainty band around the threshold */}
  <Area dataKey="threshold_upper" stroke="none" fill="#FF7E00" fillOpacity={0.08} />
  <Area dataKey="threshold_lower" stroke="none" fill="#FF7E00" fillOpacity={0.08} />

  {/* Historical (solid) */}
  <Line type="monotone" dataKey="pm25" stroke="#00E400" strokeWidth={2} dot={false} />

  {/* Forecast median (dashed) */}
  <Line type="monotone" dataKey="pm25_q50" stroke="#FF7E00" strokeDasharray="5 5" strokeWidth={2} dot={false} />

  {/* Quantile band — BOTH bounds */}
  <Area dataKey="pm25_q90" stroke="none" fill="#FF7E00" fillOpacity={0.12} />
  <Area dataKey="pm25_q10" stroke="none" fill="#FFFFFF" fillOpacity={0} />
</ComposedChart>
```

The merge keys the arrays on `timestamp`; missing quantile data renders as a gap, not zero.

---

## 9. Performance

1. **Code splitting** — `React.lazy()` for Recharts and route chunks.
2. **Throttled commits** — WS data buffered in a ref, flushed to Zustand at 1 Hz (§5.2).
3. **Memoization** — `useMemo` on history/forecast merge; `React.memo` on the sidebar.
4. **Virtualization** — `@tanstack/react-virtual` for the device list.

---

## 10. Pre-Deployment Verification Checklist

- [ ] STOMP auto-reconnects after a backend restart and re-authenticates.
- [ ] JWT is attached to every REST call and to the STOMP CONNECT frame.
- [ ] 401 responses route to login; refresh flow works.
- [ ] Dark/light toggle persists in `localStorage`.
- [ ] `getAqiColor` boundary cases pass (50/51, 100/101, 150/151).
- [ ] Forecast chart renders both quantile bands **and** the threshold line; gaps handled.
- [ ] Decision timeline shows the trigger, threshold and dosage for each event.
- [ ] MANUAL control round-trips to the node; the node's physical button overrides it.
- [ ] CO2 advisory shows when `co2_alert === true` and never triggers actuation.
- [ ] Lighthouse ≥ 90 on the production build (`npm run build && npm run preview`).
- [ ] Responsive: sidebar collapses to a hamburger; charts resize without clipped axes.
- [ ] Mockup images render on their correct screens.
