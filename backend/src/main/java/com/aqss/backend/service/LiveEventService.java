package com.aqss.backend.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-device live fan-out for the room chart.
 *
 * <p>One ring buffer per device keeps the last {@value #BUFFER_SIZE} events so a browser that
 * reconnects with {@code Last-Event-ID} can catch up without a full page reload. Sending happens on
 * a bounded executor, never on the MQTT callback thread; a subscriber that cannot keep up gets a
 * {@code reset} event instead of stalling ingestion.
 */
@Service
@Slf4j
public class LiveEventService {

    public static final int BUFFER_SIZE = 1_000;
    public static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private static final int SUBSCRIBER_QUEUE = BUFFER_SIZE + 256;
    private static final long HEARTBEAT_SECONDS = 15;

    /** One stored event; {@code eventId} is the cursor clients replay from. */
    public record LiveEvent(long eventId, String deviceId, String type, Instant occurredAt,
                            Instant receivedAt, Object payload) {
    }

    /** Wire envelope. Jackson renders every field snake_case, like the rest of the API. */
    public record LiveEnvelope(long eventId, String deviceId, String type, Instant occurredAt,
                               Instant receivedAt, Object payload) {
    }

    public record ResetPayload(String deviceId, long cursor, Instant serverTime) {
    }

    public record ActuatorUpdate(String state, Integer level, Integer dutyPct, String mode,
                                 Integer filterRuntimeS) {
    }

    private static final class DeviceChannel {
        final String deviceId;
        final ArrayDeque<LiveEvent> buffer = new ArrayDeque<>(BUFFER_SIZE);
        final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
        long sequence;

        DeviceChannel(String deviceId) {
            this.deviceId = deviceId;
        }
    }

    private static final class Outbound {
        final LiveEvent event;
        final Long resetCursor;
        final String comment;

        private Outbound(LiveEvent event, Long resetCursor, String comment) {
            this.event = event;
            this.resetCursor = resetCursor;
            this.comment = comment;
        }

        static Outbound event(LiveEvent event) {
            return new Outbound(event, null, null);
        }

        static Outbound reset(long cursor) {
            return new Outbound(null, cursor, null);
        }

        static Outbound comment(String text) {
            return new Outbound(null, null, text);
        }
    }

    private final class Subscriber {
        final DeviceChannel channel;
        final String deviceId;
        final SseEmitter emitter;
        final BlockingQueue<Outbound> queue = new LinkedBlockingQueue<>(SUBSCRIBER_QUEUE);
        final AtomicBoolean draining = new AtomicBoolean();
        volatile boolean closed;

        Subscriber(DeviceChannel channel, SseEmitter emitter) {
            this.channel = channel;
            this.deviceId = channel.deviceId;
            this.emitter = emitter;
        }

        void offerEvent(LiveEvent event, long cursor) {
            if (closed) {
                return;
            }
            if (!queue.offer(Outbound.event(event))) {
                // The client is too slow. Dropping the backlog is safe: reset makes it resync the
                // snapshot, which carries the current state anyway.
                queue.clear();
                queue.offer(Outbound.reset(cursor));
            }
            scheduleDrain();
        }

        void offerReset(long cursor) {
            if (!closed && queue.offer(Outbound.reset(cursor))) {
                scheduleDrain();
            }
        }

        void offerComment(String text) {
            if (!closed && queue.offer(Outbound.comment(text))) {
                scheduleDrain();
            }
        }

        void scheduleDrain() {
            if (closed || !draining.compareAndSet(false, true)) {
                return;
            }
            try {
                sender.execute(this::drain);
            } catch (RejectedExecutionException e) {
                draining.set(false);
                close();
            }
        }

        void drain() {
            try {
                Outbound outbound;
                while (!closed && (outbound = queue.poll()) != null) {
                    if (outbound.comment != null) {
                        emitter.send(SseEmitter.event().comment(outbound.comment));
                    } else if (outbound.resetCursor != null) {
                        sendReset(outbound.resetCursor);
                    } else {
                        sendEvent(outbound.event);
                    }
                }
            } catch (Exception e) {
                log.debug("SSE stream ended for {}: {}", deviceId, e.getMessage());
                close();
            } finally {
                draining.set(false);
                if (!closed && !queue.isEmpty()) {
                    scheduleDrain();
                }
            }
        }

        void sendEvent(LiveEvent event) throws IOException {
            LiveEnvelope envelope = new LiveEnvelope(event.eventId(), event.deviceId(), event.type(),
                    event.occurredAt(), event.receivedAt(), event.payload());
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.eventId()))
                    .name(event.type())
                    .data(envelope, MediaType.APPLICATION_JSON));
        }

        void sendReset(long cursor) throws IOException {
            Instant now = Instant.now();
            LiveEnvelope envelope = new LiveEnvelope(cursor, deviceId, "reset", now, now,
                    new ResetPayload(deviceId, cursor, now));
            emitter.send(SseEmitter.event()
                    .id(Long.toString(cursor))
                    .name("reset")
                    .data(envelope, MediaType.APPLICATION_JSON));
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            channel.subscribers.remove(this);
            queue.clear();
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // The container already closed the connection.
            }
        }
    }

    private final Map<String, DeviceChannel> channels = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newFixedThreadPool(4, daemon("live-sse"));
    private final ScheduledExecutorService heartbeats =
            Executors.newSingleThreadScheduledExecutor(daemon("live-heartbeat"));

    @PostConstruct
    void startHeartbeat() {
        heartbeats.scheduleAtFixedRate(() -> {
            for (DeviceChannel channel : channels.values()) {
                for (Subscriber subscriber : channel.subscribers) {
                    subscriber.offerComment("heartbeat");
                }
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        heartbeats.shutdownNow();
        sender.shutdownNow();
    }

    /** Current high-water cursor for a device; 0 when it has never published. */
    public long cursor(String deviceId) {
        DeviceChannel channel = channels.get(deviceId);
        if (channel == null) {
            return 0L;
        }
        synchronized (channel) {
            return channel.sequence;
        }
    }

    /**
     * Append and fan out one event. Call from {@link #publishAfterCommit} when a transaction is
     * involved, so the browser never sees a row that later rolls back.
     */
    public LiveEvent publish(String deviceId, String type, Instant occurredAt, Instant receivedAt,
                             Object payload) {
        if (deviceId == null || deviceId.isBlank() || type == null) {
            return null;
        }
        DeviceChannel channel = channels.computeIfAbsent(deviceId, DeviceChannel::new);
        LiveEvent event;
        synchronized (channel) {
            Instant now = Instant.now();
            event = new LiveEvent(++channel.sequence, deviceId, type,
                    occurredAt == null ? now : occurredAt,
                    receivedAt == null ? now : receivedAt,
                    payload);
            channel.buffer.addLast(event);
            while (channel.buffer.size() > BUFFER_SIZE) {
                channel.buffer.removeFirst();
            }
            long cursor = channel.sequence;
            for (Subscriber subscriber : channel.subscribers) {
                subscriber.offerEvent(event, cursor);
            }
        }
        return event;
    }

    /** Publish after the surrounding transaction commits, or immediately when there is none. */
    public void publishAfterCommit(String deviceId, String type, Instant occurredAt,
                                   Instant receivedAt, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(deviceId, type, occurredAt, receivedAt, payload);
                }
            });
        } else {
            publish(deviceId, type, occurredAt, receivedAt, payload);
        }
    }

    /**
     * Register a browser stream. Replay is ordered before new events inside the channel lock, so a
     * packet that arrives between the snapshot and this call is neither lost nor duplicated.
     */
    public void subscribe(String deviceId, Long after, SseEmitter emitter) {
        DeviceChannel channel = channels.computeIfAbsent(deviceId, DeviceChannel::new);
        Subscriber subscriber = new Subscriber(channel, emitter);
        emitter.onCompletion(subscriber::close);
        emitter.onTimeout(subscriber::close);
        emitter.onError(error -> subscriber.close());

        synchronized (channel) {
            long current = channel.sequence;
            if (after != null) {
                long oldest = channel.buffer.isEmpty() ? current + 1 : channel.buffer.peekFirst().eventId();
                if (after > current || after < oldest - 1) {
                    subscriber.offerReset(current);
                } else {
                    for (LiveEvent event : channel.buffer) {
                        if (event.eventId() > after) {
                            subscriber.offerEvent(event, current);
                        }
                    }
                }
            }
            channel.subscribers.add(subscriber);
        }
        subscriber.scheduleDrain();
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger index = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
