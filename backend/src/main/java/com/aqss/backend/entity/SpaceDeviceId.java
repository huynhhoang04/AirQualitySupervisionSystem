package com.aqss.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

/** Composite key of {@code space_devices}: {@code (space_id, device_id)}. */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SpaceDeviceId implements Serializable {

    @Column(name = "space_id")
    private Long spaceId;

    @Column(name = "device_id", length = 32)
    private String deviceId;
}
