package com.aqss.backend.repo;

import com.aqss.backend.model.ForecastPoint;
import com.aqss.backend.service.ScoredForecastPoint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ForecastPointRepository extends JpaRepository<ForecastPoint, Long> {

    List<ForecastPoint> findByRunIdOrderByHorizonMinutesAsc(UUID runId);

    /** Oldest pending points first, so a backlog left by downtime is scored or expired quickly. */
    List<ForecastPoint> findByEvaluationStatusAndTargetAtBeforeOrderByTargetAtAsc(
            String evaluationStatus, Instant before, Pageable pageable);

    /**
     * Per-horizon accuracy for one device and model cohort inside a window. One query instead of
     * loading every point: at a 5 s cadence the window holds tens of thousands of rows.
     */
    @Query("""
            select p.horizonMinutes as horizonMinutes,
                   sum(case when p.evaluationStatus = 'SCORED' then 1 else 0 end) as matched,
                   avg(case when p.evaluationStatus = 'SCORED' then abs(p.error) else null end) as mae,
                   sum(case when p.evaluationStatus = 'SCORED' and abs(p.error) <= :tolerance
                            then 1 else 0 end) as within,
                   sum(case when p.evaluationStatus = 'PENDING' then 1 else 0 end) as pending,
                   sum(case when p.evaluationStatus = 'UNSCORED_MISSING_TARGET' then 1 else 0 end) as missing,
                   sum(case when p.evaluationStatus = 'LATE_SUBMISSION' then 1 else 0 end) as late
              from ForecastPoint p, ForecastRun r
             where p.runId = r.id
               and r.deviceId = :deviceId
               and r.modelVersion = :modelVersion
               and p.targetAt >= :from and p.targetAt < :to
             group by p.horizonMinutes
            """)
    List<HorizonAggregate> aggregate(@Param("deviceId") String deviceId,
                                     @Param("modelVersion") String modelVersion,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     @Param("tolerance") float tolerance);

    /**
     * Newest scored pairs for the drawer.
     *
     * <p>The horizon filter is part of the query, not applied after the page: filtering a page of
     * 20 mixed rows down to one horizon left two or three usable rows and made the drawer look
     * empty while the cohort held hundreds.
     */
    @Query("""
            select new com.aqss.backend.service.ScoredForecastPoint(
                   p.targetAt, p.horizonMinutes, p.pm25, p.actualPm25, p.error,
                   r.issuedAt, r.modelVersion, p.evaluatedAt)
              from ForecastPoint p, ForecastRun r
             where p.runId = r.id
               and r.deviceId = :deviceId
               and r.modelVersion = :modelVersion
               and p.evaluationStatus = 'SCORED'
               and p.targetAt >= :from
               and (:horizon IS NULL OR p.horizonMinutes = :horizon)
             order by p.targetAt desc
            """)
    List<ScoredForecastPoint> recentScored(@Param("deviceId") String deviceId,
                                           @Param("modelVersion") String modelVersion,
                                           @Param("from") Instant from,
                                           @Param("horizon") Integer horizon,
                                           Pageable pageable);

    interface HorizonAggregate {
        Integer getHorizonMinutes();

        Long getMatched();

        Double getMae();

        Long getWithin();

        Long getPending();

        Long getMissing();

        Long getLate();
    }
}
