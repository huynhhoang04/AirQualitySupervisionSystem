package com.aqss.backend.repository;

import com.aqss.backend.entity.Alert;
import com.aqss.backend.entity.AlertType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByResolvedAtIsNullOrderByTriggeredAtDesc();

    List<Alert> findAllByOrderByTriggeredAtDesc();

    List<Alert> findBySpaceIdAndResolvedAtIsNullOrderByTriggeredAtDesc(Long spaceId);

    boolean existsByDeviceIdAndAlertTypeAndResolvedAtIsNull(String deviceId, AlertType alertType);

    boolean existsBySpaceIdAndAlertTypeAndResolvedAtIsNull(Long spaceId, AlertType alertType);
}
