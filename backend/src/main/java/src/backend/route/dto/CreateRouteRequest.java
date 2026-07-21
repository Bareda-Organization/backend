package src.backend.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 노선 생성 요청. tenantId 생략 시 요청자(학원 관리자)의 소속 학원으로 만든다.
 */
public record CreateRouteRequest(
        @Schema(example = "1", description = "소속 학원 id(한빛학원)") Long tenantId,
        @NotBlank @Schema(example = "등원 A노선") String name,
        @Positive @Schema(example = "25") int assignCapacity) {
}
