package com.aqss.backend.repo;

import com.aqss.backend.model.AuditEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    List<AuditEntry> findAllByOrderByOccurredAtDesc(Pageable pageable);

    List<AuditEntry> findByDeviceIdOrderByOccurredAtDesc(String deviceId, Pageable pageable);
}
