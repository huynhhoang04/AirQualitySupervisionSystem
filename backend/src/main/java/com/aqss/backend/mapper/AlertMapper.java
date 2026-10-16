package com.aqss.backend.mapper;

import com.aqss.backend.dto.alert.AlertDto;
import com.aqss.backend.entity.Alert;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AlertMapper {

    AlertDto toDto(Alert alert);
}
