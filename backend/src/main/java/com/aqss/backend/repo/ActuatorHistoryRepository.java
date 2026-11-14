package com.aqss.backend.repo;

import com.aqss.backend.model.ActuatorHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ActuatorHistoryRepository extends JpaRepository<ActuatorHistory, Long> {

    List<ActuatorHistory> findByIdInOrderByReceivedAtAscIdAsc(Collection<Long> ids);

    List<ActuatorHistory> findTop200ByDeviceIdOrderByReceivedAtDesc(String deviceId);

    List<ActuatorHistory> findByDeviceIdOrderByReceivedAtDesc(String deviceId, Pageable pageable);

    List<ActuatorHistory> findByDeviceIdAndReceivedAtBetweenOrderByReceivedAtAscIdAsc(
            String deviceId, Instant from, Instant to);
}
