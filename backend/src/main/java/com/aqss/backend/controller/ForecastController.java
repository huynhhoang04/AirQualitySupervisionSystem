package com.aqss.backend.controller;

import com.aqss.backend.dto.common.StatusResponse;
import com.aqss.backend.dto.forecast.ForecastDto;
import com.aqss.backend.entity.Space;
import com.aqss.backend.service.ForecastService;
import com.aqss.backend.service.SpaceService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final ForecastService forecastService;
    private final SpaceService spaceService;

    @GetMapping("/{spaceId}")
    public ForecastDto latest(@PathVariable String spaceId) {
        return forecastService.latestDto(spaceService.resolve(spaceId));
    }

    @PostMapping("/run")
    public StatusResponse run(@RequestBody RunRequest request) {
        Space space = spaceService.resolve(request.spaceId());
        return StatusResponse.of(forecastService.run(space) ? "triggered" : "ai_disabled");
    }

    public record RunRequest(@NotBlank String spaceId) {
    }
}
