package com.aqss.backend.service;

import com.aqss.backend.model.ActuatorHistory;
import com.aqss.backend.model.Device;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.repo.ActuatorHistoryRepository;
import com.aqss.backend.repo.AiDatasetSnapshotRepository;
import com.aqss.backend.repo.DeviceRepository;
import com.aqss.backend.repo.RoomEventRepository;
import com.aqss.backend.repo.SpaceProfileVersionRepository;
import com.aqss.backend.repo.SpaceRepository;
import com.aqss.backend.repo.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The incremental reads must answer an empty page cleanly.
 *
 * <p>An empty actuator stream is the normal state of a new node, and it used to throw a
 * NullPointerException: a null cursor was auto-unboxed into the response's {@code long} field.
 */
class IncrementalReadTest {

    private TelemetryRepository telemetry;
    private ActuatorHistoryRepository actuator;
    private DatasetSnapshotService service;

    @BeforeEach
    void setUp() {
        telemetry = mock(TelemetryRepository.class);
        actuator = mock(ActuatorHistoryRepository.class);
        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.findById(anyString())).thenReturn(Optional.of(
                Device.builder().deviceId("dev-1").build()));

        service = new DatasetSnapshotService(
                mock(JdbcTemplate.class),
                mock(AiDatasetSnapshotRepository.class),
                telemetry,
                actuator,
                mock(RoomEventRepository.class),
                devices,
                mock(SpaceRepository.class),
                mock(SpaceProfileVersionRepository.class));
    }

    @Test
    @DisplayName("An empty actuator stream is an empty page, not an exception")
    void should_ReturnEmptyPage_When_NoActuatorReports() {
        when(actuator.findByDeviceIdAndIdGreaterThanOrderByIdAsc(eq("dev-1"), any(), any(Pageable.class)))
                .thenReturn(List.of());

        DatasetSnapshotService.IncrementalPage<ActuatorHistory> page =
                service.actuatorReportsAfter("dev-1", 0L, 50);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
        assertThat(page.highWaterId()).isZero();
    }

    @Test
    @DisplayName("An empty telemetry page reports the server high-water id")
    void should_ReturnHighWater_When_NoNewSamples() {
        when(telemetry.findByDeviceIdAndIdGreaterThanOrderByIdAsc(eq("dev-1"), any(), any(Pageable.class)))
                .thenReturn(List.of());
        when(telemetry.findMaxId("dev-1")).thenReturn(42L);

        DatasetSnapshotService.IncrementalPage<Telemetry> page = service.samplesAfter("dev-1", 40L, 50);

        assertThat(page.items()).isEmpty();
        assertThat(page.nextAfterId()).isNull();
        assertThat(page.highWaterId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("A page reports its last id as the next cursor")
    void should_ReportLastId_When_RowsExist() {
        Telemetry first = Telemetry.builder().id(7L).deviceId("dev-1").build();
        Telemetry second = Telemetry.builder().id(9L).deviceId("dev-1").build();
        when(telemetry.findByDeviceIdAndIdGreaterThanOrderByIdAsc(eq("dev-1"), any(), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(telemetry.findMaxId("dev-1")).thenReturn(9L);

        DatasetSnapshotService.IncrementalPage<Telemetry> page = service.samplesAfter("dev-1", 0L, 50);

        assertThat(page.items()).hasSize(2);
        assertThat(page.nextAfterId()).isEqualTo(9L);
    }
}
