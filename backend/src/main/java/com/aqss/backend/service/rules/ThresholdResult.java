package com.aqss.backend.service.rules;

/** ML#1 output: base PM2.5 threshold plus a GPR-style uncertainty band. */
public record ThresholdResult(
        double baseThresholdPm25,
        double uncertaintyLower,
        double uncertaintyUpper,
        String modelVersion
) {
    public static final String RULE_MODEL_VERSION = "rule-v2.0";
}
