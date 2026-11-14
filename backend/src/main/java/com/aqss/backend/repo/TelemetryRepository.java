package com.aqss.backend.repo;

import com.aqss.backend.model.Telemetry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TelemetryRepository extends JpaRepository<Telemetry, Long> {

    Optional<Telemetry> findFirstByDeviceIdOrderByRecordedAtDesc(String deviceId);

    List<Telemetry> findByDeviceIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            String deviceId, Instant from, Instant to);

    List<Telemetry> findByDeviceIdAndRecordedAtGreaterThanEqualAndRecordedAtLessThanOrderByRecordedAtAscIdAsc(
            String deviceId, Instant from, Instant to);

    List<Telemetry> findByIdInOrderByRecordedAtAscIdAsc(Collection<Long> ids);

    boolean existsByDeviceIdAndPayloadHash(String deviceId, String payloadHash);
}
