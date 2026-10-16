package com.aqss.backend.service;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Partition + materialized-view lifecycle. Fixes the V1 audit findings: the partition function
 * was never invoked (inserts would fail from 2027-01) and {@code dropOldPartitions()} could drop
 * a partition still holding recent data.
 *
 * <p>Schedule and retention are configuration ({@code PARTITION_*} / {@code aqss.partitions.*}),
 * so a deployment can move the jobs or turn them off on a replica without a rebuild.
 */
@Service
@Slf4j
public class PartitionManagerService {

    private static final String TELEMETRY_TABLE = "telemetry_data";

    @PersistenceContext
    private EntityManager entityManager;

    private final int retentionDays;

    public PartitionManagerService(@Value("${aqss.partitions.retention-days:90}") int retentionDays) {
        if (retentionDays < 1) {
            // A negative/zero value would move the cutoff into a future month and drop live data.
            log.warn("aqss.partitions.retention-days={} is not positive; using 1 day instead", retentionDays);
            retentionDays = 1;
        }
        this.retentionDays = retentionDays;
    }

    /** Cover the next month at boot so a fresh deployment never fails an insert. */
    @PostConstruct
    @Transactional
    public void createPartitionsOnStartup() {
        try {
            createNextPartition();
        } catch (Exception e) {
            log.warn("Could not pre-create telemetry partition at startup: {}", e.getMessage());
        }
    }

    /** Keep one month ahead (default: 25th of every month, 03:00). */
    @Scheduled(cron = "${aqss.partitions.create-cron:0 0 3 25 * *}")
    @Transactional
    public void createNextPartition() {
        String created = (String) entityManager
                .createNativeQuery("SELECT create_partition_for((date_trunc('month', NOW()) + INTERVAL '1 month')::date)")
                .getSingleResult();
        if (created != null) {
            log.info("Created telemetry partition: {}", created);
        }
    }

    @Scheduled(cron = "${aqss.partitions.hourly-view-cron:0 */15 * * * *}")
    @Transactional
    public void refreshHourlyView() {
        refreshView("telemetry_hourly");
    }

    @Scheduled(cron = "${aqss.partitions.daily-view-cron:0 5 * * * *}")
    @Transactional
    public void refreshDailyView() {
        refreshView("telemetry_daily");
    }

    private void refreshView(String view) {
        try {
            entityManager.createNativeQuery("REFRESH MATERIALIZED VIEW CONCURRENTLY " + view).executeUpdate();
        } catch (Exception e) {
            log.warn("Refresh of {} failed: {}", view, e.getMessage());
        }
    }

    /**
     * Drops only WHOLE expired months (older than {@code aqss.partitions.retention-days}). V1
     * derived a partition name from {@code now − 90 days} and dropped it, which could delete
     * recent data.
     */
    @Scheduled(cron = "${aqss.partitions.drop-cron:0 0 2 1 * *}")
    @Transactional
    @SuppressWarnings("unchecked")
    public void dropExpiredPartitions() {
        String cutoff = YearMonth.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS).atZone(ZoneOffset.UTC))
                .toString().replace('-', '_');   // e.g. 2026_07

        List<String> names = entityManager.createNativeQuery("""
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class p ON p.oid = i.inhparent
                JOIN pg_class c ON c.oid = i.inhrelid
                WHERE p.relname = :parent
                  AND c.relname ~ '^telemetry_data_\\d{4}_\\d{2}$'
                  AND c.relname < :parent || '_' || :cutoff
                """)
                .setParameter("parent", TELEMETRY_TABLE)
                .setParameter("cutoff", cutoff)
                .getResultList();

        for (String name : names) {
            entityManager.createNativeQuery("DROP TABLE IF EXISTS " + name).executeUpdate();
            log.info("Dropped expired partition: {} (retention {} days)", name, retentionDays);
        }
    }
}
