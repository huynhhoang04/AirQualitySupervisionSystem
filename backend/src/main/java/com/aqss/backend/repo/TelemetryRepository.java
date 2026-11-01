package com.aqss.backend.repo;

import com.aqss.backend.model.Telemetry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    Optional<Telemetry> findFirstByDeviceIdOrderByRecordedAtDesc(String deviceId);

    List<Telemetry> findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            String deviceId, Instant from, Instant to);
}
