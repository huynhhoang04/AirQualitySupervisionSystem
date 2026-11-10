package com.aqss.backend.api;

import com.aqss.backend.model.AuditEntry;
import com.aqss.backend.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** One log for every action: user commands, backend decisions, device status, AI updates. */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService audit;

    /**
     * {@code @RequestParam} binds the Java parameter name, not a JSON-named field — the naming
     * strategy does not apply here. The name is therefore spelled out so the UI's {@code device_id}
     * actually filters instead of silently returning every device's events.
     */
    @GetMapping
    public List<AuditEntry> recent(@RequestParam(name = "device_id", required = false) String deviceId,
                                   @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return audit.recent(deviceId, limit);
    }
}
