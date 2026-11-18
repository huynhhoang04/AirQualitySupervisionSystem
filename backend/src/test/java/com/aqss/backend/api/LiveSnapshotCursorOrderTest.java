package com.aqss.backend.api;

import com.aqss.backend.model.Device;
import com.aqss.backend.model.Telemetry;
import com.aqss.backend.service.AiService;
import com.aqss.backend.service.DeviceService;
import com.aqss.backend.service.LiveEventService;
import com.aqss.backend.service.TelemetryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The snapshot cursor must be read before the sample query.
 *
 * <p>Otherwise an event published between the query and the cursor read is in neither the snapshot
 * (it was not stored when the query ran) nor the replay (its id is at or below the cursor), and the
 * chart silently loses that sample until a full reload.
 */
class LiveSnapshotCursorOrderTest {

    @Test
    @DisplayName("The cursor is captured before the samples are read")
    void should_ReadCursorBeforeSamples() {
        LiveEventService live = mock(LiveEventService.class);
        DeviceService devices = mock(DeviceService.class);
        TelemetryService telemetry = mock(TelemetryService.class);
        AiService ai = mock(AiService.class);

        when(devices.require("dev-1")).thenReturn(Device.builder().deviceId("dev-1").build());
        when(live.cursor("dev-1")).thenReturn(7L);
        when(telemetry.latestOptional("dev-1")).thenReturn(Optional.of(
                Telemetry.builder().id(1L).deviceId("dev-1").recordedAt(Instant.now()).build()));
        when(telemetry.recentWindow(anyString(), any(), any())).thenReturn(List.of());
        when(ai.latestForecast("dev-1")).thenReturn(Optional.empty());

        LiveController controller = new LiveController(live, devices, telemetry, ai);
        LiveController.LiveSnapshot snapshot = controller.snapshot("dev-1");

        InOrder order = inOrder(live, telemetry);
        order.verify(live).cursor("dev-1");
        order.verify(telemetry).recentWindow(anyString(), any(), any());
        assertThat(snapshot.cursor()).isEqualTo(7L);
    }
}
