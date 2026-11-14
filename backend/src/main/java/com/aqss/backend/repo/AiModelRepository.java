package com.aqss.backend.repo;

import com.aqss.backend.model.AiModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiModelRepository extends JpaRepository<AiModel, UUID> {

    List<AiModel> findByDeviceIdOrderByCreatedAtDesc(String deviceId);

    Optional<AiModel> findFirstByDeviceIdAndStatusOrderByCreatedAtDesc(String deviceId, String status);

    Optional<AiModel> findFirstByDeviceIdAndVersion(String deviceId, String version);
}
