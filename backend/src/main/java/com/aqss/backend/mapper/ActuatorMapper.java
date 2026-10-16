package com.aqss.backend.mapper;

import com.aqss.backend.dto.actuator.ActuatorDto;
import com.aqss.backend.entity.Actuator;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ActuatorMapper {

    @Mapping(target = "state", source = "currentState")
    @Mapping(target = "level", source = "currentLevel")
    @Mapping(target = "since", source = "stateSince")
    ActuatorDto toDto(Actuator actuator);
}
