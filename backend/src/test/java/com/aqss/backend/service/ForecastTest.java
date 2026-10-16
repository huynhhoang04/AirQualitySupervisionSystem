package com.aqss.backend.service;

import com.aqss.backend.dto.forecast.ForecastPointDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastTest {

    private static ForecastPointDto point(long minutesAhead, float q90) {
        return new ForecastPointDto(Instant.now().plus(minutesAhead, ChronoUnit.MINUTES), q90 - 2, q90 - 4, q90);
    }

    @Test
    @DisplayName("Hours until breach is the earliest q90 crossing inside the horizon")
    void should_ReturnEarliestCrossing_When_Q90BreachesThreshold() {
        Forecast forecast = new Forecast(Instant.now(), "v2.0",
                List.of(point(30, 10), point(90, 25), point(180, 40)));

        Double hours = forecast.hoursUntilQ90Breach(20.0);

        assertThat(hours).isNotNull();
        assertThat(hours).isBetween(1.4, 1.6);
    }

    @Test
    @DisplayName("No crossing means no early warning")
    void should_ReturnNull_When_Q90StaysBelowThreshold() {
        Forecast forecast = new Forecast(Instant.now(), "v2.0", List.of(point(30, 5), point(60, 8)));

        assertThat(forecast.hoursUntilQ90Breach(20.0)).isNull();
    }

    @Test
    @DisplayName("An empty forecast sequence never triggers a warning")
    void should_ReturnNull_When_SequenceIsEmpty() {
        assertThat(new Forecast(Instant.now(), "v2.0", List.of()).hoursUntilQ90Breach(20.0)).isNull();
    }
}
