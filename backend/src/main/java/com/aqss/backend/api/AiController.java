package com.aqss.backend.api;

import com.aqss.backend.model.AiAlert;
import com.aqss.backend.model.AiJob;
import com.aqss.backend.model.AiModel;
import com.aqss.backend.service.AiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Read side of the AI layer plus the "train again" button. The worker does the work. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AiController {

    private final AiService ai;

    @GetMapping("/ai/status")
    public AiService.Status status(@RequestParam(name = "device_id", required = false) String deviceId) {
        return ai.status(deviceId);
    }

    @PostMapping("/ai/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AiJob createJob(@RequestBody AiService.JobRequest request) {
        return ai.createJob(request);
    }

    @GetMapping("/ai/jobs")
    public List<AiJob> jobs(@RequestParam(name = "device_id", required = false) String deviceId,
                            @RequestParam(defaultValue = "50") int limit) {
        return ai.listJobs(deviceId, limit);
    }

    @GetMapping("/ai/jobs/{id}")
    public AiJob job(@PathVariable UUID id) {
        return ai.job(id);
    }

    @GetMapping("/ai/models")
    public List<AiModel> models(@RequestParam(name = "device_id", required = false) String deviceId) {
        return ai.models(deviceId);
    }

    /** Latest issued forecast, including its freshness — the UI must show how old it is. */
    @GetMapping("/devices/{deviceId}/forecast")
    public AiService.ForecastView forecast(@PathVariable String deviceId) {
        return ai.latestForecast(deviceId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no forecast for " + deviceId));
    }

    @GetMapping("/devices/{deviceId}/alerts")
    public List<AiAlert> alerts(@PathVariable String deviceId) {
        return ai.alerts(deviceId);
    }

    /** "Đã kiểm tra" / "bỏ qua" / "đã xử lý" — recorded, never inferred from a click elsewhere. */
    @PostMapping("/alerts/{id}/feedback")
    public AiAlert feedback(@PathVariable UUID id, @RequestBody FeedbackBody body) {
        return ai.feedback(id, body.feedback());
    }

    public record FeedbackBody(String feedback) {
    }
}
