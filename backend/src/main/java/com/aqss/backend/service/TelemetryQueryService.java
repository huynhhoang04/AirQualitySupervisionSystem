package com.aqss.backend.service;

import com.aqss.backend.config.AqssProperties;
import com.aqss.backend.dto.telemetry.AggregateRowDto;
import com.aqss.backend.dto.telemetry.HistoryPointDto;
import com.aqss.backend.dto.telemetry.TelemetryLatestDto;
import com.aqss.backend.entity.TelemetryData;
import com.aqss.backend.exception.BadRequestException;
import com.aqss.backend.exception.NotFoundException;
import com.aqss.backend.mapper.TelemetryMapper;
import com.aqss.backend.repository.TelemetryDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.aqss.backend.service.ai.dto.ForecastRequest;
import com.aqss.backend.service.ai.dto.ForecastRequest.SeriesPoint;

/** Read path for telemetry: latest (with derived AQI), history, aggregates, rolling median. */
@Service
@RequiredArgsConstructor
public class TelemetryQueryService {

    private static final Set<String> ALLOWED_INTERVALS = Set.of("hour", "day", "week", "month");

    private final TelemetryDataRepository telemetryRepository;
    private final NowCastService nowCastService;
    private final TelemetryMapper telemetryMapper;
    private final AqssProperties properties;

    @Transactional(readOnly = true)
    public TelemetryLatestDto latest(String deviceId) {
        TelemetryData latest = telemetryRepository.findLatestByDeviceId(deviceId)
                .orElseThrow(() -> NotFoundException.of("Telemetry", deviceId));

        List<Double> hourly = telemetryRepository.findHourlyAveragePm25ByDevice(
                deviceId, Instant.now().minus(12, ChronoUnit.HOURS));
        if (hourly.isEmpty()) {
            hourly = List.of((double) latest.getPm25());
        }
        int aqi = nowCastService.computeAqi(hourly);

        return new TelemetryLatestDto(
                latest.getDeviceId(),
                latest.getSpaceId(),
                latest.getId().getRecordedAt(),
                latest.getPm25(),
                latest.getCo2(),
                latest.getTemperature(),
                latest.getHumidity(),
                aqi,
                nowCastService.aqiLevel(aqi),
                SensorRanges.co2Alert(latest.getCo2()),
                latest.getWifiRssi());
    }

    @Transactional(readOnly = true)
    public List<HistoryPointDto> history(String deviceId, Instant start, Instant end, int limit) {
        Instant from = start != null ? start : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant to = end != null ? end : Instant.now();
        return telemetryRepository.findHistoryByDeviceId(deviceId, from, to, PageRequest.of(0, limit)).stream()
                .map(telemetryMapper::toHistoryPoint)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AggregateRowDto> aggregate(String spaceId, String interval, Instant start, Instant end) {
        String granularity = interval == null ? "hour" : interval.toLowerCase();
        if (!ALLOWED_INTERVALS.contains(granularity)) {
            throw new BadRequestException("Unsupported interval '" + interval + "' (hour|day|week|month)");
        }
        Instant from = start != null ? start : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant to = end != null ? end : Instant.now();
        return telemetryRepository.aggregateBySpaceId(spaceId, granularity, from, to).stream()
                .map(row -> new AggregateRowDto(row.getBucket(), row.getAvgPm25(), row.getAvgCo2(),
                        row.getAvgTemperature(), row.getAvgHumidity(), row.getSampleCount()))
                .toList();
    }

    /** Build a complete 24-hour, 15-minute median series when recent telemetry coverage is sufficient. */
    @Transactional(readOnly = true)
    public ForecastRequest forecastInput(String spaceCode, Instant now) {
        Instant until = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), 900) * 900);
        Instant since = until.minus(24, ChronoUnit.HOURS);
        List<Object[]> rows = telemetryRepository.findForecastBuckets(spaceCode, since, until);
        if (rows.isEmpty()) {
            return null;
        }

        List<SeriesPoint> points = new ArrayList<>(96);
        int present = 0;
        Instant latest = null;
        int rowIndex = 0;
        for (int bucket = 0; bucket < 96; bucket++) {
            Instant expected = since.plus(15L * bucket, ChronoUnit.MINUTES);
            if (rowIndex < rows.size() && bucketInstant(rows.get(rowIndex)[0]).equals(expected)) {
                Object[] row = rows.get(rowIndex++);
                double pm25 = ((Number) row[1]).doubleValue();
                points.add(new SeriesPoint(expected, (float) pm25));
                present++;
                latest = expected;
            } else {
                points.add(null);
            }
        }

        if (present != 96 || latest == null || latest.isBefore(now.minus(30, ChronoUnit.MINUTES))) {
            return null;
        }
        return new ForecastRequest(spaceCode, points);
    }

    private Instant bucketInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unsupported telemetry bucket type: " + value.getClass().getName());
    }

    /** Rolling median PM2.5 for a space — the value the control loop compares to the threshold. */
    @Transactional(readOnly = true)
    public Double rollingMedianPm25(String spaceCode) {
        Instant since = Instant.now().minus(properties.telemetry().rollingWindowMinutes(), ChronoUnit.MINUTES);
        return telemetryRepository.medianPm25Since(spaceCode, since);
    }
}
