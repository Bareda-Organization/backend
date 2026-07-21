package src.backend.student.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** 학생 하차지(하원) 좌표 설정 — routing 모듈이 하원 노선 계산에 사용한다. */
public record UpdateDropoffRequest(
        @Schema(example = "서울 송파구 자택") String dropoffAddress,
        @NotNull @Schema(example = "37.5060") Double dropoffLat,
        @NotNull @Schema(example = "127.0290") Double dropoffLng) {
}
