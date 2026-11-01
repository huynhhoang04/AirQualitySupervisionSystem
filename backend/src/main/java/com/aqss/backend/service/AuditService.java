package com.aqss.backend.service;

import com.aqss.backend.model.AuditEntry;
import com.aqss.backend.repo.AuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** One audit trail for every action the backend takes or receives. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditRepository repository;
    private final ObjectMapper mapper;

    @Transactional
    public void log(String actor, String action, String deviceId, Map<String, Object> detail) {
        try {
            repository.save(AuditEntry.builder()
                    .occurredAt(Instant.now())
                    .actor(actor)
                    .action(action)
                    .deviceId(deviceId)
                    .detail(detail == null ? null : mapper.writeValueAsString(detail))
                    .build());
        } catch (Exception e) {
            log.warn("Could not write audit entry {} {}: {}", actor, action, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> recent(String deviceId, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 500)));
        return deviceId == null || deviceId.isBlank()
                ? repository.findAllByOrderByOccurredAtDesc(page)
                : repository.findByDeviceIdOrderByOccurredAtDesc(deviceId, page);
    }
}
