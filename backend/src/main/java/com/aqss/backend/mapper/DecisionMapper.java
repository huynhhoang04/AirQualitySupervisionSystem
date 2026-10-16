package com.aqss.backend.mapper;

import com.aqss.backend.dto.decision.DecisionDto;
import com.aqss.backend.entity.ControlDecision;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DecisionMapper {

    DecisionDto toDto(ControlDecision decision);
}
