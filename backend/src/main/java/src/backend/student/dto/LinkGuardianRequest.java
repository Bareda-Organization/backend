package src.backend.student.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 학생에 보호자(학부모) 연결 요청.
 */
public record LinkGuardianRequest(
        @NotNull @Schema(example = "2", description = "보호자 user id(이부모)") Long guardianUserId,
        @Schema(example = "모") String relation) {
}
