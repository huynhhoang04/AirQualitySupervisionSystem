package com.aqss.backend.api;

import com.aqss.backend.model.Device;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.service.AiService;
import com.aqss.backend.service.DeviceService;
import com.aqss.backend.service.LiveEventService;
import com.aqss.backend.service.TelemetryService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;

/**
 * Initial state and the live stream for one room (io_contract §12).
 *
 * <p>The snapshot and the stream are deliberately separate: the snapshot is a normal JSON request
 * the client can retry, and the stream only carries changes after the returned cursor.
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class LiveController {

    private static final int WINDOW_SECONDS = 30 * 60;

    private final LiveEventService live;
    private final DeviceService devices;
    private final TelemetryService telemetry;
    private final AiService ai;

    /**
     * The initial state for the chart.
     *
     * <p>The cursor is taken **before** the sample query, not after. Anything published between the
     * two steps is then replayed by the stream; the client deduplicates by sample id, so a row that
     * appears in both the snapshot and the replay is drawn once. Taking the cursor after the query
     * (the old code) discarded exactly those events.
     */
    @GetMapping("/{deviceId}/live/snapshot")
    public LiveSnapshot snapshot(@PathVariable String deviceId) {
        Device device = devices.require(deviceId);
        long cursor = live.cursor(deviceId);
        Instant now = Instant.now();
        Instant anchor = telemetry.latestOptional(deviceId)
                .map(Telemetry::getRecordedAt)
                .orElse(now);
        List<Telemetry> samples = telemetry.recentWindow(deviceId,
                anchor.minusSeconds(WINDOW_SECONDS), anchor);
        return new LiveSnapshot(deviceId, now, cursor, WINDOW_SECONDS,
                samples, ai.latestForecast(deviceId).orElse(null), device);
    }

    @GetMapping(value = "/{deviceId}/live/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String deviceId,
            @RequestParam(name = "after", required = false) Long after,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            HttpServletResponse response) {
        Long cursor = parseCursor(lastEventId);
        if (cursor == null) {
            cursor = after;
        }
        // SseEmitter must be the declared return type for Spring's async return-value handler.
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        SseEmitter emitter = new SseEmitter(LiveEventService.EMITTER_TIMEOUT_MS);
        live.subscribe(deviceId, cursor, emitter);
        return emitter;
    }

    private static Long parseCursor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record LiveSnapshot(String deviceId, Instant serverTime, long cursor, int windowSeconds,
                               List<Telemetry> samples, AiService.ForecastView forecast, Device device) {
    }
}
