package com.aqss.backend.repo;

import com.aqss.backend.model.Space;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpaceRepository extends JpaRepository<Space, String> {
}
