package com.aqss.backend.repo;

import com.aqss.backend.model.Prediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Optional<Prediction> findFirstByDeviceIdOrderByPredictedAtDesc(String deviceId);
}
