package src.backend.student.dto;

import src.backend.student.entity.Student;

/**
 * 학생 요약 응답.
 */
public record StudentResponse(
        Long id,
        Long tenantId,
        String name,
        Long userId,
        Long assignedBusId,
        Long boardingStopId) {

    public static StudentResponse of(Student student) {
        return new StudentResponse(
                student.getId(),
                student.getTenant().getId(),
                student.getName(),
                student.getUserId(),
                student.getAssignedBus() != null ? student.getAssignedBus().getId() : null,
                student.getBoardingStop() != null ? student.getBoardingStop().getId() : null);
    }
}
