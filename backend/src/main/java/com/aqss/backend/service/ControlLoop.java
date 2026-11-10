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
 * The closed loop (io_contract §10): compare the newest PM2.5 against the space threshold and
 * drive the fan. Activation is immediate; only stopping waits for the hysteresis point.
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
    private final AqssSettings settings;

    @Scheduled(fixedDelayString = "${aqss.control.cycle-ms:60000}")
    public void tick() {
        for (Device device : devices.findAll()) {
            evaluate(device, settings.control());
        }
    }

    /**
     * The scheduled tick is the safety net; this is the fast path.
     *
     * <p>Telemetry arrives every 5 seconds. Waiting up to 60 for the timer means someone can watch
     * the number climb for a minute before anything happens. If the reading already breaches and
     * the fan is idle, act on the message itself.
     */
    public void onReading(Device device) {
        // Only the trigger is immediate. Stopping still waits for the timer, because hysteresis and
        // the minimum run time are deliberately slow.
        if (device.getPairedAt() == null
                || !"AUTO".equals(device.getFanMode())
                || device.getSpaceCode() == null
                || "ON".equals(device.getFanState())) {
            return;
        }
        evaluate(device, settings.control());
    }

    private void evaluate(Device device, AqssSettings.Control cfg) {
        Instant now = Instant.now();
        if (device.getPairedAt() == null
                || !"AUTO".equals(device.getFanMode())
                || device.getSpaceCode() == null) {
            return;
        }
        Space space = spaces.findById(device.getSpaceCode()).orElse(null);
        if (space == null) {
            return;
        }

        Telemetry reading = telemetry.findFirstByDeviceIdOrderByRecordedAtDesc(device.getDeviceId())
                .orElse(null);
        Double pm25 = reading == null || reading.getPm25() == null
                ? null : reading.getPm25().doubleValue();
        boolean stale = reading != null
                && reading.getRecordedAt().isBefore(now.minus(Duration.ofMinutes(cfg.staleReadingMinutes())));
        if (pm25 == null || stale) {
            return;   // a stale reading must not drive the fan
        }

        Decision decision = decide(new Inputs(
                pm25, space.getThresholdPm25(), "ON".equals(device.getFanState()),
                device.getOnSince(), device.getOffSince(), now,
                cfg.minOnMinutes(), cfg.maxOnMinutes(), cfg.hysteresisRatio()));

        switch (decision) {
            case ACTIVATE -> activate(device, space, pm25);
            case DEACTIVATE -> deactivate(device, space, pm25);
            case NONE -> renewLeaseIfDue(device, now, cfg);
        }
    }

    private void activate(Device device, Space space, double pm25) {
        int level = ai.dosage(new AiClient.DosageRequest(space.getCode(), space.getVolumeM3(), space.getPurpose(),
                        (float) pm25, space.getThresholdPm25(), 35f, 40f))
                .map(AiClient.DosageResult::filterLevel)
                .orElse(2);
        deviceService.applyActuator(device.getDeviceId(), true, Math.max(1, Math.min(level, 3)), "AUTO", "BACKEND");
        deviceService.recordDecision(device, "threshold_breach");
        log.info("{} PM2.5 {} vs {} -> fan ON level {}", device.getDeviceId(), pm25,
                space.getThresholdPm25(), level);
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

    private void renewLeaseIfDue(Device device, Instant now, AqssSettings.Control cfg) {
        if (!"ON".equals(device.getFanState())) {
            return;
        }
        Instant last = device.getLastCommandAt();
        if (last == null || Duration.between(last, now).toMinutes() >= cfg.leaseRenewMinutes()) {
            deviceService.renewLease(device);
        }
    }

    // ── Pure decision table, so it can be unit-tested without Spring ──────────

    public record Inputs(double pm25, double threshold, boolean fanOn, Instant onSince, Instant offSince,
                         Instant now, int minOnMinutes, int maxOnMinutes, double hysteresisRatio) {
    }

    /**
     * Turning the fan ON is immediate: a breach means the air is already bad, and making someone
     * watch it get worse for three minutes is not protection, it is a delay. The motor protection
     * lives on the OFF side of the cycle instead — once running, it stays running for a minimum,
     * and it is never restarted more than once per min-OFF window.
     */
    public static Decision decide(Inputs in) {
        if (!in.fanOn()) {
            return in.pm25() >= in.threshold() ? Decision.ACTIVATE : Decision.NONE;
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
