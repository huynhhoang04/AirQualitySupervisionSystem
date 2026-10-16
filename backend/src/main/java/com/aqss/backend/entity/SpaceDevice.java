package com.aqss.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Node ↔ space mapping. */
@Entity
@Table(name = "space_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpaceDevice {

    @EmbeddedId
    private SpaceDeviceId id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeviceRole role;
}
