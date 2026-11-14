package com.aqss.backend.repo;

import com.aqss.backend.model.RoomEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RoomEventRepository extends JpaRepository<RoomEvent, UUID> {

    List<RoomEvent> findByIdInOrderByOccurredAtAscIdAsc(Collection<UUID> ids);

    List<RoomEvent> findByDeviceIdAndOccurredAtBetweenOrderByOccurredAtAsc(
            String deviceId, Instant from, Instant to);

    List<RoomEvent> findTop200ByDeviceIdOrderByOccurredAtDesc(String deviceId);
}
