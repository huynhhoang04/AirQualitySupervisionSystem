package com.aqss.backend.service;

import com.aqss.backend.dto.device.CreateDeviceRequest;
import com.aqss.backend.dto.device.CreateDeviceResponse;
import com.aqss.backend.dto.device.DeviceDto;
import com.aqss.backend.entity.Device;
import com.aqss.backend.exception.ConflictException;
import com.aqss.backend.exception.NotFoundException;
import com.aqss.backend.mapper.DeviceMapper;
import com.aqss.backend.repository.DeviceRepository;
import com.aqss.backend.security.ApiKeyHasher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Device registry CRUD. The raw API key is returned exactly once, at creation. */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceMapper deviceMapper;

    @Transactional(readOnly = true)
    public List<DeviceDto> findAll() {
        return deviceRepository.findAll().stream().map(deviceMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Device getDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> NotFoundException.of("Device", deviceId));
    }

    @Transactional
    public CreateDeviceResponse create(CreateDeviceRequest request) {
        if (deviceRepository.existsById(request.deviceId())) {
            throw new ConflictException("Device already exists: " + request.deviceId());
        }
        String rawKey = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        Device device = Device.builder()
                .deviceId(request.deviceId())
                .name(request.name())
                .locationName(request.locationName())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .apiKeyHash(ApiKeyHasher.sha256Hex(rawKey))
                .firmwareVersion(request.firmwareVersion() != null ? request.firmwareVersion() : "2.0.0")
                .isActive(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
        deviceRepository.save(device);
        return new CreateDeviceResponse(device.getDeviceId(), rawKey);
    }
}
