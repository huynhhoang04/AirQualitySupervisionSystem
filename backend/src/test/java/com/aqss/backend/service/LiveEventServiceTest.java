package com.aqss.backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LiveEventServiceTest {

    private final LiveEventService service = new LiveEventService();

    @AfterEach
    void stop() {
        service.stop();
    }

    @Test
    @DisplayName("A subscriber receives new events in cursor order")
    void should_DeliverNewEventsInOrder() throws Exception {
        CapturingEmitter emitter = new CapturingEmitter(3);
        service.subscribe("dev-1", null, emitter);

        publish("dev-1", "a");
        publish("dev-1", "b");
        publish("dev-1", "c");

        assertThat(emitter.await())
                .extracting(LiveEventService.LiveEnvelope::eventId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("Reconnect replays only the events after the supplied cursor")
    void should_ReplayOnlyEventsAfterCursor() throws Exception {
        publish("dev-1", "a");
        publish("dev-1", "b");
        publish("dev-1", "c");

        CapturingEmitter emitter = new CapturingEmitter(2);
        service.subscribe("dev-1", 1L, emitter);

        assertThat(emitter.await())
                .extracting(LiveEventService.LiveEnvelope::eventId)
                .containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("A cursor older than the ring buffer gets reset instead of a partial replay")
    void should_ResetWhenCursorIsStale() throws Exception {
        for (int i = 0; i < LiveEventService.BUFFER_SIZE + 1; i++) {
            publish("dev-1", "row-" + i);
        }

        CapturingEmitter emitter = new CapturingEmitter(1);
        service.subscribe("dev-1", 0L, emitter);

        assertThat(emitter.await())
                .singleElement()
                .satisfies(envelope -> {
                    assertThat(envelope.type()).isEqualTo("reset");
                    assertThat(envelope.payload())
                            .isInstanceOfSatisfying(LiveEventService.ResetPayload.class,
                                    reset -> assertThat(reset.cursor())
                                            .isEqualTo(LiveEventService.BUFFER_SIZE + 1L));
                });
    }

    @Test
    @DisplayName("A cursor ahead of the server (restart) is reset")
    void should_ResetWhenCursorIsAheadOfServer() throws Exception {
        publish("dev-1", "a");

        CapturingEmitter emitter = new CapturingEmitter(1);
        service.subscribe("dev-1", 5L, emitter);

        assertThat(emitter.await())
                .singleElement()
                .satisfies(envelope -> assertThat(envelope.type()).isEqualTo("reset"));
    }

    @Test
    @DisplayName("The snapshot cursor does not replay existing history")
    void should_StartAtHighWaterCursor_WhenNoCursorGiven() throws Exception {
        publish("dev-1", "a");
        publish("dev-1", "b");

        CapturingEmitter emitter = new CapturingEmitter(1);
        service.subscribe("dev-1", null, emitter);
        publish("dev-1", "c");

        assertThat(emitter.await())
                .singleElement()
                .satisfies(envelope -> assertThat(envelope.eventId()).isEqualTo(3L));
    }

    private void publish(String deviceId, String payload) {
        service.publish(deviceId, "telemetry", Instant.now(), Instant.now(), payload);
    }

    private static final class CapturingEmitter extends SseEmitter {

        private final List<LiveEventService.LiveEnvelope> envelopes = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch;

        CapturingEmitter(int expected) {
            this.latch = new CountDownLatch(expected);
        }

        @Override
        public void send(SseEventBuilder builder) {
            for (DataWithMediaType entry : builder.build()) {
                if (entry.getData() instanceof LiveEventService.LiveEnvelope envelope) {
                    envelopes.add(envelope);
                    latch.countDown();
                }
            }
        }

        List<LiveEventService.LiveEnvelope> await() throws InterruptedException {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out; received " + envelopes);
            }
            return envelopes;
        }
    }
}
