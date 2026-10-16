package com.aqss.backend.repository;

import com.aqss.backend.entity.ControlDecision;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ControlDecisionRepository extends JpaRepository<ControlDecision, Long> {

    List<ControlDecision> findBySpaceIdOrderByDecidedAtDesc(Long spaceId, Pageable pageable);

    long countBySpaceId(Long spaceId);
}
