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

    @GetMapping
    public List<AuditEntry> recent(@RequestParam(required = false) String deviceId,
                                   @RequestParam(defaultValue = "100") int limit) {
        return audit.recent(deviceId, limit);
    }
}
