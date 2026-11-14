package com.aqss.backend.service;

import com.aqss.backend.api.DeviceController;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RoomProfileTest {
    @Test
    void lobbyAndOfficeHaveDifferentOccupancyDefaults() {
        org.assertj.core.api.Assertions.assertThat(ThresholdService.occupancyHours("lobby")).isEqualTo(2.0);
        org.assertj.core.api.Assertions.assertThat(ThresholdService.occupancyHours("office")).isEqualTo(8.0);
    }

    @Test
    void pairingCarriesAllPresetFactorsToService() {
        DeviceService devices = mock(DeviceService.class);
        DeviceController controller = new DeviceController(devices, mock(TelemetryService.class));
        controller.pair(new DeviceService.PairRequest("ABC234", null, "Máy", "Phòng ngủ",
                "bedroom", 15f, 8, "low"));
        verify(devices).pair("ABC234", null, "Máy", "Phòng ngủ", "bedroom", 15f, 8, "low");
    }

    @Test
    void invalidRoomInputsAreRejectedInsteadOfSilentlyClamped() {
        for (float volume : new float[]{0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> SpaceService.validateProfile(volume, 8, "low"))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> SpaceService.validateProfile(15f, 25, "low"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> SpaceService.validateProfile(15f, 8, "unknown"))
                .isInstanceOf(ResponseStatusException.class);
        SpaceService.validateProfile(15f, 8, "low");
        SpaceService.validateProfile(null, null, null); // patch requests remain compatible
    }
}
