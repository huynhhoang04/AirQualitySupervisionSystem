package com.aqss.backend.dto.space;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateSpaceRequest(
        @NotBlank @Size(max = 100) String name,
        Float areaM2,
        Float ceilingM,
        @Positive Float volumeM3,
        @Pattern(regexp = "bedroom|dorm|classroom|office") String purpose,
        @Size(max = 50) String region,
        @Pattern(regexp = "low|medium|high") String ventilationEstimate
) {
}
