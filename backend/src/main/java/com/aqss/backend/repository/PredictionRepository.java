package com.aqss.backend.repository;

import com.aqss.backend.entity.Prediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PredictionRepository extends JpaRepository<Prediction, Long> {

    Optional<Prediction> findFirstBySpaceIdOrderByPredictedAtDesc(Long spaceId);
}
