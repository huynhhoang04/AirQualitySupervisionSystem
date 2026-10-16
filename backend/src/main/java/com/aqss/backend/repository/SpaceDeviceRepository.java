package com.aqss.backend.repository;

import com.aqss.backend.entity.SpaceDevice;
import com.aqss.backend.entity.SpaceDeviceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpaceDeviceRepository extends JpaRepository<SpaceDevice, SpaceDeviceId> {

    List<SpaceDevice> findByIdSpaceId(Long spaceId);

    List<SpaceDevice> findByIdDeviceId(String deviceId);

    void deleteByIdSpaceIdAndIdDeviceId(Long spaceId, String deviceId);
}
