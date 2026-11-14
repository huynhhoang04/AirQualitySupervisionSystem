package com.aqss.backend.repo;

import com.aqss.backend.model.ForecastRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ForecastRunRepository extends JpaRepository<ForecastRun, UUID> {

    Optional<ForecastRun> findFirstByDeviceIdOrderByIssuedAtDesc(String deviceId);

    Optional<ForecastRun> findByDeviceIdAndModelVersionAndInputCutoff(
            String deviceId, String modelVersion, java.time.Instant inputCutoff);

    List<ForecastRun> findByDeviceIdAndIssuedAtBetweenOrderByIssuedAtAsc(
            String deviceId, java.time.Instant from, java.time.Instant to);
}
