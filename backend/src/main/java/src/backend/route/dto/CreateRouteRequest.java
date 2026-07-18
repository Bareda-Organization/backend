package src.backend.route.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 노선 생성 요청. tenantId 생략 시 요청자(학원 관리자)의 소속 학원으로 만든다.
 */
public record CreateRouteRequest(
        Long tenantId,
        @NotBlank String name,
        @Positive int assignCapacity) {
}
