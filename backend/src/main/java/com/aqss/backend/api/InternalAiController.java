package com.aqss.backend.api;

import com.aqss.backend.model.AiJob;
import com.aqss.backend.model.AiModel;
import com.aqss.backend.model.AiWorker;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.service.AiService;
import com.aqss.backend.service.DatasetSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal API for the local AI worker. Not for the browser: the worker is the only caller and it
 * holds the bearer token.
 */
@RestController
@RequestMapping("/internal/v1/ai")
@RequiredArgsConstructor
public class InternalAiController {

    private final DatasetSnapshotService snapshots;
    private final AiService ai;

    // ── dataset ─────────────────────────────────────────────────────────────

    /** Freeze the membership of a window before paging through it. */
    @PostMapping("/dataset-snapshots")
    public DatasetSnapshotService.SnapshotSummary createSnapshot(@RequestBody CreateSnapshot request) {
        return snapshots.create(request.deviceId(), request.from(), request.to());
    }

    @GetMapping("/devices/{deviceId}/telemetry")
    public DatasetSnapshotService.TelemetryPage telemetry(
            @PathVariable String deviceId,
            @RequestParam(name = "snapshot_id") String snapshotId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "2000") int limit) {
        return snapshots.telemetry(snapshotId, cursor, limit);
    }

    @GetMapping("/devices/{deviceId}/actuator-history")
    public DatasetSnapshotService.ActuatorPage actuatorHistory(
            @PathVariable String deviceId,
            @RequestParam(name = "snapshot_id") String snapshotId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "2000") int limit) {
        return snapshots.actuator(snapshotId, cursor, limit);
    }

    @GetMapping("/devices/{deviceId}/events")
    public DatasetSnapshotService.EventPage events(
            @PathVariable String deviceId,
            @RequestParam(name = "snapshot_id") String snapshotId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "2000") int limit) {
        return snapshots.events(snapshotId, cursor, limit);
    }

    /** Device + current room profile + every known profile version. */
    @GetMapping("/devices/{deviceId}/context")
    public DatasetSnapshotService.Context context(@PathVariable String deviceId) {
        return snapshots.context(deviceId);
    }

    /** Bounded recent rows for inference. */
    @GetMapping("/devices/{deviceId}/recent")
    public List<Telemetry> recent(@PathVariable String deviceId,
                                  @RequestParam(defaultValue = "90") int minutes) {
        return snapshots.recent(deviceId, minutes);
    }

    /** Recent actuator reports, aligned to the same window as {@code recent}. */
    @GetMapping("/devices/{deviceId}/recent-actuator")
    public List<com.aqss.backend.model.ActuatorHistory> recentActuator(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "90") int minutes) {
        return snapshots.recentActuator(deviceId, minutes);
    }

    // ── jobs ────────────────────────────────────────────────────────────────

    @PostMapping("/jobs/claim")
    public ResponseEntity<AiJob> claim(@RequestParam(name = "worker_id") String workerId) {
        return ai.claim(workerId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/jobs/{id}/progress")
    public AiJob progress(@PathVariable UUID id,
                          @RequestParam(name = "lease_token") String leaseToken,
                          @RequestBody ProgressBody body) {
        return ai.progress(id, leaseToken, body.progress(), body.phase());
    }

    @PostMapping("/jobs/{id}/complete")
    public AiJob complete(@PathVariable UUID id,
                          @RequestParam(name = "lease_token") String leaseToken,
                          @RequestBody CompleteBody body) {
        return ai.complete(id, leaseToken, body.result(), body.error());
    }

    // ── models + forecasts ──────────────────────────────────────────────────

    @PostMapping("/models")
    public AiModel registerModel(@RequestBody AiService.ModelRegistration request) {
        return ai.registerModel(request);
    }

    @PostMapping("/forecast-runs")
    public AiService.ForecastView uploadForecast(@RequestBody AiService.ForecastUpload request) {
        return ai.uploadForecast(request);
    }

    @PostMapping("/workers/heartbeat")
    public AiWorker heartbeat(@RequestBody AiService.WorkerHeartbeat request) {
        return ai.heartbeat(request);
    }

    @PostMapping("/alerts")
    public com.aqss.backend.model.AiAlert createAlert(@RequestBody AiService.AlertRequest request) {
        return ai.createAlert(request);
    }

    @PostMapping("/alerts/resolve")
    public Map<String, Object> resolveAlerts(@RequestParam(name = "device_id") String deviceId,
                                             @RequestParam(defaultValue = "RESOLVED") String lifecycle) {
        int resolved = ai.resolveAlerts(deviceId, lifecycle);
        return Map.of("resolved", resolved);
    }

    public record CreateSnapshot(String deviceId, Instant from, Instant to) {
    }

    public record ProgressBody(Integer progress, String phase) {
    }

    public record CompleteBody(Map<String, Object> result, String error) {
    }
}
