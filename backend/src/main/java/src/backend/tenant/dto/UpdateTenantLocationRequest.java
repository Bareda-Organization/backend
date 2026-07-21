package src.backend.tenant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 학원 위치(depot) 좌표 설정 — routing 모듈이 노선 계산 기준점으로 사용한다. */
public record UpdateTenantLocationRequest(
        @NotNull @Schema(example = "37.5075") Double lat,
        @NotNull @Schema(example = "127.0355") Double lng) {
}
