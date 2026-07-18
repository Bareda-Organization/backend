package src.backend.route.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 정류장 생성 요청. seq 는 노선 내 순서.
 */
public record CreateStopRequest(
        @NotBlank String name,
        @PositiveOrZero int seq,
        double lat,
        double lng) {
}
