package com.aqss.backend.repository;

import com.aqss.backend.entity.Space;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpaceRepository extends JpaRepository<Space, Long> {

    Optional<Space> findByCode(String code);

    boolean existsByCode(String code);

    List<Space> findAllByOrderByIdAsc();
}
