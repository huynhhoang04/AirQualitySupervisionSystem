package com.aqss.backend.service.rules;

/** ML#2 output: discrete fan level (0–3) and run duration. */
public record DosageResult(
        short level,
        int durationMinutes,
        String modelVersion
) {
    public static final String RULE_MODEL_VERSION = "rule-v2.0";
}
