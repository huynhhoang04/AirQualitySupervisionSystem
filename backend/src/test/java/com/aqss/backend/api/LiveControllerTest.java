package com.aqss.backend.api;

import com.aqss.backend.service.AiService;
import com.aqss.backend.service.DeviceService;
import com.aqss.backend.service.LiveEventService;
import com.aqss.backend.service.TelemetryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LiveControllerTest {

    @Test
    @DisplayName("The live endpoint opens a text/event-stream async response")
    void should_OpenEventStream() throws Exception {
        LiveController controller = new LiveController(
                mock(LiveEventService.class),
                mock(DeviceService.class),
                mock(TelemetryService.class),
                mock(AiService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mvc.perform(get("/api/v1/devices/dev-1/live/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        result.getRequest().getAsyncContext().complete();
    }
}
