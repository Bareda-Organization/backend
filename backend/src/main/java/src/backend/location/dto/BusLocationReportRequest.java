package src.backend.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 기사 앱이 담당 버스의 현재 위치를 보고할 때의 요청 본문(F1). */
public record BusLocationReportRequest(
        @NotNull @Schema(example = "1", description = "버스 id(3호차)") Long busId,
        @NotNull @Schema(example = "37.5075") Double lat,
        @NotNull @Schema(example = "127.0355") Double lng) {
}
