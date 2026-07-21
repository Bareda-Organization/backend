package src.backend.routing.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/** 자동 배정(제안) 확정 요청 — 검토를 마친 RECOMMENDED 계획 id 목록. */
public record ConfirmAutoAssignRequest(
        @NotEmpty @Schema(example = "[1, 2]", description = "auto-assign 응답의 plans[].id 목록(초기 시드 기준 한빛학원은 버스 2대라 보통 1, 2)")
        List<Long> planIds) {
}
