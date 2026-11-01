package com.aqss.backend.service;

import com.aqss.backend.config.AqssSettings;
import com.aqss.backend.model.Device;
import com.aqss.backend.model.Space;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.SpaceRepository;
import com.aqss.backend.repo.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** The closed loop: compare the latest PM2.5 against the space threshold and drive the fan. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ControlLoop {

    private final DeviceRepository devices;
    private final SpaceRepository spaces;
    private final TelemetryRepository telemetry;
    private final DeviceService deviceService;
    private final AiClient ai;
    private final AuditService audit;
    private final AqssSettings settings;

    @Scheduled(fixedDelayString = "${aqss.control.cycle-ms:60000}")
    public void tick() {
        for (Device device : devices.findAll()) {
            if (!"AUTO".equals(device.getFanMode()) || device.getSpaceCode() == null) {
                continue;
            }
            Space space = spaces.findById(device.getSpaceCode()).orElse(null);
            if (space == null) {
                continue;
            }
            Double pm25 = telemetry.findFirstByDeviceIdOrderByRecordedAtDesc(device.getDeviceId())
                    .map(reading -> reading.getPm25() == null ? null : reading.getPm25().doubleValue())
                    .orElse(null);
            if (pm25 == null) {
                continue;
            }

            switch (decide(pm25, space.getThresholdPm25(), "ON".equals(device.getFanState()),
                    device.getOnSince(), Instant.now(), settings.control().minOnMinutes(),
                    settings.control().hysteresisRatio())) {
                case ACTIVATE -> activate(device, space, pm25);
                case DEACTIVATE -> {
                    deviceService.applyActuator(device.getDeviceId(), false, 0, "BACKEND");
                    log.info("{} PM2.5 {} below {} -> fan OFF", device.getDeviceId(), pm25,
                            space.getThresholdPm25() * settings.control().hysteresisRatio());
                }
                case NONE -> {
                }
            }
        }
    }

    private void activate(Device device, Space space, double pm25) {
        int level = ai.dosage(new AiClient.DosageRequest(space.getCode(), space.getVolumeM3(), space.getPurpose(),
                        (float) pm25, space.getThresholdPm25(), 35f, 40f))
                .map(AiClient.DosageResult::filterLevel)
                .orElse(2);
        deviceService.applyActuator(device.getDeviceId(), true, Math.max(1, Math.min(level, 3)), "BACKEND");
        log.info("{} PM2.5 {} >= {} -> fan ON level {}", device.getDeviceId(), pm25,
                space.getThresholdPm25(), level);
    }

    /** Pure decision table, so it can be unit-tested without Spring. */
    public static Decision decide(double pm25, double threshold, boolean fanOn, Instant onSince, Instant now,
                                  int minOnMinutes, double hysteresisRatio) {
        if (!fanOn) {
            return pm25 >= threshold ? Decision.ACTIVATE : Decision.NONE;
        }
        boolean minOnElapsed = onSince == null
                || Duration.between(onSince, now).toMinutes() >= minOnMinutes;
        return pm25 < threshold * hysteresisRatio && minOnElapsed ? Decision.DEACTIVATE : Decision.NONE;
    }

    public enum Decision {
        ACTIVATE, DEACTIVATE, NONE
    }
}
