package com.aqss.backend.mapper;

import com.aqss.backend.dto.device.DeviceDto;
import com.aqss.backend.entity.Device;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DeviceMapper {

    DeviceDto toDto(Device device);
}
