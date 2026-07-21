package src.backend.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 정류장 생성 요청. seq 는 노선 내 순서.
 */
public record CreateStopRequest(
        @NotBlank @Schema(example = "정류장 C") String name,
        @PositiveOrZero @Schema(example = "4") int seq,
        @Schema(example = "37.5090") double lat,
        @Schema(example = "127.0320") double lng) {
}
