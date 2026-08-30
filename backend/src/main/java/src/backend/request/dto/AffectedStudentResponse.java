package src.backend.request.dto;

/** 이 변경으로 정차지가 바뀌는 학생 1명(API_SPEC §5.5 상세 — {@code affected_students}). */
public record AffectedStudentResponse(Long studentId, String name) {
}
