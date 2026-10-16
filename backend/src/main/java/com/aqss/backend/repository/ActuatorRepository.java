package com.aqss.backend.repository;

import com.aqss.backend.entity.Actuator;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActuatorRepository extends JpaRepository<Actuator, String> {

    List<Actuator> findBySpaceId(Long spaceId);

    Optional<Actuator> findFirstBySpaceId(Long spaceId);
}
