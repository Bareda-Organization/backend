package src.backend.student.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 학생 인적 정보 수정 요청 — 전달된 필드만 갱신한다(null = 그대로).
 * 배차(assignment)·하차지(dropoff)는 기존 전용 PATCH 가 담당한다. 여기는 인적 정보만이다.
 */
public record UpdateStudentRequest(
        @Schema(example = "김민준") String name,
        @Schema(example = "010-1234-5678") String phone,
        @Schema(example = "https://cdn.example.com/students/1.jpg") String photoUrl) {
}
