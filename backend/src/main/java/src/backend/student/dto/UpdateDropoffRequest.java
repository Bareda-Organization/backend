package src.backend.student.dto;

import jakarta.validation.constraints.NotNull;

/** 학생 하차지(하원) 좌표 설정 — routing 모듈이 하원 노선 계산에 사용한다. */
public record UpdateDropoffRequest(
        String dropoffAddress,
        @NotNull Double dropoffLat,
        @NotNull Double dropoffLng) {
}
