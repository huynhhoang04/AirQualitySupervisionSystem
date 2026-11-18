package com.aqss.backend.service;

import com.aqss.backend.config.AqssSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes forecast curves past their retention window (io_contract §14.1).
 *
 * <p>The table is sized by cadence, not by usage: 17,280 runs/day/device at 360 floats each is about
 * 24 MiB/day/device before metadata. Keeping the curve for 48 hours is enough to explain any number
 * on the dashboard; the three scored milestones survive longer in {@code forecast_points}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForecastRetention {

    private static final int BATCH = 500;

    private final AiService ai;
    private final AqssSettings settings;

    @Scheduled(initialDelay = 60_000, fixedDelayString = "${aqss.ai.retention-interval-ms:3600000}")
    public void tick() {
        long hours = settings.ai().pathRetentionHours();
        if (hours <= 0) {
            return;   // retention disabled explicitly
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(hours));
        int total = 0;
        // Bounded batches: one slow delete must not hold a transaction open for the whole backlog.
        for (int i = 0; i < 20; i++) {
            int deleted = ai.prunePaths(cutoff, BATCH);
            total += deleted;
            if (deleted < BATCH) {
                break;
            }
        }
        if (total > 0) {
            log.info("Forecast path retention removed {} curves older than {}h", total, hours);
        }
    }
}
