package com.aqss.backend.repo;

import com.aqss.backend.model.AiJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AiJobRepository extends JpaRepository<AiJob, UUID> {

    List<AiJob> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    List<AiJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByDeviceIdAndTypeAndStatusIn(String deviceId, String type, List<String> statuses);

    Optional<AiJob> findFirstByDeviceIdAndTypeAndStatusOrderByCreatedAtDesc(
            String deviceId, String type, String status);

    List<AiJob> findByDeviceIdOrderByCreatedAtDesc(String deviceId, Pageable pageable);

    /** One worker claims one job under a row lock; a second worker cannot take the same row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AiJob j where j.status = 'QUEUED' order by j.createdAt asc")
    List<AiJob> lockNextQueued(Pageable pageable);

    boolean existsByWorkerIdAndStatus(String workerId, String status);
}
