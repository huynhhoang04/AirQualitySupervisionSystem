import { useEffect, useRef, useState } from "react";

import { api } from "../api/client";
import type {
  ForecastView,
  LiveActuator,
  LiveEventEnvelope,
  LiveMetrics,
  LiveReset,
  Telemetry,
} from "../api/types";

const WINDOW_MS = 30 * 60_000;
const RETRY_MS = 5_000;

export type LiveStatus = "loading" | "live" | "reconnecting" | "error";

export interface LiveRoom {
  deviceId: string | null;
  samples: Telemetry[];
  forecast: ForecastView | null;
  actuator: LiveActuator | null;
  /** Increments on every scored target, so the accuracy panel can refetch without a poll. */
  metricsVersion: number;
  cursor: number;
  status: LiveStatus;
  error: string | null;
}

const EMPTY: LiveRoom = {
  deviceId: null,
  samples: [],
  forecast: null,
  actuator: null,
  metricsVersion: 0,
  cursor: 0,
  status: "loading",
  error: null,
};

/**
 * One snapshot followed by an SSE stream. The browser owns reconnection; the cursor returned by
 * the snapshot lets the server replay a packet that landed between the two requests.
 */
export function useLiveRoom(deviceId?: string): LiveRoom {
  const [room, setRoom] = useState<LiveRoom>(EMPTY);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!deviceId) {
      return;
    }
    const id = deviceId;
    let cancelled = false;
    let retry: number | null = null;

    const close = () => {
      sourceRef.current?.close();
      sourceRef.current = null;
    };

    const scheduleRetry = () => {
      if (cancelled || retry != null) {
        return;
      }
      retry = window.setTimeout(() => {
        retry = null;
        void resync();
      }, RETRY_MS);
    };

    function resync() {
      close();
      return api
        .liveSnapshot(id)
        .then((snapshot) => {
          if (cancelled) {
            return;
          }
          setRoom({
            deviceId: id,
            samples: snapshot.samples,
            forecast: snapshot.forecast,
            actuator: null,
            metricsVersion: 0,
            cursor: snapshot.cursor,
            status: "live",
            error: null,
          });
          connect(snapshot.cursor);
        })
        .catch((error: unknown) => {
          if (cancelled) {
            return;
          }
          setRoom((prev) => ({
            ...prev,
            status: prev.samples.length > 0 ? "reconnecting" : "error",
            error: error instanceof Error ? error.message : "Không tải được dữ liệu trực tiếp",
          }));
          scheduleRetry();
        });
    }

    function connect(cursor: number) {
      close();
      const source = new EventSource(api.liveEventsUrl(id, cursor));
      sourceRef.current = source;

      source.onopen = () => {
        if (!cancelled) {
          setRoom((prev) => ({ ...prev, status: "live", error: null }));
        }
      };
      source.onerror = () => {
        if (!cancelled) {
          setRoom((prev) => ({ ...prev, status: "reconnecting" }));
        }
      };
      source.addEventListener("telemetry", (event) => {
        const envelope = parse<Telemetry>(event);
        if (!cancelled && envelope) {
          setRoom((prev) => applyTelemetry(prev, envelope.payload));
        }
      });
      source.addEventListener("forecast", (event) => {
        const envelope = parse<ForecastView>(event);
        if (!cancelled && envelope) {
          setRoom((prev) => applyForecast(prev, envelope.payload));
        }
      });
      source.addEventListener("actuator", (event) => {
        const envelope = parse<LiveActuator>(event);
        if (!cancelled && envelope) {
          setRoom((prev) => ({ ...prev, actuator: envelope.payload }));
        }
      });
      source.addEventListener("metrics", (event) => {
        const envelope = parse<LiveMetrics>(event);
        if (!cancelled && envelope) {
          setRoom((prev) => ({ ...prev, metricsVersion: prev.metricsVersion + 1 }));
        }
      });
      source.addEventListener("reset", (event) => {
        if (cancelled) {
          return;
        }
        const envelope = parse<LiveReset>(event);
        setRoom((prev) => ({
          ...prev,
          cursor: envelope?.payload.cursor ?? prev.cursor,
          status: "reconnecting",
        }));
        void resync();
      });
    }

    void resync();

    return () => {
      cancelled = true;
      if (retry != null) {
        window.clearTimeout(retry);
      }
      close();
    };
  }, [deviceId]);

  // A route change must not flash the previous room's samples before the new snapshot arrives.
  if (room.deviceId !== (deviceId ?? null)) {
    return { ...EMPTY, deviceId: deviceId ?? null };
  }
  return room;
}

function parse<T>(event: Event): LiveEventEnvelope<T> | null {
  if (!(event instanceof MessageEvent)) {
    return null;
  }
  try {
    return JSON.parse(event.data) as LiveEventEnvelope<T>;
  } catch {
    return null;
  }
}

function applyTelemetry(room: LiveRoom, sample: Telemetry): LiveRoom {
  if (room.samples.some((item) => item.id === sample.id)) {
    return room;
  }
  const samples = [...room.samples, sample].sort(
    (a, b) => Date.parse(a.recorded_at) - Date.parse(b.recorded_at) || a.id - b.id,
  );
  const anchor = Date.parse(samples[samples.length - 1].recorded_at);
  return {
    ...room,
    samples: samples.filter((item) => Date.parse(item.recorded_at) >= anchor - WINDOW_MS),
    status: "live",
    error: null,
  };
}

function applyForecast(room: LiveRoom, forecast: ForecastView): LiveRoom {
  if (room.forecast) {
    const incomingAccepted = Date.parse(forecast.run.accepted_at);
    const currentAccepted = Date.parse(room.forecast.run.accepted_at);
    if (incomingAccepted < currentAccepted) {
      return room;
    }
    // A buffered run can be accepted later while its input is older; it must not move the anchor
    // backwards, even though the browser received it last.
    if (Date.parse(forecast.run.input_cutoff) < Date.parse(room.forecast.run.input_cutoff)) {
      return room;
    }
  }
  return { ...room, forecast, status: "live", error: null };
}
