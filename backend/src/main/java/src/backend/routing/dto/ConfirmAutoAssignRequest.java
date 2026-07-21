package src.backend.routing.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

/** 자동 배정(제안) 확정 요청 — 검토를 마친 RECOMMENDED 계획 id 목록. */
public record ConfirmAutoAssignRequest(
        @NotEmpty @Schema(example = "[2, 4]") List<Long> planIds) {
}
