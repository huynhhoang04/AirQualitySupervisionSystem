package com.aqss.backend.repo;

import com.aqss.backend.model.ForecastPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ForecastPointRepository extends JpaRepository<ForecastPoint, Long> {

    List<ForecastPoint> findByRunIdOrderByHorizonMinutesAsc(UUID runId);
}
