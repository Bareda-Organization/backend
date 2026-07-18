package src.backend.student.dto;

import java.util.List;

import src.backend.student.entity.Student;
import src.backend.student.entity.StudentGuardian;

/**
 * 학생 상세 응답 — 요약 + 버스·정류장 이름 + 보호자 목록.
 */
public record StudentDetailResponse(
        StudentResponse student,
        String assignedBusName,
        String boardingStopName,
        List<GuardianEntry> guardians) {

    /** 보호자 항목 — 보호자 User id·이름·관계. */
    public record GuardianEntry(Long guardianUserId, String name, String relation) {
    }

    public static StudentDetailResponse of(Student student, List<StudentGuardian> guardians) {
        List<GuardianEntry> entries = guardians.stream()
                .map(g -> new GuardianEntry(g.getGuardian().getId(), g.getGuardian().getName(), g.getRelation()))
                .toList();
        return new StudentDetailResponse(
                StudentResponse.of(student),
                student.getAssignedBus() != null ? student.getAssignedBus().getName() : null,
                student.getBoardingStop() != null ? student.getBoardingStop().getName() : null,
                entries);
    }
}
