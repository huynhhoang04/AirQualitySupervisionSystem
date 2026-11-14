package com.aqss.backend.repo;

import com.aqss.backend.model.SpaceProfileVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpaceProfileVersionRepository extends JpaRepository<SpaceProfileVersion, Long> {

    Optional<SpaceProfileVersion> findFirstBySpaceCodeOrderByVersionDesc(String spaceCode);

    List<SpaceProfileVersion> findBySpaceCodeOrderByVersionDesc(String spaceCode);
}
