package com.aqss.backend.controller;

import com.aqss.backend.dto.actuator.ActuatorDto;
import com.aqss.backend.service.ActuatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/actuators")
@RequiredArgsConstructor
public class ActuatorController {

    private final ActuatorService actuatorService;

    @GetMapping
    public List<ActuatorDto> list() {
        return actuatorService.findAll();
    }
}
