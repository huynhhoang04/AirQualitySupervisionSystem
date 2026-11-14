package com.aqss.backend.api;

import com.aqss.backend.service.TelemetryAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Bucketed chart data, a range summary and paginated raw samples. */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class TelemetryAnalyticsController {

    private final TelemetryAnalyticsService analytics;

    @GetMapping("/{deviceId}/series")
    public TelemetryAnalyticsService.Series series(
            @PathVariable String deviceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "5m") String bucket) {
        return analytics.series(deviceId, from, to, bucket);
    }

    @GetMapping("/{deviceId}/summary")
    public TelemetryAnalyticsService.Summary summary(
            @PathVariable String deviceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "threshold", required = false) Float threshold) {
        return analytics.summary(deviceId, from, to, threshold);
    }

    /** Raw rows for the data table. Cursor-based so new arrivals do not shift a page. */
    @GetMapping("/{deviceId}/samples")
    public TelemetryAnalyticsService.SamplePage samples(
            @PathVariable String deviceId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String cursor) {
        return analytics.samples(deviceId, from, to, limit, cursor);
    }
}
