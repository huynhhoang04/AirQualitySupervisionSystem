package com.aqss.backend.repository;

import com.aqss.backend.entity.TelemetryData;
import com.aqss.backend.entity.TelemetryDataId;
import com.aqss.backend.repository.projection.TelemetryAggregateProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TelemetryDataRepository extends JpaRepository<TelemetryData, TelemetryDataId> {

    @Query(value = """
            SELECT * FROM telemetry_data
            WHERE device_id = :deviceId
            ORDER BY recorded_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<TelemetryData> findLatestByDeviceId(@Param("deviceId") String deviceId);

    @Query(value = """
            SELECT * FROM telemetry_data
            WHERE space_id = :spaceId
            ORDER BY recorded_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<TelemetryData> findLatestBySpaceId(@Param("spaceId") String spaceId);

    /** Rolling median PM2.5 over a space since a cutoff — robust to sensor glitches. */
    @Query(value = """
            SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY pm25)
            FROM telemetry_data
            WHERE space_id = :spaceId AND recorded_at >= :since
            """, nativeQuery = true)
    Double medianPm25Since(@Param("spaceId") String spaceId, @Param("since") Instant since);

    /** 15-minute median PM2.5 buckets for model input, oldest bucket first. */
    @Query(value = """
            SELECT date_bin(INTERVAL '15 minutes', recorded_at, TIMESTAMPTZ '1970-01-01 00:00:00+00') AS bucket,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY pm25) AS median_pm25,
                   count(*) AS sample_count
            FROM telemetry_data
            WHERE space_id = :spaceId AND recorded_at >= :since AND recorded_at < :until
              AND pm25 >= 0 AND pm25 <= 500
            GROUP BY bucket
            ORDER BY bucket ASC
            """, nativeQuery = true)
    List<Object[]> findForecastBuckets(@Param("spaceId") String spaceId,
                                       @Param("since") Instant since,
                                       @Param("until") Instant until);

    /** Hourly mean PM2.5 for one device, newest hour first — feeds the NowCast weighting. */
    @Query(value = """
            SELECT avg(pm25) FROM telemetry_data
            WHERE device_id = :deviceId AND recorded_at >= :since
            GROUP BY date_trunc('hour', recorded_at)
            ORDER BY date_trunc('hour', recorded_at) DESC
            """, nativeQuery = true)
    List<Double> findHourlyAveragePm25ByDevice(@Param("deviceId") String deviceId,
                                               @Param("since") Instant since);

    @Query(value = """
            SELECT * FROM telemetry_data
            WHERE device_id = :deviceId AND recorded_at BETWEEN :start AND :end
            ORDER BY recorded_at ASC
            """, nativeQuery = true)
    List<TelemetryData> findHistoryByDeviceId(@Param("deviceId") String deviceId,
                                              @Param("start") Instant start,
                                              @Param("end") Instant end,
                                              Pageable pageable);

    @Query(value = """
            SELECT * FROM telemetry_data
            WHERE space_id = :spaceId AND recorded_at BETWEEN :start AND :end
            ORDER BY recorded_at ASC
            """, nativeQuery = true)
    List<TelemetryData> findHistoryBySpaceId(@Param("spaceId") String spaceId,
                                             @Param("start") Instant start,
                                             @Param("end") Instant end,
                                             Pageable pageable);

    @Query(value = """
            SELECT date_trunc(:granularity, recorded_at) AS bucket,
                   avg(pm25)        AS avg_pm25,
                   avg(co2)         AS avg_co2,
                   avg(temperature) AS avg_temperature,
                   avg(humidity)    AS avg_humidity,
                   count(*)         AS sample_count
            FROM telemetry_data
            WHERE space_id = :spaceId AND recorded_at BETWEEN :start AND :end
            GROUP BY bucket
            ORDER BY bucket ASC
            """, nativeQuery = true)
    List<TelemetryAggregateProjection> aggregateBySpaceId(@Param("spaceId") String spaceId,
                                                          @Param("granularity") String granularity,
                                                          @Param("start") Instant start,
                                                          @Param("end") Instant end);
}
