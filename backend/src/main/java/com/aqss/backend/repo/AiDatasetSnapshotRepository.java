package com.aqss.backend.repo;

import com.aqss.backend.model.AiDatasetSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AiDatasetSnapshotRepository extends JpaRepository<AiDatasetSnapshot, UUID> {

    List<AiDatasetSnapshot> findTop20ByDeviceIdOrderByCreatedAtDesc(String deviceId);
}
