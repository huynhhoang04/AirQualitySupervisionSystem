package com.aqss.backend.repository;

import com.aqss.backend.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, String> {

    Optional<Device> findByApiKeyHash(String apiKeyHash);

    boolean existsByDeviceId(String deviceId);

    List<Device> findByIsActiveTrue();
}
