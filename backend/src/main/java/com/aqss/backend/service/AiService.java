package com.aqss.backend.service;

import com.aqss.backend.model.AiAlert;
import com.aqss.backend.model.AiJob;
import com.aqss.backend.model.AiModel;
import com.aqss.backend.model.AiWorker;
import com.aqss.backend.model.ForecastPoint;
import com.aqss.backend.model.ForecastRun;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.repo.AiAlertRepository;
import com.aqss.backend.repo.AiJobRepository;
import com.aqss.backend.repo.AiModelRepository;
import com.aqss.backend.repo.AiWorkerRepository;
import com.aqss.backend.repo.ForecastPointRepository;
import com.aqss.backend.repo.ForecastRunRepository;
import com.aqss.backend.repo.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Job queue, model registry and forecast ledger. Metadata only; artifacts live on the worker. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private static final Duration LEASE = Duration.ofSeconds(90);
    private static final Duration ONLINE_WINDOW = Duration.ofSeconds(90);

    private final AiJobRepository jobs;
    private final AiModelRepository models;
    private final AiWorkerRepository workers;
    private final ForecastRunRepository runs;
    private final ForecastPointRepository points;
    private final AiAlertRepository alerts;
    private final TelemetryRepository telemetry;

    // ── jobs ────────────────────────────────────────────────────────────────

    @Transactional
    public AiJob createJob(JobRequest request) {
        if (request.type() == null || !List.of("train", "evaluate", "export").contains(request.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be train, evaluate or export");
        }
        if ("train".equals(request.type())
                && jobs.existsByDeviceIdAndTypeAndStatusIn(request.deviceId(), "train",
                List.of("QUEUED", "RUNNING"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a train job is already queued for this device");
        }
        Instant now = Instant.now();
        AiJob job = AiJob.builder()
                .id(UUID.randomUUID())
                .type(request.type())
                .deviceId(request.deviceId())
                .params(request.params() == null ? Map.of() : request.params())
                .status("QUEUED")
                .progress(0)
                .attempts(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return jobs.save(job);
    }

    @Transactional(readOnly = true)
    public List<AiJob> listJobs(String deviceId, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        return deviceId == null || deviceId.isBlank()
                ? jobs.findAllByOrderByCreatedAtDesc(page)
                : jobs.findByDeviceIdOrderByCreatedAtDesc(deviceId, page);
    }

    @Transactional(readOnly = true)
    public AiJob job(UUID id) {
        return jobs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found"));
    }

    /** Claim one queued job atomically. A worker already training does not claim another train. */
    @Transactional
    public Optional<AiJob> claim(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "worker_id is required");
        }
        if (jobs.existsByWorkerIdAndStatus(workerId, "RUNNING")) {
            return Optional.empty();
        }
        List<AiJob> next = jobs.lockNextQueued(PageRequest.of(0, 1));
        if (next.isEmpty()) {
            return Optional.empty();
        }
        AiJob job = next.get(0);
        job.setStatus("RUNNING");
        job.setWorkerId(workerId);
        job.setLeaseToken(UUID.randomUUID().toString());
        job.setLeaseExpiresAt(Instant.now().plus(LEASE));
        job.setAttempts(job.getAttempts() == null ? 1 : job.getAttempts() + 1);
        job.setPhase("claimed");
        job.setUpdatedAt(Instant.now());
        return Optional.of(jobs.save(job));
    }

    @Transactional
    public AiJob progress(UUID id, String token, Integer progress, String phase) {
        AiJob job = requireLease(id, token);
        if (progress != null) {
            job.setProgress(Math.max(0, Math.min(progress, 100)));
        }
        if (phase != null) {
            job.setPhase(phase);
        }
        job.setLeaseExpiresAt(Instant.now().plus(LEASE));
        job.setUpdatedAt(Instant.now());
        return jobs.save(job);
    }

    @Transactional
    public AiJob complete(UUID id, String token, Map<String, Object> result, String error) {
        AiJob job = requireLease(id, token);
        job.setStatus(error == null ? "COMPLETED" : "FAILED");
        job.setResult(result);
        job.setError(error);
        job.setProgress(error == null ? 100 : job.getProgress());
        job.setPhase(error == null ? "completed" : "failed");
        job.setLeaseExpiresAt(null);
        job.setUpdatedAt(Instant.now());
        return jobs.save(job);
    }

    private AiJob requireLease(UUID id, String token) {
        AiJob job = jobs.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found"));
        if (token == null || !token.equals(job.getLeaseToken())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "stale lease token");
        }
        return job;
    }

    // ── models ──────────────────────────────────────────────────────────────

    @Transactional
    public AiModel registerModel(ModelRegistration request) {
        if (request.version() == null || request.version().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version is required");
        }
        Optional<AiModel> existing = request.deviceId() == null
                ? Optional.empty() : models.findFirstByDeviceIdAndVersion(request.deviceId(), request.version());
        if (existing.isPresent()) {
            return existing.get();
        }
        String status = request.status() == null ? "EXPERIMENTAL" : request.status();
        if ("PRODUCTION".equals(status)) {
            // Atomic pointer move: exactly one production model per device.
            models.findFirstByDeviceIdAndStatusOrderByCreatedAtDesc(request.deviceId(), "PRODUCTION")
                    .ifPresent(previous -> {
                        previous.setStatus("EXPERIMENTAL");
                        models.save(previous);
                    });
        }
        AiModel model = AiModel.builder()
                .id(UUID.randomUUID())
                .deviceId(request.deviceId())
                .version(request.version())
                .algorithm(request.algorithm())
                .featureVersion(request.featureVersion())
                .datasetSnapshotId(uuidOrNull(request.datasetSnapshotId()))
                .datasetHash(request.datasetHash())
                .params(request.params())
                .trainCutoff(request.trainCutoff())
                .metrics(request.metrics())
                .artifactHash(request.artifactHash())
                .artifactPath(request.artifactPath())
                .status(status)
                .promotionReason(request.promotionReason())
                .createdAt(Instant.now())
                .build();
        return models.save(model);
    }

    @Transactional(readOnly = true)
    public List<AiModel> models(String deviceId) {
        return deviceId == null || deviceId.isBlank()
                ? models.findAll()
                : models.findByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    // ── forecast ledger ─────────────────────────────────────────────────────

    @Transactional
    public ForecastView uploadForecast(ForecastUpload upload) {
        if (upload.deviceId() == null || upload.modelVersion() == null
                || upload.inputCutoff() == null || upload.issuedAt() == null
                || upload.points() == null || upload.points().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "device_id, model_version, input_cutoff, issued_at and points are required");
        }
        Optional<ForecastRun> existing = runs.findByDeviceIdAndModelVersionAndInputCutoff(
                upload.deviceId(), upload.modelVersion(), upload.inputCutoff());
        if (existing.isPresent()) {
            return view(existing.get());
        }

        Instant acceptedAt = Instant.now();
        UUID runId = UUID.randomUUID();
        boolean late = upload.points().stream()
                .anyMatch(point -> point.targetAt() != null && point.targetAt().isBefore(acceptedAt));

        ForecastRun run = ForecastRun.builder()
                .id(runId)
                .deviceId(upload.deviceId())
                .modelId(uuidOrNull(upload.modelId()))
                .modelVersion(upload.modelVersion())
                .inputCutoff(upload.inputCutoff())
                .issuedAt(upload.issuedAt())
                .acceptedAt(acceptedAt)
                .profileVersion(upload.profileVersion())
                .thresholdPm25(upload.thresholdPm25())
                .dataQuality(upload.dataQuality())
                .workerId(upload.workerId())
                .status(late ? "LATE_SUBMISSION" : "ACCEPTED")
                .build();
        runs.save(run);

        List<ForecastPoint> saved = new ArrayList<>();
        for (Point point : upload.points()) {
            if (point.horizonMinutes() == null || point.targetAt() == null || point.pm25() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "each point needs horizon, target_at and pm25");
            }
            boolean pointLate = point.targetAt().isBefore(acceptedAt);
            saved.add(points.save(ForecastPoint.builder()
                    .runId(runId)
                    .horizonMinutes(point.horizonMinutes())
                    .targetAt(point.targetAt())
                    .pm25(point.pm25())
                    .evaluationStatus(pointLate ? "LATE_SUBMISSION" : "PENDING")
                    .build()));
        }
        return new ForecastView(run, saved);
    }

    @Transactional(readOnly = true)
    public Optional<ForecastView> latestForecast(String deviceId) {
        return runs.findFirstByDeviceIdOrderByIssuedAtDesc(deviceId).map(this::view);
    }

    private ForecastView view(ForecastRun run) {
        return new ForecastView(run, points.findByRunIdOrderByHorizonMinutesAsc(run.getId()));
    }

    @Transactional(readOnly = true)
    public List<AiAlert> alerts(String deviceId) {
        return alerts.findTop100ByDeviceIdOrderByCreatedAtDesc(deviceId);
    }

    /**
     * One active forecast alert per device. A second breach forecast does not create a second row;
     * the worker's consecutive-run rule decides when it is allowed to ask.
     */
    @Transactional
    public AiAlert createAlert(AlertRequest request) {
        if (request.deviceId() == null || request.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "device_id and type are required");
        }
        Optional<AiAlert> active = alerts.findFirstByDeviceIdAndLifecycleOrderByCreatedAtDesc(
                request.deviceId(), "ACTIVE");
        if (active.isPresent()) {
            return active.get();
        }
        AiAlert alert = AiAlert.builder()
                .id(UUID.randomUUID())
                .deviceId(request.deviceId())
                .forecastRunId(uuidOrNull(request.forecastRunId()))
                .type(request.type())
                .createdAt(Instant.now())
                .thresholdSnapshot(request.thresholdSnapshot())
                .horizonMinutes(request.horizonMinutes())
                .reason(request.reason())
                .lifecycle(request.lifecycle() == null ? "ACTIVE" : request.lifecycle())
                .build();
        return alerts.save(alert);
    }

    @Transactional
    public int resolveAlerts(String deviceId, String lifecycle) {
        List<AiAlert> active = alerts.findByDeviceIdAndLifecycle(deviceId, "ACTIVE");
        for (AiAlert alert : active) {
            alert.setLifecycle(lifecycle == null ? "RESOLVED" : lifecycle);
            alerts.save(alert);
        }
        return active.size();
    }

    @Transactional
    public AiAlert feedback(UUID alertId, String feedback) {
        AiAlert alert = alerts.findById(alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "alert not found"));
        alert.setFeedback(feedback);
        alert.setFeedbackAt(Instant.now());
        alert.setLifecycle("ACKNOWLEDGED");
        return alerts.save(alert);
    }

    // ── worker + status ─────────────────────────────────────────────────────

    @Transactional
    public AiWorker heartbeat(WorkerHeartbeat request) {
        AiWorker worker = workers.findById(request.workerId()).orElseGet(() -> AiWorker.builder()
                .id(request.workerId())
                .status("ONLINE")
                .build());
        worker.setLastHeartbeatAt(Instant.now());
        worker.setCapabilities(request.capabilities());
        worker.setModelArtifact(request.modelArtifact());
        worker.setStatus("ONLINE");
        return workers.save(worker);
    }

    @Transactional(readOnly = true)
    public Status status(String deviceId) {
        Instant now = Instant.now();
        List<WorkerStatus> workerStatuses = workers.findAll().stream()
                .map(worker -> new WorkerStatus(
                        worker.getId(),
                        worker.getLastHeartbeatAt() != null
                                && worker.getLastHeartbeatAt().isAfter(now.minus(ONLINE_WINDOW)),
                        worker.getLastHeartbeatAt(),
                        worker.getModelArtifact(), worker.getCapabilities()))
                .toList();

        AiModel current = deviceId == null || deviceId.isBlank() ? null
                : models.findFirstByDeviceIdAndStatusOrderByCreatedAtDesc(deviceId, "PRODUCTION").orElse(null);
        Telemetry latest = deviceId == null || deviceId.isBlank() ? null
                : telemetry.findFirstByDeviceIdOrderByRecordedAtDesc(deviceId).orElse(null);
        ForecastRun latestRun = deviceId == null || deviceId.isBlank() ? null
                : runs.findFirstByDeviceIdOrderByIssuedAtDesc(deviceId).orElse(null);

        return new Status(
                workerStatuses.stream().anyMatch(WorkerStatus::online),
                workerStatuses,
                current,
                latest == null ? null : latest.getRecordedAt(),
                latest == null ? null : latest.getReceivedAt(),
                latestRun == null ? null : latestRun.getIssuedAt(),
                latestRun == null ? null : latestRun.getAcceptedAt());
    }

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid uuid: " + value);
        }
    }

    // ── request/response shapes ─────────────────────────────────────────────

    public record JobRequest(String type, String deviceId, Map<String, Object> params) {
    }

    public record ModelRegistration(String deviceId, String version, String algorithm, String featureVersion,
                                    String datasetSnapshotId, String datasetHash, Map<String, Object> params,
                                    Instant trainCutoff, Map<String, Object> metrics, String artifactHash,
                                    String artifactPath, String status, String promotionReason) {
    }

    public record Point(Integer horizonMinutes, Instant targetAt, Float pm25) {
    }

    public record ForecastUpload(String deviceId, String modelId, String modelVersion, Instant inputCutoff,
                                 Instant issuedAt, Integer profileVersion, Float thresholdPm25,
                                 Map<String, Object> dataQuality, String workerId, List<Point> points) {
    }

    public record ForecastView(ForecastRun run, List<ForecastPoint> points) {
    }

    public record WorkerHeartbeat(String workerId, Map<String, Object> capabilities, String modelArtifact) {
    }

    public record WorkerStatus(String id, boolean online, Instant lastHeartbeatAt, String modelArtifact,
                               Map<String, Object> capabilities) {
    }

    public record AlertRequest(String deviceId, String forecastRunId, String type, Float thresholdSnapshot,
                               Integer horizonMinutes, String reason, String lifecycle) {
    }

    public record Status(boolean workerOnline, List<WorkerStatus> workers, AiModel currentModel,
                         Instant latestTelemetryRecordedAt, Instant latestTelemetryReceivedAt,
                         Instant latestForecastIssuedAt, Instant latestForecastAcceptedAt) {
    }
}
