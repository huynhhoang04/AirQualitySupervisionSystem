package com.aqss.backend.controller;

import com.aqss.backend.dto.alert.AlertDto;
import com.aqss.backend.dto.common.StatusResponse;
import com.aqss.backend.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    @GetMapping
    public List<AlertDto> list(@RequestParam(defaultValue = "false") boolean active) {
        return active ? alertService.findActive() : alertService.findAll();
    }

    @PutMapping("/{id}/resolve")
    public StatusResponse resolve(@PathVariable Long id) {
        alertService.resolve(id);
        return StatusResponse.of("resolved");
    }
}
