package src.backend.rideevent.dto;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import src.backend.rideevent.entity.RideType;

/**
 * 승하차 기록 정정 요청. 원본을 덮어쓰지 않고 정정 기록을 새로 남긴다.
 * occurredAt 생략 시 원본 시각을 유지한다.
 */
public record CorrectionRequest(
        @NotNull @Schema(example = "BOARD") RideType type,
        @Schema(example = "2026-07-20T08:15:00") LocalDateTime occurredAt,
        @Schema(example = "1", description = "정류장 id(정류장 A)") Long stopId,
        @Schema(example = "37.5010") Double lat,
        @Schema(example = "127.0275") Double lng) {
}
