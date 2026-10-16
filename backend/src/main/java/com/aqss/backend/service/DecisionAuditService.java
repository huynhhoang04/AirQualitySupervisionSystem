package com.aqss.backend.service;

import com.aqss.backend.dto.decision.DecisionEventDto;
import com.aqss.backend.dto.space.DosageDto;
import com.aqss.backend.entity.ControlDecision;
import com.aqss.backend.entity.DecisionAction;
import com.aqss.backend.entity.DecisionMode;
import com.aqss.backend.entity.Space;
import com.aqss.backend.mapper.DecisionMapper;
import com.aqss.backend.repository.ControlDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Writes the audit trail and pushes the decision stream to {@code /topic/spaces/{id}/decision}. */
@Service
@RequiredArgsConstructor
@Slf4j
public class DecisionAuditService {

    private final ControlDecisionRepository decisionRepository;
    private final DecisionMapper decisionMapper;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional
    public ControlDecision record(Space space, DecisionAction action, DecisionMode mode, String trigger,
                                  Double pm25, Double threshold, Short dosageLevel, Integer dosageDurationMin) {
        ControlDecision decision = ControlDecision.builder()
                .spaceId(space.getId())
                .decidedAt(Instant.now())
                .action(action)
                .mode(mode)
                .triggerReason(trigger)
                .pm25AtDecision(pm25 == null ? null : pm25.floatValue())
                .thresholdUsed(threshold == null ? null : threshold.floatValue())
                .dosageLevel(dosageLevel)
                .dosageDurationMin(dosageDurationMin)
                .build();
        ControlDecision saved = decisionRepository.save(decision);

        DosageDto dosage = dosageLevel == null ? null : new DosageDto(dosageLevel, dosageDurationMin);
        messagingTemplate.convertAndSend("/topic/spaces/" + space.getId() + "/decision",
                new DecisionEventDto(action.name(), mode.name(), trigger,
                        threshold == null ? null : threshold.floatValue(), dosage, saved.getDecidedAt()));
        log.info("Decision [{}] space={} trigger={} pm25={}", action, space.getCode(), trigger, pm25);
        return saved;
    }
}
