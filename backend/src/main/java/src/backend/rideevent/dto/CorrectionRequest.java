package src.backend.rideevent.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;
import src.backend.rideevent.entity.RideType;

/**
 * 승하차 기록 정정 요청. 원본을 덮어쓰지 않고 정정 기록을 새로 남긴다.
 * occurredAt 생략 시 원본 시각을 유지한다.
 */
public record CorrectionRequest(
        @NotNull RideType type,
        LocalDateTime occurredAt,
        Long stopId,
        Double lat,
        Double lng) {
}
