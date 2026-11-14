package com.aqss.backend.service;

import com.aqss.backend.model.Experiment;
import com.aqss.backend.model.RoomEvent;
import com.aqss.backend.repo.ExperimentRepository;
import com.aqss.backend.repo.RoomEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Operator context for a room: an event happened, or a session was being observed. */
@Service
@RequiredArgsConstructor
public class RoomEventService {

    private final RoomEventRepository events;
    private final ExperimentRepository experiments;
    private final AuditService audit;

    @Transactional
    public RoomEvent record(String deviceId, EventRequest request) {
        if (request.type() == null || request.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type is required");
        }
        Instant now = Instant.now();
        Instant occurredAt = request.occurredAt() == null ? now : request.occurredAt();
        if (occurredAt.isAfter(now.plusSeconds(120))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "occurred_at is in the future");
        }
        RoomEvent event = RoomEvent.builder()
                .id(UUID.randomUUID())
                .deviceId(deviceId)
                .spaceCode(request.spaceCode())
                .occurredAt(occurredAt)
                .receivedAt(now)
                .type(request.type())
                .note(request.note())
                .experimentId(uuidOrNull(request.experimentId()))
                .build();
        events.save(event);
        audit.log("USER", "EVENT", deviceId, Map.of(
                "event_id", event.getId().toString(),
                "type", event.getType(),
                "occurred_at", occurredAt.toString()));
        return event;
    }

    @Transactional(readOnly = true)
    public List<RoomEvent> list(String deviceId, Instant from, Instant to) {
        if (from == null || to == null) {
            return events.findTop200ByDeviceIdOrderByOccurredAtDesc(deviceId);
        }
        return events.findByDeviceIdAndOccurredAtBetweenOrderByOccurredAtAsc(deviceId, from, to);
    }

    @Transactional
    public Experiment startExperiment(ExperimentRequest request) {
        Instant now = Instant.now();
        Experiment experiment = Experiment.builder()
                .id(UUID.randomUUID())
                .deviceId(request.deviceId())
                .spaceCode(request.spaceCode())
                .startedAt(request.startedAt() == null ? now : request.startedAt())
                .stoppedAt(request.stoppedAt())
                .protocolVersion(request.protocolVersion())
                .groupLabel(request.groupLabel())
                .conditions(request.conditions())
                .status(request.status() == null ? "open" : request.status())
                .notes(request.notes())
                .build();
        experiments.save(experiment);
        audit.log("USER", "EXPERIMENT", request.deviceId(), Map.of(
                "experiment_id", experiment.getId().toString(),
                "group", String.valueOf(request.groupLabel())));
        return experiment;
    }

    @Transactional(readOnly = true)
    public List<Experiment> experiments(String deviceId) {
        return experiments.findByDeviceIdOrderByStartedAtDesc(deviceId);
    }

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid experiment_id");
        }
    }

    public record EventRequest(String type, String note, Instant occurredAt, String spaceCode,
                               String experimentId) {
    }

    public record ExperimentRequest(String deviceId, String spaceCode, Instant startedAt, Instant stoppedAt,
                                    String protocolVersion, String groupLabel, Map<String, Object> conditions,
                                    String status, String notes) {
    }
}
