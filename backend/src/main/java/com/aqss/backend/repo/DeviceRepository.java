package com.aqss.backend.repo;

import com.aqss.backend.model.Device;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, String> {

    List<Device> findBySpaceCode(String spaceCode);
}
