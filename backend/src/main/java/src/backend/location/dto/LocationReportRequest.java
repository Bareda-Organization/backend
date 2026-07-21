package src.backend.location.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 학생 앱이 자기 현재 위치를 보고할 때의 요청 본문.
 * 실 GPS 전환 시에도 이 인터페이스(엔드포인트 계약)는 그대로 재사용하고,
 * 좌표를 만들어내는 쪽(디바이스 GPS ↔ Mock 시뮬레이터)만 교체한다.
 */
public record LocationReportRequest(
        @NotNull @Schema(example = "37.4998") Double lat,
        @NotNull @Schema(example = "127.0245") Double lng) {
}
