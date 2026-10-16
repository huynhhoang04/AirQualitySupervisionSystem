package com.aqss.backend.service;

import com.aqss.backend.config.AqssProperties;
import com.aqss.backend.entity.AlertType;
import com.aqss.backend.entity.ControlDecision;
import com.aqss.backend.entity.DecisionAction;
import com.aqss.backend.entity.DecisionMode;
import com.aqss.backend.entity.Severity;
import com.aqss.backend.entity.Space;
import com.aqss.backend.entity.SpaceThreshold;
import com.aqss.backend.service.rules.DosageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The brain. Every control cycle it compares the space's rolling PM2.5 median against the
 * space-derived threshold and the cached DL forecast, then actuates.
 *
 * <pre>
 * 1. pm25 ≥ threshold                 → ML#2 dosage → set_actuator ON   (ACTIVATE/threshold_breach)
 * 2. q90 forecast breach within 2 h   → display_warning                 (ALERT/forecast_breach_q90)
 * 3. fan ON and pm25 &lt; 0.7 × threshold → set_actuator OFF               (DEACTIVATE/hysteresis_off)
 * </pre>
 *
 * CO2 never actuates (HEPA cannot remove CO2). Manual override always wins.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlDecisionService {

    private static final double ALERT_HORIZON_HOURS = 2.0;

    private final SpaceService spaceService;
    private final ThresholdService thresholdService;
    private final DosageService dosageService;
    private final ForecastService forecastService;
    private final TelemetryQueryService telemetryQueryService;
    private final ActuatorService actuatorService;
    private final DecisionAuditService auditService;
    private final AlertService alertService;
    private final AqssProperties properties;

    @Scheduled(fixedDelayString = "${aqss.control.cycle-ms:60000}")
    public void evaluateAllSpaces() {
        for (Space space : spaceService.findAll()) {
            try {
                evaluate(space);
            } catch (Exception e) {
                log.error("Control cycle failed for space {}: {}", space.getCode(), e.getMessage(), e);
            }
        }
    }

    @Transactional
    public Optional<ControlDecision> evaluate(Space space) {
        SpaceThreshold threshold = thresholdService.getOrCompute(space);
        double thresholdValue = threshold.getBaseThresholdPm25();
        Double pm25 = telemetryQueryService.rollingMedianPm25(space.getCode());
        if (pm25 == null) {
            log.debug("No recent telemetry for space {} — skipping", space.getCode());
            return Optional.empty();
        }

        // 0. Safety: maximum continuous ON time.
        if (actuatorService.isOn(space)
                && actuatorService.onDurationMinutes(space) >= properties.control().maxOnMinutes()) {
            if (actuatorService.deactivate(space, true)) {
                return Optional.of(auditService.record(space, DecisionAction.DEACTIVATE, DecisionMode.AUTO,
                        "max_on_exceeded", pm25, thresholdValue, null, null));
            }
        }

        // 1. Hard breach → dose and actuate.
        if (pm25 >= thresholdValue) {
            DosageResult dosage = dosageService.compute(space, pm25, thresholdValue);
            if (actuatorService.activate(space, dosage)) {
                alertService.raise(space.getId(), null, AlertType.PM25_THRESHOLD, Severity.WARNING,
                        "PM2.5 %.1f exceeded the space threshold %.1f µg/m³".formatted(pm25, thresholdValue));
                return Optional.of(auditService.record(space, DecisionAction.ACTIVATE, DecisionMode.AUTO,
                        "threshold_breach", pm25, thresholdValue, dosage.level(), dosage.durationMinutes()));
            }
            return Optional.empty();
        }

        // 2. Early warning from the worst-case (q90) forecast.
        Optional<Forecast> forecast = forecastService.latestFor(space);
        if (forecast.isPresent()) {
            Double hoursToBreach = forecast.get().hoursUntilQ90Breach(thresholdValue);
            if (hoursToBreach != null && hoursToBreach <= ALERT_HORIZON_HOURS) {
                actuatorService.warn(space, hoursToBreach);
                return Optional.of(auditService.record(space, DecisionAction.ALERT, DecisionMode.ALERT,
                        "forecast_breach_q90", pm25, thresholdValue, null, null));
            }
        }

        // 3. Hysteresis release.
        if (actuatorService.isOn(space) && pm25 < thresholdValue * properties.control().hysteresisRatio()) {
            if (actuatorService.deactivate(space, false)) {
                return Optional.of(auditService.record(space, DecisionAction.DEACTIVATE, DecisionMode.AUTO,
                        "hysteresis_off", pm25, thresholdValue, null, null));
            }
        }

        return Optional.empty();
    }
}
