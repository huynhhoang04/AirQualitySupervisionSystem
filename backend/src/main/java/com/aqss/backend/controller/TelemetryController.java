package com.aqss.backend.controller;

import com.aqss.backend.dto.telemetry.AggregateRowDto;
import com.aqss.backend.dto.telemetry.HistoryPointDto;
import com.aqss.backend.dto.telemetry.TelemetryLatestDto;
import com.aqss.backend.service.TelemetryQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryQueryService telemetryQueryService;

    @GetMapping("/latest")
    public TelemetryLatestDto latest(@RequestParam("device_id") String deviceId) {
        return telemetryQueryService.latest(deviceId);
    }

    @GetMapping("/history")
    public List<HistoryPointDto> history(
            @RequestParam("device_id") String deviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @RequestParam(defaultValue = "500") int limit) {
        return telemetryQueryService.history(deviceId, start, end, limit);
    }

    @GetMapping("/aggregate")
    public List<AggregateRowDto> aggregate(
            @RequestParam("space_id") String spaceId,
            @RequestParam(defaultValue = "hour") String interval,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end) {
        return telemetryQueryService.aggregate(spaceId, interval, start, end);
    }
}
