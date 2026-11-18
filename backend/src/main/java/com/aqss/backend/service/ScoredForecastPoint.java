package com.aqss.backend.service;

import java.time.Instant;

/** One scored forecast point with the run it came from, for the accuracy drawer. */
public record ScoredForecastPoint(Instant targetAt, Integer horizonMinutes, Float predictedPm25,
                                  Float actualPm25, Float error, Instant issuedAt,
                                  String modelVersion, Instant evaluatedAt) {
}
