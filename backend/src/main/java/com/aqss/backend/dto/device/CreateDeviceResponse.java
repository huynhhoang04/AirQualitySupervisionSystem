package com.aqss.backend.dto.device;

/** Returned once on device creation — the raw API key is never retrievable again. */
public record CreateDeviceResponse(
        String deviceId,
        String apiKey
) {
}
