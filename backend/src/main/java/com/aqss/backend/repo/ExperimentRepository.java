package com.aqss.backend.repo;

import com.aqss.backend.model.Experiment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExperimentRepository extends JpaRepository<Experiment, UUID> {

    List<Experiment> findByDeviceIdOrderByStartedAtDesc(String deviceId);
}
