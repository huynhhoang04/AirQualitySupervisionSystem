package com.aqss.backend.repo;

import com.aqss.backend.model.CommandLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommandLogRepository extends JpaRepository<CommandLog, UUID> {

    Optional<CommandLog> findFirstByDeviceIdAndStatusInOrderByRequestedAtDesc(
            String deviceId, Collection<String> statuses);

    List<CommandLog> findByDeviceIdOrderByRequestedAtDesc(String deviceId, Pageable pageable);
}
