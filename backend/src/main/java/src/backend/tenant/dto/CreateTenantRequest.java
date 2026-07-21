package src.backend.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 학원(테넌트) 생성 요청.
 */
public record CreateTenantRequest(
        @NotBlank @Schema(example = "새싹학원") String name,
        @Schema(example = "37.5200") Double lat,
        @Schema(example = "127.0400") Double lng) {
}
