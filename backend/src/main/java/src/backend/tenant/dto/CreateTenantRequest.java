package src.backend.tenant.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 학원(테넌트) 생성 요청.
 */
public record CreateTenantRequest(
        @NotBlank String name,
        Double lat,
        Double lng) {
}
