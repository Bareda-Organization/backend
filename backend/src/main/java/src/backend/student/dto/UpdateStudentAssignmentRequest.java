package src.backend.student.dto;

/**
 * 학생 재배정 요청 — 담당 버스·기본 승차 정류장. 둘 다 생략 가능(전달된 값만 갱신).
 */
public record UpdateStudentAssignmentRequest(
        Long assignedBusId,
        Long boardingStopId) {
}
