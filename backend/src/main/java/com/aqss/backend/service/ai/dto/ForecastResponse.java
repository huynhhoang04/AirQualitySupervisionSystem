package com.aqss.backend.service.ai.dto;

import com.aqss.backend.dto.forecast.ForecastPointDto;

import java.util.List;

/** io_contract §10 — DL forecast response (96 × {pm25_q10, pm25_q50, pm25_q90}). */
public record ForecastResponse(
        String spaceId,
        String status,
        String modelVersion,
        List<ForecastPointDto> forecastSequence
) {
}
