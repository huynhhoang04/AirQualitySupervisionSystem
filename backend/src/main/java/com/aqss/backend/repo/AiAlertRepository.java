package com.aqss.backend.repo;

import com.aqss.backend.model.AiAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiAlertRepository extends JpaRepository<AiAlert, UUID> {

    List<AiAlert> findTop100ByDeviceIdOrderByCreatedAtDesc(String deviceId);

    Optional<AiAlert> findFirstByDeviceIdAndLifecycleOrderByCreatedAtDesc(String deviceId, String lifecycle);

    List<AiAlert> findByDeviceIdAndLifecycle(String deviceId, String lifecycle);
}
