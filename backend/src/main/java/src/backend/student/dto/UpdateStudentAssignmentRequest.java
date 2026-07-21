package src.backend.student.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 학생 재배정 요청 — 담당 버스·기본 승차 정류장. 둘 다 생략 가능(전달된 값만 갱신).
 */
public record UpdateStudentAssignmentRequest(
        @Schema(example = "1", description = "담당 버스 id(3호차)") Long assignedBusId,
        @Schema(example = "1", description = "기본 승차 정류장 id(정류장 A)") Long boardingStopId) {
}
