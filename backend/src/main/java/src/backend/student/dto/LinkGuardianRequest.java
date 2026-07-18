package src.backend.student.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 학생에 보호자(학부모) 연결 요청.
 */
public record LinkGuardianRequest(
        @NotNull Long guardianUserId,
        String relation) {
}
