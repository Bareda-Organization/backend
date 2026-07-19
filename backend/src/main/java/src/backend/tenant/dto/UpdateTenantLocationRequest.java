package src.backend.tenant.dto;

import jakarta.validation.constraints.NotNull;

/** 학원 위치(depot) 좌표 설정 — routing 모듈이 노선 계산 기준점으로 사용한다. */
public record UpdateTenantLocationRequest(
        @NotNull Double lat,
        @NotNull Double lng) {
}
