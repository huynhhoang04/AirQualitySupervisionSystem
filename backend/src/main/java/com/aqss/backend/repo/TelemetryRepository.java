package com.aqss.backend.repo;

import com.aqss.backend.model.Telemetry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Incremental worker read: everything after a row id, oldest first.
     *
     * <p>Ids, not timestamps: two samples can share a millisecond, and a timestamp cursor would
     * either skip or repeat one of them.
     */
    List<Telemetry> findByDeviceIdAndIdGreaterThanOrderByIdAsc(String deviceId, Long afterId, Pageable pageable);

    /** Newest row id for a device, so a fresh worker can start from the present. */
    @Query("select max(t.id) from Telemetry t where t.deviceId = :deviceId")
    Long findMaxId(@Param("deviceId") String deviceId);
}
