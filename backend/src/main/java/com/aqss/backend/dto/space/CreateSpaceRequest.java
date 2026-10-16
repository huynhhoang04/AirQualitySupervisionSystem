package com.aqss.backend.dto.space;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateSpaceRequest(
        @NotBlank @Size(max = 20) @Pattern(regexp = "SP_\\w+") String code,
        @NotBlank @Size(max = 100) String name,
        Float areaM2,
        Float ceilingM,
        @NotNull @Positive Float volumeM3,
        @NotBlank @Pattern(regexp = "bedroom|dorm|classroom|office") String purpose,
        @NotBlank @Size(max = 50) String region,
        @Pattern(regexp = "low|medium|high") String ventilationEstimate
) {
}
