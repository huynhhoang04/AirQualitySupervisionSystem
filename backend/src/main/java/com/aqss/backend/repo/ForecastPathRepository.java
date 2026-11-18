package com.aqss.backend.repo;

import com.aqss.backend.model.ForecastPath;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ForecastPathRepository extends JpaRepository<ForecastPath, UUID> {

    /**
     * Paths old enough to delete, but only when every milestone of the run is already scored.
     * A pending point means the curve is still the evidence behind a number on the dashboard.
     */
    @Query("""
            select p.runId from ForecastPath p
             where p.anchorAt < :before
               and not exists (
                   select 1 from ForecastPoint f
                    where f.runId = p.runId and f.evaluationStatus = 'PENDING'
               )
             order by p.anchorAt asc
            """)
    List<UUID> findPrunable(@Param("before") Instant before, Pageable pageable);
}
