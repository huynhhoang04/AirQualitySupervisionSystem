package com.aqss.backend.service;

import com.aqss.backend.config.AqssSettings;
import com.aqss.backend.model.Device;
import com.aqss.backend.model.Space;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.SpaceRepository;
import com.aqss.backend.repo.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The closed loop (io_contract §10): compare the newest PM2.5 — and the forecast, when one is
 * fresh — against the space threshold, and drive the fan before the room is uncomfortable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlLoop {

    private final DeviceRepository devices;
    private final SpaceRepository spaces;
    private final TelemetryRepository telemetry;
    private final DeviceService deviceService;
    private final TelemetryService telemetryService;
    private final AiClient ai;
    private final AuditService audit;
    private final AqssSettings settings;

    @Scheduled(fixedDelayString = "${aqss.control.cycle-ms:60000}")
    public void tick() {
        AqssSettings.Control cfg = settings.control();
        Instant now = Instant.now();

        for (Device device : devices.findAll()) {
            if (device.getPairedAt() == null
                    || !"AUTO".equals(device.getFanMode())
                    || device.getSpaceCode() == null) {
                continue;
            }
            Space space = spaces.findById(device.getSpaceCode()).orElse(null);
            if (space == null) {
                continue;
            }

            Telemetry reading = telemetry.findFirstByDeviceIdOrderByRecordedAtDesc(device.getDeviceId())
                    .orElse(null);
            Double pm25 = reading == null || reading.getPm25() == null
                    ? null : reading.getPm25().doubleValue();
            boolean stale = reading != null
                    && reading.getRecordedAt().isBefore(now.minus(Duration.ofMinutes(cfg.staleReadingMinutes())));
            if (pm25 == null || stale) {
                continue;   // a stale reading must not drive the fan
            }

            boolean forecastBreach = forecastBreaches(device, space.getThresholdPm25(), now, cfg);

            Decision decision = decide(new Inputs(
                    pm25, space.getThresholdPm25(), "ON".equals(device.getFanState()),
                    device.getOnSince(), device.getOffSince(), forecastBreach, now,
                    cfg.minOnMinutes(), cfg.minOffMinutes(), cfg.maxOnMinutes(), cfg.hysteresisRatio()));

            switch (decision) {
                case ACTIVATE -> activate(device, space, pm25, forecastBreach ? "forecast_breach" : "threshold_breach");
                case DEACTIVATE -> deactivate(device, space, pm25);
                case NONE -> renewLeaseIfDue(device, now, cfg);
            }        }
    }

    private void activate(Device device, Space space, double pm25, String reason) {
        int level = ai.dosage(new AiClient.DosageRequest(space.getCode(), space.getVolumeM3(), space.getPurpose(),
                        (float) pm25, space.getThresholdPm25(), 35f, 40f))
                .map(AiClient.DosageResult::filterLevel)
                .orElse(2);
        deviceService.applyActuator(device.getDeviceId(), true, Math.max(1, Math.min(level, 3)), "AUTO", "BACKEND");
        deviceService.recordDecision(device, reason);
        log.info("{} PM2.5 {} vs {} -> fan ON level {} ({})", device.getDeviceId(), pm25,
                space.getThresholdPm25(), level, reason);

        if ("forecast_breach".equals(reason)) {
            warnAboutForecast(device, space, pm25);
        }
    }

    private void deactivate(Device device, Space space, double pm25) {
        String reason = device.getOnSince() != null
                && Duration.between(device.getOnSince(), Instant.now()).toMinutes()
                >= settings.control().maxOnMinutes()
                ? "max_on" : "hysteresis_off";
        deviceService.applyActuator(device.getDeviceId(), false, 0, "AUTO", "BACKEND");
        deviceService.recordDecision(device, reason);
        log.info("{} PM2.5 {} vs {} -> fan OFF ({})", device.getDeviceId(), pm25,
                space.getThresholdPm25(), reason);
    }

    /** Rule 2: tell the room early, once per 30 min, through the OLED. */
    private void warnAboutForecast(Device device, Space space, double pm25) {
        Instant lastAlert = audit.lastAlertAt(device.getDeviceId());
        if (lastAlert != null && Duration.between(lastAlert, Instant.now()).toMinutes() < 30) {
            return;
        }
        String message = String.format("Du bao: PM2.5 se vuot %.0f ug/m3", space.getThresholdPm25());
        deviceService.rawCommand(device.getDeviceId(), "display_warning",
                Map.of("message", message, "severity", "WARNING"));
        audit.log("BACKEND", "ALERT", device.getDeviceId(), Map.of(
                "reason", "forecast_breach",
                "threshold_pm25", space.getThresholdPm25(),
                "current_pm25", pm25));
    }

    private void renewLeaseIfDue(Device device, Instant now, AqssSettings.Control cfg) {
        if (!"ON".equals(device.getFanState())) {
            return;
        }
        Instant last = device.getLastCommandAt();
        if (last == null || Duration.between(last, now).toMinutes() >= cfg.leaseRenewMinutes()) {
            deviceService.renewLease(device);
        }
    }

    /** Any point within the horizon whose q90 reaches the threshold. */
    private boolean forecastBreaches(Device device, double threshold, Instant now, AqssSettings.Control cfg) {
        if (!ai.enabled()) {
            return false;
        }
        return telemetryService.newestPrediction(device.getDeviceId())
                .filter(prediction -> prediction.getPredictedAt()
                        .isAfter(now.minus(Duration.ofMinutes(cfg.forecastMaxAgeMinutes()))))
                .map(prediction -> {
                    Instant horizon = now.plus(Duration.ofMinutes(cfg.forecastHorizonMinutes()));
                    return telemetryService.forecastPoints(device.getDeviceId()).stream().anyMatch(point ->
                            point.targetTime() != null
                                    && point.targetTime().isAfter(now)
                                    && point.targetTime().isBefore(horizon)
                                    && point.pm25Q90() != null
                                    && point.pm25Q90() >= threshold);
                })
                .orElse(false);
    }

    // ── Pure decision table, so it can be unit-tested without Spring ──────────

    public record Inputs(double pm25, double threshold, boolean fanOn, Instant onSince, Instant offSince,
                         boolean forecastBreach, Instant now, int minOnMinutes, int minOffMinutes,
                         int maxOnMinutes, double hysteresisRatio) {
    }

    public static Decision decide(Inputs in) {
        if (!in.fanOn()) {
            if (!elapsed(in.offSince(), in.now(), in.minOffMinutes())) {
                return Decision.NONE;   // min OFF protects the motor
            }
            return (in.forecastBreach() || in.pm25() >= in.threshold())
                    ? Decision.ACTIVATE : Decision.NONE;
        }
        if (elapsed(in.onSince(), in.now(), in.maxOnMinutes())) {
            return Decision.DEACTIVATE;   // max ON protects the motor
        }
        boolean minOnElapsed = elapsed(in.onSince(), in.now(), in.minOnMinutes());
        return in.pm25() < in.threshold() * in.hysteresisRatio() && minOnElapsed
                ? Decision.DEACTIVATE : Decision.NONE;
    }

    private static boolean elapsed(Instant since, Instant now, int minutes) {
        return since == null || Duration.between(since, now).toMinutes() >= minutes;
    }

    public enum Decision {
        ACTIVATE, DEACTIVATE, NONE
    }
}
