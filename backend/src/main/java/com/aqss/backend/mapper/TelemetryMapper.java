package com.aqss.backend.mapper;

import com.aqss.backend.dto.telemetry.HistoryPointDto;
import com.aqss.backend.entity.TelemetryData;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TelemetryMapper {

    @Mapping(target = "recordedAt", source = "id.recordedAt")
    HistoryPointDto toHistoryPoint(TelemetryData data);
}
